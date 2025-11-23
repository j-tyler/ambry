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
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import java.lang.reflect.Field;
import java.net.SocketAddress;
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
 * Tests for ByteBuf memory management in NettyResponseChannel.
 *
 * These tests verify that NettyResponseChannel correctly manages ByteBuf reference counts
 * according to Netty's reference counting contract: whoever creates or retains a ByteBuf
 * is responsible for releasing it.
 *
 * WHY THESE TESTS ARE CRITICAL:
 * NettyResponseChannel has two write methods with different ownership semantics:
 *
 * 1. write(ByteBuffer): NettyResponseChannel wraps the ByteBuffer in a ByteBuf internally.
 *    Since NettyResponseChannel creates this wrapper, it must release it. Failure to do so
 *    causes a native memory leak on every call.
 *
 * 2. write(ByteBuf): The caller provides their own ByteBuf and retains ownership.
 *    NettyResponseChannel must NOT release it, or it will cause double-free errors when
 *    the caller tries to release their own ByteBuf.
 *
 * Both tests use reflection to replace ByteBufs with TrackingByteBuf wrappers that monitor
 * whether release() was called, allowing us to verify correct ownership behavior. These are
 * regression tests to ensure the memory management contract is never violated.
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

    // Create channel with ChunkedWriteHandler
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
   * WHAT THIS TEST VERIFIES:
   * write(ByteBuffer) calls Unpooled.wrappedBuffer() which creates a ByteBuf with refCnt=1.
   * Since NettyResponseChannel creates this wrapper internally, it is responsible for releasing
   * it when the chunk is processed. This test verifies that resolveChunk() calls release()
   * on the wrapper, bringing its refCnt to 0.
   *
   * WHY THIS MATTERS:
   * If the wrapper is not released, every call to write(ByteBuffer) leaks native memory.
   * Netty allocates ByteBufs from pooled direct memory, so leaked buffers cause the pool
   * to grow unbounded until OOM. This test ensures the wrapper lifecycle is correct.
   *
   * TEST APPROACH:
   * Since we can't control what wrapper write(ByteBuffer) creates, we use reflection to
   * extract it after creation, then replace it with a TrackingByteBuf that monitors whether
   * release() gets called during normal chunk processing. This verifies the production code
   * correctly releases the wrapper.
   */
  @Test
  public void testWriteByteBufferReleasesWrapper() throws Exception {
    // Set up NettyResponseChannel with mock context that returns active channel
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

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

    // VERIFY: resolveChunk() must call release() on the wrapper it created
    assertTrue("Wrapper ByteBuf release() should have been called", trackingWrapper.wasReleased());
    assertEquals("Wrapper ByteBuf should have refCnt=0 after release", 0, trackingWrapper.refCnt());
  }

  /**
   * Verifies that write(ByteBuf) respects caller ownership and does NOT release the ByteBuf.
   *
   * WHAT THIS TEST VERIFIES:
   * When a caller passes a ByteBuf to write(ByteBuf), the caller retains ownership and is
   * responsible for releasing it. NettyResponseChannel stores the ByteBuf directly in a Chunk
   * without creating a wrapper. This test verifies that NettyResponseChannel does NOT call
   * release() on the caller's ByteBuf during chunk processing.
   *
   * WHY THIS MATTERS:
   * If NettyResponseChannel incorrectly releases the caller's ByteBuf, it causes double-free
   * errors when the caller tries to release their own ByteBuf (refCnt goes negative, triggering
   * IllegalReferenceCountException). The caller expects to maintain full lifecycle control of
   * ByteBufs they pass to write(ByteBuf). This test ensures the ownership contract is respected.
   *
   * TEST APPROACH:
   * Pass a caller-owned ByteBuf to write(ByteBuf), then use reflection to replace it with
   * a TrackingByteBuf that monitors whether NettyResponseChannel incorrectly calls release()
   * during chunk processing. The test then demonstrates correct ownership by having the caller
   * release the ByteBuf themselves.
   */
  @Test
  public void testWriteByteBufDoesNotReleaseCaller() throws Exception {
    // Set up NettyResponseChannel with mock context that returns active channel
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

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

    // VERIFY: NettyResponseChannel must NOT call release() on caller's ByteBuf
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

  /**
   * Mock ChannelHandlerContext that wraps an EmbeddedChannel.
   * This ensures ctx.channel().isActive() returns true, which is required for
   * maybeWriteResponseMetadata() to set finalResponseMetadata.
   */
  private static class MockChannelHandlerContext implements ChannelHandlerContext {
    private final EmbeddedChannel embeddedChannel;

    MockChannelHandlerContext(EmbeddedChannel embeddedChannel) {
      this.embeddedChannel = embeddedChannel;
    }

    @Override
    public Channel channel() {
      return embeddedChannel;
    }

    @Override
    public EventExecutor executor() {
      return embeddedChannel.eventLoop();
    }

    @Override
    public ChannelHandlerContext fireChannelRead(Object msg) {
      return this;
    }

    @Override
    public ChannelHandlerContext fireChannelReadComplete() {
      return this;
    }

    @Override
    public ChannelFuture write(Object msg) {
      return embeddedChannel.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
      return embeddedChannel.write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
      return embeddedChannel.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
      return embeddedChannel.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelPromise newPromise() {
      return embeddedChannel.newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
      return embeddedChannel.newProgressivePromise();
    }

    @Override
    public ChannelPipeline pipeline() {
      return embeddedChannel.pipeline();
    }

    // Remaining methods return null or no-op - only implement what NettyResponseChannel needs
    @Override public String name() { return null; }
    @Override public ChannelHandler handler() { return null; }
    @Override public boolean isRemoved() { return false; }
    @Override public ChannelHandlerContext fireChannelRegistered() { return this; }
    @Override public ChannelHandlerContext fireChannelUnregistered() { return this; }
    @Override public ChannelHandlerContext fireChannelActive() { return this; }
    @Override public ChannelHandlerContext fireChannelInactive() { return this; }
    @Override public ChannelHandlerContext fireExceptionCaught(Throwable cause) { return this; }
    @Override public ChannelHandlerContext fireUserEventTriggered(Object evt) { return this; }
    @Override public ChannelHandlerContext fireChannelWritabilityChanged() { return this; }
    @Override public ChannelFuture bind(SocketAddress localAddress) { return null; }
    @Override public ChannelFuture connect(SocketAddress remoteAddress) { return null; }
    @Override public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) { return null; }
    @Override public ChannelFuture disconnect() { return null; }
    @Override public ChannelFuture close() { return null; }
    @Override public ChannelFuture deregister() { return null; }
    @Override public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) { return null; }
    @Override public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) { return null; }
    @Override public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) { return null; }
    @Override public ChannelFuture disconnect(ChannelPromise promise) { return null; }
    @Override public ChannelFuture close(ChannelPromise promise) { return null; }
    @Override public ChannelFuture deregister(ChannelPromise promise) { return null; }
    @Override public ChannelHandlerContext read() { return this; }
    @Override public ChannelHandlerContext flush() { embeddedChannel.flush(); return this; }
    @Override public ChannelPromise voidPromise() { return embeddedChannel.voidPromise(); }
    @Override public ByteBufAllocator alloc() { return embeddedChannel.alloc(); }
    @Override public <T> Attribute<T> attr(AttributeKey<T> key) { return embeddedChannel.attr(key); }
    @Override public <T> boolean hasAttr(AttributeKey<T> key) { return embeddedChannel.hasAttr(key); }
  }
}
