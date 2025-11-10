/*
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

import com.github.ambry.commons.Callback;
import com.github.ambry.router.AsyncWritableChannel;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Production-testing test for NettyRequest.writeContent() ByteBuf leak.
 *
 * CRITICAL BUG: NettyRequest.writeContent() line 494-495
 * - httpContent.retain() is called (line 494)
 * - If writeChannel.write() throws (line 495), ContentWriteCallback is never created
 * - Therefore httpContent.release() (in callback) is never called
 * - The retained ByteBuf leaks
 *
 * This test will FAIL with current production code due to the leak.
 * After fixing the bug, this test will PASS.
 */
public class NettyRequestWriteContentLeakTest {
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();
  private NettyMetrics nettyMetrics;
  private EmbeddedChannel channel;

  @Before
  public void setUp() throws Exception {
    leakHelper.beforeTest();
    nettyMetrics = new NettyMetrics(new MockRegistry());
    channel = new EmbeddedChannel();
  }

  @After
  public void tearDown() throws Exception {
    if (channel != null) {
      channel.close();
    }
    leakHelper.afterTest();
  }

  /**
   * Test that demonstrates the CRITICAL bug in NettyRequest.writeContent()
   *
   * Bug location: ambry-rest/src/main/java/com/github/ambry/rest/NettyRequest.java:494-495
   *
   * Sequence:
   * 1. readInto() is called with a faulty AsyncWritableChannel
   * 2. addContent() is called with HttpContent
   * 3. writeContent() is invoked internally (line 430)
   * 4. Line 494: httpContent.retain() is called → refCnt becomes 2
   * 5. Line 495: writeChannel.write() throws RuntimeException
   * 6. ContentWriteCallback is never created, so release() is never called
   * 7. LEAK: The retained HttpContent (refCnt=1) is never released
   *
   * Expected: This test FAILS with current code (leak detected)
   * After fix: This test PASSES (no leak)
   */
  @Test
  public void testWriteContentExceptionLeaksRetainedBuffer() throws Exception {
    // Create request
    HttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());

    // Create a WriteChannel that throws during write
    CountDownLatch exceptionLatch = new CountDownLatch(1);
    AsyncWritableChannel faultyChannel = new AsyncWritableChannel() {
      @Override
      public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
        throw new RuntimeException("Simulated write failure");
      }

      @Override
      public Future<Long> write(ByteBuf src, Callback<Long> callback) {
        exceptionLatch.countDown();
        throw new RuntimeException("Simulated write failure");
      }

      @Override
      public void close() {}

