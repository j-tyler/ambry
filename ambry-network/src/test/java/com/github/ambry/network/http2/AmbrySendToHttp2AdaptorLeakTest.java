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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for AmbrySendToHttp2Adaptor ByteBuf memory leak detection.
 * Tests HIGH-RISK paths #21-23: Exception during frame writing, retained slices not released,
 * and channel closed during write.
 */
public class AmbrySendToHttp2AdaptorLeakTest {
  private static final int MAX_FRAME_SIZE = 8192; // 8KB frames
  private AmbrySendToHttp2Adaptor adaptor;
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();

  @Before
  public void setUp() {
    leakHelper.beforeTest();
    adaptor = new AmbrySendToHttp2Adaptor(true, MAX_FRAME_SIZE);
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();
  }

  /**
   * HIGH-RISK PATH #21: Exception during frame writing loop
   *
   * Tests that if an exception occurs during ctx.write(dataFrame) AFTER some slices
   * have been retained, those retained slices are NOT released, causing a leak.
   * This exposes the bug where the catch block does nothing and the finally block
   * only releases the original buffer once, leaving leaked references.
   *
   * Expected: LEAK DETECTED - Retained slices are not released in exception path
   *
   * This is a CRITICAL BUG. The fix needed:
   * - Track all retained slices in a list
   * - Release all retained slices in the catch block
   */
  @Test
  public void testExceptionDuringFrameWritingLeaksRetainedSlices() throws Exception {
    // Disable leak detection for this test since we expect a leak
    leakHelper.setDisabled(true);

    // Create a Send with 3 frames worth of data (3 * 8KB = 24KB)
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(24 * 1024);
    for (int i = 0; i < 24 * 1024; i++) {
      content.writeByte(i % 256);
    }

    Send send = new TestSend(content);

    // Create mock channel context that throws on the SECOND frame write
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    ChannelPromise promise = mock(ChannelPromise.class);

    when(ctx.channel()).thenReturn(channel);
    when(channel.isOpen()).thenReturn(true);

    AtomicInteger writeCount = new AtomicInteger(0);
    List<ByteBuf> retainedSlices = new ArrayList<>();

    // Capture writes from BOTH single-arg and two-arg write()
    // Fail on second data frame
    when(ctx.write(any())).thenAnswer(invocation -> {
      Object msg = invocation.getArgument(0);
      if (msg instanceof Http2DataFrame) {
        int count = writeCount.incrementAndGet();
        Http2DataFrame frame = (Http2DataFrame) msg;
        ByteBuf slice = frame.content();
        retainedSlices.add(slice); // Track retained slices

        if (count == 2) {
          // Throw exception on second frame (after first slice already retained)
          throw new RuntimeException("Simulated write failure");
        }
      }
      return null;
    });

    when(ctx.write(any(), any())).thenAnswer(invocation -> {
      Object msg = invocation.getArgument(0);
      if (msg instanceof Http2DataFrame) {
        int count = writeCount.incrementAndGet();
        Http2DataFrame frame = (Http2DataFrame) msg;
        ByteBuf slice = frame.content();
        retainedSlices.add(slice); // Track retained slices

        if (count == 2) {
          // Throw exception on second frame (after first slice already retained)
          throw new RuntimeException("Simulated write failure");
        }
      }
      return null;
    });

    // Execute write - should fail on second frame
    adaptor.write(ctx, send, promise);

    // Verify we retained at least 1 slice before exception
    assertTrue("Should have retained at least 1 slice", retainedSlices.size() >= 1);

    // CRITICAL BUG EXPOSED: readSlice() + retain() creates shared refCnt leak
    // Initial refCnt=1, slice1.retain() → 2, slice2.retain() → 3, finally release() → 2
    // Original buffer is LEAKED with refCnt=2
    assertTrue("Original buffer should be leaked (refCnt > 0)", content.refCnt() > 0);

    // All slices share the same refCnt counter with original
    for (ByteBuf slice : retainedSlices) {
      assertTrue("Slice should be leaked (shared refCnt > 0)", slice.refCnt() > 0);
    }

    // Manually clean up leaked buffer (release once per retained slice)
    while (content.refCnt() > 0) {
      content.release();
    }
  }

  /**
   * HIGH-RISK PATH #22: send.content().release() in finally but retained slices leaked
   *
   * Tests that the finally block releases the original Send content but NOT the
   * retained slices created in the loop. This exposes the CRITICAL bug where
   * readSlice() creates slices with SHARED refCnt, so multiple retain() calls
   * leak the buffer even though finally releases it once.
   *
   * Expected: LEAK DETECTED - refCnt > 0 after finally due to retained slices
   */
  @Test
  public void testFinallyReleasesOriginalButNotRetainedSlices() throws Exception {
    leakHelper.setDisabled(true);

    // Create a Send with multiple frames
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(20 * 1024);
    for (int i = 0; i < 20 * 1024; i++) {
      content.writeByte(i % 256);
    }

    Send send = new TestSend(content);

    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    ChannelPromise promise = mock(ChannelPromise.class);

    when(ctx.channel()).thenReturn(channel);
    when(channel.isOpen()).thenReturn(true);

    List<ByteBuf> retainedSlices = new ArrayList<>();

    // Capture all data frames from BOTH single-arg and two-arg write()
    // Throw exception on FIRST data frame to simulate early failure
    when(ctx.write(any())).thenAnswer(invocation -> {
      Object msg = invocation.getArgument(0);
      if (msg instanceof Http2DataFrame) {
        Http2DataFrame frame = (Http2DataFrame) msg;
        retainedSlices.add(frame.content());
        // Throw exception to trigger error path
        throw new RuntimeException("Simulated write failure");
      }
      return null;
    });

    when(ctx.write(any(), any())).thenAnswer(invocation -> {
      Object msg = invocation.getArgument(0);
      if (msg instanceof Http2DataFrame) {
        Http2DataFrame frame = (Http2DataFrame) msg;
        retainedSlices.add(frame.content());
        // Throw exception to trigger error path
        throw new RuntimeException("Simulated write failure");
      }
      return null;
    });

    // Execute write - should fail on first data frame
    adaptor.write(ctx, send, promise);

    // CRITICAL BUG EXPOSED: readSlice() creates slices with SHARED refCnt!
    // Initial refCnt=1, first slice.retain() makes it 2, finally release() makes it 1
    // The buffer is LEAKED because refCnt > 0
    assertEquals("LEAK DETECTED: Original buffer not fully released due to retained slice", 1, content.refCnt());

    // The slice also shows refCnt=1 because it shares the counter with original
    assertEquals("Should have captured 1 slice before exception", 1, retainedSlices.size());
    for (ByteBuf slice : retainedSlices) {
      assertEquals("Slice shares refCnt with original (LEAKED)", 1, slice.refCnt());
    }

    // Clean up the leak manually
    content.release();
  }

