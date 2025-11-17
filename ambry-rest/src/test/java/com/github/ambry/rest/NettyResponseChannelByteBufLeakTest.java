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
import java.util.Collections;
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
    nettyByteBufLeakHelper.afterTest();
  }

  /**
   * This test proves the ByteBuf leak when write(ByteBuffer) creates an internal wrapper.
   *
   * Test flow:
   * 1. Call write(ByteBuffer) which creates internal ByteBuf wrapper
   * 2. Process the write through completion
   * 3. NettyByteBufLeakHelper will detect leaked ByteBuf in tearDown()
   *
   * Before fix: Test FAILS - NettyByteBufLeakHelper detects the internal ByteBuf wrapper leaked
   * After fix: Test PASSES - Internal ByteBuf wrapper is released in resolveChunk()
   */
  @Test
  public void testWriteByteBufferLeaksInternalWrapper() throws Exception {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // PRODUCTION PATTERN: Call write(ByteBuffer)
    // This creates an internal ByteBuf wrapper via Unpooled.wrappedBuffer()
    String data = "test data that will leak";
    ByteBuffer byteBuffer = ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));

    TestCallback callback = new TestCallback();
    responseChannel.write(byteBuffer, callback);

    // Complete the response to trigger chunk processing
    responseChannel.onResponseComplete(null);

    // Wait for callback
    assertTrue("Callback should complete", callback.awaitCompletion(1000));

    // Read response
    HttpResponse response = channel.readOutbound();
    assertNotNull("Should have response", response);

    // Read and release HttpContent objects
    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof HttpContent) {
        HttpContent content = (HttpContent) outbound;
        content.release();
      }
    }

    // LEAK DETECTION:
    // The NettyByteBufLeakHelper.afterTest() in tearDown() will detect any ByteBuf leaks.
    // Before fix: Will detect the leaked internal ByteBuf wrapper → TEST FAILS
    // After fix: No leak detected → TEST PASSES
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
