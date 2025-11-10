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
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.github.ambry.router.CryptoTestUtils.*;
import static org.junit.Assert.*;

/**
 * Production tests for DecryptJob memory leak fixes.
 *
 * These tests will FAIL when the bug exists and PASS when fixed.
 * Bug: DecryptJob.closeJob() does not release encryptedBlobContent
 *
 * Location: ambry-router/src/main/java/com/github/ambry/router/DecryptJob.java:112-114
 */
public class DecryptJobProductionLeakTest {
  private static final int DEFAULT_KEY_SIZE = 64;
  private static final int RANDOM_KEY_SIZE_IN_BITS = 256;
  private static final String CLUSTER_NAME = "test-cluster";
  private static final MetricRegistry REGISTRY = new MetricRegistry();

  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();
  private GCMCryptoService cryptoService;
  private KeyManagementService<SecretKeySpec> kms;
  private CryptoJobMetricsTracker metricsTracker;
  private BlobId blobId;
  private SecretKeySpec key;

  @Before
  public void setUp() throws Exception {
    leakHelper.beforeTest();

    String defaultKey = TestUtils.getRandomKey(DEFAULT_KEY_SIZE);
    Properties props = getKMSProperties(defaultKey, RANDOM_KEY_SIZE_IN_BITS);
    VerifiableProperties verifiableProperties = new VerifiableProperties(props);

    kms = new SingleKeyManagementServiceFactory(verifiableProperties, CLUSTER_NAME, REGISTRY)
        .getKeyManagementService();
    cryptoService = (GCMCryptoService) new GCMCryptoServiceFactory(verifiableProperties, REGISTRY).getCryptoService();

    CryptoJobMetrics decryptJobMetrics = new CryptoJobMetrics(DecryptJobProductionLeakTest.class, "Decrypt", REGISTRY);
    metricsTracker = new CryptoJobMetricsTracker(decryptJobMetrics);

    blobId = new BlobId(BlobId.BLOB_ID_V6, BlobId.BlobIdType.NATIVE, (byte) 0, (short) 1, (short) 1,
        new MockClusterMap().getWritablePartitionIds(null).get(0), false, BlobId.BlobDataType.DATACHUNK);

    byte[] keyBytes = TestUtils.getRandomBytes(32);
    key = new SecretKeySpec(keyBytes, "AES");
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();
  }

  /**
   * PRODUCTION TEST: DecryptJob abort before execution should release encrypted content
   *
   * This test will FAIL with the current bug because closeJob() doesn't release encryptedBlobContent.
   * After fix, this test will PASS.
   *
   * Scenario: Job submitted but aborted before run() executes (e.g., operation timeout)
   */
  @Test
  public void testDecryptJobAbortedBeforeExecutionReleasesBuffer() throws Exception {
    // Create and encrypt some data
    ByteBuf plaintext = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    plaintext.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec perBlobKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(plaintext, perBlobKey);
    plaintext.release();

    // Encrypt the key
    SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
    ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

    CountDownLatch callbackLatch = new CountDownLatch(1);
    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();

    // Create decrypt job
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      exceptionRef.set(exception);
      callbackLatch.countDown();
    };

    DecryptJob decryptJob = new DecryptJob(
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

    // Abort the job BEFORE run() is called (simulates operation timeout/cancellation)
    decryptJob.closeJob(new GeneralSecurityException("Operation aborted before execution"));

    // Wait for callback
    callbackLatch.await();

    // Verify callback was invoked with exception
    assertNotNull("Exception should be set", exceptionRef.get());
    assertNull("Result should be null on abort", resultRef.get());

    // Release our reference to encryptedContent (bug: closeJob should have done this)
    // If bug exists: encryptedContent.refCnt() will be 1, test FAILS with leak detection
    // After fix: encryptedContent.refCnt() will be 0

    // If bug exists: encryptedBlobContent was never released by closeJob()
    // Test will FAIL here with leak detection error
    //
    // After fix: closeJob() releases encryptedBlobContent
    // Test will PASS
  }

  /**
   * PRODUCTION TEST: DecryptJob abort during execution should clean up properly
   *
   * This test will FAIL with the current bug.
   * After fix, this test will PASS.
   *
   * Scenario: Job starts executing but is aborted mid-execution
   */
  @Test
  public void testDecryptJobAbortedDuringExecutionReleasesBuffer() throws Exception {
    // Create and encrypt some data
    ByteBuf plaintext = PooledByteBufAllocator.DEFAULT.heapBuffer(2048);
    plaintext.writeBytes(TestUtils.getRandomBytes(2048));

    SecretKeySpec perBlobKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(plaintext, perBlobKey);
    plaintext.release();

    // Encrypt the key
    SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
    ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

    CountDownLatch jobStarted = new CountDownLatch(1);
    CountDownLatch proceedWithClose = new CountDownLatch(1);
    CountDownLatch callbackLatch = new CountDownLatch(1);
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();

    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      exceptionRef.set(exception);
      callbackLatch.countDown();
    };

    DecryptJob decryptJob = new DecryptJob(
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

    // Start job in separate thread
    Thread jobThread = new Thread(() -> {
      jobStarted.countDown();
      decryptJob.run();
    });
    jobThread.start();

    // Wait for job to start
    jobStarted.await();

    // Give it a moment to start processing
    Thread.sleep(10);

    // Abort the job while it's running
    decryptJob.closeJob(new GeneralSecurityException("Operation aborted during execution"));

    // Wait for completion
    jobThread.join(5000);
    callbackLatch.await();

    // Release our reference
    encryptedContent.release();

    // If bug exists: Buffer leaked
    // After fix: Buffer properly cleaned up
  }

  /**
   * PRODUCTION TEST: Multiple DecryptJob aborts should not leak
   *
   * Simulates production scenario where multiple operations are cancelled.
   */
  @Test
  public void testMultipleDecryptJobAbortsDoNotLeak() throws Exception {
    for (int i = 0; i < 10; i++) {
      // Create and encrypt data
      ByteBuf plaintext = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
      plaintext.writeBytes(TestUtils.getRandomBytes(512));

      SecretKeySpec perBlobKey = kms.getRandomKey();
      ByteBuf encryptedContent = cryptoService.encrypt(plaintext, perBlobKey);
      plaintext.release();

      // Encrypt the key
      SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
      ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

      CountDownLatch callbackLatch = new CountDownLatch(1);

      Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
        callbackLatch.countDown();
      };

      DecryptJob decryptJob = new DecryptJob(
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

      // Abort immediately
      decryptJob.closeJob(new GeneralSecurityException("Operation " + i + " aborted"));
      callbackLatch.await();

      // Release our reference (bug: closeJob should have done this)
      // If bug exists: encryptedContent leaked, test FAILS
      // After fix: encryptedContent already released by closeJob
    }

    // If bug exists: 10 buffers leaked
    // After fix: No leaks
  }
}
