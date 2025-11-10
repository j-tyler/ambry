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
import java.security.GeneralSecurityException;
import java.util.Properties;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.github.ambry.router.CryptoTestUtils.*;
import static org.junit.Assert.*;

/**
 * Production tests for GCMCryptoService.decrypt() memory leak fix.
 *
 * These tests will FAIL when the bug exists and PASS when fixed.
 * Bug: decrypt() catch block releases wrong buffer (toDecrypt instead of decryptedContent)
 *
 * Location: ambry-router/src/main/java/com/github/ambry/router/GCMCryptoService.java:196-200
 */
public class GCMCryptoServiceProductionLeakTest {
  private static final int DEFAULT_KEY_SIZE = 64;
  private static final MetricRegistry REGISTRY = new MetricRegistry();

  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();
  private GCMCryptoService cryptoService;
  private SecretKeySpec key;

  @Before
  public void setUp() throws Exception {
    leakHelper.beforeTest();

    String defaultKey = TestUtils.getRandomKey(DEFAULT_KEY_SIZE);
    Properties props = getKMSProperties(defaultKey, DEFAULT_KEY_SIZE);
    VerifiableProperties verifiableProperties = new VerifiableProperties(props);
    cryptoService = (GCMCryptoService) new GCMCryptoServiceFactory(verifiableProperties, REGISTRY).getCryptoService();

    byte[] keyBytes = TestUtils.getRandomBytes(32);
    key = new SecretKeySpec(keyBytes, "AES");
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();
  }

  /**
   * PRODUCTION TEST: Decryption failure with corrupted data should not leak
   *
   * This test will FAIL with the current bug because catch block releases caller's buffer
   * instead of the allocated decryptedContent buffer.
   * After fix, this test will PASS.
   *
   * Scenario: Invalid/corrupted encrypted data causes decryption to fail
   */
  @Test
  public void testDecryptCorruptedDataDoesNotLeak() throws Exception {
    // Create corrupted encrypted data that will fail GCM authentication
    ByteBuf corruptedData = PooledByteBufAllocator.DEFAULT.heapBuffer(100);

    // Write valid IV (12 bytes for GCM)
    byte[] fakeIv = TestUtils.getRandomBytes(12);
    corruptedData.writeBytes(fakeIv);

    // Write random garbage that will fail authentication
    corruptedData.writeBytes(TestUtils.getRandomBytes(88));

    try {
      ByteBuf decrypted = cryptoService.decrypt(corruptedData, key);
      // Unlikely to succeed, but if it does, clean up
      if (decrypted != null) {
        decrypted.release();
      }
      fail("Expected GeneralSecurityException for corrupted data");
    } catch (GeneralSecurityException e) {
      // Expected - decryption should fail with corrupted data
      // This is the production scenario we're testing
    }

    // Clean up our input buffer
    corruptedData.release();

    // If bug exists: decryptedContent buffer was allocated on line 180 but never released
    // The catch block incorrectly released corruptedData (our buffer) instead
    // Test will FAIL here with leak detection error
    //
    // After fix: catch block releases decryptedContent correctly
    // Test will PASS
  }

  /**
   * PRODUCTION TEST: Decryption failure with wrong key should not leak
   *
   * This test will FAIL with the current bug.
   * After fix, this test will PASS.
   *
   * Scenario: Using wrong decryption key (common in key rotation scenarios)
   */
  @Test
  public void testDecryptWithWrongKeyDoesNotLeak() throws Exception {
    // Create valid encrypted data
    ByteBuf plaintext = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
    plaintext.writeBytes(TestUtils.getRandomBytes(512));
    ByteBuf encrypted = cryptoService.encrypt(plaintext, key);
    plaintext.release();

    // Create different key for decryption (wrong key)
    byte[] wrongKeyBytes = TestUtils.getRandomBytes(32);
    SecretKeySpec wrongKey = new SecretKeySpec(wrongKeyBytes, "AES");

    try {
      ByteBuf decrypted = cryptoService.decrypt(encrypted, wrongKey);
      if (decrypted != null) {
        decrypted.release();
      }
      fail("Expected GeneralSecurityException for wrong key");
    } catch (GeneralSecurityException e) {
      // Expected - authentication will fail with wrong key
    }

    // Clean up
    encrypted.release();

    // If bug exists: decryptedContent leaked
    // After fix: No leak
  }

