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
 * This test suite verifies two distinct ownership models:
 * 1. write(ByteBuffer): NettyResponseChannel creates and owns the wrapper → must release it
 * 2. write(ByteBuf): Caller owns the ByteBuf → NettyResponseChannel must NOT release it
 *
 * Both tests use reflection to replace ByteBufs with TrackingByteBuf wrappers that monitor
 * whether release() was called, allowing us to verify correct ownership behavior.
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
   * THE BUG:
   * write(ByteBuffer) calls Unpooled.wrappedBuffer() which creates a ByteBuf with refCnt=1.
   * This wrapper is stored in a Chunk, but resolveChunk() never calls release(), causing
   * a memory leak of native memory.
   *
   * TEST APPROACH:
   * Since we can't control what wrapper write(ByteBuffer) creates, we use reflection to
   * extract it after creation, then replace it with a TrackingByteBuf that monitors whether
   * release() gets called during normal processing.
   *
   * EXPECTED BEHAVIOR:
   * Before fix: release() is NOT called → wrapper leaks (refCnt stays 1) → TEST FAILS
   * After fix: release() IS called in resolveChunk() → refCnt becomes 0 → TEST PASSES
   */
  @Test
  public void testWriteByteBufferReleasesWrapper() throws Exception {
    // Set up NettyResponseChannel with real pipeline context
    ChunkedWriteHandler chunkedWriteHandler = channel.pipeline().get(ChunkedWriteHandler.class);
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        channel.pipeline().context(chunkedWriteHandler), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // Call write(ByteBuffer) - this is the production code path that creates the wrapper
    String testData = "test data";
    ByteBuffer byteBuffer = ByteBuffer.wrap(testData.getBytes(StandardCharsets.UTF_8));

    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    // Use reflection to extract the wrapper ByteBuf that write() created internally
    ByteBuf internalWrapper = getWrapperFromChannel(responseChannel);
    assertNotNull("Wrapper should exist in chunksToWrite", internalWrapper);

    // Replace the internal wrapper with our tracking wrapper to monitor release() calls
    // WHY: We can't intercept the wrapper creation, so we swap it after the fact
    TrackingByteBuf trackingWrapper = new TrackingByteBuf(internalWrapper);
    replaceWrapperInChunk(responseChannel, trackingWrapper);

    // Execute normal response processing flow
    responseChannel.onResponseComplete(null);
    assertTrue("Callback should complete", callback.awaitCompletion(CALLBACK_TIMEOUT_MS));
    assertNull("Callback should complete successfully without exception", callback.exception);

    // Simulate Netty's normal pipeline: read and release the HttpContent
    // WHY: This triggers resolveChunk() which should release the wrapper
    HttpResponse response = channel.readOutbound();
    assertNotNull("Should have response", response);

    HttpContent content;
    while ((content = channel.readOutbound()) != null) {
      content.release();
    }

    // VERIFY THE FIX: resolveChunk() should have called release() on the wrapper
    assertTrue("Wrapper ByteBuf release() should have been called", trackingWrapper.wasReleased());
    assertEquals("Wrapper ByteBuf should have refCnt=0 after release", 0, trackingWrapper.refCnt());
  }

  /**
   * Verifies that write(ByteBuf) does NOT release the caller's ByteBuf.
   *
   * THE CONTRACT:
   * According to AsyncWritableChannel ownership semantics, when a caller passes a ByteBuf
   * to write(ByteBuf), the caller retains ownership and is responsible for releasing it.
   * NettyResponseChannel should NOT release ByteBufs passed to write(ByteBuf).
   *
   * TEST APPROACH:
   * Pass a caller-owned ByteBuf to write(ByteBuf), then use reflection to replace it with
   * a TrackingByteBuf that monitors whether NettyResponseChannel incorrectly calls release()
   * on the caller's ByteBuf during chunk processing.
   *
   * EXPECTED BEHAVIOR:
   * NettyResponseChannel should NOT call release() on the caller's ByteBuf.
   * The caller remains responsible for releasing it after the write completes.
   * This test verifies correct ownership semantics - write(ByteBuf) should NOT leak
   * because it should NOT touch the caller's reference count.
   */
  @Test
  public void testWriteByteBufDoesNotReleaseCaller() throws Exception {
    // Set up NettyResponseChannel with real pipeline context
    ChunkedWriteHandler chunkedWriteHandler = channel.pipeline().get(ChunkedWriteHandler.class);
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        channel.pipeline().context(chunkedWriteHandler), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // Create a ByteBuf - the caller owns this and is responsible for releasing it
    String testData = "test data";
    ByteBuf callerByteBuf = io.netty.buffer.Unpooled.wrappedBuffer(testData.getBytes(StandardCharsets.UTF_8));
    assertEquals("Caller ByteBuf should start with refCnt=1", 1, callerByteBuf.refCnt());

    // Call write(ByteBuf) - this is the production code path where caller passes their own ByteBuf
    TestCallback callback = new TestCallback();
    responseChannel.write(callerByteBuf, callback);

    // Verify the Chunk contains the same ByteBuf we passed (not a wrapper)
    // WHY: write(ByteBuf) should store the caller's ByteBuf directly, unlike write(ByteBuffer)
    ByteBuf chunkByteBuf = getWrapperFromChannel(responseChannel);
    assertNotNull("ByteBuf should exist in chunksToWrite", chunkByteBuf);
    assertSame("Chunk should contain the same ByteBuf we passed", callerByteBuf, chunkByteBuf);

    // Replace with tracking wrapper to monitor release() calls
    // WHY: We need to detect if NettyResponseChannel incorrectly calls release() on the caller's ByteBuf
    TrackingByteBuf trackingWrapper = new TrackingByteBuf(callerByteBuf);
    replaceWrapperInChunk(responseChannel, trackingWrapper);

    // Execute normal response processing flow
    responseChannel.onResponseComplete(null);
    assertTrue("Callback should complete", callback.awaitCompletion(CALLBACK_TIMEOUT_MS));
    assertNull("Callback should complete successfully without exception", callback.exception);

    // Simulate Netty's normal pipeline: read and release the HttpContent
    // WHY: This triggers chunk processing which should NOT release the caller's ByteBuf
    HttpResponse response = channel.readOutbound();
    assertNotNull("Should have response", response);

    HttpContent content;
    while ((content = channel.readOutbound()) != null) {
      content.release();
    }

    // VERIFY THE CONTRACT: NettyResponseChannel should NOT call release() on caller's ByteBuf
    // The caller retains ownership and must release it themselves
    assertFalse("Caller's ByteBuf release() should NOT have been called by NettyResponseChannel",
        trackingWrapper.wasReleased());

    // Clean up - caller releases their own ByteBuf (demonstrating correct ownership)
    callerByteBuf.release();
    assertEquals("Caller ByteBuf should be released by caller", 0, callerByteBuf.refCnt());
  }

  // Helper classes

  /**
   * ByteBuf wrapper that tracks whether release() was called.
   * Used to verify correct ByteBuf ownership and release behavior in both test scenarios:
   * - write(ByteBuffer): verifies the internal wrapper IS released
   * - write(ByteBuf): verifies the caller's ByteBuf is NOT released
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
