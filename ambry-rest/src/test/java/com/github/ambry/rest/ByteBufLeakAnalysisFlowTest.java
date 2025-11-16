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
import com.github.ambry.utils.NettyByteBufLeakHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelProgressivePromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ByteBuf Flow Analysis Tests for BYTEBUF_LEAK_ANALYSIS_PART3.md
 *
 * These tests replicate ACTUAL PRODUCTION PATTERNS for NettyResponseChannel$Chunk to analyze
 * ByteBuf flow with the ByteBuf Flow Tracker agent. Tests are designed to PASS (not fail) while
 * demonstrating various flow scenarios including potential leak cases.
 *
 * Run with: ./gradlew :ambry-rest:test --tests ByteBufLeakAnalysisFlowTest -PwithByteBufTracking
 *
 * Class under test: NettyResponseChannel$Chunk (internal class)
 * Production pattern: ByteBuffer -> Unpooled.wrappedBuffer() -> Chunk -> HttpContent -> Netty
 */
public class ByteBufLeakAnalysisFlowTest {
  private NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();
  private EmbeddedChannel channel;
  private NettyMetrics nettyMetrics;
  private NettyConfig nettyConfig;
  private PerformanceConfig performanceConfig;

  @Before
  public void setUp() {
    nettyByteBufLeakHelper.beforeTest();
    nettyMetrics = new NettyMetrics(new MetricRegistry());

    Properties properties = new Properties();
    VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
    nettyConfig = new NettyConfig(verifiableProperties);
    performanceConfig = new PerformanceConfig(verifiableProperties);

    // Create channel with ChunkedWriteHandler like production
    channel = new EmbeddedChannel();
    ChunkedWriteHandler chunkedWriteHandler = new ChunkedWriteHandler();
    channel.pipeline().addLast(chunkedWriteHandler);
  }

  @After
  public void tearDown() {
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
    nettyByteBufLeakHelper.afterTest();
  }

  // ========================================
  // NettyResponseChannel$Chunk Flow Tests
  // ========================================

  /**
   * TEST 1: Normal write path with ByteBuffer (PRODUCTION PATTERN)
   *
   * Flow: Application allocates ByteBuffer -> write(ByteBuffer) ->
   *       Unpooled.wrappedBuffer() creates ByteBuf -> Chunk stores wrapped ByteBuf ->
   *       ChunkDispenser.readChunk() -> retainedDuplicate() -> HttpContent ->
   *       Netty writes and releases
   *
   * This is the ACTUAL production pattern where application uses ByteBuffer.
   *
   * Expected: NO LEAK - Netty manages HttpContent lifecycle
   */
  @Test
  public void testNormalWriteWithByteBuffer_ProductionPattern() throws Exception {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // PRODUCTION PATTERN: Use ByteBuffer, not ByteBuf
    String data = "test data";
    ByteBuffer byteBuffer = ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));

    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);  // Wraps as ByteBuf internally

    responseChannel.onResponseComplete(null);

    // Verify callback completed
    assertTrue("Callback should complete", callback.awaitCompletion(1000));

    // Read outbound response
    HttpResponse response = channel.readOutbound();
    assertNotNull("Should have response", response);

    // Read and release content
    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof HttpContent) {
        ((HttpContent) outbound).release();
      }
    }
  }

  /**
   * TEST 2: Multiple chunks sequentially
   *
   * Production pattern with multiple ByteBuffer writes.
   */
  @Test
  public void testMultipleChunks_ProductionPattern() throws Exception {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/multi");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    List<TestCallback> callbacks = new ArrayList<>();

    // Write multiple ByteBuffers
    for (int i = 0; i < 5; i++) {
      ByteBuffer buf = ByteBuffer.wrap(("chunk-" + i).getBytes(StandardCharsets.UTF_8));
      TestCallback cb = new TestCallback();
      callbacks.add(cb);
      responseChannel.write(buf, cb);
    }

    responseChannel.onResponseComplete(null);

    // Verify all callbacks
    for (TestCallback cb : callbacks) {
      assertTrue("Callback should complete", cb.awaitCompletion(1000));
    }

    // Clean up outbound
    HttpResponse response = channel.readOutbound();
    assertNotNull(response);

    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof HttpContent) {
        ((HttpContent) outbound).release();
      }
    }
  }

  /**
   * TEST 3: Close with pending chunks - LEAK-13.3 (CRITICAL)
   *
   * Flow: write() creates Chunks -> close() BEFORE ChunkDispenser processes them
   *
   * This demonstrates the CRITICAL bug where resolveChunk() doesn't release buffers.
   */
  @Test
  public void testCloseWithPendingChunks_CriticalLeak() throws Exception {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/close");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // Write chunks
    for (int i = 0; i < 3; i++) {
      ByteBuffer buf = ByteBuffer.wrap(("pending-" + i).getBytes(StandardCharsets.UTF_8));
      responseChannel.write(buf, null);
    }

    // CRITICAL: Close WITHOUT letting Netty process chunks
    // This triggers the leak scenario - chunks in queue but never released
    responseChannel.close();

    // Clean up any outbound content
    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof HttpContent) {
        ((HttpContent) outbound).release();
      }
    }
  }

  /**
   * TEST 4: Error before chunks processed - LEAK-13.2
   */
  @Test
  public void testErrorBeforeChunksProcessed_Leak() throws Exception {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/error");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer buf = ByteBuffer.wrap("error data".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(buf, callback);

    // Error BEFORE Netty processes
    responseChannel.onResponseComplete(new ClosedChannelException());

    assertTrue("Callback should complete", callback.awaitCompletion(1000));

    // Clean up
    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof HttpContent) {
        ((HttpContent) outbound).release();
      }
    }
  }

  /**
   * TEST 5: Content-Length with last chunk
   */
  @Test
  public void testContentLength_LastChunk() throws Exception {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/length");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);

    String data = "exact length";
    responseChannel.setHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), String.valueOf(data.length()));

    ByteBuffer buf = ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(buf, callback);

    responseChannel.onResponseComplete(null);

    assertTrue("Callback should complete", callback.awaitCompletion(1000));

    // Clean up
    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof HttpContent) {
        ((HttpContent) outbound).release();
      }
    }
  }

  // ========================================
  // Helper Classes
  // ========================================

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
}
