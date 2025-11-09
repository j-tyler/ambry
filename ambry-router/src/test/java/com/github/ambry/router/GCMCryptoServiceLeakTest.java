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
package com.github.ambry.router;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Properties;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.util.encoders.Hex;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static com.github.ambry.router.CryptoTestUtils.*;

/**
 * Tests to verify that the ByteBuf leak in GCMCryptoService.decrypt() error handling is fixed.
 *
 * BEFORE FIX: Test will fail with "DirectMemoryLeak" or "HeapMemoryLeak" assertion error
 * AFTER FIX: Test will pass with no leaks detected
 */
public class GCMCryptoServiceLeakTest {
  private static final int DEFAULT_KEY_SIZE_IN_CHARS = 64;
  private static final MetricRegistry REGISTRY = new MetricRegistry();
  private final NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();
  private GCMCryptoService cryptoService;
  private SecretKeySpec secretKeySpec;

  @Before
  public void before() throws Exception {
    nettyByteBufLeakHelper.beforeTest();

    // Setup real crypto service (no mocking)
    String key = TestUtils.getRandomKey(DEFAULT_KEY_SIZE_IN_CHARS);
    Properties props = getKMSProperties(key, DEFAULT_KEY_SIZE_IN_CHARS);
    VerifiableProperties verifiableProperties = new VerifiableProperties(props);
    cryptoService = (GCMCryptoService) new GCMCryptoServiceFactory(verifiableProperties, REGISTRY).getCryptoService();
    secretKeySpec = new SecretKeySpec(Hex.decode(key), "AES");
  }

  @After
  public void after() {
    // This will FAIL if there are any pooled ByteBuf leaks
    // The error message will show: "DirectMemoryLeak: [allocation|deallocation] before test[X|Y], after test[A|B]"
    // Where A-X != B-Y (more allocations than deallocations)
    nettyByteBufLeakHelper.afterTest();
  }

  /**
   * Test that verifies the decrypt() error path properly releases the allocated decryptedContent buffer.
   *
   * This test triggers a decryption error by passing corrupted/invalid encrypted data.
   * BEFORE FIX: The pooled buffer allocated on line 180 (decryptedContent) will leak because
   *             the catch block on line 197 incorrectly releases toDecrypt instead.
   * AFTER FIX: The pooled buffer will be properly released in the catch block.
   */
  @Test
  public void testDecryptErrorPathReleasesPooledBuffer() throws Exception {
    // Step 1: Create valid encrypted data first
    byte[] plainData = new byte[1024];
    TestUtils.RANDOM.nextBytes(plainData);
    ByteBuf plainBuf = Unpooled.wrappedBuffer(plainData);

    ByteBuf validEncrypted = null;
    try {
      validEncrypted = cryptoService.encrypt(plainBuf, secretKeySpec);

      // Step 2: Corrupt the encrypted data to trigger decryption error
      // We'll corrupt the IV or the encrypted content to make decryption fail
      ByteBuf corruptedEncrypted = validEncrypted.retainedSlice();

      // Corrupt a byte in the middle of the encrypted content (after IV header)
      int corruptionIndex = 20; // After the IV record header
      byte originalByte = corruptedEncrypted.getByte(corruptionIndex);
      corruptedEncrypted.setByte(corruptionIndex, (byte) (originalByte ^ 0xFF));

      // Step 3: Attempt to decrypt the corrupted data
      // This MUST trigger the error path in decrypt()
      try {
        ByteBuf decrypted = cryptoService.decrypt(corruptedEncrypted, secretKeySpec);
        // If decryption somehow succeeds, release the result
        decrypted.release();
        Assert.fail("Decryption should have failed with corrupted data");
      } catch (GeneralSecurityException e) {
        // Expected: decryption fails with corrupted data
        // CRITICAL: The catch block in decrypt() should have released decryptedContent
        // If the bug exists, decryptedContent is leaked here
      } finally {
        corruptedEncrypted.release();
      }
    } finally {
      if (validEncrypted != null) {
        validEncrypted.release();
      }
      plainBuf.release();
    }

    // Step 4: The afterTest() method will verify no leaks occurred
    // If the bug still exists, afterTest() will fail with:
    // "DirectMemoryLeak: [allocation|deallocation] before test[X|Y], after test[X+1|Y]"
    // This shows 1 more allocation than deallocation = the leaked decryptedContent buffer
  }

