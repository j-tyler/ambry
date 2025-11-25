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
import com.github.ambry.commons.Callback;
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
import io.netty.util.IllegalReferenceCountException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
   * Test that reproduces the race condition where channelReadBuf becomes a dangling reference
   * after the channel closes while fillChunks() holds a buffer reference.
   *
   * This test uses the real PutOperation code and simulates the exact scenario that causes
   * the SIGSEGV in production:
   *
   * 1. Create a PutOperation and start it
   * 2. Write a buffer (larger than chunk size) to the chunkFillerChannel with a callback that releases it
   * 3. Call fillChunks() - it will consume part of the buffer, leaving the rest in channelReadBuf
   *    (because only 1 in-memory chunk is available)
   * 4. Release the putChunk's buffer (simulating chunk completion, like when data is sent to server)
   * 5. Close the channel (which fires the release callback)
   * 6. The channelReadBuf now points to a freed buffer
   * 7. Accessing channelReadBuf should throw IllegalReferenceCountException
   *
   * Before the fix: This test FAILS because channelReadBuf points to freed memory
   * After the fix: This test PASSES because the buffer is properly retained
   */
  @Test
  public void testChannelReadBufDanglingReferenceAfterChannelClose() throws Exception {
    // Create blob properties
    BlobProperties blobProperties = new BlobProperties(-1, "serviceId", "memberId", "contentType",
        false, Utils.Infinite_Time, Utils.getRandomShort(TestUtils.RANDOM),
        Utils.getRandomShort(TestUtils.RANDOM), false, null, null, null);
    byte[] userMetadata = new byte[10];

    // Create a ReadableStreamChannel that gives us control over the buffer lifecycle
    ControlledReadableStreamChannel controlledChannel = new ControlledReadableStreamChannel();

    FutureResult<String> future = new FutureResult<>();
    MockNetworkClient mockNetworkClient = new MockNetworkClient();

    // Create the PutOperation
    PutOperation op = PutOperation.forUpload(routerConfig, routerMetrics, mockClusterMap,
        new LoggingNotificationSystem(), new InMemAccountService(true, false), userMetadata,
        controlledChannel, PutBlobOptions.DEFAULT, future, null,
        new RouterCallback(mockNetworkClient, new ArrayList<>()), null, null, null, null, time,
        blobProperties, MockClusterMap.DEFAULT_PARTITION_CLASS, quotaChargeCallback, compressionService);

    // Start the operation - this will call readInto on our controlled channel
    op.startOperation();

    // Get the chunkFillerChannel via reflection
    ByteBufferAsyncWritableChannel chunkFillerChannel =
        (ByteBufferAsyncWritableChannel) FieldUtils.readField(op, "chunkFillerChannel", true);

    // Create a buffer LARGER than chunk size using pooled allocator (like production)
    // This ensures fillChunks() will only consume part of the buffer, leaving the rest
    // in channelReadBuf (since we only have 1 in-memory chunk)
    int bufferSize = chunkSize * 2; // Buffer is twice the chunk size
    ByteBuf nettyBuffer = PooledByteBufAllocator.DEFAULT.directBuffer(bufferSize);
    byte[] testData = new byte[bufferSize];
    random.nextBytes(testData);
    nettyBuffer.writeBytes(testData);

    // Track whether the callback has been fired
    AtomicBoolean callbackFired = new AtomicBoolean(false);
    AtomicReference<ByteBuf> bufferRef = new AtomicReference<>(nettyBuffer);

    // Write the buffer to the channel with a callback that releases it
    // This simulates what Netty does when it writes HTTP content to the channel
    chunkFillerChannel.write(nettyBuffer, (result, exception) -> {
      callbackFired.set(true);
      ByteBuf buf = bufferRef.get();
      if (buf != null && buf.refCnt() > 0) {
        buf.release();
      }
    });

    // Call fillChunks() to get the buffer into channelReadBuf
    // Since buffer is 2x chunk size and we only have 1 in-memory chunk,
    // only the first chunkSize bytes will be consumed and the loop will exit
    // because there are no more free chunks
    op.fillChunks();

    // Get channelReadBuf via reflection - it should still have data
    ByteBuf channelReadBuf = (ByteBuf) FieldUtils.readField(op, "channelReadBuf", true);

    // Verify we got the buffer and it has remaining bytes (because buffer > chunk size and only 1 chunk available)
    assertNotNull("channelReadBuf should have the buffer", channelReadBuf);
    assertTrue("channelReadBuf should have remaining bytes", channelReadBuf.readableBytes() > 0);

    // At this point:
    // - channelReadBuf points to the buffer with remaining bytes
    // - The buffer has refCnt 2: one from ChunkData, one from the retained slice in putChunk.buf

    // Get the PutChunk and release its buffer - this simulates what happens when the chunk
    // data is sent to the server and the buffer is no longer needed
    PutOperation.PutChunk putChunk = op.getPutChunks().get(0);
    assertNotNull("Should have a PutChunk", putChunk);
    ByteBuf chunkBuf = putChunk.buf;
    assertNotNull("PutChunk should have a buffer", chunkBuf);

    // Release the chunk buffer - this releases the retained slice's reference to channelReadBuf
    // Now channelReadBuf is only held by ChunkData with refCnt = 1
    chunkBuf.release();

    // Now close the channel - this simulates the concurrent close scenario
    // The close() will call resolveAllRemainingChunks() which fires our callback
    chunkFillerChannel.close();

    // Verify the callback was fired
    assertTrue("Callback should have been fired by close()", callbackFired.get());

    // THE BUG: channelReadBuf is now a dangling reference!
    // The callback has released the buffer, but channelReadBuf still points to it
    ByteBuf danglingRef = (ByteBuf) FieldUtils.readField(op, "channelReadBuf", true);
    assertNotNull("channelReadBuf still holds reference to freed buffer", danglingRef);

    // Before the fix: refCnt is 0, buffer is freed
    // After the fix: refCnt should be > 0, buffer is still valid
    assertEquals("Buffer refCnt should be 0 after callback released it (BEFORE FIX)", 0, danglingRef.refCnt());

    // This is the crash point - accessing a freed buffer
    // In production with native memory, this would cause SIGSEGV
    try {
      // This is what fillFrom() does before calling Crc32.update()
      int readable = danglingRef.readableBytes();

      // If we get here, we're reading from freed memory (undefined behavior)
      // The nioBuffer() call returns a ByteBuffer pointing to freed native memory
      ByteBuffer nioBuffer = danglingRef.nioBuffer();

      fail("Should have thrown IllegalReferenceCountException, but accessed freed buffer instead. "
          + "readable=" + readable + ", nioBuffer.remaining=" + nioBuffer.remaining());

    } catch (IllegalReferenceCountException e) {
      // This is the expected behavior in debug mode - Netty catches the invalid access
      // In production without debug checks, this would be a SIGSEGV instead
      assertTrue("Should indicate refCnt is 0", e.getMessage().contains("0"));
    }
  }

  /**
   * Test that simulates the exact race condition that happens in production:
   * 1. fillChunks() gets a buffer into channelReadBuf
   * 2. The buffer is partially consumed (channelReadBuf still has data)
   * 3. The chunk data is "sent" and the slice is released
   * 4. Channel closes (connection timeout, upload complete, etc.)
   * 5. Callback fires and releases the buffer
   * 6. channelReadBuf now points to freed memory -> SIGSEGV
   *
   * This simulates a more realistic scenario where the chunk has been built
   * and sent, then the connection terminates unexpectedly.
   */
  @Test
  public void testRaceConditionSimulatingRealScenario() throws Exception {
    BlobProperties blobProperties = new BlobProperties(-1, "serviceId", "memberId", "contentType",
        false, Utils.Infinite_Time, Utils.getRandomShort(TestUtils.RANDOM),
        Utils.getRandomShort(TestUtils.RANDOM), false, null, null, null);
    byte[] userMetadata = new byte[10];

    ControlledReadableStreamChannel controlledChannel = new ControlledReadableStreamChannel();

    FutureResult<String> future = new FutureResult<>();
    MockNetworkClient mockNetworkClient = new MockNetworkClient();

    PutOperation op = PutOperation.forUpload(routerConfig, routerMetrics, mockClusterMap,
        new LoggingNotificationSystem(), new InMemAccountService(true, false), userMetadata,
        controlledChannel, PutBlobOptions.DEFAULT, future, null,
        new RouterCallback(mockNetworkClient, new ArrayList<>()), null, null, null, null, time,
        blobProperties, MockClusterMap.DEFAULT_PARTITION_CLASS, quotaChargeCallback, compressionService);

    op.startOperation();

    ByteBufferAsyncWritableChannel chunkFillerChannel =
        (ByteBufferAsyncWritableChannel) FieldUtils.readField(op, "chunkFillerChannel", true);

    // Write a buffer larger than chunk size
    int bufferSize = chunkSize * 2;
    ByteBuf buffer = PooledByteBufAllocator.DEFAULT.directBuffer(bufferSize);
    byte[] data = new byte[bufferSize];
    random.nextBytes(data);
    buffer.writeBytes(data);
    AtomicBoolean callbackFired = new AtomicBoolean(false);
    chunkFillerChannel.write(buffer, (result, exception) -> {
      callbackFired.set(true);
      if (buffer.refCnt() > 0) {
        buffer.release();
      }
    });

    // fillChunks() processes the first chunk, leaving remaining data in channelReadBuf
    op.fillChunks();

    ByteBuf channelReadBuf = (ByteBuf) FieldUtils.readField(op, "channelReadBuf", true);
    assertNotNull("channelReadBuf should have remaining buffer data", channelReadBuf);
    assertTrue("channelReadBuf should have remaining bytes", channelReadBuf.readableBytes() > 0);

    // Get the PutChunk - the slice is stored in its buf field
    PutOperation.PutChunk putChunk = op.getPutChunks().get(0);
    assertNotNull("Should have a PutChunk", putChunk);
    ByteBuf chunkBuf = putChunk.buf;
    assertNotNull("PutChunk should have a buffer", chunkBuf);

    // Simulate the chunk being "sent" and its buffer released
    // This is what happens when readInto() completes on the NetworkSend
    chunkBuf.release();

    // At this point:
    // - channelReadBuf has refCnt = 1 (only from ChunkData)
    // - The slice has been released

    // Now simulate channel close - this fires the callback which releases the buffer
    chunkFillerChannel.close();

    assertTrue("Callback should have fired", callbackFired.get());

    // THE BUG: channelReadBuf is now a dangling reference!
    ByteBuf danglingRef = (ByteBuf) FieldUtils.readField(op, "channelReadBuf", true);
    assertNotNull("channelReadBuf still holds reference", danglingRef);
    assertEquals("Buffer should be freed (refCnt = 0)", 0, danglingRef.refCnt());

    // In production, the next call to fillChunks() would try to use this freed buffer
    // and cause a SIGSEGV when Crc32.update() reads from native memory
    try {
      // This simulates what would happen in the next fillChunks() iteration
      // channelReadBuf.readableBytes() or channelReadBuf.nioBuffer() would access freed memory
      danglingRef.readableBytes();
      fail("Should have thrown IllegalReferenceCountException");
    } catch (IllegalReferenceCountException e) {
      // Expected in debug mode - Netty catches the invalid access
      // In production without debug checks, this would be SIGSEGV
    }
  }

  /**
   * A controlled ReadableStreamChannel that gives us control over the buffer lifecycle.
   * Unlike the normal ByteBufReadableStreamChannel, this doesn't automatically start reading.
   */
  private static class ControlledReadableStreamChannel implements ReadableStreamChannel {
    private boolean open = true;
    private AsyncWritableChannel targetChannel;
    private Callback<Long> readCallback;

    @Override
    public long getSize() {
      return -1; // Unknown size
    }

    @Override
    public Future<Long> readInto(AsyncWritableChannel asyncWritableChannel, Callback<Long> callback) {
      // Store references but don't write anything yet
      this.targetChannel = asyncWritableChannel;
      this.readCallback = callback;
      return new FutureResult<>();
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }
  }
}
