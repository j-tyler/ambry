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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
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
 * CRITICAL OWNERSHIP SEMANTICS:
 * NettyResponseChannel has two write methods with different ownership semantics:
 *
 * 1. write(ByteBuffer): NettyResponseChannel wraps the ByteBuffer in a ByteBuf internally.
 *    Since NettyResponseChannel creates this wrapper, it MUST release it. Failure to do so
 *    causes a native memory leak on every call.
 *
 * 2. write(ByteBuf): The caller provides their own ByteBuf and retains ownership.
 *    NettyResponseChannel MUST NOT release it, or it will cause double-free errors when
 *    the caller tries to release their own ByteBuf.
 *
 * These tests prove the correct (or incorrect) behavior by observing ByteBuf reference counts
 * throughout the response lifecycle.
 */
public class NettyResponseChannelByteBufLeakTest {
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
   * Verifies that write(ByteBuffer) releases the ByteBuf wrapper it creates internally.
   *
   * WHAT THIS TEST PROVES:
   * When write(ByteBuffer) is called, NettyResponseChannel creates a ByteBuf wrapper via
   * Unpooled.wrappedBuffer(). Since NettyResponseChannel creates this wrapper, it owns it
   * and MUST release it. This test proves whether that release happens correctly.
   *
   * HOW IT WORKS:
   * 1. Call write(ByteBuffer) which creates an internal wrapper
   * 2. The wrapper ends up in an HttpContent in the outbound channel
   * 3. Read the HttpContent and get the wrapper ByteBuf from it
   * 4. Check refCnt - should be 2 (wrapper created with 1, HttpContent retains it to 2)
   * 5. Release the HttpContent - this decrements refCnt by 1
   * 6. Check final refCnt - should be 0 if NettyResponseChannel also released it
   *
   * EXPECTED RESULT (WITH BUG):
   * This test will FAIL because NettyResponseChannel never releases the wrapper.
   * After releasing HttpContent, refCnt will be 1 instead of 0, proving the memory leak.
   *
   * EXPECTED RESULT (AFTER FIX):
   * This test will PASS once NettyResponseChannel is fixed to call release() on the wrapper.
   * After releasing HttpContent, refCnt will correctly be 0.
   */
  @Test
  public void testWriteByteBufferReleasesWrapper() throws Exception {
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // Call write(ByteBuffer) - this creates an internal wrapper that NettyResponseChannel owns
    String testData = "test data";
    ByteBuffer byteBuffer = ByteBuffer.wrap(testData.getBytes(StandardCharsets.UTF_8));
    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    // Read the HttpResponse and HttpContent from the outbound channel
    HttpResponse response = channel.readOutbound();
    assertNotNull("HttpResponse should be written to channel", response);

    HttpContent content = channel.readOutbound();
    assertNotNull("HttpContent should be written to channel", content);

    // Get the wrapper ByteBuf from the HttpContent
    ByteBuf wrapperByteBuf = content.content();
    assertNotNull("HttpContent should contain ByteBuf", wrapperByteBuf);

    // Verify initial refCnt - should be 2
    // (1 from Unpooled.wrappedBuffer() + 1 from HttpContent retaining it)
    int initialRefCnt = wrapperByteBuf.refCnt();
    assertEquals("Wrapper ByteBuf should have refCnt=2 (created + retained by HttpContent)",
        2, initialRefCnt);

    // Release the HttpContent - this should decrement refCnt by 1
    content.release();

    // CRITICAL ASSERTION: refCnt should now be 0
    // - HttpContent.release() decrements by 1 (from 2 to 1)
    // - NettyResponseChannel should have also called release() (from 1 to 0)
    //
    // If this assertion fails with refCnt=1, it proves NettyResponseChannel
    // never released the wrapper, causing a memory leak.
    int finalRefCnt = wrapperByteBuf.refCnt();
    assertEquals("Wrapper ByteBuf should have refCnt=0 after HttpContent release. "
            + "If this fails with refCnt=1, it proves NettyResponseChannel leaked the wrapper.",
        0, finalRefCnt);
  }

