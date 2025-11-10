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

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.IllegalReferenceCountException;
import java.security.GeneralSecurityException;
import java.util.Properties;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.github.ambry.router.CryptoTestUtils.*;
import static org.junit.Assert.*;

/**
 * Production tests for GCMCryptoService.decrypt(ByteBuf) memory leak.
 *
 * BUG: GCMCryptoService.decrypt() releases the WRONG buffer on exception.
 * Location: ambry-router/src/main/java/com/github/ambry/router/GCMCryptoService.java:196-200
 *
 * Current behavior (WRONG):
 * <pre>
 * {@code
 * catch (Exception e) {
 *   if (toDecrypt != null) {
 *     toDecrypt.release();  // ❌ WRONG! Releases caller's buffer
 *   }
 *   // ❌ LEAKS decryptedContent (never released)
 * }
 * }
 * </pre>
 *
 * Expected fix:
 * <pre>
 * {@code
 * catch (Exception e) {
 *   if (decryptedContent != null) {
 *     decryptedContent.release();  // ✓ Release our NEW buffer
 *   }
 *   // Do NOT touch toDecrypt - caller owns it!
 * }
 * }
 * </pre>
 *
 * These tests will FAIL until the bug is fixed.
 */
public class GCMCryptoServiceDecryptLeakTest {
  private static final int DEFAULT_KEY_SIZE = 64;
  private static final int RANDOM_KEY_SIZE_IN_BITS = 256;
  private static final String CLUSTER_NAME = "test-cluster";
  private static final MetricRegistry REGISTRY = new MetricRegistry();

  private CryptoService<SecretKeySpec> cryptoService;
  private KeyManagementService<SecretKeySpec> kms;
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();

  @Before
  public void setUp() throws Exception {
    leakHelper.beforeTest();
    String defaultKey = TestUtils.getRandomKey(DEFAULT_KEY_SIZE);
    Properties props = getKMSProperties(defaultKey, RANDOM_KEY_SIZE_IN_BITS);
    VerifiableProperties verifiableProperties = new VerifiableProperties(props);
    kms = new SingleKeyManagementServiceFactory(verifiableProperties, CLUSTER_NAME, REGISTRY)
        .getKeyManagementService();
    cryptoService = new GCMCryptoServiceFactory(verifiableProperties, REGISTRY).getCryptoService();
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();
  }

  /**
   * Production test: decrypt() with corrupted ciphertext should not leak.
   *
   * CURRENT BEHAVIOR: This test will FAIL with TWO issues:
   * 1. Leak detection failure - decryptedContent buffer leaked
   * 2. IllegalReferenceCountException - toDecrypt over-released
   *
   * EXPECTED BEHAVIOR: After fix, this test will PASS:
   * 1. No leak - decryptedContent released in catch block
   * 2. No exception - toDecrypt NOT touched by decrypt()
   *
   * Ownership contract:
   * - Input toDecrypt: Caller retains ownership (service does NOT release)
   * - Output: Service returns NEW buffer, caller must release
   * - On exception: Service must release its NEW buffer, NOT input
   */
  @Test
  public void testDecryptWithInvalidCiphertextDoesNotLeakOrReleaseInput() throws Exception {
    // Create valid encrypted content first
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    originalContent.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec key = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, key);
    originalContent.release();

    // Corrupt the encrypted content to trigger decryption failure
    // Overwrite some bytes in the middle (after IV but within ciphertext)
    int corruptionStart = 20; // After IV
    for (int i = corruptionStart; i < corruptionStart + 16; i++) {
      encryptedContent.setByte(i, (byte) 0xFF);
    }

    // Verify input buffer is valid before decrypt
    assertEquals("Input buffer should have refCnt=1", 1, encryptedContent.refCnt());

    // Attempt to decrypt - should throw exception
    GeneralSecurityException exception = null;
    try {
      ByteBuf decryptedContent = cryptoService.decrypt(encryptedContent, key);
      fail("Decrypt should have thrown exception for corrupted ciphertext");
    } catch (GeneralSecurityException e) {
      exception = e;
    }

    assertNotNull("Should have thrown GeneralSecurityException", exception);

    // CRITICAL CHECKS:
    // 1. Input buffer should still be valid (refCnt=1)
    //    CURRENT BUG: This will fail with IllegalReferenceCountException
    //    because decrypt() incorrectly released it (refCnt=0)
    try {
      assertEquals("Input buffer should still have refCnt=1 (caller owns it)",
          1, encryptedContent.refCnt());
    } catch (IllegalReferenceCountException e) {
      fail("BUG DETECTED: decrypt() incorrectly released caller's input buffer! "
          + "The service should NOT release toDecrypt on exception. "
          + "Exception: " + e.getMessage());
    }

    // 2. Caller is still responsible for releasing input
    encryptedContent.release();

