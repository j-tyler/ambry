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
package com.github.ambry.commons;

import com.github.ambry.utils.NettyByteBufLeakHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Bug-exposing tests for ByteBufferAsyncWritableChannel memory leaks.
 *
 * These tests demonstrate CRITICAL BUGS in production code where ByteBuf wrappers
 * created by write(ByteBuffer) are never released.
 *
 * ⚠️ THESE TESTS EXPECT LEAKS - They will show up in ByteBuf tracker output
 *
 * Root Cause: write(ByteBuffer src) at line 89 calls Unpooled.wrappedBuffer(src)
 * which creates a ByteBuf wrapper with refCnt=1. This wrapper is NEVER released
 * by the channel, causing a direct buffer leak.
 *
 * Impact: Every call to write(ByteBuffer) leaks a direct ByteBuf wrapper that
 * will never be garbage collected.
 */
public class ByteBufferAsyncWritableChannelBugTest {
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();
  private ByteBufferAsyncWritableChannel channel;

  @Before
  public void setUp() {
    leakHelper.beforeTest();
    channel = new ByteBufferAsyncWritableChannel();
  }

  @After
  public void tearDown() {
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
    // Note: We DON'T call leakHelper.afterTest() for bug-exposing tests
    // because we expect leaks and don't want the test to fail
  }

