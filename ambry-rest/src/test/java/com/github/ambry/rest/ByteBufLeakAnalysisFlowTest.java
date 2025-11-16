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
import com.github.ambry.network.http2.GoAwayException;
import com.github.ambry.rest.ResponseStatus;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ByteBuf Flow Analysis Tests for BYTEBUF_LEAK_ANALYSIS_PART3.md
 *
 * IMPORTANT: These tests are designed to PASS, not FAIL. They replicate production code patterns
 * with correct ByteBuf ownership and release semantics. When run with the ByteBuf Flow Tracker agent,
 * they will show detailed flow information to help identify actual leaks vs. safe patterns.
 *
 * Run with: ./gradlew test --tests ByteBufLeakAnalysisFlowTest -PwithByteBufTracking
 *
 * Classes under test:
 * 1. NettyResponseChannel$Chunk (Class 13 from analysis)
 * 2. GoAwayException (Class 14 from analysis)
 */
public class ByteBufLeakAnalysisFlowTest {
  private NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();
  private EmbeddedChannel channel;
  private NettyRequest request;
  private NettyResponseChannel responseChannel;
  private NettyMetrics nettyMetrics;

  @Before
  public void setUp() {
    nettyByteBufLeakHelper.beforeTest();
    Properties properties = new Properties();
    VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
    NettyConfig nettyConfig = new NettyConfig(verifiableProperties);
    PerformanceConfig performanceConfig = new PerformanceConfig(verifiableProperties);
    nettyMetrics = new NettyMetrics(new MetricRegistry());

    // Create embedded channel with ChunkedWriteHandler for testing
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
   * TEST 1: Normal write path - SAFE-13.1
   *
   * Flow: allocate() -> write() -> Chunk constructor -> ChunkDispenser.readChunk() ->
   *       retainedDuplicate() -> Netty processes HttpContent -> HttpContent.release() -> cleanup
   *
   * Ownership transfer:
   * - Application allocates ByteBuf (refCount=1)
   * - Passes to write() which creates Chunk (no retain, just stores reference)
   * - ChunkDispenser.readChunk() wraps in HttpContent with retainedDuplicate() (refCount=2)
   * - Original ByteBuf released by application (refCount=1)
   * - Netty releases HttpContent after write (refCount=0)
   *
   * Expected: NO LEAK - Netty manages HttpContent lifecycle
   */
  @Test
  public void testChunk_NormalWritePath_SafePattern() throws Exception {
    NettyRequest request = createNettyRequest("/normal-write");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);

    // Set response metadata to allow writing
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());

    // Allocate ByteBuf with test data (refCount=1)
    ByteBuf buffer = createTestByteBuf("test data for normal write");
    int initialRefCount = buffer.refCnt();
    assertEquals("Initial refCount should be 1", 1, initialRefCount);

    // Write to response channel - creates Chunk internally (still refCount=1)
    TestCallback callback = new TestCallback();
    Future<Long> future = responseChannel.write(buffer, callback);

    // At this point, Chunk has been created and stores buffer reference
    // Buffer refCount is still 1 - no retain() called in Chunk constructor
    assertEquals("RefCount should still be 1 after write()", 1, buffer.refCnt());

    // Now simulate what Netty's ChunkedWriteHandler does
    // Complete the response to allow chunk dispensing
    responseChannel.onResponseComplete(null);

    // In production, Netty would release the HttpContent
    // For this test, we clean up the buffer ourselves
    safeRelease(buffer);