  /**
   * Test that verifies decrypt() error path with multiple failures doesn't accumulate leaks.
   *
   * This test calls decrypt() with invalid data multiple times to ensure each failure
   * properly releases its allocated buffer (no accumulation).
   */
  @Test
  public void testMultipleDecryptErrorsDoNotAccumulateLeaks() throws Exception {
    // Create some valid encrypted data first
    byte[] plainData = new byte[512];
    TestUtils.RANDOM.nextBytes(plainData);
    ByteBuf plainBuf = Unpooled.wrappedBuffer(plainData);
    ByteBuf validEncrypted = null;

    try {
      validEncrypted = cryptoService.encrypt(plainBuf, secretKeySpec);

      // Trigger decryption errors 10 times
      for (int i = 0; i < 10; i++) {
        ByteBuf corruptedEncrypted = validEncrypted.retainedSlice();

        // Corrupt different bytes each iteration
        corruptedEncrypted.setByte(20 + i, (byte) 0xFF);

        try {
          ByteBuf decrypted = cryptoService.decrypt(corruptedEncrypted, secretKeySpec);
          decrypted.release();
          Assert.fail("Decryption should have failed on iteration " + i);
        } catch (GeneralSecurityException e) {
          // Expected - each failure should properly release decryptedContent
        } finally {
          corruptedEncrypted.release();
        }
      }
    } finally {
      if (validEncrypted != null) {
        validEncrypted.release();
      }
      plainBuf.release();
    }

    // afterTest() will verify no accumulated leaks from 10 failures
    // BEFORE FIX: Would show 10 leaked buffers
    // AFTER FIX: Shows 0 leaked buffers
  }

  /**
   * Test that verifies decrypt() success path doesn't leak (sanity check).
   *
   * This ensures our fix doesn't break the normal success path.
   */
  @Test
  public void testDecryptSuccessPathNoLeak() throws Exception {
    // This test verifies the success path still works correctly
    for (int i = 0; i < 5; i++) {
      byte[] plainData = new byte[256];
      TestUtils.RANDOM.nextBytes(plainData);
      ByteBuf plainBuf = Unpooled.wrappedBuffer(plainData);

      ByteBuf encrypted = null;
      ByteBuf decrypted = null;
      try {
        encrypted = cryptoService.encrypt(plainBuf, secretKeySpec);
        decrypted = cryptoService.decrypt(encrypted, secretKeySpec);

        // Verify decryption worked
        Assert.assertEquals(plainData.length, decrypted.readableBytes());
      } finally {
        if (encrypted != null) {
          encrypted.release();
        }
        if (decrypted != null) {
          decrypted.release();
        }
        plainBuf.release();
      }
    }

    // afterTest() verifies no leaks in success path
  }

  /**
   * Test that verifies decrypt() with invalid IV data triggers error path.
   *
   * This creates a ByteBuf that looks like encrypted data but has invalid IV format,
   * triggering the deserializeIV error path.
   */
  @Test
  public void testDecryptWithInvalidIVFormatReleasesBuffer() throws Exception {
    // Create a ByteBuf with invalid IV record format
    // The IV record expects: version (2 bytes) + size (4 bytes) + IV data
    ByteBuf invalidIVBuf = PooledByteBufAllocator.DEFAULT.ioBuffer(100);

    try {
      // Write invalid version
      invalidIVBuf.writeShort((short) 999); // Invalid version
      // Write size
      invalidIVBuf.writeInt(12); // IV size
      // Write some random bytes
      byte[] randomBytes = new byte[12];
      TestUtils.RANDOM.nextBytes(randomBytes);
      invalidIVBuf.writeBytes(randomBytes);
      // Add more random data to simulate encrypted content
      byte[] moreData = new byte[50];
      TestUtils.RANDOM.nextBytes(moreData);
      invalidIVBuf.writeBytes(moreData);

      // Attempt to decrypt - should fail during IV deserialization
      try {
        ByteBuf decrypted = cryptoService.decrypt(invalidIVBuf, secretKeySpec);
        decrypted.release();
        Assert.fail("Decryption should have failed with invalid IV version");
      } catch (GeneralSecurityException e) {
        // Expected - error during IV deserialization
        // The decryptedContent buffer should be released in catch block
      }
    } finally {
      invalidIVBuf.release();
    }

    // afterTest() verifies the pooled buffer was released despite early error
  }
}
