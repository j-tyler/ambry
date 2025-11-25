/**
 * Copyright 2025 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.router;

import com.github.ambry.account.InMemAccountService;
import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.commons.ByteBufferAsyncWritableChannel;
import com.github.ambry.commons.ByteBufReadableStreamChannel;
import com.github.ambry.commons.LoggingNotificationSystem;
import com.github.ambry.config.RouterConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.network.ResponseInfo;
import com.github.ambry.protocol.PutResponse;
import com.github.ambry.quota.QuotaChargeCallback;
import com.github.ambry.quota.QuotaTestUtils;
import com.github.ambry.utils.MockTime;
import com.github.ambry.utils.NettyByteBufDataInputStream;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import com.github.ambry.utils.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;


/**
 * Test that reproduces the use-after-free race condition in PutOperation.fillChunks().
 *
 * This bug causes rare SIGSEGV crashes in Crc32.update() during put operations.
 *
 * Root cause: When a ByteBuf is obtained via chunkFillerChannel.getNextByteBuf() and stored in
 * the instance field channelReadBuf, the channel can be closed concurrently. The close()
 * fires callbacks which signal to Netty that it can release the buffer. However, the
 * channelReadBuf field still holds a dangling reference to the now-freed buffer.
 *
 * The crash occurs when:
 * 1. ChunkFiller gets buffer B, stores in channelReadBuf, partially consumes it
 * 2. ChunkFiller exits fillChunks() loop (e.g., no free chunks available)
 * 3. Channel closes, callback fires, Netty releases buffer B
 * 4. ChunkFiller re-enters fillChunks(), channelReadBuf still points to freed B
 * 5. fillFrom() calls slice.nioBuffer() which returns pointer to freed native memory
 * 6. Crc32.update() reads from freed memory -> SIGSEGV
 */
public class PutOperationChannelReadBufRaceConditionTest {

  private final MockClusterMap mockClusterMap = new MockClusterMap();
  private final NonBlockingRouterMetrics routerMetrics = new NonBlockingRouterMetrics(mockClusterMap, null);
  private final MockTime time = new MockTime();
  private final MockServer mockServer = new MockServer(mockClusterMap, "");
  private final Map<Integer, PutOperation> correlationIdToPutOperation = new TreeMap<>();
  private final RequestRegistrationCallback<PutOperation> requestRegistrationCallback =
      new RequestRegistrationCallback<>(correlationIdToPutOperation);
  private final QuotaChargeCallback quotaChargeCallback = QuotaTestUtils.createTestQuotaChargeCallback();
  private final Random random = new Random();
  private final int chunkSize = 4096;
  private final int requestParallelism = 3;
  private final NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();

  private RouterConfig routerConfig;
  private CompressionService compressionService;

