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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
  private TestContextCapture contextCapture;

  @Before
  public void setUp() {
    nettyMetrics = new NettyMetrics(new MetricRegistry());

    Properties properties = new Properties();
    VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
    nettyConfig = new NettyConfig(verifiableProperties);
    performanceConfig = new PerformanceConfig(verifiableProperties);

    // Create channel with handler that captures active context
    contextCapture = new TestContextCapture();
    ChunkedWriteHandler chunkedWriteHandler = new ChunkedWriteHandler();
    channel = new EmbeddedChannel(chunkedWriteHandler, contextCapture);
  }

  @After
  public void tearDown() {
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
  }

  /**
   * EXPERIMENT 1: Use TestContextCapture from channelActive callback
   * Strategy: Handler captures context when channelActive fires during EmbeddedChannel construction
   */
  @Test
  public void experiment01_TestContextCapture() throws Exception {
    System.out.println("EXPERIMENT 1: TestContextCapture from channelActive");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    ByteBuf result = getWrapperFromChannel(responseChannel);
    System.out.println("  Result: " + (result != null ? "SUCCESS - chunk found" : "FAILURE - chunk is null"));
    System.out.println("  Context: " + contextCapture.ctx);
    System.out.println("  Context.channel().isActive(): " + (contextCapture.ctx != null ? contextCapture.ctx.channel().isActive() : "null ctx"));
  }

  /**
   * EXPERIMENT 2: Use MockChannelHandlerContext wrapping EmbeddedChannel
   * Strategy: Use the existing mock class that just returns the channel
   */
  @Test
  public void experiment02_MockChannelHandlerContext() throws Exception {
    System.out.println("\nEXPERIMENT 2: MockChannelHandlerContext");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    ByteBuf result = getWrapperFromChannel(responseChannel);
    System.out.println("  Result: " + (result != null ? "SUCCESS - chunk found" : "FAILURE - chunk is null"));
    System.out.println("  Channel.isActive(): " + channel.isActive());
  }

  /**
   * EXPERIMENT 3: Get context from pipeline after channel construction
   * Strategy: Use channel.pipeline().context(ChunkedWriteHandler.class)
   */
  @Test
  public void experiment03_PipelineContext() throws Exception {
    System.out.println("\nEXPERIMENT 3: Pipeline context");

    ChunkedWriteHandler handler = channel.pipeline().get(ChunkedWriteHandler.class);
    ChannelHandlerContext ctx = channel.pipeline().context(handler);

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    ByteBuf result = getWrapperFromChannel(responseChannel);
    System.out.println("  Result: " + (result != null ? "SUCCESS - chunk found" : "FAILURE - chunk is null"));
    System.out.println("  Context: " + ctx);
    System.out.println("  Context.channel().isActive(): " + ctx.channel().isActive());
  }

  /**
   * EXPERIMENT 4: Fire channelActive explicitly before getting context
   * Strategy: Manually fire channelActive, then get pipeline context
   */
  @Test
  public void experiment04_ExplicitFireChannelActive() throws Exception {
    System.out.println("\nEXPERIMENT 4: Explicitly fire channelActive before getting context");

    // Fire channelActive explicitly
    channel.pipeline().fireChannelActive();

    ChunkedWriteHandler handler = channel.pipeline().get(ChunkedWriteHandler.class);
    ChannelHandlerContext ctx = channel.pipeline().context(handler);

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    ByteBuf result = getWrapperFromChannel(responseChannel);
    System.out.println("  Result: " + (result != null ? "SUCCESS - chunk found" : "FAILURE - chunk is null"));
    System.out.println("  Context.channel().isActive(): " + ctx.channel().isActive());
  }

  /**
   * EXPERIMENT 5: Use context from TestContextCapture but check AFTER write
   * Strategy: Maybe the chunk appears after some pipeline processing
   */
  @Test
  public void experiment05_CheckAfterRunPendingTasks() throws Exception {
    System.out.println("\nEXPERIMENT 5: Check after runPendingTasks");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    // Try running pending tasks
    channel.runPendingTasks();
    channel.flush();

    ByteBuf result = getWrapperFromChannel(responseChannel);
    System.out.println("  Result: " + (result != null ? "SUCCESS - chunk found" : "FAILURE - chunk is null"));
  }

  /**
   * EXPERIMENT 6: Create new channel WITHOUT TestContextCapture, use MockChannelHandlerContext
   * Strategy: Maybe TestContextCapture is interfering
   */
  @Test
  public void experiment06_NoTestContextCapture() throws Exception {
    System.out.println("\nEXPERIMENT 6: New channel without TestContextCapture");

    ChunkedWriteHandler chunkedWriteHandler = new ChunkedWriteHandler();
    EmbeddedChannel testChannel = new EmbeddedChannel(chunkedWriteHandler);

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(testChannel), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    ByteBuf result = getWrapperFromChannel(responseChannel);
    System.out.println("  Result: " + (result != null ? "SUCCESS - chunk found" : "FAILURE - chunk is null"));
    System.out.println("  Channel.isActive(): " + testChannel.isActive());
    System.out.println("  Channel.isOpen(): " + testChannel.isOpen());
    System.out.println("  Channel.isRegistered(): " + testChannel.isRegistered());

    testChannel.close();
  }

  /**
   * EXPERIMENT 7: Check finalResponseMetadata field directly
   * Strategy: See if finalResponseMetadata is actually set
   */
  @Test
  public void experiment07_CheckFinalResponseMetadata() throws Exception {
    System.out.println("\nEXPERIMENT 7: Check finalResponseMetadata field");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // Check finalResponseMetadata before write
    Field finalResponseMetadataField = NettyResponseChannel.class.getDeclaredField("finalResponseMetadata");
    finalResponseMetadataField.setAccessible(true);
    Object beforeWrite = finalResponseMetadataField.get(responseChannel);
    System.out.println("  finalResponseMetadata BEFORE write: " + beforeWrite);

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    // Check finalResponseMetadata after write
    Object afterWrite = finalResponseMetadataField.get(responseChannel);
    System.out.println("  finalResponseMetadata AFTER write: " + afterWrite);

    ByteBuf result = getWrapperFromChannel(responseChannel);
    System.out.println("  Result: " + (result != null ? "SUCCESS - chunk found" : "FAILURE - chunk is null"));
  }

  /**
   * EXPERIMENT 8: Check responseMetadataWriteInitiated flag
   * Strategy: See if the metadata write was attempted
   */
  @Test
  public void experiment08_CheckWriteInitiatedFlag() throws Exception {
    System.out.println("\nEXPERIMENT 8: Check responseMetadataWriteInitiated");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    Field writeInitiatedField = NettyResponseChannel.class.getDeclaredField("responseMetadataWriteInitiated");
    writeInitiatedField.setAccessible(true);
    Object beforeWrite = writeInitiatedField.get(responseChannel);
    System.out.println("  responseMetadataWriteInitiated BEFORE write: " + beforeWrite);

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    Object afterWrite = writeInitiatedField.get(responseChannel);
    System.out.println("  responseMetadataWriteInitiated AFTER write: " + afterWrite);

    ByteBuf result = getWrapperFromChannel(responseChannel);
    System.out.println("  Result: " + (result != null ? "SUCCESS - chunk found" : "FAILURE - chunk is null"));
  }

  /**
   * EXPERIMENT 9: Use context from ChunkedWriteHandler's context method
   * Strategy: Get context directly from the handler instance
   */
  @Test
  public void experiment09_HandlerContext() throws Exception {
    System.out.println("\nEXPERIMENT 9: Context from handler.context()");

    ChunkedWriteHandler handler = channel.pipeline().get(ChunkedWriteHandler.class);
    // ChunkedWriteHandler might have a way to get its context
    ChannelHandlerContext ctx = channel.pipeline().context(handler);

    System.out.println("  Handler: " + handler);
    System.out.println("  Context: " + ctx);
    System.out.println("  Context != null: " + (ctx != null));
    if (ctx != null) {
      System.out.println("  Context.channel(): " + ctx.channel());
      System.out.println("  Context.channel().isActive(): " + ctx.channel().isActive());
    }

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    ByteBuf result = getWrapperFromChannel(responseChannel);
    System.out.println("  Result: " + (result != null ? "SUCCESS - chunk found" : "FAILURE - chunk is null"));
  }

  /**
   * EXPERIMENT 10: Compare contextCapture.ctx vs pipeline context
   * Strategy: See if they're the same object or different
   */
  @Test
  public void experiment10_CompareContexts() throws Exception {
    System.out.println("\nEXPERIMENT 10: Compare different context sources");

    ChunkedWriteHandler handler = channel.pipeline().get(ChunkedWriteHandler.class);
    ChannelHandlerContext pipelineCtx = channel.pipeline().context(handler);
    ChannelHandlerContext capturedCtx = contextCapture.ctx;

    System.out.println("  Pipeline context: " + pipelineCtx);
    System.out.println("  Captured context: " + capturedCtx);
    System.out.println("  Same object? " + (pipelineCtx == capturedCtx));
    System.out.println("  Pipeline ctx.channel().isActive(): " + (pipelineCtx != null ? pipelineCtx.channel().isActive() : "null"));
    System.out.println("  Captured ctx.channel().isActive(): " + (capturedCtx != null ? capturedCtx.channel().isActive() : "null"));

    // Try both
    System.out.println("\n  Trying with captured context:");
    NettyResponseChannel responseChannel1 = new NettyResponseChannel(
        capturedCtx, nettyMetrics, performanceConfig, nettyConfig);
    responseChannel1.setStatus(ResponseStatus.Ok);
    responseChannel1.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");
    ByteBuffer byteBuffer1 = ByteBuffer.wrap("test1".getBytes(StandardCharsets.UTF_8));
    responseChannel1.write(byteBuffer1, new TestCallback());
    ByteBuf result1 = getWrapperFromChannel(responseChannel1);
    System.out.println("    Result: " + (result1 != null ? "SUCCESS" : "FAILURE"));

    System.out.println("\n  Trying with pipeline context:");
    NettyResponseChannel responseChannel2 = new NettyResponseChannel(
        pipelineCtx, nettyMetrics, performanceConfig, nettyConfig);
    responseChannel2.setStatus(ResponseStatus.Ok);
    responseChannel2.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");
    ByteBuffer byteBuffer2 = ByteBuffer.wrap("test2".getBytes(StandardCharsets.UTF_8));
    responseChannel2.write(byteBuffer2, new TestCallback());
    ByteBuf result2 = getWrapperFromChannel(responseChannel2);
    System.out.println("    Result: " + (result2 != null ? "SUCCESS" : "FAILURE"));
  }

  /**
   * EXPERIMENT 11: Check if channel.isActive() vs ctx.channel().isActive() differ
   * Strategy: Maybe EmbeddedChannel itself reports active differently
   */
  @Test
  public void experiment11_ChannelActiveStates() throws Exception {
    System.out.println("\nEXPERIMENT 11: Compare channel active states");

    System.out.println("  channel.isActive(): " + channel.isActive());
    System.out.println("  channel.isOpen(): " + channel.isOpen());
    System.out.println("  channel.isRegistered(): " + channel.isRegistered());
    System.out.println("  contextCapture.ctx.channel().isActive(): " +
        (contextCapture.ctx != null ? contextCapture.ctx.channel().isActive() : "null ctx"));
    System.out.println("  Are they the same channel? " +
        (contextCapture.ctx != null ? (contextCapture.ctx.channel() == channel) : "null ctx"));
  }

  /**
   * EXPERIMENT 12: Check queue SIZE instead of peeking
   * Strategy: Maybe peek() returns null but queue has items?
   */
  @Test
  public void experiment12_CheckQueueSize() throws Exception {
    System.out.println("\nEXPERIMENT 12: Check queue size");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    Field chunksToWriteField = NettyResponseChannel.class.getDeclaredField("chunksToWrite");
    chunksToWriteField.setAccessible(true);
    Queue<?> chunksToWrite = (Queue<?>) chunksToWriteField.get(responseChannel);

    System.out.println("  chunksToWrite.size(): " + chunksToWrite.size());
    System.out.println("  chunksToWrite.isEmpty(): " + chunksToWrite.isEmpty());
    System.out.println("  chunksToWrite.peek(): " + chunksToWrite.peek());
  }

  /**
   * EXPERIMENT 13: Check chunksAwaitingCallback queue
   * Strategy: Maybe chunks are moved to a different queue
   */
  @Test
  public void experiment13_CheckAwaitingCallbackQueue() throws Exception {
    System.out.println("\nEXPERIMENT 13: Check chunksAwaitingCallback queue");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    Field chunksToWriteField = NettyResponseChannel.class.getDeclaredField("chunksToWrite");
    chunksToWriteField.setAccessible(true);
    Queue<?> chunksToWrite = (Queue<?>) chunksToWriteField.get(responseChannel);

    Field chunksAwaitingCallbackField = NettyResponseChannel.class.getDeclaredField("chunksAwaitingCallback");
    chunksAwaitingCallbackField.setAccessible(true);
    Queue<?> chunksAwaitingCallback = (Queue<?>) chunksAwaitingCallbackField.get(responseChannel);

    System.out.println("  chunksToWrite.size(): " + chunksToWrite.size());
    System.out.println("  chunksAwaitingCallback.size(): " + chunksAwaitingCallback.size());
    System.out.println("  chunksAwaitingCallback.peek(): " + chunksAwaitingCallback.peek());
  }

  /**
   * EXPERIMENT 14: Check if data appears in outbound channel
   * Strategy: Maybe chunk is processed and written to channel immediately
   */
  @Test
  public void experiment14_CheckOutboundChannel() throws Exception {
    System.out.println("\nEXPERIMENT 14: Check outbound channel");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    Object outbound1 = channel.readOutbound();
    Object outbound2 = channel.readOutbound();
    Object outbound3 = channel.readOutbound();

    System.out.println("  First outbound: " + outbound1);
    System.out.println("  Second outbound: " + outbound2);
    System.out.println("  Third outbound: " + outbound3);

    if (outbound1 != null && outbound1 instanceof ByteBuf) {
      ((ByteBuf) outbound1).release();
    }
    if (outbound2 != null && outbound2 instanceof ByteBuf) {
      ((ByteBuf) outbound2).release();
    }
  }

  /**
   * EXPERIMENT 15: Check queue immediately in write callback
   * Strategy: Check state synchronously during the write operation
   */
  @Test
  public void experiment15_CheckDuringCallback() throws Exception {
    System.out.println("\nEXPERIMENT 15: Check during write callback");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));

    CountDownLatch latch = new CountDownLatch(1);
    Callback<Long> callback = new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        try {
          Field chunksToWriteField = NettyResponseChannel.class.getDeclaredField("chunksToWrite");
          chunksToWriteField.setAccessible(true);
          Queue<?> chunksToWrite = (Queue<?>) chunksToWriteField.get(responseChannel);
          System.out.println("  Inside callback - chunksToWrite.size(): " + chunksToWrite.size());
        } catch (Exception e) {
          e.printStackTrace();
        }
        latch.countDown();
      }
    };

    responseChannel.write(byteBuffer, callback);

    // Check before callback
    Field chunksToWriteField = NettyResponseChannel.class.getDeclaredField("chunksToWrite");
    chunksToWriteField.setAccessible(true);
    Queue<?> chunksToWrite = (Queue<?>) chunksToWriteField.get(responseChannel);
    System.out.println("  Before callback - chunksToWrite.size(): " + chunksToWrite.size());

    latch.await(5, TimeUnit.SECONDS);
  }

  /**
   * EXPERIMENT 16: Check chunkedWriteHandler state
   * Strategy: See if ChunkedWriteHandler has the chunk
   */
  @Test
  public void experiment16_CheckChunkedWriteHandler() throws Exception {
    System.out.println("\nEXPERIMENT 16: Check ChunkedWriteHandler");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ChunkedWriteHandler handler = channel.pipeline().get(ChunkedWriteHandler.class);
    System.out.println("  Handler before write: " + handler);

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    System.out.println("  Handler after write: " + handler);

    // Try to trigger handler
    channel.runPendingTasks();

    System.out.println("  Handler after runPendingTasks: " + handler);
  }

  /**
   * EXPERIMENT 17: Check if isOpen() returns false
   * Strategy: Maybe channel is not considered "open" by NettyResponseChannel
   */
  @Test
  public void experiment17_CheckIsOpen() throws Exception {
    System.out.println("\nEXPERIMENT 17: Check isOpen()");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    System.out.println("  Before write:");
    System.out.println("    responseChannel.isOpen(): " + responseChannel.isOpen());

    Field responseCompleteCalledField = NettyResponseChannel.class.getDeclaredField("responseCompleteCalled");
    responseCompleteCalledField.setAccessible(true);
    Object responseCompleteCalled = responseCompleteCalledField.get(responseChannel);
    System.out.println("    responseCompleteCalled: " + responseCompleteCalled);

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    System.out.println("  After write:");
    System.out.println("    responseChannel.isOpen(): " + responseChannel.isOpen());
    System.out.println("    responseCompleteCalled: " + responseCompleteCalledField.get(responseChannel));
  }

  /**
   * EXPERIMENT 18: Check queue at different points in write flow
   * Strategy: Use reflection to check queue size at each step
   */
  @Test
  public void experiment18_MultipleQueueChecks() throws Exception {
    System.out.println("\nEXPERIMENT 18: Multiple queue size checks");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    Field chunksToWriteField = NettyResponseChannel.class.getDeclaredField("chunksToWrite");
    chunksToWriteField.setAccessible(true);
    Queue<?> chunksToWrite = (Queue<?>) chunksToWriteField.get(responseChannel);

    System.out.println("  Initial: " + chunksToWrite.size());

    responseChannel.setStatus(ResponseStatus.Ok);
    System.out.println("  After setStatus: " + chunksToWrite.size());

    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");
    System.out.println("  After setHeader: " + chunksToWrite.size());

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    System.out.println("  Before write: " + chunksToWrite.size());

    responseChannel.write(byteBuffer, callback);
    System.out.println("  Immediately after write: " + chunksToWrite.size());

    Thread.sleep(10);
    System.out.println("  After 10ms: " + chunksToWrite.size());

    channel.runPendingTasks();
    System.out.println("  After runPendingTasks: " + chunksToWrite.size());
  }

  /**
   * EXPERIMENT 19: Check if finalResponseMetadata is FullHttpResponse
   * Strategy: See what type finalResponseMetadata actually is
   */
  @Test
  public void experiment19_CheckResponseType() throws Exception {
    System.out.println("\nEXPERIMENT 19: Check response type");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    Field finalResponseMetadataField = NettyResponseChannel.class.getDeclaredField("finalResponseMetadata");
    finalResponseMetadataField.setAccessible(true);
    Object finalResponseMetadata = finalResponseMetadataField.get(responseChannel);

    System.out.println("  finalResponseMetadata class: " + finalResponseMetadata.getClass().getName());
    System.out.println("  Is FullHttpResponse? " + (finalResponseMetadata instanceof io.netty.handler.codec.http.FullHttpResponse));
    System.out.println("  Is HttpResponse? " + (finalResponseMetadata instanceof io.netty.handler.codec.http.HttpResponse));
  }

  /**
   * EXPERIMENT 20: Write WITHOUT setting headers to see different code path
   * Strategy: Skip transfer-encoding:chunked header
   */
  @Test
  public void experiment20_WriteWithoutChunkedHeader() throws Exception {
    System.out.println("\nEXPERIMENT 20: Write without chunked header");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    // DON'T set transfer-encoding header

    ByteBuffer byteBuffer = ByteBuffer.wrap("test".getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    Field chunksToWriteField = NettyResponseChannel.class.getDeclaredField("chunksToWrite");
    chunksToWriteField.setAccessible(true);
    Queue<?> chunksToWrite = (Queue<?>) chunksToWriteField.get(responseChannel);

    System.out.println("  chunksToWrite.size(): " + chunksToWrite.size());
    System.out.println("  chunksToWrite.peek(): " + chunksToWrite.peek());

    ByteBuf result = getWrapperFromChannel(responseChannel);
    System.out.println("  Result: " + (result != null ? "SUCCESS - chunk found" : "FAILURE - chunk is null"));
  }

  /**
   * EXPERIMENT 21: Call write multiple times
   * Strategy: See if multiple writes accumulate in queue
   */
  @Test
  public void experiment21_MultipleWrites() throws Exception {
    System.out.println("\nEXPERIMENT 21: Multiple writes");

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    Field chunksToWriteField = NettyResponseChannel.class.getDeclaredField("chunksToWrite");
    chunksToWriteField.setAccessible(true);
    Queue<?> chunksToWrite = (Queue<?>) chunksToWriteField.get(responseChannel);

    System.out.println("  Before writes: " + chunksToWrite.size());

    responseChannel.write(ByteBuffer.wrap("test1".getBytes()), new TestCallback());
    System.out.println("  After write 1: " + chunksToWrite.size());

    responseChannel.write(ByteBuffer.wrap("test2".getBytes()), new TestCallback());
    System.out.println("  After write 2: " + chunksToWrite.size());

    responseChannel.write(ByteBuffer.wrap("test3".getBytes()), new TestCallback());
    System.out.println("  After write 3: " + chunksToWrite.size());
  }

  /**
   * Original test - keeping for comparison
   */
  @Test
  public void testWriteByteBufferReleasesWrapper() throws Exception {
    // Set up NettyResponseChannel with real active context from pipeline
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

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
    // Set up NettyResponseChannel with real active context from pipeline
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

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
   * Handler that captures the ChannelHandlerContext when channelActive is called.
   * This ensures we have a context from an actually active channel.
   */
  private static class TestContextCapture extends ChannelInboundHandlerAdapter {
    ChannelHandlerContext ctx;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      this.ctx = ctx;
      ctx.fireChannelActive();
    }
  }
}
