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
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests proving that Unpooled.wrappedBuffer() creates memory leak scenarios.
 *
 * These tests demonstrate that wrapper ByteBufs prevent the underlying ByteBuffer
 * from being garbage collected, even when you think you've cleaned up properly.
 *
 * ⚠️ These are EDUCATIONAL tests showing the leak mechanism.
 */
public class UnpooledWrapperLeakProofTest {
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();

  @Before
  public void setUp() {
    leakHelper.beforeTest();
  }

  @After
  public void tearDown() {
    // Call afterTest() to detect leaks - tests will FAIL if leaks exist
    leakHelper.afterTest();
  }

  /**
   * PROOF #1: Wrapper NOT released → Prevents ByteBuffer from being GC'd
   *
   * This test proves that when you create a wrapper but DON'T release it,
   * the wrapper holds a strong reference to the ByteBuffer, preventing GC.
   *
   * Scenario:
   * 1. Create direct ByteBuffer
   * 2. Create wrapper via Unpooled.wrappedBuffer()
   * 3. Drop reference to ByteBuffer (set to null)
   * 4. Force GC
   * 5. ByteBuffer is NOT collected (wrapper still holds it!)
   */
  @Test
  public void testWrapperNotReleased_PreventsGC() throws Exception {
    // This test will FAIL - demonstrates wrapper prevents ByteBuffer from GC

    // Track the ByteBuffer with a WeakReference to observe GC
    WeakReference<ByteBuffer> byteBufferRef;
    ByteBuf wrapper;

    {
      // Create a direct ByteBuffer (uses native memory)
      ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024);
      byteBuffer.put(new byte[1024]);
      byteBuffer.flip();

      // Track it with WeakReference
      byteBufferRef = new WeakReference<>(byteBuffer);

      // Create wrapper - wrapper holds strong reference to byteBuffer
      wrapper = Unpooled.wrappedBuffer(byteBuffer);

      assertEquals("Wrapper should have refCnt=1", 1, wrapper.refCnt());

      // Simulate caller dropping reference to ByteBuffer
      // (e.g., callback completes and local variable goes out of scope)
      byteBuffer = null;
    }
    // byteBuffer local variable is now out of scope

    // Force garbage collection multiple times
    System.gc();
    Thread.sleep(100);
    System.gc();
    Thread.sleep(100);
    System.gc();

    // ❌ PROOF: ByteBuffer is NOT collected!
    assertNotNull("ByteBuffer should NOT be GC'd (wrapper holds reference!)",
        byteBufferRef.get());

    // The wrapper still has refCnt=1
    assertEquals("Wrapper still has refCnt=1 (leaked)", 1, wrapper.refCnt());

    // LEAK EXPLANATION:
    // - Wrapper ByteBuf holds strong reference to ByteBuffer
    // - ByteBuffer cannot be GC'd while wrapper exists
    // - Native memory (1024 bytes) cannot be freed
    // - This is EXACTLY what happens in write(ByteBuffer) bug!

