/**
 * Copyright 2024 LinkedIn Corp. All rights reserved.
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
import com.github.ambry.quota.QuotaChargeCallback;
import com.github.ambry.quota.QuotaTestUtils;
import com.github.ambry.utils.MockTime;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import com.github.ambry.utils.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Random;
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
  private final QuotaChargeCallback quotaChargeCallback = QuotaTestUtils.createTestQuotaChargeCallback();
  private final Random random = new Random();
  private final int chunkSize = 4096;
  private final NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();

  private RouterConfig routerConfig;
  private CompressionService compressionService;

  public PutOperationChannelReadBufRaceConditionTest() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("router.hostname", "localhost");
    properties.setProperty("router.datacenter.name", "DC1");
    properties.setProperty("router.max.put.chunk.size.bytes", Integer.toString(chunkSize));
    properties.setProperty("router.put.request.parallelism", "3");
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
   * Test that verifies channelReadBuf remains valid after the channel closes.
   *
   * This test uses actual production code paths:
   * - ByteBufReadableStreamChannel.readInto() writes buffer to chunkFillerChannel
   * - The callback registered by readInto() releases the buffer when fired
   * - PutOperation.fillChunks() processes the buffer
   *
   * The scenario:
   * 1. Create a PutOperation with ByteBufReadableStreamChannel (buffer > chunk size)
   * 2. startOperation() calls readInto() which writes buffer with release callback
   * 3. fillChunks() consumes part of buffer, stores remainder in channelReadBuf
   * 4. Release putChunk.buf (simulates chunk data sent over network)
   * 5. Close chunkFillerChannel - fires the readInto callback which releases buffer
   * 6. Verify channelReadBuf is still valid and accessible
   *
   * Before the fix: This test FAILS because channelReadBuf points to freed memory (refCnt = 0)
   * After the fix: This test PASSES because the buffer is properly retained (refCnt > 0)
   */
  @Test
  public void testChannelReadBufRemainsValidAfterChannelClose() throws Exception {
    // Create blob properties
    BlobProperties blobProperties = new BlobProperties(-1, "serviceId", "memberId", "contentType",
        false, Utils.Infinite_Time, Utils.getRandomShort(TestUtils.RANDOM),
        Utils.getRandomShort(TestUtils.RANDOM), false, null, null, null);
    byte[] userMetadata = new byte[10];

    // Create a buffer LARGER than chunk size using pooled allocator (like production)
    // This ensures fillChunks() will only consume part of the buffer, leaving the rest
    // in channelReadBuf (since we only have 1 in-memory chunk configured)
    int bufferSize = chunkSize * 2;
    ByteBuf nettyBuffer = PooledByteBufAllocator.DEFAULT.directBuffer(bufferSize);
    byte[] testData = new byte[bufferSize];
    random.nextBytes(testData);
    nettyBuffer.writeBytes(testData);

    // Use ByteBufReadableStreamChannel - this is the actual production class that:
    // 1. Writes the buffer to the channel in readInto()
    // 2. Registers a callback that calls buf.release() when the chunk is resolved
    ByteBufReadableStreamChannel channel = new ByteBufReadableStreamChannel(nettyBuffer);

    FutureResult<String> future = new FutureResult<>();
    MockNetworkClient mockNetworkClient = new MockNetworkClient();

    // Create the PutOperation
    PutOperation op = PutOperation.forUpload(routerConfig, routerMetrics, mockClusterMap,
        new LoggingNotificationSystem(), new InMemAccountService(true, false), userMetadata,
        channel, PutBlobOptions.DEFAULT, future, null,
        new RouterCallback(mockNetworkClient, new ArrayList<>()), null, null, null, null, time,
        blobProperties, MockClusterMap.DEFAULT_PARTITION_CLASS, quotaChargeCallback, compressionService);

    // Start the operation - this calls channel.readInto(chunkFillerChannel, callback)
    // which writes nettyBuffer to chunkFillerChannel with a callback that releases it
    op.startOperation();

    // Call fillChunks() to process the buffer
    // Since buffer is 2x chunk size and we only have 1 in-memory chunk,
    // only the first chunkSize bytes will be consumed via fillFrom()
    // The remaining bytes stay in channelReadBuf
    op.fillChunks();

    // Get channelReadBuf via reflection - it should still have remaining data
    ByteBuf channelReadBuf = (ByteBuf) FieldUtils.readField(op, "channelReadBuf", true);
    assertNotNull("channelReadBuf should have the buffer", channelReadBuf);
    assertTrue("channelReadBuf should have remaining bytes", channelReadBuf.readableBytes() > 0);

    // Get the PutChunk - its buf field holds a retained slice created by fillFrom()
    PutOperation.PutChunk putChunk = op.getPutChunks().get(0);
    assertNotNull("Should have a PutChunk", putChunk);
    ByteBuf chunkBuf = putChunk.buf;
    assertNotNull("PutChunk should have a buffer (retained slice)", chunkBuf);

    // Release the chunk buffer - this is what happens when chunk data is sent over
    // the network and the NetworkSend completes. This releases the retained slice's
    // reference to the underlying buffer.
    chunkBuf.release();

    // Get chunkFillerChannel and close it - this triggers resolveAllRemainingChunks()
    // which fires the callback that ByteBufReadableStreamChannel registered.
    // That callback calls buf.release() on the original buffer.
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
    assertTrue("Buffer should still be valid (refCnt > 0) after channel close. "
            + "Current refCnt=" + bufAfterClose.refCnt() + ". "
            + "If refCnt is 0, the use-after-free bug is present.",
        bufAfterClose.refCnt() > 0);

    // The buffer should be safely accessible without throwing IllegalReferenceCountException
    // Before the fix, this would throw IllegalReferenceCountException (or SIGSEGV in production)
    int readable = bufAfterClose.readableBytes();
    assertTrue("Buffer should have readable bytes", readable > 0);

    // Clean up - release the buffer that PutOperation retained (after fix is applied)
    if (bufAfterClose.refCnt() > 0) {
      bufAfterClose.release();
    }
  }

  /**
   * Test that verifies the buffer can be safely used after channel close in a realistic scenario.
   *
   * Same as above but structured slightly differently to ensure coverage.
   *
   * Before the fix: This test FAILS because channelReadBuf points to freed memory
   * After the fix: This test PASSES because the buffer is properly retained
   */
  @Test
  public void testBufferSafetyInRealisticScenario() throws Exception {
    BlobProperties blobProperties = new BlobProperties(-1, "serviceId", "memberId", "contentType",
        false, Utils.Infinite_Time, Utils.getRandomShort(TestUtils.RANDOM),
        Utils.getRandomShort(TestUtils.RANDOM), false, null, null, null);
    byte[] userMetadata = new byte[10];

    // Create buffer larger than chunk size
    int bufferSize = chunkSize * 2;
    ByteBuf buffer = PooledByteBufAllocator.DEFAULT.directBuffer(bufferSize);
    byte[] data = new byte[bufferSize];
    random.nextBytes(data);
    buffer.writeBytes(data);

    // Use actual ByteBufReadableStreamChannel - its readInto() registers a callback
    // that releases the buffer when the write is resolved
    ByteBufReadableStreamChannel channel = new ByteBufReadableStreamChannel(buffer);

    FutureResult<String> future = new FutureResult<>();
    MockNetworkClient mockNetworkClient = new MockNetworkClient();

    PutOperation op = PutOperation.forUpload(routerConfig, routerMetrics, mockClusterMap,
        new LoggingNotificationSystem(), new InMemAccountService(true, false), userMetadata,
        channel, PutBlobOptions.DEFAULT, future, null,
        new RouterCallback(mockNetworkClient, new ArrayList<>()), null, null, null, null, time,
        blobProperties, MockClusterMap.DEFAULT_PARTITION_CLASS, quotaChargeCallback, compressionService);

    // startOperation() calls channel.readInto() which writes buffer to chunkFillerChannel
    op.startOperation();

    // fillChunks() processes the first chunk, leaving remaining data in channelReadBuf
    op.fillChunks();

    ByteBuf channelReadBuf = (ByteBuf) FieldUtils.readField(op, "channelReadBuf", true);
    assertNotNull("channelReadBuf should have remaining buffer data", channelReadBuf);
    assertTrue("channelReadBuf should have remaining bytes", channelReadBuf.readableBytes() > 0);

    // Get the PutChunk and release its buffer (simulates network send completion)
    PutOperation.PutChunk putChunk = op.getPutChunks().get(0);
    assertNotNull("Should have a PutChunk", putChunk);
    ByteBuf chunkBuf = putChunk.buf;
    assertNotNull("PutChunk should have a buffer", chunkBuf);
    chunkBuf.release();

    // Close chunkFillerChannel - fires the ByteBufReadableStreamChannel callback
    ByteBufferAsyncWritableChannel chunkFillerChannel =
        (ByteBufferAsyncWritableChannel) FieldUtils.readField(op, "chunkFillerChannel", true);
    chunkFillerChannel.close();

    // Get the reference to channelReadBuf after channel close
    ByteBuf bufAfterClose = (ByteBuf) FieldUtils.readField(op, "channelReadBuf", true);
    assertNotNull("channelReadBuf should still hold a reference", bufAfterClose);

    // THE FIX VERIFICATION:
    // The buffer should still be valid and accessible.
    // Before the fix, refCnt is 0 and this assertion FAILS.
    assertTrue("Buffer should still be valid (refCnt > 0) after channel close. "
            + "Current refCnt=" + bufAfterClose.refCnt() + ". "
            + "If refCnt is 0, the use-after-free bug is present.",
        bufAfterClose.refCnt() > 0);

    // Verify the buffer can be safely read - this is what fillChunks() would do
    // Before the fix, this throws IllegalReferenceCountException (or SIGSEGV in production)
    int readable = bufAfterClose.readableBytes();
    assertTrue("Buffer should have readable bytes", readable > 0);

    // Clean up
    if (bufAfterClose.refCnt() > 0) {
      bufAfterClose.release();
    }
  }
}