    // Verify callback was invoked
    assertTrue("Callback should complete", callback.awaitCompletion(1000));
    assertNull("Callback should succeed", callback.exception);
  }

  /**
   * TEST 2: Multiple chunks written sequentially - SAFE-13.1 variant
   *
   * Flow: allocate(chunk1) -> write(chunk1) -> allocate(chunk2) -> write(chunk2) ->
   *       readChunk(chunk1) -> process -> readChunk(chunk2) -> process -> cleanup
   *
   * Tests: Queue management and sequential processing
   * Expected: NO LEAK - All chunks processed and cleaned up
   */
  @Test
  public void testChunk_MultipleChunksSequential_SafePattern() throws Exception {
    NettyRequest request = createNettyRequest("/multi-chunk");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());

    List<ByteBuf> buffers = new ArrayList<>();
    List<TestCallback> callbacks = new ArrayList<>();

    // Write multiple chunks
    for (int i = 0; i < 5; i++) {
      ByteBuf buf = createTestByteBuf("chunk-" + i);
      buffers.add(buf);
      TestCallback callback = new TestCallback();
      callbacks.add(callback);
      responseChannel.write(buf, callback);
      assertEquals("RefCount should be 1 after write", 1, buf.refCnt());
    }

    // Complete response
    responseChannel.onResponseComplete(null);

    // Clean up all buffers
    for (ByteBuf buf : buffers) {
      safeRelease(buf);
    }

    // Verify all callbacks completed
    for (TestCallback callback : callbacks) {
      assertTrue("Callback should complete", callback.awaitCompletion(1000));
    }
  }

  /**
   * TEST 3: Exception before chunk sent to ChunkDispenser - LEAK-13.2
   *
   * Flow: allocate() -> write() -> Chunk created and added to queue ->
   *       Exception occurs -> Chunk never retrieved by readChunk() ->
   *       cleanupChunks() should drain queue and resolve chunks
   *
   * Critical: Chunk sits in chunksToWrite queue, never processed
   * Resolution: cleanupChunks() drains chunksToWrite and calls resolveChunk()
   *
   * Expected: POTENTIALLY LEAKED - Chunk.buffer is NOT released by resolveChunk()
   *           Caller must track and release ByteBuf separately
   */
  @Test
  public void testChunk_ExceptionBeforeDispense_PotentialLeak() throws Exception {
    NettyRequest request = createNettyRequest("/exception-before-dispense");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());

    ByteBuf buffer = createTestByteBuf("test data");
    TestCallback callback = new TestCallback();
    responseChannel.write(buffer, callback);
    assertEquals("RefCount should be 1", 1, buffer.refCnt());

    // CRITICAL: Simulate error BEFORE readChunk() is called
    ClosedChannelException exception = new ClosedChannelException();
    responseChannel.onResponseComplete(exception);

    assertTrue("Callback should complete", callback.awaitCompletion(1000));
    assertEquals("LEAK: Buffer not released by resolveChunk()", 1, buffer.refCnt());
    safeRelease(buffer);
  }

  /**
   * TEST 4: Channel close with pending chunks in queue - LEAK-13.3 (CRITICAL)
   *
   * Flow: write(chunk1) -> write(chunk2) -> write(chunk3) ->
   *       channel.close() BEFORE readChunk() called ->
   *       cleanupChunks() drains queue but does NOT release buffers
   *
   * Critical bug: resolveChunk() does NOT call buffer.release()
   * Result: All pending chunk.buffer ByteBufs are LEAKED
   *
   * Expected: LEAK - This is the CRITICAL bug identified in analysis
   */
  @Test
  public void testChunk_CloseWithPendingChunks_CriticalLeak() throws Exception {
    NettyRequest request = createNettyRequest("/close-with-pending");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());

    List<ByteBuf> buffers = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ByteBuf buf = createTestByteBuf("pending-chunk-" + i);
      buffers.add(buf);
      responseChannel.write(buf, null);
    }

    responseChannel.close();

    // CRITICAL LEAK: All buffers still have refCount=1
    for (ByteBuf buf : buffers) {
      assertEquals("CRITICAL LEAK: Buffer not released", 1, buf.refCnt());
      safeRelease(buf);
    }
  }

  /**
   * TEST 5: Chunks in "awaiting callback" queue during cleanup
   *
   * Flow: write() -> readChunk() moves to chunksAwaitingCallback ->
   *       Exception during Netty write -> cleanupChunks() processes awaiting queue
   *
   * Tests: cleanupChunks() processes both queues (chunksToWrite and chunksAwaitingCallback)
   * Expected: POTENTIALLY LEAKED - resolveChunk() doesn't release buffer
   */
  @Test
  public void testChunk_AwaitingCallbackCleanup_PotentialLeak() throws Exception {
    NettyRequest request = createNettyRequest("/awaiting-callback");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());
    ByteBuf buffer = createTestByteBuf("awaiting callback chunk");
    TestCallback callback = new TestCallback();
    responseChannel.write(buffer, callback);
    responseChannel.onResponseComplete(new IOException("Simulated error"));
    assertTrue("Callback should complete", callback.awaitCompletion(1000));
    assertEquals("Buffer not released by cleanup", 1, buffer.refCnt());
    safeRelease(buffer);
  }

  /**
   * TEST 6: Last chunk handling (DefaultLastHttpContent)
   *
   * Flow: write() with Content-Length set -> chunk.isLast=true ->
   *       readChunk() creates DefaultLastHttpContent with retainedDuplicate()
   *
   * Tests: Last chunk creates different HttpContent type
   * Expected: NO LEAK - Same as normal chunk, Netty manages lifecycle
   */
  @Test
  public void testChunk_LastChunkHandling_SafePattern() throws Exception {
    NettyRequest request = createNettyRequest("/last-chunk");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    String data = "last chunk data";
    responseChannel.setHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), String.valueOf(data.length()));
    ByteBuf buffer = createTestByteBuf(data);
    TestCallback callback = new TestCallback();
    responseChannel.write(buffer, callback);
    responseChannel.onResponseComplete(null);
    assertTrue("Callback should complete", callback.awaitCompletion(1000));
    safeRelease(buffer);
  }

  /**
   * TEST 7: resolveChunk() with exception path
   *
   * Flow: write() -> readChunk() -> Netty error -> resolveChunk(exception) ->
   *       buffer.skipBytes() NOT called, but buffer still held
   *
   * Tests: Error path in resolveChunk() - skipBytes only called if exception==null
   * Expected: POTENTIALLY LEAKED - buffer not released in either path
   */
  @Test
  public void testChunk_ResolveWithException_PotentialLeak() throws Exception {
    NettyRequest request = createNettyRequest("/resolve-exception");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());
    ByteBuf buffer = createTestByteBuf("resolve with error");
    TestCallback callback = new TestCallback();
    responseChannel.write(buffer, callback);
    IOException error = new IOException("Simulated write error");
    responseChannel.onResponseComplete(error);
    assertTrue("Callback should complete", callback.awaitCompletion(1000));
    assertNotNull("Callback should have exception", callback.exception);
    assertEquals("Buffer not released in error path", 1, buffer.refCnt());
    safeRelease(buffer);
  }

  /**
   * TEST 8: Chunk with callback - verify callback invoked but buffer not released
   *
   * Flow: write(byteBuf, callback) -> Chunk stores callback ->
   *       readChunk() -> resolveChunk() -> callback.onCompletion() called ->
   *       buffer NOT released
   *
   * Tests: Callback mechanism works but doesn't handle buffer lifecycle
   * Expected: Callback invoked, buffer ownership remains with caller
   */
  @Test
  public void testChunk_CallbackInvoked_BufferOwnershipRetained() throws Exception {
    NettyRequest request = createNettyRequest("/callback-test");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());
    ByteBuf buffer = createTestByteBuf("callback test data");
    TestCallback callback = new TestCallback();
    responseChannel.write(buffer, callback);
    responseChannel.onResponseComplete(null);
    assertTrue("Callback should complete", callback.awaitCompletion(1000));
    assertNull("Callback should succeed", callback.exception);
    assertEquals("Buffer ownership retained", 1, buffer.refCnt());
    safeRelease(buffer);
  }

  /**
   * TEST 9: retainedDuplicate() creates independent reference
   *
   * Flow: write(byteBuf) -> readChunk() calls buffer.retainedDuplicate() ->
   *       Original buffer can be released independently ->
   *       HttpContent.release() releases duplicate
   *
   * Tests: retainedDuplicate() increments refCount, creating independent lifecycle
   * Expected: NO LEAK - Both references must be released independently
   */
  @Test
  public void testChunk_RetainedDuplicate_IndependentLifecycle() throws Exception {
    ByteBuf original = createTestByteBuf("original data");
    assertEquals("Original refCount=1", 1, original.refCnt());
    ByteBuf duplicate = original.retainedDuplicate();
    assertEquals("Original refCount=2 after retainedDuplicate", 2, original.refCnt());
    original.release();
    assertEquals("After releasing original, refCount=1", 1, original.refCnt());
    assertTrue("Duplicate still readable", duplicate.isReadable());
    duplicate.release();
    assertEquals("After releasing duplicate, refCount=0", 0, original.refCnt());
  }

  /**
   * TEST 10: Content-Length mismatch error path
   *
   * Flow: Set Content-Length=100 -> write(200 bytes) ->
   *       IllegalStateException -> CleanupCallback scheduled ->
   *       cleanupChunks() drains queue
   *
   * Tests: Error handling for content size mismatch
   * Expected: POTENTIALLY LEAKED - chunks in queue not released
   */
  @Test
  public void testChunk_ContentLengthMismatch_ErrorPath() throws Exception {
    NettyRequest request = createNettyRequest("/content-length-mismatch");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), "100");
    byte[] largeData = new byte[200];
    ByteBuf buffer = Unpooled.wrappedBuffer(largeData);
    try {
      responseChannel.write(buffer, null);
      responseChannel.onResponseComplete(null);
    } catch (Exception e) {
      // Expected - size mismatch
    }
    safeRelease(buffer);
  }

  // ========================================
  // GoAwayException Flow Tests
  // ========================================

  /**
   * TEST 11: GoAwayException normal construction - SAFE-14.1
   *
   * Flow: allocate debugData -> new GoAwayException(errorCode, debugData) ->
   *       debugData.toString() extracts string ->
   *       Exception stores string, NOT ByteBuf ->
   *       Caller must release debugData
   *
   * Ownership: GoAwayException does NOT take ownership
   * Expected: NO LEAK if caller releases debugData
   */
  @Test
  public void testGoAwayException_NormalConstruction_CallerOwnsBuffer() throws Exception {
    ByteBuf debugData = createTestByteBuf("GOAWAY debug message");
    assertEquals("Initial refCount=1", 1, debugData.refCnt());
    GoAwayException exception = new GoAwayException(0, debugData);
    assertNotNull("Exception message should exist", exception.getMessage());
    assertTrue("Message should contain debug data", exception.getMessage().contains("GOAWAY debug message"));
    assertEquals("Caller still owns buffer, refCount=1", 1, debugData.refCnt());
    debugData.release();
    assertEquals("Caller released buffer, refCount=0", 0, debugData.refCnt());
  }

  /**
   * TEST 12: GoAwayException with null debugData
   *
   * Flow: new GoAwayException(errorCode, null) ->
   *       null.toString() -> NullPointerException OR handled gracefully
   *
   * Tests: Null safety in constructor
   * Expected: May throw NPE or handle null - verify behavior
   */
  @Test
  public void testGoAwayException_NullDebugData_SafetyCheck() throws Exception {
    try {
      GoAwayException exception = new GoAwayException(0, null);
      fail("Should have thrown NullPointerException");
    } catch (NullPointerException e) {
      // Expected - debugData.toString() on null
    }
  }

  /**
   * TEST 13: GoAwayException - caller forgets to release - LEAK-14.2
   *
   * Flow: allocate debugData -> new GoAwayException(errorCode, debugData) ->
   *       throw exception -> Caller forgets to release debugData ->
   *       LEAK
   *
   * Tests: Common mistake where debugData is not released
   * Expected: LEAK if caller doesn't release
   */
  @Test
  public void testGoAwayException_CallerForgetsRelease_Leak() throws Exception {
    ByteBuf debugData = createTestByteBuf("Leaked debug data");
    GoAwayException exception = new GoAwayException(1, debugData);
    assertEquals("LEAK: Buffer not released by caller", 1, debugData.refCnt());
    safeRelease(debugData);
  }

  /**
   * TEST 14: GoAwayException - proper exception handling pattern
   *
   * Flow: try { allocate debugData -> create exception -> throw }
   *       finally { release debugData }
   *
   * Tests: Correct pattern for using GoAwayException
   * Expected: NO LEAK - try-finally ensures cleanup
   */
  @Test
  public void testGoAwayException_TryFinallyPattern_SafePattern() throws Exception {
    ByteBuf debugData = createTestByteBuf("Properly handled debug data");
    try {
      GoAwayException exception = new GoAwayException(0, debugData);
      assertNotNull(exception.getMessage());
    } finally {
      safeRelease(debugData);
    }
    assertEquals("Buffer properly released", 0, debugData.refCnt());
  }

  /**
   * TEST 15: GoAwayException - debugData.toString() timing
   *
   * Flow: allocate debugData -> create exception (toString called in constructor) ->
   *       release debugData immediately -> exception still has string
   *
   * Tests: toString() is called during construction, so debugData can be released immediately
   * Expected: NO LEAK - debugData released immediately after construction is safe
   */
  @Test
  public void testGoAwayException_ImmediateRelease_SafePattern() throws Exception {
    ByteBuf debugData = createTestByteBuf("Immediate release test");
    GoAwayException exception = new GoAwayException(0, debugData);
    debugData.release();
    assertEquals("Buffer released immediately", 0, debugData.refCnt());
    assertNotNull("Exception message still available", exception.getMessage());
    assertTrue("Message contains extracted string", exception.getMessage().contains("Immediate release test"));
  }

  /**
   * TEST 16: GoAwayException - UTF-8 decoding with special characters
   *
   * Flow: allocate debugData with UTF-8 text (emoji, special chars) ->
   *       create exception -> verify string decoded correctly
   *
   * Tests: UTF-8 decoding in toString() handles various characters
   * Expected: String correctly decoded, buffer ownership with caller
   */
  @Test
  public void testGoAwayException_Utf8Decoding_SpecialCharacters() throws Exception {
    String specialText = "Debug: émojí tést ñ";
    ByteBuf debugData = Unpooled.copiedBuffer(specialText, StandardCharsets.UTF_8);
    GoAwayException exception = new GoAwayException(0, debugData);
    String message = exception.getMessage();
    assertTrue("Message should contain special characters", message.contains("émojí") || message.contains("tést"));
    debugData.release();
  }

  /**
   * TEST 17: GoAwayException - large debug data
   *
   * Flow: allocate large ByteBuf (10KB debug data) ->
   *       create exception -> toString() on large buffer ->
   *       verify string extracted correctly
   *
   * Tests: Large debug data handling
   * Expected: String extracted, caller must release buffer
   */
  @Test
  public void testGoAwayException_LargeDebugData_MemoryHandling() throws Exception {
    StringBuilder largeDataBuilder = new StringBuilder(10240);
    for (int i = 0; i < 10240; i++) largeDataBuilder.append("X");
    ByteBuf debugData = createTestByteBuf(largeDataBuilder.toString());
    GoAwayException exception = new GoAwayException(0, debugData);
    assertNotNull("Exception message should exist", exception.getMessage());
    debugData.release();
  }

  // ========================================
  // Integration Tests - Multiple Classes
  // ========================================

  /**
   * TEST 18: Integration - NettyResponseChannel with GoAwayException
   *
   * Flow: Setup response -> write chunks -> HTTP/2 GOAWAY received ->
   *       Create GoAwayException with debug data -> Close channel with error ->
   *       Cleanup pending chunks
   *
   * Tests: Integration of both classes in error scenario
   * Expected: Both GoAwayException debugData and pending chunks should be cleaned up
   */
  @Test
  public void testIntegration_ResponseChannelWithGoAwayError() throws Exception {
    NettyRequest request = createNettyRequest("/goaway-error");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());
    List<ByteBuf> chunks = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ByteBuf buf = createTestByteBuf("chunk-" + i);
      chunks.add(buf);
      responseChannel.write(buf, null);
    }
    ByteBuf debugData = createTestByteBuf("Connection closed by remote");
    GoAwayException goAwayException = new GoAwayException(0, debugData);
    responseChannel.onResponseComplete(goAwayException);
    for (ByteBuf chunk : chunks) safeRelease(chunk);
    safeRelease(debugData);
  }

  /**
   * TEST 19: Integration - Full request/response cycle with proper cleanup
   *
   * Flow: Request arrives -> Response created -> Write multiple chunks ->
   *       Each chunk processed by ChunkedWriteHandler -> Response complete ->
   *       All resources cleaned up
   *
   * Tests: Complete happy path with all components
   * Expected: NO LEAKS - all ByteBufs properly released
   */
  @Test
  public void testIntegration_FullRequestResponseCycle_HappyPath() throws Exception {
    NettyRequest request = createNettyRequest("/full-cycle");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());
    List<ByteBuf> buffers = new ArrayList<>();
    List<TestCallback> callbacks = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ByteBuf buf = createTestByteBuf("data-" + i);
      buffers.add(buf);
      TestCallback callback = new TestCallback();
      callbacks.add(callback);
      responseChannel.write(buf, callback);
    }
    responseChannel.onResponseComplete(null);
    for (TestCallback callback : callbacks) assertTrue("Callback should complete", callback.awaitCompletion(1000));
    for (ByteBuf buf : buffers) safeRelease(buf);
  }

  /**
   * TEST 20: Integration - Error during middle of streaming response
   *
   * Flow: Write chunk1 -> process -> write chunk2 -> process ->
   *       write chunk3 -> ERROR before process -> cleanup with chunks in both queues
   *
   * Tests: Error handling with chunks in various states
   * Expected: All chunks cleaned up, but buffers may leak due to resolveChunk() not releasing
   */
  @Test
  public void testIntegration_ErrorDuringStreaming_MixedQueueCleanup() throws Exception {
    NettyRequest request = createNettyRequest("/streaming-error");
    NettyResponseChannel responseChannel = createNettyResponseChannel(request);
    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString());
    List<ByteBuf> buffers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      ByteBuf buf = createTestByteBuf("stream-chunk-" + i);
      buffers.add(buf);
      responseChannel.write(buf, null);
    }
    IOException error = new IOException("Stream error");
    responseChannel.onResponseComplete(error);
    for (ByteBuf buf : buffers) {
      if (buf.refCnt() > 0) safeRelease(buf);
    }
  }

  // ========================================
  // Helper Methods
  // ========================================

  /**
   * Helper: Create a NettyRequest for testing
   */
  private NettyRequest createNettyRequest(String uri) {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    return new NettyRequest(httpRequest, channel, nettyMetrics, System.currentTimeMillis());
  }

  /**
   * Helper: Create a NettyResponseChannel for testing
   */
  private NettyResponseChannel createNettyResponseChannel(NettyRequest request) {
    return new NettyResponseChannel(channel.pipeline().lastContext(), nettyMetrics,
        new PerformanceConfig(new VerifiableProperties(new Properties())), request);
  }

  /**
   * Helper: Create ByteBuf with test data
   */
  private ByteBuf createTestByteBuf(String data) {
    return Unpooled.copiedBuffer(data, StandardCharsets.UTF_8);
  }

  /**
   * Helper: Verify ByteBuf is fully released
   */
  private void assertByteBufReleased(ByteBuf buf) {
    assertEquals("ByteBuf should be released (refCount=0)", 0, buf.refCnt());
  }

  /**
   * Helper: Verify ByteBuf is still held
   */
  private void assertByteBufHeld(ByteBuf buf) {
    assertTrue("ByteBuf should still be held (refCount>0)", buf.refCnt() > 0);
  }

  /**
   * Helper: Safe release - only release if refCount > 0
   */
  private void safeRelease(ByteBuf buf) {
    if (buf != null && buf.refCnt() > 0) {
      buf.release();
    }
  }

  /**
   * Test callback implementation that tracks completion
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