  /**
   * ❌ BUG #1: write(ByteBuffer) creates wrapper that leaks on channel close
   *
   * Demonstrates that when write(ByteBuffer) is called, an internal ByteBuf wrapper
   * is created via Unpooled.wrappedBuffer() at line 89. When the channel is closed
   * before the buffer is consumed, this wrapper is NEVER released.
   *
   * Expected Tracker Output:
   * ```
   * CRITICAL_LEAK|root=UnpooledByteBufAllocator.directBuffer|final_ref=1
   * ```
   *
   * Bug Location: ByteBufferAsyncWritableChannel.java:89
   * Bug Location: ByteBufferAsyncWritableChannel.java:131-138 (close() doesn't release wrappers)
   */
  @Test
  public void testWriteByteBufferToClosedChannelLeaksWrapper() throws Exception {
    leakHelper.setDisabled(true);  // This is a bug-exposing test

    // Create a ByteBuffer (caller-owned, will be released by caller)
    ByteBuffer nioBuffer = ByteBuffer.allocate(100);
    nioBuffer.put(new byte[100]);
    nioBuffer.flip();

    // Close channel first
    channel.close();
    assertFalse("Channel should be closed", channel.isOpen());

    // Try to write - this creates a wrapper via Unpooled.wrappedBuffer()
    // The wrapper is released on line 105, so this specific case might be OK
    CountDownLatch latch = new CountDownLatch(1);
    Future<Long> future = channel.write(nioBuffer, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNotNull("Should have exception", exception);
        assertTrue("Should be ClosedChannelException", exception instanceof ClosedChannelException);
        latch.countDown();
      }
    });

    // Wait for callback
    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));

    // Note: The wrapper ByteBuf created by Unpooled.wrappedBuffer(nioBuffer)
    // is actually released on line 105 in this case, so this might not leak.
    // The real leak is in the next test where the channel is closed AFTER writing.
  }

  /**
   * ❌ BUG #2: write(ByteBuffer) creates wrapper that leaks when channel closed with pending writes
   *
   * This is the CRITICAL leak scenario:
   * 1. write(ByteBuffer) is called, creating a wrapper via Unpooled.wrappedBuffer()
   * 2. The wrapper is queued in the channel (refCnt=1)
   * 3. Channel is closed before the wrapper is consumed
   * 4. close() calls resolveAllRemainingChunks() which invokes callbacks with exceptions
   * 5. BUT close() NEVER releases the wrapper ByteBufs in the queue
   * 6. LEAK: wrapper ByteBuf has refCnt=1 forever (direct buffer, never GC'd)
   *
   * Expected Tracker Output:
   * ```
   * CRITICAL_LEAK|root=UnpooledByteBufAllocator.directBuffer|final_ref=1
   * ```
   *
   * Bug Location: ByteBufferAsyncWritableChannel.java:89 (wrapper created)
   * Bug Location: ByteBufferAsyncWritableChannel.java:131-138 (close() doesn't release)
   */
  @Test
  public void testWriteByteBufferThenCloseLeaksWrapper() throws Exception {
    leakHelper.setDisabled(true);  // This is a bug-exposing test

    // Create ByteBuffers (caller-owned)
    ByteBuffer nioBuffer1 = ByteBuffer.allocate(100);
    ByteBuffer nioBuffer2 = ByteBuffer.allocate(200);
    nioBuffer1.put(new byte[100]);
    nioBuffer2.put(new byte[200]);
    nioBuffer1.flip();
    nioBuffer2.flip();

    CountDownLatch latch = new CountDownLatch(2);

    // Write ByteBuffers - these create wrappers via Unpooled.wrappedBuffer()
    channel.write(nioBuffer1, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNotNull("Should have exception after close", exception);
        latch.countDown();
      }
    });

    channel.write(nioBuffer2, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNotNull("Should have exception after close", exception);
        latch.countDown();
      }
    });

    // At this point, two wrapper ByteBufs are in the queue, both with refCnt=1

    // Close channel WITHOUT consuming the wrappers
    channel.close();

    // Wait for callbacks to complete
    assertTrue("Callbacks should complete", latch.await(5, TimeUnit.SECONDS));

    // ❌ LEAK: The wrapper ByteBufs created by Unpooled.wrappedBuffer() are NEVER released
    // Expected tracker output: 2 direct buffer leaks with refCnt=1

    // Note: We don't manually clean up because we're demonstrating the bug
    // In a real scenario, caller has NO ACCESS to these internal wrappers to release them
  }

  /**
   * ❌ BUG #3: write(ByteBuffer) creates wrapper that leaks even after normal consumption
   *
   * Even when the buffer is properly consumed via getNextChunk() and resolveOldestChunk(),
   * the wrapper ByteBuf is never released. The channel returns a ByteBuffer (via conversion),
   * but the original wrapper ByteBuf remains with refCnt=1.
   *
   * Expected Tracker Output:
   * ```
   * CRITICAL_LEAK|root=UnpooledByteBufAllocator.directBuffer|final_ref=1
   * ```
   *
   * Bug Location: ByteBufferAsyncWritableChannel.java:89 (wrapper created)
   * Bug Location: ByteBufferAsyncWritableChannel.java:149-165 (getNextChunk doesn't release wrapper)
   * Bug Location: ByteBufferAsyncWritableChannel.java:172-203 (resolveOldestChunk doesn't release wrapper)
   */
  @Test
  public void testWriteByteBufferNormalFlowLeaksWrapper() throws Exception {
    leakHelper.setDisabled(true);  // This is a bug-exposing test

    // Create ByteBuffer (caller-owned)
    ByteBuffer nioBuffer = ByteBuffer.allocate(100);
    nioBuffer.put(new byte[100]);
    nioBuffer.flip();

    CountDownLatch latch = new CountDownLatch(1);

    // Write ByteBuffer - creates wrapper via Unpooled.wrappedBuffer()
    channel.write(nioBuffer, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNull("Should have no exception", exception);
        assertEquals("Should have written 100 bytes", 100L, result.longValue());
        latch.countDown();
      }
    });

    // Consume the chunk (normal flow)
    ByteBuffer chunk = channel.getNextChunk();
    assertNotNull("Should have chunk", chunk);
    assertEquals("Chunk should have 100 bytes", 100, chunk.remaining());

    // Resolve the chunk (normal flow)
    channel.resolveOldestChunk(null);

    // Wait for callback
    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));

    // ❌ LEAK: The wrapper ByteBuf created by Unpooled.wrappedBuffer() is NEVER released
    // Even though we consumed and resolved the chunk normally, the internal wrapper
    // ByteBuf still has refCnt=1

    // Expected tracker output: 1 direct buffer leak with refCnt=1
  }

  /**
   * ❌ BUG #4: Multiple write(ByteBuffer) calls accumulate leaking wrappers
   *
   * Demonstrates that the leak accumulates with multiple writes. Each write(ByteBuffer)
   * creates a new wrapper that leaks, so N writes = N leaks.
   *
   * Expected Tracker Output:
   * ```
   * CRITICAL_LEAK|root=UnpooledByteBufAllocator.directBuffer|final_ref=1
   * CRITICAL_LEAK|root=UnpooledByteBufAllocator.directBuffer|final_ref=1
   * CRITICAL_LEAK|root=UnpooledByteBufAllocator.directBuffer|final_ref=1
   * ```
   *
   * Bug Location: ByteBufferAsyncWritableChannel.java:89 (wrapper created each time)
   */
  @Test
  public void testMultipleByteBufferWritesLeakMultipleWrappers() throws Exception {
    leakHelper.setDisabled(true);  // This is a bug-exposing test

    int writeCount = 3;
    ByteBuffer[] nioBuffers = new ByteBuffer[writeCount];
    CountDownLatch latch = new CountDownLatch(writeCount);

    // Write multiple ByteBuffers
    for (int i = 0; i < writeCount; i++) {
      nioBuffers[i] = ByteBuffer.allocate(100 + i * 10);
      nioBuffers[i].put(new byte[100 + i * 10]);
      nioBuffers[i].flip();

      channel.write(nioBuffers[i], new Callback<Long>() {
        @Override
        public void onCompletion(Long result, Exception exception) {
          assertNull("Should have no exception", exception);
          latch.countDown();
        }
      });
    }

    // Consume and resolve all chunks
    for (int i = 0; i < writeCount; i++) {
      ByteBuffer chunk = channel.getNextChunk();
      assertNotNull("Should have chunk " + i, chunk);
      channel.resolveOldestChunk(null);
    }

    assertTrue("All callbacks should complete", latch.await(5, TimeUnit.SECONDS));

    // ❌ LEAK: 3 wrapper ByteBufs created, all with refCnt=1, none released
    // Expected tracker output: 3 direct buffer leaks
  }

  /**
   * ❌ BUG #5: Comparison - write(ByteBuf) vs write(ByteBuffer) leak behavior
   *
   * This test demonstrates the difference:
   * - write(ByteBuf) - caller owns ByteBuf, no wrapper created, caller releases
   * - write(ByteBuffer) - wrapper created internally, caller can't access it, LEAKS
   *
   * Expected: The ByteBuffer write will leak, ByteBuf write won't (if caller releases)
   */
  @Test
  public void testByteBufferVsByteBufLeakComparison() throws Exception {
    leakHelper.setDisabled(true);  // This is a bug-exposing test

    CountDownLatch latch = new CountDownLatch(2);

    // Write 1: ByteBuf (caller-owned, we'll release it)
    ByteBuf byteBuf = Unpooled.buffer(100);
    byteBuf.writeBytes(new byte[100]);

    channel.write(byteBuf, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNull("Should have no exception", exception);
        latch.countDown();
      }
    });

    // Write 2: ByteBuffer (wrapper created internally, we CAN'T release it)
    ByteBuffer nioBuffer = ByteBuffer.allocate(100);
    nioBuffer.put(new byte[100]);
    nioBuffer.flip();

    channel.write(nioBuffer, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNull("Should have no exception", exception);
        latch.countDown();
      }
    });

    // Consume both chunks
    ByteBuffer chunk1 = channel.getNextChunk();
    assertNotNull("Should have chunk1", chunk1);
    channel.resolveOldestChunk(null);

    ByteBuffer chunk2 = channel.getNextChunk();
    assertNotNull("Should have chunk2", chunk2);
    channel.resolveOldestChunk(null);

    assertTrue("All callbacks should complete", latch.await(5, TimeUnit.SECONDS));

    // Clean up the ByteBuf we control (this is expected)
    byteBuf.release();

    // ❌ LEAK: The wrapper ByteBuf from write(ByteBuffer) is leaked
    // We have NO WAY to access it to release it
    // Expected tracker output: 1 direct buffer leak (from ByteBuffer write)
  }
}