      @Override
      public boolean isOpen() {
        return true;
      }
    };

    // Start reading into the faulty channel
    CountDownLatch readCallbackLatch = new CountDownLatch(1);
    request.readInto(faultyChannel, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        readCallbackLatch.countDown();
      }
    });

    // Create HttpContent with ByteBuf
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    content.writeBytes(new byte[1024]);
    HttpContent httpContent = new DefaultHttpContent(content);

    // Verify initial refCnt
    assertEquals("Initial refCnt should be 1", 1, content.refCnt());

    // Add content - this will trigger writeContent() which will throw
    try {
      request.addContent(httpContent);
      fail("Should have thrown exception from write");
    } catch (RuntimeException e) {
      assertEquals("Simulated write failure", e.getMessage());
    }

    // Wait for exception to be thrown in write
    assertTrue("Write should have been attempted", exceptionLatch.await(5, TimeUnit.SECONDS));

    // Release our reference (we still own it since addContent threw)
    // Following the ownership contract: if addContent throws, we still own our reference
    httpContent.release();

    // At this point, the bug manifests:
    // - Line 494 called httpContent.retain() → refCnt went 1 → 2
    // - Line 495 threw exception → ContentWriteCallback never created
    // - Our httpContent.release() above → refCnt went 2 → 1
    // - LEAK: refCnt is still 1, the retained copy is never released

    // Close the request
    request.close();

    // The NettyByteBufLeakHelper in @After will detect the leak
    // Current code: TEST FAILS (leak detected)
    // After fix: TEST PASSES (no leak)
  }

  /**
   * Test that verifies the fix works correctly
   *
   * When writeChannel.write() succeeds, the ContentWriteCallback is created
   * and properly releases the retained buffer.
   *
   * This test should PASS both before and after the fix.
   */
  @Test
  public void testWriteContentSuccessProperlyReleasesBuffer() throws Exception {
    // Create request
    HttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());

    // Create a working WriteChannel
    CountDownLatch writeLatch = new CountDownLatch(1);
    AsyncWritableChannel workingChannel = new AsyncWritableChannel() {
      @Override
      public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
        new Thread(() -> {
          try {
            Thread.sleep(10);
            callback.onCompletion((long) src.remaining(), null);
          } catch (InterruptedException e) {
            callback.onCompletion(null, e);
          }
        }).start();
        return null;
      }

      @Override
      public Future<Long> write(ByteBuf src, Callback<Long> callback) {
        long size = src.readableBytes();
        new Thread(() -> {
          try {
            Thread.sleep(10);
            callback.onCompletion(size, null);
            writeLatch.countDown();
          } catch (InterruptedException e) {
            callback.onCompletion(null, e);
          }
        }).start();
        return null;
      }

      @Override
      public void close() {}

      @Override
      public boolean isOpen() {
        return true;
      }
    };

    CountDownLatch readLatch = new CountDownLatch(1);
    request.readInto(workingChannel, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        readLatch.countDown();
      }
    });

    // Add content
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    content.writeBytes(new byte[1024]);
    HttpContent httpContent = new DefaultHttpContent(content);

    request.addContent(httpContent);

    // Release our reference - following proper ownership contract
    httpContent.release();

    // Wait for write to complete
    assertTrue("Write should complete", writeLatch.await(5, TimeUnit.SECONDS));

    // Close request
    request.close();

    // No leak - NettyByteBufLeakHelper in @After verifies
  }

  /**
   * Test demonstrating that multiple content chunks with write failure leak multiple buffers
   *
   * This test shows the severity of the bug when multiple chunks are written and
   * a failure occurs.
   *
   * Expected: FAILS with current code (multiple leaks)
   * After fix: PASSES (no leaks)
   */
  @Test
  public void testMultipleContentChunksWithWriteFailureLeakAll() throws Exception {
    // Create request
    HttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
    NettyRequest request = new NettyRequest(httpRequest, channel, nettyMetrics, Collections.emptySet());

    // Create a WriteChannel that succeeds twice, then fails
    final int[] writeCount = {0};
    CountDownLatch failureLatch = new CountDownLatch(1);

    AsyncWritableChannel faultyChannel = new AsyncWritableChannel() {
      @Override
      public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
        throw new RuntimeException("Simulated write failure");
      }

      @Override
      public Future<Long> write(ByteBuf src, Callback<Long> callback) {
        writeCount[0]++;
        if (writeCount[0] <= 2) {
          // First two writes succeed
          long size = src.readableBytes();
          new Thread(() -> {
            try {
              Thread.sleep(10);
              callback.onCompletion(size, null);
            } catch (InterruptedException e) {
              callback.onCompletion(null, e);
            }
          }).start();
          return null;
        } else {
          // Third write fails
          failureLatch.countDown();
          throw new RuntimeException("Simulated write failure on third write");
        }
      }

      @Override
      public void close() {}

      @Override
      public boolean isOpen() {
        return true;
      }
    };

    CountDownLatch readCallbackLatch = new CountDownLatch(1);
    request.readInto(faultyChannel, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        readCallbackLatch.countDown();
      }
    });

    // Add three content chunks
    for (int i = 0; i < 3; i++) {
      ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
      content.writeBytes(new byte[1024]);
      HttpContent httpContent = new DefaultHttpContent(content);

      if (i < 2) {
        // First two should succeed
        request.addContent(httpContent);
        httpContent.release();  // Release our reference
      } else {
        // Third should fail
        try {
          request.addContent(httpContent);
          httpContent.release();  // Would release our reference if succeeded
          fail("Should have thrown exception from write");
        } catch (RuntimeException e) {
          assertEquals("Simulated write failure on third write", e.getMessage());
          httpContent.release();  // Release our reference since addContent threw
        }
      }
    }

    // Wait for failure
    assertTrue("Write failure should occur", failureLatch.await(5, TimeUnit.SECONDS));

    // Close request
    request.close();

    // The bug: The third httpContent was retained (line 494) but never released
    // NettyByteBufLeakHelper in @After will detect this
  }

  /**
   * Mock registry for NettyMetrics
   */
  private static class MockRegistry extends com.codahale.metrics.MetricRegistry {
    // Simple mock implementation
  }
}
