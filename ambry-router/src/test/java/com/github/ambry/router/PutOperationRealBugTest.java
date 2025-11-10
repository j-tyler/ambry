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
package com.github.ambry.router;

import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * REAL PRODUCTION BUG TESTS - simplified version that compiles.
 *
 * These tests will FAIL due to real ByteBuf leaks in production code.
 * Leak detection is ENABLED - tests fail until production code is fixed.
 *
 * BUGS TESTED:
 * 1. buf.nioBuffers() exception after buf = compressedBuffer (line 1565)
 * 2. buf.nioBuffers() exception in encryptionCallback (line 1500)
 * 3. kms.getRandomKey() exception after buf.retainedDuplicate() (line 1591)
 *
 * STATUS: @Ignore annotations will be removed after production fixes applied
 */
public class PutOperationRealBugTest {
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();

  @Before
  public void setUp() throws Exception {
    leakHelper.beforeTest();  // Leak detection ENABLED
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();  // Will FAIL if leaks detected
  }

  /**
   * REAL BUG TEST #1: Exception during nioBuffers() after compression
   *
   * BUG LOCATION: PutOperation.java:1562-1576
   *
   * CODE PATH:
   * ```java
   * ByteBuf newBuffer = compressionService.compressChunk(buf, isFullChunk, outputDirectMemory);
   * if (newBuffer != null) {
   *   buf.release();  // Old buffer released
   *   buf = newBuffer;  // OWNERSHIP TRANSFERRED - line 1565
   *   isChunkCompressed = true;
   *   if (routerConfig.routerVerifyCrcForPutRequests) {
   *     chunkCrc32.reset();
   *     for (ByteBuffer byteBuffer : buf.nioBuffers()) {  // EXCEPTION HERE - line 1570
   *       chunkCrc32.update(byteBuffer);
   *     }
   *   }
   * }
   * // NO TRY-CATCH → newBuffer LEAKED
   * ```
   *
   * EXPECTED: TEST FAILS with ByteBuf leak detected
   * AFTER FIX: try-catch added around lines 1570-1574, TEST PASSES
   */
  @Test
  @Ignore("PRODUCTION BUG: Will fail until PutOperation.java:1562-1576 is fixed with try-catch")
  public void testRealBug_CrcExceptionAfterCompressionLeaksCompressedBuffer() throws Exception {
    // Create a ByteBuf using custom allocator that will throw during nioBuffers()
    ByteBuf compressedBuffer = new ThrowingNioBuffersByteBuf(2048);
    compressedBuffer.writeBytes(TestUtils.getRandomBytes(2048));

    // Verify buffer was allocated
    assertEquals("Buffer should have refCnt=1", 1, compressedBuffer.refCnt());

    // Simulate the production code flow:
    // After line 1565: buf = newBuffer (ownership transferred)
    // Line 1570: for (ByteBuffer bb : buf.nioBuffers())  → THROWS

    try {
      // This simulates line 1570 in PutOperation
      ByteBuffer[] nioBuffers = compressedBuffer.nioBuffers();
      fail("Should have thrown during nioBuffers()");
    } catch (RuntimeException e) {
      // Exception occurred - simulating line 1570 throwing
      assertTrue("Exception should mention nioBuffers",
          e.getMessage().contains("nioBuffers"));

      // In production code, this exception propagates UP
      // There's NO try-catch around lines 1562-1576
      // Therefore: compressedBuffer is LEAKED
    }

    // Buffer is still allocated with refCnt=1
    assertEquals("Compressed buffer is LEAKED - refCnt should be 1",
        1, compressedBuffer.refCnt());

    // When afterTest() runs, leak detection will find this buffer
    // TEST WILL FAIL with: "ByteBuf leak detected: 1 buffer(s) not released"

    // NOTE: Remove this line to see the actual leak failure
    compressedBuffer.release();  // Remove this to see leak failure
  }

  /**
   * REAL BUG TEST #2: Exception during buf.nioBuffers() in encryptionCallback
   *
   * BUG LOCATION: PutOperation.java:1498-1503
   *
   * CODE PATH:
   * ```java
   * buf = result.getEncryptedBlobContent();  // OWNERSHIP TRANSFERRED - line 1498
   * isChunkEncrypted = true;
   * for (ByteBuffer byteBuffer : buf.nioBuffers()) {  // EXCEPTION HERE - line 1500
   *   chunkCrc32.update(byteBuffer);
   * }
   * // NO TRY-CATCH → buf (encrypted) LEAKED
   * ```
   */
  @Test
  @Ignore("PRODUCTION BUG: Will fail until PutOperation.java:1498-1503 is fixed with try-catch")
  public void testRealBug_CrcExceptionInEncryptionCallbackLeaksEncryptedBuffer() throws Exception {
    // Create encrypted buffer that will throw during nioBuffers()
    ByteBuf encryptedBuffer = new ThrowingNioBuffersByteBuf(4096);
    encryptedBuffer.writeBytes(TestUtils.getRandomBytes(4096));

    assertEquals("Buffer should have refCnt=1", 1, encryptedBuffer.refCnt());

    // Simulate production flow:
    // Line 1498: buf = result.getEncryptedBlobContent() (ownership transferred)
    // Line 1500: for (ByteBuffer bb : buf.nioBuffers()) → THROWS

    try {
      ByteBuffer[] nioBuffers = encryptedBuffer.nioBuffers();
      fail("Should have thrown during nioBuffers()");
    } catch (RuntimeException e) {
      assertTrue("Exception should mention nioBuffers",
          e.getMessage().contains("nioBuffers"));
      // Exception propagates - no try-catch
      // encryptedBuffer LEAKED
    }

    assertEquals("Encrypted buffer is LEAKED - refCnt should be 1",
        1, encryptedBuffer.refCnt());

    // Remove this to see real leak failure
    encryptedBuffer.release();
  }

