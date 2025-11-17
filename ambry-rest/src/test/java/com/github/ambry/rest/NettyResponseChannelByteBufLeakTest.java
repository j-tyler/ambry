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
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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
 * This test uses reflection to extract the internal wrapper ByteBuf and verify its refCount
 * lifecycle to prove the bug exists before the fix and is resolved after the fix.
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

    // Create channel with handler in constructor to ensure it's registered and active
    ChunkedWriteHandler chunkedWriteHandler = new ChunkedWriteHandler();
    channel = new EmbeddedChannel(chunkedWriteHandler);
  }

  @After
  public void tearDown() {
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
    nettyByteBufLeakHelper.afterTest();
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
   * 3. Verify wrapper has refCnt=1 before chunk processing
   * 4. Trigger chunk processing through normal flow (readChunk creates duplicate)
   * 5. After Netty releases the duplicate and resolveChunk() is called
   * 6. Assert wrapper has refCnt=0 (released)
   *
   * Before fix: Assertion FAILS - wrapper has refCnt=1 (leaked)
   * After fix: Assertion PASSES - wrapper has refCnt=0 (released in resolveChunk)
   */
  @Test
  public void testWriteByteBufferReleasesWrapper() throws Exception {
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());

    NettyResponseChannel responseChannel = new NettyResponseChannel(
        new MockChannelHandlerContext(channel), nettyMetrics, performanceConfig, nettyConfig);

    responseChannel.setStatus(ResponseStatus.Ok);
    responseChannel.setHeader(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked");

    // PRODUCTION PATTERN: Call write(ByteBuffer)
    // This creates an internal ByteBuf wrapper via Unpooled.wrappedBuffer()
    ByteBuffer byteBuffer = ByteBuffer.allocate(100);
    byteBuffer.put(new byte[100]);
    byteBuffer.flip();

    CountDownLatch latch = new CountDownLatch(1);
    responseChannel.write(byteBuffer, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNull("Should have no exception", exception);
        latch.countDown();
      }
    });

    // Get the wrapper ByteBuf from the channel before processing
    ByteBuf wrapper = getWrapperFromChannel(responseChannel);
    assertNotNull("Wrapper ByteBuf should exist in chunksToWrite queue after write() call", wrapper);

    // Wrapper should have refCnt=1 initially (created by Unpooled.wrappedBuffer)
    assertEquals("Wrapper should have refCnt=1 before chunk processing", 1, wrapper.refCnt());

    // Complete the response to trigger chunk processing
    // This will call readChunk() which creates a retainedDuplicate (refCnt becomes 2)
    // Then Netty releases the duplicate (refCnt becomes 1)
    // Then resolveChunk() is called
    responseChannel.onResponseComplete(null);

    // Wait for callback
    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));

    // Read and release HttpContent objects
    HttpResponse response = channel.readOutbound();
    assertNotNull("Should have response", response);

    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof HttpContent) {
        HttpContent content = (HttpContent) outbound;
        content.release();
      }
    }

    // CRITICAL ASSERTION: Wrapper ByteBuf should be released after resolveChunk()
    // Before fix: refCnt=1 (LEAKED - resolveChunk doesn't call release)
    // After fix: refCnt=0 (RELEASED - resolveChunk calls buffer.release())
    assertEquals("Wrapper ByteBuf should be released after resolveChunk()", 0, wrapper.refCnt());
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

}
