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
package com.github.ambry.network.http2;

import com.github.ambry.network.Send;
import com.github.ambry.utils.AbstractByteBufHolder;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import java.nio.channels.WritableByteChannel;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Production tests for AmbrySendToHttp2Adaptor shared refCnt memory leak fix.
 *
 * These tests will FAIL when the bug exists and PASS when fixed.
 * Bug: Retained slices not released on exception due to shared refCnt
 *
 * Location: ambry-network/.../http2/AmbrySendToHttp2Adaptor.java:82-98
 */
public class AmbrySendToHttp2AdaptorProductionLeakTest {
  private static final int MAX_FRAME_SIZE = 8192;

  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();
  private ChannelHandlerContext mockContext;
  private ChannelPromise mockPromise;
  private AmbrySendToHttp2Adaptor adaptor;

  @Before
  public void setUp() throws Exception {
    leakHelper.beforeTest();

    mockContext = mock(ChannelHandlerContext.class);
    mockPromise = mock(ChannelPromise.class);

    when(mockContext.channel()).thenReturn(mock(io.netty.channel.Channel.class));
    when(mockContext.channel().isOpen()).thenReturn(true);
    when(mockContext.newPromise()).thenReturn(mockPromise);

    adaptor = new AmbrySendToHttp2Adaptor(false, MAX_FRAME_SIZE);
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();
  }

  /**
   * PRODUCTION TEST: Exception during frame writing should not leak slices
   *
   * This test will FAIL with the current bug.
   * After fix, this test will PASS.
   *
   * Bug: readSlice() creates slices with SHARED refCnt. Multiple retain() calls
   * increment the shared counter, but finally block only releases once.
   *
   * Scenario: Exception during ctx.write() after some slices retained
   */
  @Test
  public void testExceptionDuringFrameWritingShouldNotLeakSlices() throws Exception {
    // Create content requiring 3 frames (24KB)
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(24 * 1024);
    fillWithRandomData(content, 24 * 1024);

    assertEquals("Should start with refCnt=1", 1, content.refCnt());

    // Create Send wrapper
    TestSend send = new TestSend(content);

    // Mock ctx.write() to succeed for first frame, fail on second
    AtomicInteger writeCount = new AtomicInteger(0);
    when(mockContext.write(any(Http2DataFrame.class))).thenAnswer(invocation -> {
      int count = writeCount.incrementAndGet();
      if (count == 2) {
        // Second frame fails
        throw new RuntimeException("Simulated write failure on frame " + count);
      }
      return mockPromise;
    });

    // Attempt write
    try {
      adaptor.write(mockContext, send, mockPromise);
      fail("Expected exception from frame write failure");
    } catch (RuntimeException e) {
      // Expected - write failed on second frame
      assertEquals("Simulated write failure on frame 2", e.getMessage());
    }

    // BUG: At this point, refCnt tracking is broken due to shared refCnt:
    // - Original buffer: refCnt=1
    // - After 1st readSlice().retain(): shared refCnt=2
    // - After 2nd readSlice().retain(): shared refCnt=3
    // - Exception thrown
    // - Finally block: content.release() → shared refCnt=2 (NOT 0!)
    //
    // Result: Buffer leaked with refCnt=2

    // In production code, we need to track and release all retained slices
    // After fix, this should pass

    // If bug exists: content.refCnt() will be > 0, test FAILS with leak detection
    // After fix: All slices released in catch block, content.refCnt() == 0
  }

  /**
   * PRODUCTION TEST: Finally block should account for shared refCnt
   *
   * This test will FAIL with the current bug.
   * After fix, this test will PASS.
   *
   * Bug: Even if no exception, finally block's single release() doesn't fully clean up
   * when multiple slices have been retained with shared refCnt.
   */
  @Test
  public void testFinallyBlockShouldAccountForSharedRefCnt() throws Exception {
    // Create content requiring 3 frames
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(20 * 1024);
    fillWithRandomData(content, 20 * 1024);

    TestSend send = new TestSend(content);

    // Mock to fail on FIRST frame
    when(mockContext.write(any(Http2DataFrame.class))).thenAnswer(invocation -> {
      throw new RuntimeException("Simulated failure on first frame");
    });

    try {
      adaptor.write(mockContext, send, mockPromise);
      fail("Expected exception");
    } catch (RuntimeException e) {
      // Expected
    }

    // BUG: Even though we only retained 1 slice before exception,
    // the shared refCnt means finally block's single release() isn't enough
    //
    // Sequence:
    // 1. content refCnt = 1
    // 2. readSlice().retain() → shared refCnt = 2
    // 3. ctx.write() throws
    // 4. finally: content.release() → shared refCnt = 1 (LEAKED!)

    // If bug exists: refCnt=1, test FAILS
    // After fix: Catch block releases retained slice before finally, refCnt=0
  }

