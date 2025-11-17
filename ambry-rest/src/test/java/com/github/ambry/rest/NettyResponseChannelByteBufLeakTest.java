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
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.nio.ByteBuffer;
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
 * Test to prove ByteBuf leak in NettyResponseChannel when write(ByteBuffer) creates internal wrappers.
 *
 * The bug: When write(ByteBuffer) is called, NettyResponseChannel creates a ByteBuf wrapper using
 * Unpooled.wrappedBuffer(). This ByteBuf is stored in a Chunk, and when readChunk() is called,
 * it creates a retainedDuplicate() which increments the refCount. Netty releases the duplicate,
 * but the original ByteBuf in the Chunk is never released in resolveChunk().
 */
public class NettyResponseChannelByteBufLeakTest {
  private NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();
  private EmbeddedChannel channel;
  private NettyMetrics nettyMetrics;
  private NettyConfig nettyConfig;
  private PerformanceConfig performanceConfig;
  private List<ByteBuf> capturedByteBufs = new ArrayList<>();

  @Before
  public void setUp() {
    nettyByteBufLeakHelper.beforeTest();
    nettyMetrics = new NettyMetrics(new MetricRegistry());

    Properties properties = new Properties();
    VerifiableProperties verifiableProperties = new VerifiableProperties(properties);
    nettyConfig = new NettyConfig(verifiableProperties);
    performanceConfig = new PerformanceConfig(verifiableProperties);

    channel = new EmbeddedChannel();
    ChunkedWriteHandler chunkedWriteHandler = new ChunkedWriteHandler();
    channel.pipeline().addLast(chunkedWriteHandler);
  }

  @After
  public void tearDown() {
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
    // Release any captured ByteBufs to prevent test framework leaks
    for (ByteBuf buf : capturedByteBufs) {
      if (buf.refCnt() > 0) {
        buf.release(buf.refCnt());
      }
    }
    nettyByteBufLeakHelper.afterTest();
  }

  /**
   * This test proves the ByteBuf leak when write(ByteBuffer) creates an internal wrapper.
   *
   * The test directly uses write(ByteBuf) with a tracked ByteBuf to prove the leak pattern.
   *
   * Expected behavior:
   * - write(ByteBuf) with our tracked ByteBuf, refCount=1
   * - Chunk stores the ByteBuf
   * - readChunk() calls retainedDuplicate(), refCount=2
   * - Netty releases duplicate, refCount=1
   * - resolveChunk() should release original, refCount=0
   *
   * Actual behavior (BUG):
   * - resolveChunk() does NOT release, refCount stays at 1 → LEAK
   */
  @Test
  public void testWriteByteBufferLeaksInternalWrapper() throws Exception {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // Create a tracked ByteBuf directly (simulating what Unpooled.wrappedBuffer does internally)
    String data = "test data that will leak";
    ByteBuf trackedByteBuf = Unpooled.wrappedBuffer(data.getBytes(StandardCharsets.UTF_8));
    capturedByteBufs.add(trackedByteBuf); // Track for cleanup

    // Initial state: refCount = 1
    assertEquals("Initial refCount should be 1", 1, trackedByteBuf.refCnt());

    TestCallback callback = new TestCallback();

    // Call write(ByteBuf) directly with our tracked ByteBuf
    // This simulates what happens inside write(ByteBuffer) when it calls Unpooled.wrappedBuffer()
    responseChannel.write(trackedByteBuf, callback);

    // RefCount should still be 1 (Chunk just stores the reference, doesn't retain)
    assertEquals("After write(), refCount should still be 1", 1, trackedByteBuf.refCnt());

    // Complete the response to trigger chunk processing
    responseChannel.onResponseComplete(null);

    // Wait for callback
    assertTrue("Callback should complete", callback.awaitCompletion(1000));

    // Read response
    HttpResponse response = channel.readOutbound();
    assertNotNull("Should have response", response);

    // Read and release HttpContent objects (these are the duplicates)
    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof HttpContent) {
        HttpContent content = (HttpContent) outbound;
        // The content wraps a retainedDuplicate() of our trackedByteBuf
        // When we release it, it should decrement the original's refCount
        content.release();
      }
    }

    // BUG VERIFICATION:
    // After all processing, the original trackedByteBuf should have refCount=0
    // But due to the bug in resolveChunk(), it will be 1 (LEAKED)

    int finalRefCnt = trackedByteBuf.refCnt();

    // This assertion will FAIL, proving the bug
    assertEquals(
        "ByteBuf should be fully released (refCnt=0) after chunk resolution, but it's LEAKED! " +
        "The bug is in NettyResponseChannel.Chunk.resolveChunk() which never calls buffer.release()",
        0,
        finalRefCnt
    );
  }

  /**
   * Helper callback implementation
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
