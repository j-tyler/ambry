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
package com.github.ambry.rest;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.commons.Callback;
import com.github.ambry.config.NettyConfig;
import com.github.ambry.config.PerformanceConfig;
import com.github.ambry.config.VerifiableProperties;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for ByteBuf memory leak in NettyResponseChannel.
 *
 * NettyResponseChannel.write(ByteBuffer) creates a memory leak by wrapping the input ByteBuffer
 * in a Netty ByteBuf wrapper without releasing it. The callback has no reference to it.
 * According to Netty's reference counting contract, whoever creates or retains a ByteBuf is
 * responsible for releasing it. Since NettyResponseChannel creates the wrapper internally,
 * it must release it. The original code never calls wrapper.release() in resolveChunk(),
 * causing every call to write(ByteBuffer) to leak native memory.
 *
 * This test uses reflection to replace the internal wrapper ByteBuf with a TrackingByteBuf
 * that tracks whether release() was called, proving the bug exists before the fix.
 */
public class NettyResponseChannelByteBufLeakTest {
  private static final long CALLBACK_TIMEOUT_MS = 5000;

  private EmbeddedChannel channel;
  private NettyMetrics nettyMetrics;
  private NettyConfig nettyConfig;
  private PerformanceConfig performanceConfig;

  @Before
  public void setUp() {
    nettyMetrics = new NettyMetrics(new MetricRegistry());

    Properties properties = new Properties();
    VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
    nettyConfig = new NettyConfig(verifiableProperties);
    performanceConfig = new PerformanceConfig(verifiableProperties);

    // Create channel with ChunkedWriteHandler in constructor to get proper context
    ChunkedWriteHandler chunkedWriteHandler = new ChunkedWriteHandler();
    channel = new EmbeddedChannel(chunkedWriteHandler);
  }

  @After
  public void tearDown() {
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
  }