  /**
   * Verifies that write(ByteBuf) does NOT release the caller's ByteBuf.
   *
   * WHAT THIS TEST PROVES:
   * When write(ByteBuf) is called, the caller passes their own ByteBuf and retains ownership.
   * NettyResponseChannel MUST NOT call release() on it. The caller is responsible for releasing
   * their own ByteBuf. This test proves that ownership contract is respected.
   *
   * HOW IT WORKS:
   * 1. Caller creates a ByteBuf with refCnt=1
   * 2. Call write(ByteBuf) passing the caller's ByteBuf
   * 3. Track the caller's ByteBuf refCnt throughout - it should stay at 1
   * 4. Read and release the HttpContent
   * 5. Verify caller's ByteBuf still has refCnt=1 (proving it wasn't retained or released)
   * 6. Caller releases their own ByteBuf - refCnt goes to 0
   *
   * NOTE: The HttpContent may contain a duplicate/slice of the caller's ByteBuf with its own
   * refCnt lifecycle. What matters is the caller's ORIGINAL ByteBuf maintains refCnt=1,
   * proving NettyResponseChannel didn't touch it.
   *
   * EXPECTED RESULT:
   * This test should PASS because NettyResponseChannel correctly does NOT retain or release the
   * caller's ByteBuf. The caller's ByteBuf maintains refCnt=1 throughout, and the caller can
   * safely release it themselves.
   *
   * FAILURE MODE:
   * If NettyResponseChannel incorrectly releases the caller's ByteBuf, the refCnt would drop
   * to 0, and the caller's final release() would throw IllegalReferenceCountException (double-free).
   */
  @Test
  public void testWriteByteBufDoesNotReleaseCaller() throws Exception {
    NettyResponseChannel responseChannel = new NettyResponseChannel(
        contextCapture.ctx, nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // Caller creates a ByteBuf - the caller owns this and is responsible for releasing it
    String testData = "test data";
    ByteBuf callerByteBuf = io.netty.buffer.Unpooled.wrappedBuffer(testData.getBytes(StandardCharsets.UTF_8));
    assertEquals("Caller's ByteBuf should start with refCnt=1", 1, callerByteBuf.refCnt());

    // Call write(ByteBuf) - passing the caller's ByteBuf
    TestCallback callback = new TestCallback();
    responseChannel.write(callerByteBuf, callback);

    // CRITICAL: Check that caller's ByteBuf refCnt is still 1
    // If NettyResponseChannel incorrectly called retain(), this would be 2
    assertEquals("Caller's ByteBuf should still have refCnt=1 after write(). "
            + "NettyResponseChannel must NOT retain the caller's ByteBuf.",
        1, callerByteBuf.refCnt());

    // Read the HttpResponse and HttpContent from the outbound channel
    HttpResponse response = channel.readOutbound();
    assertNotNull("HttpResponse should be written to channel", response);

    HttpContent content = channel.readOutbound();
    assertNotNull("HttpContent should be written to channel", content);

    // Release the HttpContent (which may contain a duplicate of the caller's ByteBuf)
    content.release();

    // CRITICAL ASSERTION: Caller's ByteBuf refCnt should STILL be 1
    // - NettyResponseChannel should NOT have retained it (still 1, not 2)
    // - NettyResponseChannel should NOT have released it (still 1, not 0)
    // - The caller maintains full ownership
    assertEquals("Caller's ByteBuf should still have refCnt=1 after HttpContent release. "
            + "NettyResponseChannel must NOT retain or release the caller's ByteBuf.",
        1, callerByteBuf.refCnt());

    // Caller releases their own ByteBuf (demonstrating correct ownership)
    callerByteBuf.release();
    assertEquals("Caller's ByteBuf should have refCnt=0 after caller releases it",
        0, callerByteBuf.refCnt());
  }

  // Helper classes

  /**
   * Minimal callback implementation for write operations.
   * EmbeddedChannel processes operations synchronously, so no need to wait for completion.
   */
  private static class TestCallback implements Callback<Long> {
    @Override
    public void onCompletion(Long result, Exception exception) {
      // No-op: EmbeddedChannel processes synchronously
    }
  }

  /**
   * Handler that captures the ChannelHandlerContext when channelActive is called.
   * This ensures we have a context from an actually active channel for testing.
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
