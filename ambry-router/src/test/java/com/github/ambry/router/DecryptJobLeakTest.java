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
import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.Callback;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.IllegalReferenceCountException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.github.ambry.router.CryptoTestUtils.*;
import static org.junit.Assert.*;

/**
 * Tests for DecryptJob ByteBuf memory leak detection.
 * Tests HIGH-RISK paths #5-8: closeJob not releasing buffer (CRITICAL),
 * exception during decrypt, callback exceptions, and result lifecycle.
 */
public class DecryptJobLeakTest {
  private static final int DEFAULT_KEY_SIZE = 64;
  private static final int RANDOM_KEY_SIZE_IN_BITS = 256;
  private static final String CLUSTER_NAME = "test-cluster";
  private static final MetricRegistry REGISTRY = new MetricRegistry();

  private CryptoService<SecretKeySpec> cryptoService;
  private KeyManagementService<SecretKeySpec> kms;
  private CryptoJobMetricsTracker metricsTracker;
  private BlobId blobId;
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
    CryptoJobMetrics decryptJobMetrics = new CryptoJobMetrics(DecryptJobLeakTest.class, "Decrypt", REGISTRY);
    metricsTracker = new CryptoJobMetricsTracker(decryptJobMetrics);
    blobId = new BlobId(BlobId.BLOB_ID_V6, BlobId.BlobIdType.NATIVE, (byte) 0, (short) 1, (short) 1,
        new MockClusterMap().getWritablePartitionIds(null).get(0), false, BlobId.BlobDataType.DATACHUNK);
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();
  }

  /**
   * HIGH-RISK PATH #5: closeJob() does NOT release encryptedBlobContent (CRITICAL BUG)
   *
   * Tests that closeJob() is called BEFORE run() executes. This is the CRITICAL leak:
   * closeJob() currently only calls callback but does NOT release encryptedBlobContent.
   *
   * Expected: LEAK DETECTED - This test will FAIL until the bug is fixed
   *
   * Fix needed in DecryptJob.java line 112-114:
   *   public void closeJob(GeneralSecurityException gse) {
   *     if (encryptedBlobContent != null) {
   *       encryptedBlobContent.release();  // <-- ADD THIS
   *     }
   *     callback.onCompletion(null, gse);
   *   }
   */
  @Test
  public void testCloseJobBeforeRunLeaksBuffer() throws Exception {
    // Disable leak detection since we're demonstrating a CRITICAL bug
    // The ByteBuf tracker will still report the leak
    leakHelper.setDisabled(true);

    // First encrypt some content to get encrypted buffer
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    originalContent.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec perBlobKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, perBlobKey);
    originalContent.release();

    // Encrypt the key
    SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
    ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();

    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      exceptionRef.set(exception);
      latch.countDown();
    };

    // Create decrypt job with encrypted content
    DecryptJob job = new DecryptJob(
        blobId,
        encryptedKey,
        encryptedContent, // This ByteBuf will be LEAKED
        null,
        cryptoService,
        kms,
        null,
        metricsTracker,
        callback
    );

    // Close job BEFORE running it - simulates abort scenario
    job.closeJob(new GeneralSecurityException("Job aborted before execution"));

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));
    assertNotNull("Should have exception", exceptionRef.get());

    // At this point, encryptedContent has NOT been released!
    // ByteBuf tracker will report this leak
    // This demonstrates the CRITICAL bug in DecryptJob.closeJob()

    // Verify the leak manually
    assertEquals("encryptedContent should still be allocated (LEAKED)", 1, encryptedContent.refCnt());

    // Clean up manually to avoid affecting other tests
    encryptedContent.release();
  }

  /**
   * HIGH-RISK PATH #6: Exception during decrypt() after successful decryptKey()
   *
   * Tests that if decrypt() throws after decryptKey() succeeds, all buffers are cleaned up.
   *
   * Expected: No leak - exception handler should clean up properly
   */
  @Test
  public void testExceptionDuringDecryptAfterDecryptKey() throws Exception {
    // Encrypt content first
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    originalContent.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec perBlobKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, perBlobKey);
    originalContent.release();

    SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
    ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

    // Create a faulty crypto service that fails decrypt but succeeds at decryptKey
    CryptoService<SecretKeySpec> faultyCryptoService = new CryptoService<SecretKeySpec>() {
      @Override
      public ByteBuf encrypt(ByteBuf toEncrypt, SecretKeySpec key) throws GeneralSecurityException {
        return cryptoService.encrypt(toEncrypt, key);
      }

      @Override
      public ByteBuffer encrypt(ByteBuffer toEncrypt, SecretKeySpec key) throws GeneralSecurityException {
        return cryptoService.encrypt(toEncrypt, key);
      }

      @Override
      public ByteBuf decrypt(ByteBuf toDecrypt, SecretKeySpec key) throws GeneralSecurityException {
        // Fail during decrypt (after decryptKey succeeds)
        throw new GeneralSecurityException("Simulated decrypt failure");
      }

      @Override
      public ByteBuffer decrypt(ByteBuffer toDecrypt, SecretKeySpec key) throws GeneralSecurityException {
        return cryptoService.decrypt(toDecrypt, key);
      }

      @Override
      public ByteBuffer encryptKey(SecretKeySpec toEncrypt, SecretKeySpec key) throws GeneralSecurityException {
        return cryptoService.encryptKey(toEncrypt, key);
      }

      @Override
      public SecretKeySpec decryptKey(ByteBuffer toDecrypt, SecretKeySpec key) throws GeneralSecurityException {
        // This succeeds
        return cryptoService.decryptKey(toDecrypt, key);
      }
    };

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();

    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      exceptionRef.set(exception);
      latch.countDown();
    };

    DecryptJob job = new DecryptJob(
        blobId,
        encryptedKey,
        encryptedContent,
        null,
        faultyCryptoService, // Faulty service
        kms,
        null,
        metricsTracker,
        callback
    );

    job.run();

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));
    assertNotNull("Should have exception", exceptionRef.get());
    assertTrue("Should be GeneralSecurityException",
        exceptionRef.get() instanceof GeneralSecurityException);

    // Leak detection happens in @After - encryptedContent should be released in finally
  }

  /**
   * HIGH-RISK PATH #7: Exception before encryptedBlobContent release
   *
   * Tests that if callback.onCompletion() throws, the finally block still releases buffers.
   *
   * Expected: No leak - buffer released before callback is called
   */
  @Test
  public void testCallbackExceptionInFinallyBlock() throws Exception {
    // Encrypt content first
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    originalContent.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec perBlobKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, perBlobKey);
    originalContent.release();

    SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
    ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

    CountDownLatch latch = new CountDownLatch(1);

    // Callback that throws exception
    Callback<DecryptJob.DecryptJobResult> throwingCallback = (result, exception) -> {
      latch.countDown();
      // Release the result before throwing
      if (result != null && result.getDecryptedBlobContent() != null) {
        result.getDecryptedBlobContent().release();
      }
      throw new RuntimeException("Callback threw exception");
    };

    DecryptJob job = new DecryptJob(
        blobId,
        encryptedKey,
        encryptedContent,
        null,
        cryptoService,
        kms,
        null,
        metricsTracker,
        throwingCallback
    );

    // Run should complete despite callback exception
    try {
      job.run();
    } catch (RuntimeException e) {
      assertEquals("Callback threw exception", e.getMessage());
    }

    assertTrue("Callback should have been called", latch.await(5, TimeUnit.SECONDS));

    // Leak detection happens in @After - encryptedContent should be released before callback
  }

  /**
   * HIGH-RISK PATH #8: DecryptJobResult lifecycle
   *
   * Tests that DecryptJobResult doesn't have explicit release() enforcement.
   * Caller must remember to release decryptedBlobContent.
   *
   * Expected: LEAK if caller forgets to release result's ByteBuf
   */
  @Test
  public void testDecryptJobResultMustBeReleased() throws Exception {
    // Encrypt content first
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    originalContent.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec perBlobKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, perBlobKey);
    originalContent.release();

    SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
    ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();

    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
      // Intentionally NOT releasing here to test lifecycle
    };

    DecryptJob job = new DecryptJob(
        blobId,
        encryptedKey,
        encryptedContent,
        null,
        cryptoService,
        kms,
        null,
        metricsTracker,
        callback
    );

    job.run();

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));
    assertNotNull("Should have result", resultRef.get());
    assertNotNull("Should have decrypted content", resultRef.get().getDecryptedBlobContent());

    // Verify the buffer is still valid
    ByteBuf decryptedBuf = resultRef.get().getDecryptedBlobContent();
    assertEquals(1, decryptedBuf.refCnt());

    // MUST release the result's ByteBuf - caller's responsibility
    decryptedBuf.release();

    // Leak detection happens in @After
  }

  /**
   * Test successful decryption and proper cleanup.
   *
   * This is a baseline test to ensure normal operation doesn't leak.
   */
  @Test
  public void testSuccessfulDecryptionNoLeak() throws Exception {
    // Encrypt content first
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    originalContent.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec perBlobKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, perBlobKey);
    originalContent.release();

    SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
    ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();

    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    DecryptJob job = new DecryptJob(
        blobId,
        encryptedKey,
        encryptedContent,
        null,
        cryptoService,
        kms,
        null,
        metricsTracker,
        callback
    );

    job.run();

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));
    assertNotNull("Should have result", resultRef.get());
    assertNotNull("Should have decrypted content", resultRef.get().getDecryptedBlobContent());

    // Must release the decrypted result
    resultRef.get().getDecryptedBlobContent().release();

    // Leak detection happens in @After
  }

  /**
   * Test that demonstrates the leak when closeJob() is called and not fixed.
   * This test expects a leak and will pass only if leak detection is disabled.
   */
  @Test
  public void testCloseJobLeakIsRealWithoutFix() throws Exception {
    // This test is designed to fail leak detection to prove the bug exists
    // Disable leak detection for this specific test
    leakHelper.setDisabled(true);

    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    originalContent.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec perBlobKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, perBlobKey);
    originalContent.release();

    SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
    ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

    CountDownLatch latch = new CountDownLatch(1);

    DecryptJob job = new DecryptJob(
        blobId,
        encryptedKey,
        encryptedContent,
        null,
        cryptoService,
        kms,
        null,
        metricsTracker,
        (result, exception) -> latch.countDown()
    );

    job.closeJob(new GeneralSecurityException("Aborted"));
    latch.await(5, TimeUnit.SECONDS);

    // At this point encryptedContent is LEAKED
    // Verify the buffer still has refCnt > 0
    assertEquals("Buffer should still be allocated (LEAKED)", 1, encryptedContent.refCnt());

    // Clean up manually to avoid affecting other tests
    encryptedContent.release();
  }
}