  /**
   * PRODUCTION TEST: Multiple frame write failures should not accumulate leaks
   *
   * Simulates production scenario with multiple failed HTTP/2 writes.
   */
  @Test
  public void testMultipleFrameWriteFailuresDoNotLeak() throws Exception {
    for (int i = 0; i < 10; i++) {
      ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(16 * 1024);
      fillWithRandomData(content, 16 * 1024);

      TestSend send = new TestSend(content);

      // Fail at different frames each time
      final int iteration = i;
      AtomicInteger writeCount = new AtomicInteger(0);
      when(mockContext.write(any(Http2DataFrame.class))).thenAnswer(invocation -> {
        int count = writeCount.incrementAndGet();
        if (count == (iteration % 2) + 1) {
          throw new RuntimeException("Failure " + iteration);
        }
        return mockPromise;
      });

      try {
        adaptor.write(mockContext, send, mockPromise);
      } catch (RuntimeException e) {
        // Expected
      }
    }

    // If bug exists: Multiple buffers leaked
    // After fix: No leaks
  }

  /**
   * PRODUCTION TEST: Large multi-frame send with failure
   *
   * Tests with many frames to amplify the shared refCnt problem.
   */
  @Test
  public void testLargeMultiFrameSendWithFailureDoesNotLeak() throws Exception {
    // Create 100KB content (requires ~13 frames at 8KB each)
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(100 * 1024);
    fillWithRandomData(content, 100 * 1024);

    TestSend send = new TestSend(content);

    // Fail after 5 frames
    AtomicInteger writeCount = new AtomicInteger(0);
    when(mockContext.write(any(Http2DataFrame.class))).thenAnswer(invocation -> {
      int count = writeCount.incrementAndGet();
      if (count == 5) {
        throw new RuntimeException("Failure after 5 frames");
      }
      return mockPromise;
    });

    try {
      adaptor.write(mockContext, send, mockPromise);
      fail("Expected exception");
    } catch (RuntimeException e) {
      // Expected
    }

    // BUG: With shared refCnt, after 5 retains and 1 release in finally:
    // refCnt = 1 + 5 - 1 = 5 (MASSIVE LEAK!)
    //
    // After fix: All 5 retained slices released in catch, refCnt = 0
  }

  /**
   * PRODUCTION TEST: Successful multi-frame write should work correctly
   *
   * Verifies that the fix doesn't break normal operation.
   */
  @Test
  public void testSuccessfulMultiFrameWriteNoLeak() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(25 * 1024);
    fillWithRandomData(content, 25 * 1024);

    TestSend send = new TestSend(content);

    // Capture all data frames
    ArgumentCaptor<Http2DataFrame> frameCaptor = ArgumentCaptor.forClass(Http2DataFrame.class);
    when(mockContext.write(frameCaptor.capture())).thenReturn(mockPromise);

    // Successful write
    adaptor.write(mockContext, send, mockPromise);

    // Verify frames were written
    assertTrue("Should have written multiple frames", frameCaptor.getAllValues().size() >= 3);

    // Simulate channel releasing frames (normal HTTP/2 behavior)
    for (Http2DataFrame frame : frameCaptor.getAllValues()) {
      frame.content().release();
    }

    // Should have no leaks
    // Note: In real operation, Netty's HTTP/2 codec releases frames after writing
  }

  /**
   * Helper method to fill ByteBuf with random data
   */
  private void fillWithRandomData(ByteBuf buf, int size) {
    Random random = new Random(12345); // Fixed seed for reproducibility
    byte[] data = new byte[size];
    random.nextBytes(data);
    buf.writeBytes(data);
  }

  /**
   * Test implementation of Send interface
   */
  private static class TestSend extends AbstractByteBufHolder<TestSend> implements Send {
    private final ByteBuf content;

    public TestSend(ByteBuf content) {
      this.content = content;
    }

    @Override
    public long writeTo(WritableByteChannel channel) {
      return 0;
    }

    @Override
    public boolean isSendComplete() {
      return !content.isReadable();
    }

    @Override
    public long sizeInBytes() {
      return content.readableBytes();
    }

    @Override
    public ByteBuf content() {
      return content;
    }
  }
}