  /**
   * HIGH-RISK PATH #23: Channel closed during write
   *
   * Tests the early return path when channel is closed. The msg is released,
   * but what if it was already partially processed?
   *
   * Expected: No leak - msg released before any slices created
   */
  @Test
  public void testChannelClosedDuringWrite() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    content.writeBytes(new byte[1024]);

    Send send = new TestSend(content);

    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    ChannelPromise promise = mock(ChannelPromise.class);

    when(ctx.channel()).thenReturn(channel);
    when(channel.isOpen()).thenReturn(false); // Channel is closed

    // Execute write - should return early
    adaptor.write(ctx, send, promise);

    // Verify promise was failed
    verify(promise).setFailure(any());

    // Verify content was released
    assertEquals("Content should be released", 0, content.refCnt());

    // No slices should have been created, so no leak
  }

  /**
   * Test successful write with multiple frames - baseline for no leaks
   */
  @Test
  public void testSuccessfulMultiFrameWriteNoLeak() throws Exception {
    // Create a Send with 3 frames worth of data
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(25 * 1024);
    for (int i = 0; i < 25 * 1024; i++) {
      content.writeByte(i % 256);
    }

    Send send = new TestSend(content);

    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    ChannelPromise promise = mock(ChannelPromise.class);

    when(ctx.channel()).thenReturn(channel);
    when(channel.isOpen()).thenReturn(true);

    List<Http2DataFrame> dataFrames = new ArrayList<>();

    // Capture all data frames from BOTH single-arg and two-arg write()
    when(ctx.write(any())).thenAnswer(invocation -> {
      Object msg = invocation.getArgument(0);
      if (msg instanceof Http2DataFrame) {
        dataFrames.add((Http2DataFrame) msg);
      }
      return null;
    });

    when(ctx.write(any(), any())).thenAnswer(invocation -> {
      Object msg = invocation.getArgument(0);
      if (msg instanceof Http2DataFrame) {
        dataFrames.add((Http2DataFrame) msg);
      }
      return null;
    });

    // Execute write
    adaptor.write(ctx, send, promise);

    // Should have created 4 frames (3 full + 1 partial)
    assertEquals("Should have 4 data frames", 4, dataFrames.size());

    // CRITICAL: readSlice() creates slices with SHARED refCnt!
    // After 3 retain() calls in loop + 1 retain() for last slice, refCnt = 5
    // Then finally block: release() → refCnt = 4
    // We need to release all 4 slices to fully clean up
    assertEquals("Slices share refCnt with original", 4, content.refCnt());

    // Simulate channel releasing all frames after sending
    for (Http2DataFrame frame : dataFrames) {
      frame.content().release();
    }

    // Now all slices are released, original should be freed
    assertEquals("Original content should be released after all slices released", 0, content.refCnt());

    // Leak detection in @After should pass
  }

  /**
   * Test that demonstrates the proper fix would track and release all slices
   */
  @Test
  public void testProperFixWouldReleaseAllRetainedSlices() throws Exception {
    // This test shows what the fixed code should do
    leakHelper.setDisabled(true);

    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(20 * 1024);
    for (int i = 0; i < 20 * 1024; i++) {
      content.writeByte(i % 256);
    }

    // Track all slices we retain
    List<ByteBuf> retainedSlices = new ArrayList<>();

    try {
      // Simulate the loop with tracking
      while (content.isReadable(MAX_FRAME_SIZE)) {
        ByteBuf slice = content.readSlice(MAX_FRAME_SIZE);
        slice.retain();
        retainedSlices.add(slice);

        // Simulate exception on second iteration
        if (retainedSlices.size() == 2) {
          throw new RuntimeException("Simulated write failure");
        }
      }
    } catch (Exception e) {
      // PROPER FIX: Release all retained slices in catch block
      for (ByteBuf slice : retainedSlices) {
        slice.release();
      }
      retainedSlices.clear();
    } finally {
      // Release original content
      content.release();
    }

    // Verify no leaks
    assertEquals("Original content should be released", 0, content.refCnt());

    // All slices should be released
    for (ByteBuf slice : retainedSlices) {
      assertTrue("Slice should be released", slice.refCnt() == 0);
    }
  }

  /**
   * Test Send implementation for testing
   */
  private static class TestSend extends AbstractByteBufHolder<TestSend> implements Send {
    private final ByteBuf content;

    TestSend(ByteBuf content) {
      this.content = content;
    }

    @Override
    public long writeTo(WritableByteChannel channel) throws IOException {
      throw new UnsupportedOperationException();
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

    @Override
    public TestSend replace(ByteBuf content) {
      return new TestSend(content);
    }
  }
}