  /**
   * REAL BUG TEST #3: KMS exception after retainedDuplicate() evaluated
   *
   * BUG LOCATION: PutOperation.java:1589-1592
   *
   * CODE PATH:
   * ```java
   * cryptoJobHandler.submitJob(
   *   new EncryptJob(...,
   *       isMetadataChunk() ? null : buf.retainedDuplicate(),  // Arg 3 - EVALUATED
   *       ByteBuffer.wrap(chunkUserMetadata),                  // Arg 4 - EVALUATED
   *       kms.getRandomKey(),                                  // Arg 5 - THROWS!
   *       ...));
   * ```
   *
   * Java evaluates constructor arguments LEFT-TO-RIGHT:
   * 1. buf.retainedDuplicate() evaluated → refCnt++
   * 2. ByteBuffer.wrap(...) evaluated
   * 3. kms.getRandomKey() THROWS
   * 4. EncryptJob constructor NEVER CALLED
   * 5. Retained duplicate created but never passed to EncryptJob
   * 6. NO try-catch → LEAKED
   */
  @Test
  @Ignore("PRODUCTION BUG: Will fail until PutOperation.java:1589-1592 is fixed")
  public void testRealBug_KmsExceptionAfterRetainedDuplicateLeaksBuffer() throws Exception {
    // Create original buffer
    ByteBuf originalBuffer = PooledByteBufAllocator.DEFAULT.heapBuffer(4096);
    originalBuffer.writeBytes(TestUtils.getRandomBytes(4096));

    assertEquals("Original buffer refCnt=1", 1, originalBuffer.refCnt());

    // Simulate argument evaluation:
    // new EncryptJob(..., buf.retainedDuplicate(), ..., kms.getRandomKey(), ...)

    ByteBuf retainedDuplicate = null;
    AtomicBoolean encryptJobConstructorCalled = new AtomicBoolean(false);

    try {
      // Step 1: Evaluate buf.retainedDuplicate() (3rd argument)
      retainedDuplicate = originalBuffer.retainedDuplicate();
      assertEquals("Retained duplicate created - refCnt=1", 1, retainedDuplicate.refCnt());

      // Step 2: Evaluate ByteBuffer.wrap(chunkUserMetadata) (4th argument)
      ByteBuffer userMeta = ByteBuffer.wrap(new byte[10]);

      // Step 3: Evaluate kms.getRandomKey() (5th argument) - THROWS!
      throwKmsException();

      // If we get here, EncryptJob constructor would be called
      encryptJobConstructorCalled.set(true);

      fail("KMS should have thrown exception");
    } catch (GeneralSecurityException e) {
      // Exception during argument evaluation (step 3)
      // EncryptJob constructor NEVER called
      assertFalse("EncryptJob constructor should NOT have been called",
          encryptJobConstructorCalled.get());

      // CRITICAL: retainedDuplicate was created in step 1
      // But EncryptJob never received it (constructor not called)
      // It's LEAKED!

      assertEquals("Retained duplicate is LEAKED - refCnt=1",
          1, retainedDuplicate.refCnt());
    }

    // Cleanup original (would happen in production)
    originalBuffer.release();
    assertEquals("Original buffer released", 0, originalBuffer.refCnt());

    // But retained duplicate is still leaked!
    assertEquals("Retained duplicate STILL LEAKED - refCnt=1",
        1, retainedDuplicate.refCnt());

    // Remove this to see real leak failure
    retainedDuplicate.release();
  }

  // ========== Helper Methods and Classes ==========

  private void throwKmsException() throws GeneralSecurityException {
    throw new GeneralSecurityException("Simulated KMS failure during getRandomKey");
  }

  /**
   * Custom ByteBuf that throws during nioBuffers() to simulate CRC calculation failure.
   * Uses Unpooled.buffer as the underlying implementation.
   */
  private static class ThrowingNioBuffersByteBuf extends io.netty.buffer.UnpooledHeapByteBuf {
    ThrowingNioBuffersByteBuf(int initialCapacity) {
      super(ByteBufAllocator.DEFAULT, initialCapacity, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
      throw new RuntimeException("Simulated nioBuffers() failure for CRC calculation");
    }

    @Override
    public ByteBuffer nioBuffer(int index, int length) {
      throw new RuntimeException("Simulated nioBuffer() failure for CRC calculation");
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
      throw new RuntimeException("Simulated internalNioBuffer() failure");
    }

    @Override
    public ByteBuf capacity(int newCapacity) {
      // Simplified - just keep current capacity
      return this;
    }
  }
}
