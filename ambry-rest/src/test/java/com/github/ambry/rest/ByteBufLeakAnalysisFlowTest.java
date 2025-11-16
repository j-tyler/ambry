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
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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
    // TODO: Implement test
    // 1. Create NettyRequest and NettyResponseChannel
    // 2. Set response metadata (status 200, chunked encoding)
    // 3. Allocate ByteBuf with test data
    // 4. Call responseChannel.write(byteBuf, callback)
    //    - This creates Chunk internally
    // 5. Simulate ChunkedWriteHandler pulling chunk via readChunk()
    // 6. Verify HttpContent created with retainedDuplicate()
    // 7. Release original ByteBuf (simulating application cleanup)
    // 8. Release HttpContent (simulating Netty cleanup)
    // 9. Call onResponseComplete() to trigger cleanup
    // 10. Verify all ByteBufs released (refCount=0)
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
    // TODO: Implement test
    // 1. Setup response channel
    // 2. Write multiple chunks (3-5 chunks)
    // 3. Process each chunk via readChunk()
    // 4. Release each chunk's HttpContent
    // 5. Complete response
    // 6. Verify all ByteBufs released
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
    // TODO: Implement test
    // 1. Setup response channel
    // 2. Allocate ByteBuf
    // 3. Call write() to create Chunk and add to queue
    // 4. Before calling readChunk(), trigger error (e.g., channel close)
    // 5. Verify cleanupChunks() is called (via onResponseComplete with exception)
    // 6. Check if ByteBuf is released - EXPECTATION: May still be held
    // 7. Explicitly release ByteBuf if needed to prevent leak
    // 8. This test demonstrates LEAK-13.2 scenario
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
    // TODO: Implement test
    // 1. Setup response channel
    // 2. Allocate multiple ByteBufs (3-5)
    // 3. Write all chunks to queue via write()
    // 4. WITHOUT calling readChunk(), close the channel/response
    // 5. Verify cleanupChunks() drains chunksToWrite queue
    // 6. Verify resolveChunk() called on each chunk
    // 7. CRITICAL: Check ByteBuf refCounts - SHOULD STILL BE 1 (LEAKED)
    // 8. Explicitly release to prevent actual leak in test
    // 9. This demonstrates LEAK-13.3 - the critical production bug
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
    // TODO: Implement test
    // 1. Setup response channel
    // 2. Write chunk
    // 3. Call readChunk() - moves chunk to chunksAwaitingCallback
    // 4. Simulate Netty write failure
    // 5. Verify cleanupChunks() processes chunksAwaitingCallback
    // 6. Check ByteBuf refCount
    // 7. Clean up to prevent leak
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
    // TODO: Implement test
    // 1. Setup response channel with Content-Length header
    // 2. Write chunk that matches Content-Length (isLast=true)
    // 3. readChunk() should create DefaultLastHttpContent
    // 4. Verify retainedDuplicate() used
    // 5. Release HttpContent
    // 6. Verify no leak
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
    // TODO: Implement test
    // 1. Setup response channel
    // 2. Write chunk
    // 3. readChunk() to get HttpContent
    // 4. Simulate error and call resolveChunk(exception)
    // 5. Verify skipBytes() NOT called
    // 6. Verify buffer.release() NOT called
    // 7. Check refCount
    // 8. Clean up
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
    // TODO: Implement test
    // 1. Setup response channel
    // 2. Create callback that tracks invocation
    // 3. Write chunk with callback
    // 4. Process via readChunk()
    // 5. Trigger resolveChunk()
    // 6. Verify callback invoked
    // 7. Verify buffer still has refCount > 0
    // 8. Clean up
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
    // TODO: Implement test
    // 1. Setup response channel
    // 2. Allocate ByteBuf (refCount=1)
    // 3. Write to channel (Chunk stores reference, still refCount=1)
    // 4. readChunk() creates HttpContent with retainedDuplicate() (refCount=2)
    // 5. Release original ByteBuf (refCount=1)
    // 6. Verify HttpContent still valid
    // 7. Release HttpContent (refCount=0)
    // 8. Verify both released
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
    // TODO: Implement test
    // 1. Setup response channel with Content-Length=100
    // 2. Write chunk with 200 bytes
    // 3. Should trigger IllegalStateException
    // 4. CleanupCallback scheduled
    // 5. Verify cleanupChunks() called
    // 6. Check buffer lifecycle
    // 7. Clean up to prevent leak
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
    // TODO: Implement test
    // 1. Allocate ByteBuf for debug data
    // 2. Write some debug message to buffer
    // 3. Create GoAwayException(errorCode=0, debugData)
    // 4. Verify exception message contains debug string
    // 5. Verify GoAwayException does NOT store ByteBuf reference
    // 6. Verify debugData refCount still 1 (caller still owns it)
    // 7. Release debugData
    // 8. Verify refCount=0
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
    // TODO: Implement test
    // 1. Try to create GoAwayException with null debugData
    // 2. Catch any NPE if thrown
    // 3. Verify exception behavior
    // 4. Document whether null is safe or not
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
    // TODO: Implement test
    // 1. Allocate ByteBuf for debug data
    // 2. Create GoAwayException
    // 3. Simulate forgetting to release debugData
    // 4. Verify ByteBuf leaked (refCount=1)
    // 5. For test purposes, release to clean up
    // 6. Documents LEAK-14.2 scenario
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
    // TODO: Implement test
    // 1. Allocate ByteBuf
    // 2. Use try-finally block
    // 3. Create and throw GoAwayException in try
    // 4. Release ByteBuf in finally
    // 5. Verify refCount=0
    // 6. Demonstrates safe usage pattern
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
    // TODO: Implement test
    // 1. Allocate ByteBuf
    // 2. Create GoAwayException (toString() called in constructor)
    // 3. Immediately release ByteBuf (safe because string already extracted)
    // 4. Verify exception.getMessage() still works
    // 5. Verify ByteBuf refCount=0
    // 6. Demonstrates that debugData can be released immediately
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
    // TODO: Implement test
    // 1. Allocate ByteBuf
    // 2. Write UTF-8 text with special characters (emoji, accents, etc.)
    // 3. Create GoAwayException
    // 4. Verify message contains correctly decoded string
    // 5. Release ByteBuf
    // 6. Verify no leak
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
    // TODO: Implement test
    // 1. Allocate large ByteBuf (e.g., 10KB)
    // 2. Fill with debug data
    // 3. Create GoAwayException
    // 4. Verify message contains data
    // 5. Release ByteBuf
    // 6. Verify no leak
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
    // TODO: Implement test
    // 1. Setup NettyResponseChannel
    // 2. Write several chunks
    // 3. Allocate debug data for GoAwayException
    // 4. Create GoAwayException
    // 5. Close response channel with GoAwayException
    // 6. Verify chunks cleaned up
    // 7. Verify debugData released
    // 8. No leaks
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
    // TODO: Implement test
    // 1. Create request
    // 2. Create response channel
    // 3. Set response metadata
    // 4. Write multiple chunks
    // 5. Process each chunk via readChunk()
    // 6. Simulate Netty write and release
    // 7. Complete response
    // 8. Verify all ByteBufs released
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
    // TODO: Implement test
    // 1. Setup response channel
    // 2. Write chunk1, process via readChunk() (moves to awaiting queue)
    // 3. Write chunk2, process via readChunk() (moves to awaiting queue)
    // 4. Write chunk3, chunk4 (stay in chunksToWrite queue)
    // 5. Trigger error
    // 6. Verify cleanupChunks() processes both queues
    // 7. Check for leaks
    // 8. Clean up properly
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
}