  /**
   * Verifies that write(ByteBuffer) properly releases internal wrapper ByteBufs.
   *
   * Bug: write(ByteBuffer) calls Unpooled.wrappedBuffer() which creates a ByteBuf
   * with refCnt=1. This wrapper is stored in a Chunk, but resolveChunk() never
   * calls release(), causing a memory leak.
   *
   * Test strategy:
   * 1. Call write(ByteBuffer) to create internal wrapper via Unpooled.wrappedBuffer()
   * 2. Use reflection to extract the wrapper ByteBuf from the Chunk
   * 3. Use reflection to replace it with a TrackingByteBuf that tracks release() calls
   * 4. Trigger chunk processing through normal flow (onResponseComplete)
   * 5. Assert that release() was called on the wrapper
   *
   * Before fix: Assertion FAILS - release() was NOT called (leaked)
   * After fix: Assertion PASSES - release() WAS called (released in resolveChunk)
   */
  @Test
  public void testWriteByteBufferReleasesWrapper() throws Exception {
    ChunkedWriteHandler handler = channel.pipeline().get(ChunkedWriteHandler.class);
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        channel.pipeline().context(handler), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // Create ByteBuffer to write
    String data = "test data";
    ByteBuffer byteBuffer = ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));

    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    // Get the wrapper ByteBuf that was created by write() using reflection
    ByteBuf originalWrapper = getWrapperFromChannel(responseChannel);
    assertNotNull("Wrapper should exist in chunksToWrite", originalWrapper);

    // Replace it with our tracking wrapper
    TrackingByteBuf trackingWrapper = new TrackingByteBuf(originalWrapper);
    replaceWrapperInChunk(responseChannel, trackingWrapper);

    // Trigger processing
    responseChannel.onResponseComplete(null);
    assertTrue("Callback should complete", callback.awaitCompletion(CALLBACK_TIMEOUT_MS));

    // Read and release HttpContent
    HttpResponse response = channel.readOutbound();
    assertNotNull("Should have response", response);

    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof HttpContent) {
        ((HttpContent) outbound).release();
      }
    }

    // CRITICAL ASSERTION: release() should have been called on the wrapper
    // Before fix: release() was NOT called (leak)
    // After fix: release() WAS called
    assertTrue("Wrapper ByteBuf release() should have been called", trackingWrapper.wasReleased());
    assertEquals("Wrapper ByteBuf should have refCnt=0 after release", 0, trackingWrapper.refCnt());
  }

  /**
   * Verifies that write(ByteBuf) does NOT release the ByteBuf.
   *
   * According to AsyncWritableChannel contract, when a caller passes a ByteBuf
   * to write(ByteBuf), the caller retains ownership and is responsible for
   * releasing it. NettyResponseChannel should NOT release ByteBufs passed to
   * write(ByteBuf).
   *
   * Test strategy:
   * 1. Create a ByteBuf with refCnt=1
   * 2. Call write(ByteBuf) directly (not write(ByteBuffer))
   * 3. Get reference to this ByteBuf from the Chunk using reflection
   * 4. Replace it with TrackingByteBuf to monitor release() calls
   * 5. Trigger chunk processing
   * 6. Assert that release() was NOT called (caller still owns it)
   *
   * This test verifies the correct behavior - write(ByteBuf) should NOT leak
   * because it should NOT release the caller's ByteBuf.
   */
  @Test
  public void testWriteByteBufDoesNotReleaseCaller() throws Exception {
    ChunkedWriteHandler handler = channel.pipeline().get(ChunkedWriteHandler.class);
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        channel.pipeline().context(handler), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // Create a ByteBuf (caller owns this)
    String data = "test data";
    ByteBuf callerByteBuf = io.netty.buffer.Unpooled.wrappedBuffer(data.getBytes(StandardCharsets.UTF_8));
    assertEquals("Caller ByteBuf should start with refCnt=1", 1, callerByteBuf.refCnt());

    TestCallback callback = new TestCallback();
    responseChannel.write(callerByteBuf, callback);

    // Get the ByteBuf from the Chunk - should be the same one we passed
    ByteBuf chunkByteBuf = getWrapperFromChannel(responseChannel);
    assertNotNull("ByteBuf should exist in chunksToWrite", chunkByteBuf);
    assertSame("Chunk should contain the same ByteBuf we passed", callerByteBuf, chunkByteBuf);

    // Replace with tracking wrapper to monitor release() calls
    TrackingByteBuf trackingWrapper = new TrackingByteBuf(callerByteBuf);
    replaceWrapperInChunk(responseChannel, trackingWrapper);

    // Trigger processing
    responseChannel.onResponseComplete(null);
    assertTrue("Callback should complete", callback.awaitCompletion(CALLBACK_TIMEOUT_MS));

    // Read and release HttpContent
    HttpResponse response = channel.readOutbound();
    assertNotNull("Should have response", response);

    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof HttpContent) {
        ((HttpContent) outbound).release();
      }
    }

    // CRITICAL ASSERTION: release() should NOT have been called on caller's ByteBuf
    // The caller is responsible for releasing it, not NettyResponseChannel
    assertFalse("Caller's ByteBuf release() should NOT have been called by NettyResponseChannel",
        trackingWrapper.wasReleased());

    // Clean up - caller releases their own ByteBuf
    callerByteBuf.release();
    assertEquals("Caller ByteBuf should be released by caller", 0, callerByteBuf.refCnt());
  }

  // Helper classes

  /**
   * ByteBuf wrapper that tracks whether release() was called.
   * This allows us to verify that the wrapper created by write(ByteBuffer)
   * is properly released by resolveChunk().
   */
  private static class TrackingByteBuf extends DelegateByteBuf {
    private volatile boolean released = false;

    TrackingByteBuf(ByteBuf delegate) {
      super(delegate);
    }

    @Override
    public boolean release() {
      released = true;
      return super.release();
    }

    @Override
    public boolean release(int decrement) {
      released = true;
      return super.release(decrement);
    }

    boolean wasReleased() {
      return released;
    }
  }

  /**
   * Test callback implementation
   */
  private static class TestCallback implements Callback<Long> {
    volatile Long result = null;
    volatile Exception exception = null;
    private final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void onCompletion(Long result, Exception exception) {
      this.result = result;
      this.exception = exception;
      latch.countDown();
    }

    boolean awaitCompletion(long timeoutMs) throws InterruptedException {
      return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }
  }

  // Helper methods

  /**
   * Gets the wrapper ByteBuf from the channel's chunk queue using reflection.
   * This allows us to verify the internal ByteBuf's refCount before and after resolution.
   *
   * @param responseChannel the NettyResponseChannel to extract the wrapper from
   * @return the wrapper ByteBuf from chunksToWrite queue
   * @throws Exception if reflection fails
   */
  private ByteBuf getWrapperFromChannel(NettyResponseChannel responseChannel) throws Exception {
    // Access the private chunksToWrite field
    Field chunksToWriteField = NettyResponseChannel.class.getDeclaredField("chunksToWrite");
    chunksToWriteField.setAccessible(true);
    Queue<?> chunksToWrite = (Queue<?>) chunksToWriteField.get(responseChannel);

    // Get the first Chunk
    Object chunk = chunksToWrite.peek();
    if (chunk == null) {
      return null;
    }

    // Access the Chunk's buffer field
    Class<?> chunkClass = chunk.getClass();
    Field bufferField = chunkClass.getDeclaredField("buffer");
    bufferField.setAccessible(true);
    return (ByteBuf) bufferField.get(chunk);
  }

  /**
   * Replaces the wrapper ByteBuf in the chunk with a tracking wrapper.
   * This allows us to verify whether release() is called on it.
   *
   * @param responseChannel the NettyResponseChannel containing the chunk
   * @param newWrapper the tracking wrapper to replace with
   * @throws Exception if reflection fails
   */
  private void replaceWrapperInChunk(NettyResponseChannel responseChannel, ByteBuf newWrapper) throws Exception {
    // Access the private chunksToWrite field
    Field chunksToWriteField = NettyResponseChannel.class.getDeclaredField("chunksToWrite");
    chunksToWriteField.setAccessible(true);
    Queue<?> chunksToWrite = (Queue<?>) chunksToWriteField.get(responseChannel);

    // Get the first Chunk
    Object chunk = chunksToWrite.peek();
    assertNotNull("Chunk should exist", chunk);

    // Access the Chunk's buffer field and replace it
    Class<?> chunkClass = chunk.getClass();
    Field bufferField = chunkClass.getDeclaredField("buffer");
    bufferField.setAccessible(true);
    bufferField.set(chunk, newWrapper);
  }

}