  /**
   * PRODUCTION TEST: Multiple decryption failures should not accumulate leaks
   *
   * Simulates production scenario with multiple invalid requests.
   */
  @Test
  public void testMultipleDecryptionFailuresDoNotLeak() throws Exception {
    for (int i = 0; i < 20; i++) {
      ByteBuf corruptedData = PooledByteBufAllocator.DEFAULT.heapBuffer(100);

      // Create different corrupted data each time
      byte[] fakeIv = TestUtils.getRandomBytes(12);
      corruptedData.writeBytes(fakeIv);
      corruptedData.writeBytes(TestUtils.getRandomBytes(88));

      try {
        ByteBuf decrypted = cryptoService.decrypt(corruptedData, key);
        if (decrypted != null) {
          decrypted.release();
        }
      } catch (GeneralSecurityException e) {
        // Expected for corrupted data
      }

      corruptedData.release();
    }

    // If bug exists: 20 buffers leaked
    // After fix: No leaks
  }

  /**
   * PRODUCTION TEST: Decryption failure with truncated data should not leak
   *
   * Scenario: Network transmission error or incomplete data
   */
  @Test
  public void testDecryptTruncatedDataDoesNotLeak() throws Exception {
    // Create valid encrypted data
    ByteBuf plaintext = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    plaintext.writeBytes(TestUtils.getRandomBytes(1024));
    ByteBuf encrypted = cryptoService.encrypt(plaintext, key);
    plaintext.release();

    // Truncate the encrypted data (simulate network error)
    int originalSize = encrypted.readableBytes();
    ByteBuf truncated = PooledByteBufAllocator.DEFAULT.heapBuffer(originalSize / 2);
    encrypted.readBytes(truncated, originalSize / 2);

    try {
      ByteBuf decrypted = cryptoService.decrypt(truncated, key);
      if (decrypted != null) {
        decrypted.release();
      }
      fail("Expected GeneralSecurityException for truncated data");
    } catch (GeneralSecurityException e) {
      // Expected - truncated data will fail
    }

    // Clean up
    encrypted.release();
    truncated.release();

    // If bug exists: Leak
    // After fix: No leak
  }

  /**
   * PRODUCTION TEST: Successful decryption after failures should work correctly
   *
   * Verifies that the fix doesn't break normal operation.
   */
  @Test
  public void testSuccessfulDecryptionAfterFailures() throws Exception {
    // First, trigger some failures
    for (int i = 0; i < 5; i++) {
      ByteBuf corruptedData = PooledByteBufAllocator.DEFAULT.heapBuffer(100);
      byte[] fakeIv = TestUtils.getRandomBytes(12);
      corruptedData.writeBytes(fakeIv);
      corruptedData.writeBytes(TestUtils.getRandomBytes(88));

      try {
        ByteBuf decrypted = cryptoService.decrypt(corruptedData, key);
        if (decrypted != null) {
          decrypted.release();
        }
      } catch (GeneralSecurityException e) {
        // Expected
      }

      corruptedData.release();
    }

    // Now do a successful decrypt
    ByteBuf plaintext = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
    byte[] data = TestUtils.getRandomBytes(512);
    plaintext.writeBytes(data);

    ByteBuf encrypted = cryptoService.encrypt(plaintext, key);
    ByteBuf decrypted = cryptoService.decrypt(encrypted, key);

    assertNotNull("Decryption should succeed", decrypted);
    assertEquals("Decrypted size should match", 512, decrypted.readableBytes());

    // Verify data integrity
    byte[] decryptedData = new byte[512];
    decrypted.readBytes(decryptedData);
    assertArrayEquals("Decrypted data should match original", data, decryptedData);

    // Clean up
    plaintext.release();
    encrypted.release();
    decrypted.release();

    // Should have no leaks from failures or success
  }
}
