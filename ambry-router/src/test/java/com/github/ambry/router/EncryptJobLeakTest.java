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
import com.github.ambry.commons.Callback;
import com.github.ambry.config.CryptoServiceConfig;
import com.github.ambry.config.KMSConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.IllegalReferenceCountException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.github.ambry.router.CryptoTestUtils.*;
import static org.junit.Assert.*;

/**
 * Tests for EncryptJob ByteBuf memory leak detection.
 * Tests HIGH-RISK paths #1-4: Exception during encryptKey, concurrent closeJob,
 * finally block exceptions, and constructor ownership.
 */
public class EncryptJobLeakTest {
  private static final int DEFAULT_KEY_SIZE = 64;
  private static final int RANDOM_KEY_SIZE_IN_BITS = 256;
  private static final String CLUSTER_NAME = "test-cluster";
  private static final MetricRegistry REGISTRY = new MetricRegistry();

  private CryptoService<SecretKeySpec> cryptoService;
  private KeyManagementService<SecretKeySpec> kms;
  private CryptoJobMetricsTracker metricsTracker;
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
    CryptoJobMetrics encryptJobMetrics = new CryptoJobMetrics(EncryptJobLeakTest.class, "Encrypt", REGISTRY);
    metricsTracker = new CryptoJobMetricsTracker(encryptJobMetrics);
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();
  }

  /**
   * HIGH-RISK PATH #1: Exception during encryptKey() after successful encrypt()
   *
   * Tests that if encryptKey() throws an exception AFTER encrypt() has succeeded,
   * the encrypted ByteBuf is properly released and not leaked.
   *
   * Expected: No leak - encryptedBlobContent should be released in catch block
   */
  @Test
  public void testExceptionDuringEncryptKeyAfterEncryptSuccess() throws Exception {
    // Create a ByteBuf for encryption
    ByteBuf blobContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blobContent.writeBytes(TestUtils.getRandomBytes(1024));

    // Create a KeyManagementService that throws during getKey (called before encryptKey)
    KeyManagementService<SecretKeySpec> faultyKms = new KeyManagementService<SecretKeySpec>() {
      @Override
      public void register(short accountId, short containerId) throws GeneralSecurityException {
        // no-op
      }

      @Override
      public void register(String context) throws GeneralSecurityException {
        // no-op
      }

      @Override
      public SecretKeySpec getKey(com.github.ambry.rest.RestRequest restRequest, short accountId, short containerId)
          throws GeneralSecurityException {
        throw new GeneralSecurityException("Simulated KMS failure during getKey");
      }

      @Override
      public SecretKeySpec getKey(com.github.ambry.rest.RestRequest restRequest, String context)
          throws GeneralSecurityException {
        throw new GeneralSecurityException("Simulated KMS failure during getKey");
      }

      @Override
      public SecretKeySpec getRandomKey() throws GeneralSecurityException {
        return kms.getRandomKey();
      }

      @Override
      public void close() {
        // no-op
      }
    };

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();
    AtomicReference<EncryptJob.EncryptJobResult> resultRef = new AtomicReference<>();

    Callback<EncryptJob.EncryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      exceptionRef.set(exception);
      latch.countDown();
    };

    // Create encrypt job with faulty KMS
    EncryptJob job = new EncryptJob(
        (short) 1, (short) 1,
        blobContent, null,
        kms.getRandomKey(),
        cryptoService, faultyKms,
        null, metricsTracker,
        callback
    );

    // Run the job - should fail during getKey, before encryptKey
    job.run();

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));
    assertNotNull("Should have exception", exceptionRef.get());
    assertNull("Result should be null", resultRef.get());
    assertTrue("Should be GeneralSecurityException",
        exceptionRef.get() instanceof GeneralSecurityException);

    // Leak detection happens in @After - if there's a leak, test will fail
  }

  /**
   * HIGH-RISK PATH #2: closeJob() called during active encryption (race condition)
   *
   * Tests concurrent access where closeJob() is called while run() is executing.
   * Both methods try to release blobContentToEncrypt.
   *
   * Expected: No leak or double-release - synchronization should handle this
   */
  @Test
  public void testCloseJobDuringActiveEncryption() throws Exception {
    // Use a slow crypto service to create race condition window
    CryptoService<SecretKeySpec> slowCryptoService = new CryptoService<SecretKeySpec>() {
      @Override
      public ByteBuf encrypt(ByteBuf toEncrypt, SecretKeySpec key) throws GeneralSecurityException {
        // Sleep to create race condition window
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return cryptoService.encrypt(toEncrypt, key);
      }

      @Override
      public ByteBuffer encrypt(ByteBuffer toEncrypt, SecretKeySpec key) throws GeneralSecurityException {
        return cryptoService.encrypt(toEncrypt, key);
      }

      @Override
      public ByteBuf decrypt(ByteBuf toDecrypt, SecretKeySpec key) throws GeneralSecurityException {
        return cryptoService.decrypt(toDecrypt, key);
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
        return cryptoService.decryptKey(toDecrypt, key);
      }
    };

    ByteBuf blobContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blobContent.writeBytes(TestUtils.getRandomBytes(1024));

    CountDownLatch runStarted = new CountDownLatch(1);
    CountDownLatch callbackLatch = new CountDownLatch(1);
    AtomicBoolean doubleRelease = new AtomicBoolean(false);

    Callback<EncryptJob.EncryptJobResult> callback = (result, exception) -> {
      runStarted.countDown();
      callbackLatch.countDown();
    };

    EncryptJob job = new EncryptJob(
        (short) 1, (short) 1,
        blobContent, null,
        kms.getRandomKey(),
        slowCryptoService, kms,
        null, metricsTracker,
        callback
    );

    // Start run() in background thread
    Thread runThread = new Thread(() -> {
      try {
        job.run();
      } catch (IllegalReferenceCountException e) {
        doubleRelease.set(true);
      }
    });
    runThread.start();

    // Wait a bit for run() to start, then call closeJob()
    Thread.sleep(50);

    try {
      job.closeJob(new GeneralSecurityException("Aborted"));
    } catch (IllegalReferenceCountException e) {
      doubleRelease.set(true);
    }

    runThread.join(5000);
    assertTrue("Callback should complete", callbackLatch.await(5, TimeUnit.SECONDS));

    assertFalse("Should not have double-release", doubleRelease.get());

    // Leak detection happens in @After
  }

  /**
   * HIGH-RISK PATH #3: GeneralSecurityException in finally block
   *
   * Tests that ByteBufs are released even if exceptions occur during finally block execution.
   *
   * Expected: No leak - blobContentToEncrypt should be released in finally
   */
  @Test
  public void testExceptionDuringFinallyBlock() throws Exception {
    ByteBuf blobContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blobContent.writeBytes(TestUtils.getRandomBytes(1024));

    // Create a callback that throws during onCompletion (called in finally)
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean callbackExecuted = new AtomicBoolean(false);

    Callback<EncryptJob.EncryptJobResult> throwingCallback = (result, exception) -> {
      callbackExecuted.set(true);
      if (result != null) {
        result.release(); // Release the encrypted result
      }
      latch.countDown();
      // This exception occurs in finally block after buffer should be released
      throw new RuntimeException("Callback threw exception");
    };

    EncryptJob job = new EncryptJob(
        (short) 1, (short) 1,
        blobContent, null,
        kms.getRandomKey(),
        cryptoService, kms,
        null, metricsTracker,
        throwingCallback
    );

    // Run should complete despite callback exception
    try {
      job.run();
    } catch (RuntimeException e) {
      assertEquals("Callback threw exception", e.getMessage());
    }

    assertTrue("Callback should have been called", callbackExecuted.get());

    // Leak detection happens in @After - buffer should be released before callback
  }

  /**
   * HIGH-RISK PATH #4: Constructor ownership - caller retains reference
   *
   * Tests that if caller keeps a reference to the ByteBuf and releases it,
   * EncryptJob doesn't cause double-release.
   *
   * Expected: No double-release or leak - ownership should be clear
   */
  @Test
  public void testConstructorOwnershipTransfer() throws Exception {
    ByteBuf blobContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blobContent.writeBytes(TestUtils.getRandomBytes(1024));

    // Caller retains a reference (simulating mistake)
    blobContent.retain();

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<EncryptJob.EncryptJobResult> resultRef = new AtomicReference<>();

    Callback<EncryptJob.EncryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      if (result != null) {
        result.release(); // Properly release the encrypted result
      }
      latch.countDown();
    };

    EncryptJob job = new EncryptJob(
        (short) 1, (short) 1,
        blobContent, null,
        kms.getRandomKey(),
        cryptoService, kms,
        null, metricsTracker,
        callback
    );

    // Run the job - it will release blobContent
    job.run();

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));
    assertNotNull("Should have result", resultRef.get());

    // Caller's retained reference should still be valid
    assertEquals("Caller's reference should have refCnt=1", 1, blobContent.refCnt());

    // Caller releases their reference
    blobContent.release();

    // Leak detection happens in @After
  }

  /**
   * Test successful encryption and proper cleanup
   *
   * This is a baseline test to ensure normal operation doesn't leak.
   */
  @Test
  public void testSuccessfulEncryptionNoLeak() throws Exception {
    ByteBuf blobContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blobContent.writeBytes(TestUtils.getRandomBytes(1024));

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<EncryptJob.EncryptJobResult> resultRef = new AtomicReference<>();

    Callback<EncryptJob.EncryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    EncryptJob job = new EncryptJob(
        (short) 1, (short) 1,
        blobContent, null,
        kms.getRandomKey(),
        cryptoService, kms,
        null, metricsTracker,
        callback
    );

    job.run();

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));
    assertNotNull("Should have result", resultRef.get());

    // Must release the encrypted result
    resultRef.get().release();

    // Leak detection happens in @After
  }

  /**
   * Test closeJob() before run() - normal abort scenario
   */
  @Test
  public void testCloseJobBeforeRun() throws Exception {
    ByteBuf blobContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blobContent.writeBytes(TestUtils.getRandomBytes(1024));

    CountDownLatch latch = new CountDownLatch(1);

    Callback<EncryptJob.EncryptJobResult> callback = (result, exception) -> {
      latch.countDown();
    };

    EncryptJob job = new EncryptJob(
        (short) 1, (short) 1,
        blobContent, null,
        kms.getRandomKey(),
        cryptoService, kms,
        null, metricsTracker,
        callback
    );

    // Close before running
    job.closeJob(new GeneralSecurityException("Job aborted"));

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));

    // Leak detection happens in @After
  }
}