  public PutOperationChannelReadBufRaceConditionTest() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("router.hostname", "localhost");
    properties.setProperty("router.datacenter.name", "DC1");
    properties.setProperty("router.max.put.chunk.size.bytes", Integer.toString(chunkSize));
    properties.setProperty("router.put.request.parallelism", Integer.toString(requestParallelism));
    properties.setProperty("router.put.success.target", "1");
    // Use only 1 in-memory chunk so that fillChunks() exits when the chunk is full
    // but channelReadBuf still has data
    properties.setProperty("router.max.in.mem.put.chunks", "1");
    routerConfig = new RouterConfig(new VerifiableProperties(properties));
    compressionService = new CompressionService(routerConfig.getCompressionConfig(), routerMetrics.compressionMetrics);
  }

  @Before
  public void before() {
    nettyByteBufLeakHelper.beforeTest();
  }

  @After
  public void after() {
    nettyByteBufLeakHelper.afterTest();
  }

  /**
   * Get a response from MockServer for a request.
   */
  private ResponseInfo getResponseInfo(RequestInfo requestInfo) throws Exception {
    return new ResponseInfo(requestInfo, null, mockServer.send(requestInfo.getRequest()).content());
  }

  /**
   * Verifies channelReadBuf remains valid after the channel callback releases the original buffer.
   *
   * The fix retains the buffer in channelReadBuf, preventing use-after-free when the channel
   * closes and fires its callback (which releases the original buffer).
   */
  @Test
  public void testChannelReadBufRemainsValidAfterChunkCompletesAndChannelCloses() throws Exception {
    // Create blob properties
    BlobProperties blobProperties = new BlobProperties(-1, "serviceId", "memberId", "contentType",
        false, Utils.Infinite_Time, Utils.getRandomShort(TestUtils.RANDOM),
        Utils.getRandomShort(TestUtils.RANDOM), false, null, null, null);
    byte[] userMetadata = new byte[10];

    // Buffer larger than one chunk leaves data in channelReadBuf after first fillChunks()
    int bufferSize = chunkSize * 2;
    ByteBuf nettyBuffer = PooledByteBufAllocator.DEFAULT.directBuffer(bufferSize);
    byte[] testData = new byte[bufferSize];
    random.nextBytes(testData);
    nettyBuffer.writeBytes(testData);

    ByteBufReadableStreamChannel channel = new ByteBufReadableStreamChannel(nettyBuffer);

    FutureResult<String> future = new FutureResult<>();
    MockNetworkClient mockNetworkClient = new MockNetworkClient();
    List<RequestInfo> requestInfos = new ArrayList<>();
    requestRegistrationCallback.setRequestsToSend(requestInfos);

    PutOperation op = PutOperation.forUpload(routerConfig, routerMetrics, mockClusterMap,
        new LoggingNotificationSystem(), new InMemAccountService(true, false), userMetadata,
        channel, PutBlobOptions.DEFAULT, future, null,
        new RouterCallback(mockNetworkClient, new ArrayList<>()), null, null, null, null, time,
        blobProperties, MockClusterMap.DEFAULT_PARTITION_CLASS, quotaChargeCallback, compressionService);

    op.startOperation();

    // Only the first chunk worth of data will be consumed (max.in.mem.put.chunks=1)
    op.fillChunks();

    // Get channelReadBuf via reflection - it should still have remaining data
    ByteBuf channelReadBuf = (ByteBuf) FieldUtils.readField(op, "channelReadBuf", true);
    assertNotNull("channelReadBuf should have the buffer", channelReadBuf);
    assertTrue("channelReadBuf should have remaining bytes after first fillChunks",
        channelReadBuf.readableBytes() > 0);

    // Poll to get requests for the first chunk
    op.poll(requestRegistrationCallback);
    assertEquals("Should have requests for the first chunk",
        requestParallelism, requestInfos.size());

    // Send requests to MockServer and handle responses - this completes the first chunk
    // When the chunk completes, its buffer (the retained slice) is released
    for (RequestInfo requestInfo : requestInfos) {
      ResponseInfo responseInfo = getResponseInfo(requestInfo);
      PutResponse putResponse = responseInfo.getError() == null
          ? PutResponse.readFrom(new NettyByteBufDataInputStream(responseInfo.content()))
          : null;
      op.handleResponse(responseInfo, putResponse);
      requestInfo.getRequest().release();
      responseInfo.release();
    }

    // At this point:
    // - First chunk has completed, its retained slice has been released
    // - channelReadBuf still has remaining data
    // - The buffer's refCnt depends on whether PutOperation retained it

    // Now close the chunkFillerChannel - this fires the ByteBufReadableStreamChannel
    // callback which releases the original buffer
    ByteBufferAsyncWritableChannel chunkFillerChannel =
        (ByteBufferAsyncWritableChannel) FieldUtils.readField(op, "chunkFillerChannel", true);
    chunkFillerChannel.close();

    // Get the reference to channelReadBuf after channel close
    ByteBuf bufAfterClose = (ByteBuf) FieldUtils.readField(op, "channelReadBuf", true);
    assertNotNull("channelReadBuf should still hold a reference", bufAfterClose);

    // THE FIX VERIFICATION:
    // After the fix, the buffer should still be valid (refCnt > 0) because
    // PutOperation should retain the buffer when it stores it in channelReadBuf.
    // Before the fix, refCnt is 0 and this assertion FAILS.
    assertTrue("Buffer should still be valid (refCnt > 0) after channel close",
        bufAfterClose.refCnt() > 0);

    // Verify buffer is accessible (would throw IllegalReferenceCountException before fix)
    assertTrue("Buffer should have readable bytes", bufAfterClose.readableBytes() > 0);

    op.cleanupChunks();
  }

  /**
   * End-to-end test verifying a multi-chunk put completes without errors or memory leaks.
   */
  @Test
  public void testMultiChunkPutCompletesSuccessfully() throws Exception {
    // Create blob properties
    BlobProperties blobProperties = new BlobProperties(-1, "serviceId", "memberId", "contentType",
        false, Utils.Infinite_Time, Utils.getRandomShort(TestUtils.RANDOM),
        Utils.getRandomShort(TestUtils.RANDOM), false, null, null, null);
    byte[] userMetadata = new byte[10];

    // Create a buffer for 2 data chunks - this will exercise the channelReadBuf code path
    int numChunks = 2;
    int bufferSize = chunkSize * numChunks;
    ByteBuf nettyBuffer = PooledByteBufAllocator.DEFAULT.directBuffer(bufferSize);
    byte[] testData = new byte[bufferSize];
    random.nextBytes(testData);
    nettyBuffer.writeBytes(testData);

    ByteBufReadableStreamChannel channel = new ByteBufReadableStreamChannel(nettyBuffer);

    FutureResult<String> future = new FutureResult<>();
    MockNetworkClient mockNetworkClient = new MockNetworkClient();
    List<RequestInfo> requestInfos = new ArrayList<>();
    requestRegistrationCallback.setRequestsToSend(requestInfos);

    PutOperation op = PutOperation.forUpload(routerConfig, routerMetrics, mockClusterMap,
        new LoggingNotificationSystem(), new InMemAccountService(true, false), userMetadata,
        channel, PutBlobOptions.DEFAULT, future, null,
        new RouterCallback(mockNetworkClient, new ArrayList<>()), null, null, null, null, time,
        blobProperties, MockClusterMap.DEFAULT_PARTITION_CLASS, quotaChargeCallback, compressionService);

    op.startOperation();

    // Process all chunks until operation is complete
    while (!op.isOperationComplete()) {
      op.fillChunks();
      requestInfos.clear();
      op.poll(requestRegistrationCallback);

      // Handle all responses
      for (RequestInfo requestInfo : requestInfos) {
        ResponseInfo responseInfo = getResponseInfo(requestInfo);
        PutResponse putResponse = responseInfo.getError() == null
            ? PutResponse.readFrom(new NettyByteBufDataInputStream(responseInfo.content()))
            : null;
        op.handleResponse(responseInfo, putResponse);
        requestInfo.getRequest().release();
        responseInfo.release();
      }
    }

    // Verify operation completed successfully
    assertTrue("Operation should be complete", op.isOperationComplete());
    assertNull("Operation should have no exception: " + op.getOperationException(),
        op.getOperationException());

    // Verify no memory leaks - the original buffer should have been fully released
    assertEquals("Original buffer should be fully released (refCnt = 0)",
        0, nettyBuffer.refCnt());
  }
}
