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
import com.github.ambry.router.FutureResult;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Production tests for NettyRequest writeContent() memory leak fix.
 *
 * These tests will FAIL when the bug exists and PASS when fixed.
 * Bug: writeContent() exception after retain() leaks buffer
 *
 * Location: ambry-rest/src/main/java/com/github/ambry/rest/NettyRequest.java:208-215
 */
public class NettyRequestProductionLeakTest {
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();
  private NettyRequest nettyRequest;

  @Before
  public void setUp() throws Exception {
    leakHelper.beforeTest();

    // Create a basic HTTP request
    HttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/test");
    nettyRequest = new NettyRequest(httpRequest, null, null);
  }

  @After
  public void tearDown() {
    if (nettyRequest != null) {
      nettyRequest.close();
    }
    leakHelper.afterTest();
  }

  /**
   * PRODUCTION TEST: writeContent() with channel write failure should not leak
   *
   * This test will FAIL with the current bug.
   * After fix, this test will PASS.
   *
   * Scenario: writeChannel.write() throws exception after content.retain()
   */
  @Test
  public void testWriteContentChannelWriteFailureDoesNotLeak() throws Exception {
    // Add content to the request
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    content.writeBytes(TestUtils.getRandomBytes(1024));
    HttpContent httpContent = new DefaultHttpContent(content);

    nettyRequest.addContent(httpContent);

    // Release our reference (NettyRequest retained it)
    httpContent.release();

    // Create a channel that will fail on write
    AsyncWritableChannel faultyChannel = new AsyncWritableChannel() {
      @Override
      public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
        // Simulate write failure
        FutureResult<Long> future = new FutureResult<>();
        IOException exception = new IOException("Simulated channel write failure");
        future.done(0L, exception);
        if (callback != null) {
          callback.onCompletion(0L, exception);
        }
        return future;
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() throws IOException {
      }
    };

    // Attempt to read content into faulty channel
    try {
      nettyRequest.readInto(faultyChannel, null).get();
      fail("Expected exception from channel write failure");
    } catch (Exception e) {
      // Expected - channel write failed
      // BUG: content was retained in writeContent() (line 494) but never released
      // because writeChannel.write() threw exception (line 495)
    }

    // If bug exists: content is leaked (refCnt=2: original + retain without release)
    // Test will FAIL here with leak detection
    //
    // After fix: writeContent() has try-catch that releases on exception
    // Test will PASS
  }

  /**
   * PRODUCTION TEST: Multiple writeContent() failures should not accumulate leaks
   *
   * Simulates production scenario with multiple failed write attempts.
   */
  @Test
  public void testMultipleWriteContentFailuresDoNotLeak() throws Exception {
    for (int i = 0; i < 5; i++) {
      // Create new request for each iteration
      HttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/test" + i);
      NettyRequest request = new NettyRequest(httpRequest, null, null);

      // Add content
      ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
      content.writeBytes(TestUtils.getRandomBytes(512));
      HttpContent httpContent = new DefaultHttpContent(content);
      request.addContent(httpContent);
      httpContent.release();

      // Faulty channel
      AsyncWritableChannel faultyChannel = new AsyncWritableChannel() {
        @Override
        public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
          FutureResult<Long> future = new FutureResult<>();
          IOException exception = new IOException("Write failure " + i);
          future.done(0L, exception);
          if (callback != null) {
            callback.onCompletion(0L, exception);
          }
          return future;
        }

        @Override
        public boolean isOpen() {
          return true;
        }

        @Override
        public void close() throws IOException {
        }
      };

      try {
        request.readInto(faultyChannel, null).get();
      } catch (Exception e) {
        // Expected
      }

      request.close();
    }

    // If bug exists: 5 buffers leaked
    // After fix: No leaks
  }

  /**
   * PRODUCTION TEST: writeContent() with intermittent failures
   *
   * Tests recovery after failures.
   */
  @Test
  public void testWriteContentIntermittentFailuresDoNotLeak() throws Exception {
    // First, trigger a failure
    HttpRequest httpRequest1 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/test1");
    NettyRequest request1 = new NettyRequest(httpRequest1, null, null);

    ByteBuf content1 = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    content1.writeBytes(TestUtils.getRandomBytes(1024));
    HttpContent httpContent1 = new DefaultHttpContent(content1);
    request1.addContent(httpContent1);
    httpContent1.release();

    AsyncWritableChannel faultyChannel = new AsyncWritableChannel() {
      @Override
      public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
        FutureResult<Long> future = new FutureResult<>();
        IOException exception = new IOException("Intermittent failure");
        future.done(0L, exception);
        if (callback != null) {
          callback.onCompletion(0L, exception);
        }
        return future;
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() throws IOException {
      }
    };

    try {
      request1.readInto(faultyChannel, null).get();
    } catch (Exception e) {
      // Expected failure
    }

    request1.close();

    // Now, test successful write (verifies fix doesn't break normal operation)
    HttpRequest httpRequest2 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/test2");
    NettyRequest request2 = new NettyRequest(httpRequest2, null, null);

    ByteBuf content2 = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    byte[] data = TestUtils.getRandomBytes(1024);
    content2.writeBytes(data);
    HttpContent httpContent2 = new DefaultHttpContent(content2);
    request2.addContent(httpContent2);
    httpContent2.release();

    // Successful channel
    TestAsyncWritableChannel successChannel = new TestAsyncWritableChannel();

    Future<Long> readFuture = request2.readInto(successChannel, null);
    Long bytesRead = readFuture.get();

    assertEquals("Should have read all bytes", 1024, bytesRead.longValue());

    request2.close();

    // Should have no leaks from either failure or success
  }

  /**
   * PRODUCTION TEST: Successful writeContent() should work correctly
   *
   * Verifies that the fix doesn't break normal operation.
   */
  @Test
  public void testSuccessfulWriteContentNoLeak() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(2048);
    byte[] data = TestUtils.getRandomBytes(2048);
    content.writeBytes(data);
    HttpContent httpContent = new DefaultHttpContent(content);

    nettyRequest.addContent(httpContent);
    httpContent.release();

    // Successful channel
    TestAsyncWritableChannel channel = new TestAsyncWritableChannel();

    Future<Long> readFuture = nettyRequest.readInto(channel, null);
    Long bytesRead = readFuture.get();

    assertEquals("Should have read all bytes", 2048, bytesRead.longValue());

    // Verify data
    byte[] readData = channel.getWrittenData();
    assertNotNull("Should have data", readData);
    assertEquals("Data length should match", 2048, readData.length);

    // Should have no leaks
  }

  /**
   * Simple test channel that collects written data
   */
  private static class TestAsyncWritableChannel implements AsyncWritableChannel {
    private byte[] writtenData;

    @Override
    public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
      writtenData = new byte[src.remaining()];
      src.get(writtenData);

      FutureResult<Long> future = new FutureResult<>();
      future.done((long) writtenData.length, null);
      if (callback != null) {
        callback.onCompletion((long) writtenData.length, null);
      }
      return future;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() throws IOException {
    }

    public byte[] getWrittenData() {
      return writtenData;
    }
  }
}