    // 3. NettyByteBufLeakHelper will detect if decryptedContent was leaked
    //    CURRENT BUG: This will fail because decryptedContent was never released
  }

  /**
   * Production test: decrypt() with wrong key should not leak.
   *
   * This triggers authentication failure during doFinal(), which is a different
   * exception path than corrupted ciphertext.
   */
  @Test
  public void testDecryptWithWrongKeyDoesNotLeakOrReleaseInput() throws Exception {
    // Create encrypted content with one key
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
    originalContent.writeBytes(TestUtils.getRandomBytes(512));

    SecretKeySpec correctKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, correctKey);
    originalContent.release();

    // Try to decrypt with a DIFFERENT key
    SecretKeySpec wrongKey = kms.getRandomKey();

    assertEquals("Input buffer should have refCnt=1", 1, encryptedContent.refCnt());

    // Attempt to decrypt with wrong key - should throw exception
    GeneralSecurityException exception = null;
    try {
      ByteBuf decryptedContent = cryptoService.decrypt(encryptedContent, wrongKey);
      fail("Decrypt should have thrown exception for wrong key");
    } catch (GeneralSecurityException e) {
      exception = e;
    }

    assertNotNull("Should have thrown GeneralSecurityException", exception);

    // Verify input buffer wasn't incorrectly released
    try {
      assertEquals("Input buffer should still have refCnt=1", 1, encryptedContent.refCnt());
    } catch (IllegalReferenceCountException e) {
      fail("BUG DETECTED: decrypt() released caller's input buffer on exception! " + e.getMessage());
    }

    // Caller releases input
    encryptedContent.release();

    // NettyByteBufLeakHelper will detect decryptedContent leak
  }

  /**
   * Production test: decrypt() with empty buffer should not leak.
   *
   * Edge case: Empty input might trigger different exception path.
   */
  @Test
  public void testDecryptWithEmptyBufferDoesNotLeakOrReleaseInput() throws Exception {
    ByteBuf emptyBuffer = PooledByteBufAllocator.DEFAULT.heapBuffer(0);
    SecretKeySpec key = kms.getRandomKey();

    assertEquals("Input buffer should have refCnt=1", 1, emptyBuffer.refCnt());

    // Attempt to decrypt empty buffer
    try {
      ByteBuf decryptedContent = cryptoService.decrypt(emptyBuffer, key);
      // If it somehow succeeds, release the result
      if (decryptedContent != null) {
        decryptedContent.release();
      }
    } catch (GeneralSecurityException e) {
      // Expected - verify input not released
      try {
        assertEquals("Input buffer should still have refCnt=1", 1, emptyBuffer.refCnt());
      } catch (IllegalReferenceCountException ex) {
        fail("BUG DETECTED: decrypt() released caller's input buffer! " + ex.getMessage());
      }
    }

    // Caller releases input
    emptyBuffer.release();
  }

  /**
   * Baseline test: decrypt() with valid input should not leak.
   *
   * This verifies the happy path works correctly.
   */
  @Test
  public void testSuccessfulDecryptDoesNotLeak() throws Exception {
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    originalContent.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec key = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, key);
    originalContent.release();

    assertEquals("Input buffer should have refCnt=1", 1, encryptedContent.refCnt());

    // Decrypt successfully
    ByteBuf decryptedContent = cryptoService.decrypt(encryptedContent, key);

    assertNotNull("Should have decrypted content", decryptedContent);
    assertEquals("Input buffer should still have refCnt=1", 1, encryptedContent.refCnt());

    // Caller releases both buffers
    encryptedContent.release();
    decryptedContent.release();

    // NettyByteBufLeakHelper verifies no leaks
  }

  /**
   * Production test: decrypt() exception after buffer allocation should release new buffer.
   *
   * This test specifically targets the window where decryptedContent has been allocated
   * but decryption fails, ensuring the service cleans up its own allocation.
   */
  @Test
  public void testDecryptExceptionAfterAllocationReleasesNewBuffer() throws Exception {
    // Create deliberately malformed encrypted content that will fail during doFinal()
    // but after decryptedContent buffer allocation
    ByteBuf malformedContent = PooledByteBufAllocator.DEFAULT.heapBuffer(128);

    // Write a valid-looking IV (first 12 bytes for GCM)
    byte[] fakeIV = new byte[12];
    TestUtils.RANDOM.nextBytes(fakeIV);
    malformedContent.writeBytes(fakeIV);

    // Write random bytes that will fail authentication
    byte[] fakeCiphertext = new byte[100];
    TestUtils.RANDOM.nextBytes(fakeCiphertext);
    malformedContent.writeBytes(fakeCiphertext);

    SecretKeySpec key = kms.getRandomKey();

    assertEquals("Input buffer should have refCnt=1", 1, malformedContent.refCnt());

    // This will:
    // 1. Allocate decryptedContent buffer (line 180 in GCMCryptoService)
    // 2. Fail during doFinal() (line 192)
    // 3. Current BUG: Releases malformedContent instead of decryptedContent
    try {
      ByteBuf result = cryptoService.decrypt(malformedContent, key);
      fail("Should have thrown exception");
    } catch (GeneralSecurityException e) {
      // Expected exception
    }

    // Verify caller's buffer not touched
    try {
      assertEquals("Input should still have refCnt=1", 1, malformedContent.refCnt());
    } catch (IllegalReferenceCountException e) {
      fail("BUG: Service released caller's buffer! " + e.getMessage());
    }

    malformedContent.release();

    // NettyByteBufLeakHelper will detect if decryptedContent leaked
  }
}