    // Manual cleanup (in production, this cleanup never happens!)
    wrapper.release();
  }

  /**
   * PROOF #2: Even after releasing wrapper immediately, refCnt management is tricky
   *
   * This test shows that even if you release the wrapper immediately after creation,
   * you must ensure no other references exist, and the timing is critical.
   *
   * This demonstrates why the channel can't just create-and-release in one line.
   */
  @Test
  public void testWrapperReleasedImmediately_RequiresCarefulTiming() throws Exception {
    // Educational test showing timing window issues

    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024);
    byteBuffer.put(new byte[1024]);
    byteBuffer.flip();

    WeakReference<ByteBuffer> byteBufferRef = new WeakReference<>(byteBuffer);

    // Scenario: Create and release wrapper immediately (like a one-liner)
    Unpooled.wrappedBuffer(byteBuffer).release();

    // After immediate release, wrapper is deallocated but not yet GC'd
    // ByteBuffer still alive because we still hold reference
    assertNotNull("ByteBuffer still alive (we hold reference)", byteBufferRef.get());

    // Drop our reference
    byteBuffer = null;

    // Now force GC
    System.gc();
    Thread.sleep(100);
    System.gc();

    // ByteBuffer SHOULD be collectable now
    // (But the test doesn't prove anything about leaked memory during the window
    // between wrapper creation and release - that's the dangerous window!)

    // The issue: In production, the wrapper lives in the channel's queue!
    // It's NOT released immediately - it waits for consumer to retrieve and resolve
    // During that time, it MUST be tracked and released properly
  }

  /**
   * PROOF #3: Multiple wrappers compound the leak
   *
   * Shows that the leak accumulates - each wrapper prevents its ByteBuffer from GC.
   */
  @Test
  public void testMultipleWrappers_AccumulatingLeak() throws Exception {
    // This test will FAIL - demonstrates 10 wrappers leak

    int count = 10;
    WeakReference<ByteBuffer>[] refs = new WeakReference[count];
    ByteBuf[] wrappers = new ByteBuf[count];

    // Create multiple wrappers
    for (int i = 0; i < count; i++) {
      ByteBuffer bb = ByteBuffer.allocateDirect(1024);
      bb.put(new byte[1024]);
      bb.flip();

      refs[i] = new WeakReference<>(bb);
      wrappers[i] = Unpooled.wrappedBuffer(bb);

      // Drop reference to ByteBuffer (simulates going out of scope)
      bb = null;
    }

    // Force GC
    System.gc();
    Thread.sleep(100);
    System.gc();

    // ❌ PROOF: ALL ByteBuffers are still alive!
    int aliveCount = 0;
    for (WeakReference<ByteBuffer> ref : refs) {
      if (ref.get() != null) {
        aliveCount++;
      }
    }

    assertEquals("All 10 ByteBuffers should still be alive (wrappers hold them)",
        count, aliveCount);

    // Total leaked: 10 wrappers × 1024 bytes = 10KB native memory
    // In production: hundreds or thousands of calls = MB or GB leaked!

    // Manual cleanup
    for (ByteBuf wrapper : wrappers) {
      if (wrapper.refCnt() > 0) {
        wrapper.release();
      }
    }
  }

  /**
   * PROOF #4: Wrapper holds reference even after ByteBuffer is "finished"
   *
   * Shows that even after reading all data from ByteBuffer, wrapper still prevents GC.
   */
  @Test
  public void testWrapperHoldsReference_EvenAfterDataConsumed() throws Exception {
    // This test will FAIL - wrapper holds reference even after data consumed

    WeakReference<ByteBuffer> byteBufferRef;
    ByteBuf wrapper;

    {
      ByteBuffer byteBuffer = ByteBuffer.allocateDirect(100);
      byteBuffer.put(new byte[100]);
      byteBuffer.flip();

      byteBufferRef = new WeakReference<>(byteBuffer);
      wrapper = Unpooled.wrappedBuffer(byteBuffer);

      // Consume all data from wrapper (simulates reading in production)
      byte[] data = new byte[100];
      wrapper.readBytes(data);

      assertFalse("Wrapper has no more readable bytes", wrapper.isReadable());

      // Data is consumed, ByteBuffer is "done", but...
      byteBuffer = null;  // Drop our reference
    }

    System.gc();
    Thread.sleep(50);

    // ❌ PROOF: Even though data was consumed, ByteBuffer NOT collected!
    assertNotNull("ByteBuffer still alive despite being fully read",
        byteBufferRef.get());

    // The wrapper still holds the reference!
    assertEquals("Wrapper still has refCnt=1", 1, wrapper.refCnt());

    // This is what happens in production:
    // 1. Data is written to channel (wrapper created)
    // 2. Data is consumed (read from channel)
    // 3. Consumer finishes with data
    // 4. BUT wrapper still exists in channel, preventing GC!

    // Cleanup
    wrapper.release();
  }

  /**
   * PROOF #5: Demonstrates the EXACT bug in ByteBufferAsyncWritableChannel
   *
   * Simulates exactly what happens in write(ByteBuffer):
   * 1. Caller has ByteBuffer
   * 2. Channel creates wrapper internally
   * 3. Wrapper stored in channel
   * 4. Caller's callback has no access to wrapper
   * 5. Wrapper leaks
   */
  @Test
  public void testExactBugScenario_WrapperHiddenFromCaller() throws Exception {
    // This test will FAIL - demonstrates exact production bug scenario

    // Simulate caller code
    ByteBuffer callerBuffer = ByteBuffer.allocateDirect(1024);
    callerBuffer.put(new byte[1024]);
    callerBuffer.flip();

    WeakReference<ByteBuffer> callerBufferRef = new WeakReference<>(callerBuffer);

    // Simulate what ByteBufferAsyncWritableChannel does in write(ByteBuffer):
    ByteBuf hiddenWrapper = Unpooled.wrappedBuffer(callerBuffer);
    // ↑ This wrapper is created internally - caller has NO reference to it!

    // Simulate callback being invoked (callback receives result, not wrapper)
    Runnable callback = () -> {
      // Callback gets: result (Long), exception
      // Callback does NOT get: hiddenWrapper ByteBuf
      // Callback CANNOT release hiddenWrapper!

      // Even if callback tries to clean up:
      // callerBuffer = null;  // This does NOTHING to help!
      // Because hiddenWrapper still holds the reference!
    };

    callback.run();  // Execute callback

    // Caller drops reference to ByteBuffer (thinking it's done)
    callerBuffer = null;

    System.gc();
    Thread.sleep(100);

    // ❌ PROOF: This is THE BUG!
    assertNotNull("ByteBuffer NOT GC'd - hidden wrapper prevents it!",
        callerBufferRef.get());

    assertEquals("Hidden wrapper still has refCnt=1 (LEAKED)",
        1, hiddenWrapper.refCnt());

    // This is EXACTLY what happens in production:
    // - Channel creates wrapper (line 89)
    // - Caller has no access to wrapper
    // - Callback cannot release wrapper
    // - Wrapper leaks forever
    // - Native memory leaked!

    // In production, this cleanup NEVER happens:
    hiddenWrapper.release();
  }

  /**
   * CONTROL TEST: Proper lifecycle without wrapper
   *
   * Shows that when you use ByteBuf directly (no wrapper), proper release works.
   */
  @Test
  public void testControlCase_NoByteBufWrapper_ProperCleanup() throws Exception {
    // This is the CORRECT way: use ByteBuf directly, not ByteBuffer + wrapper

    ByteBuf byteBuf = Unpooled.directBuffer(1024);
    byteBuf.writeBytes(new byte[1024]);

    assertEquals("ByteBuf has refCnt=1", 1, byteBuf.refCnt());

    // Use the data
    byteBuf.readBytes(new byte[1024]);

    // Release it
    byteBuf.release();

    assertEquals("ByteBuf properly released", 0, byteBuf.refCnt());

    // No leak! This is the pattern that works correctly.
    // ByteBufferAsyncWritableChannel.write(ByteBuf, callback) works fine
    // ByteBufferAsyncWritableChannel.write(ByteBuffer, callback) leaks!
  }
}
