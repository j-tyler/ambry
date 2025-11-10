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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.github.ambry.router.CryptoTestUtils.*;
import static org.junit.Assert.*;

/**
 * Production tests for DecryptJob.closeJob() ByteBuf memory leak.
 *
 * BUG: DecryptJob.closeJob() does NOT release encryptedBlobContent.
 * Location: ambry-router/src/main/java/com/github/ambry/router/DecryptJob.java:112-114
 *
 * These tests will FAIL until the bug is fixed.
 *
 * Expected fix:
 * <pre>
 * {@code
 * @Override
 * public void closeJob(GeneralSecurityException gse) {
 *   if (encryptedBlobContent != null) {
 *     encryptedBlobContent.release();
 *   }
 *   callback.onCompletion(null, gse);
 * }
 * }
 * </pre>
 */
public class DecryptJobCloseLeakTest {
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
    CryptoJobMetrics decryptJobMetrics = new CryptoJobMetrics(DecryptJobCloseLeakTest.class, "Decrypt", REGISTRY);
    metricsTracker = new CryptoJobMetricsTracker(decryptJobMetrics);
    blobId = new BlobId(BlobId.BLOB_ID_V6, BlobId.BlobIdType.NATIVE, (byte) 0, (short) 1, (short) 1,
        new MockClusterMap().getWritablePartitionIds(null).get(0), false, BlobId.BlobDataType.DATACHUNK);
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();
  }

  /**
   * Production test: closeJob() called before run() should not leak encryptedBlobContent.
   *
   * CURRENT BEHAVIOR: This test will FAIL - closeJob() does NOT release encryptedBlobContent.
   * EXPECTED BEHAVIOR: After fix, this test will PASS - closeJob() releases encryptedBlobContent.
   *
   * Ownership contract:
   * - DecryptJob constructor takes ownership of encryptedBlobContent
   * - DecryptJob is responsible for releasing it
   * - closeJob() is a cleanup method and must release owned resources
   */
  @Test
  public void testCloseJobReleasesEncryptedContent() throws Exception {
    // Prepare encrypted content
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    originalContent.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec perBlobKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, perBlobKey);
    originalContent.release(); // Test releases its input buffer

    // Encrypt the key
    SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
    ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();

    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      exceptionRef.set(exception);
      latch.countDown();
    };

    // Create DecryptJob - ownership of encryptedContent transfers to job
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

    // Call closeJob() BEFORE run() - simulates job cancellation
    job.closeJob(new GeneralSecurityException("Job aborted before execution"));

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));
    assertNotNull("Should have exception", exceptionRef.get());

    // At this point, closeJob() should have released encryptedContent
    // NettyByteBufLeakHelper will detect if it wasn't released
    // This test will FAIL until the bug is fixed
  }

  /**
   * Production test: closeJob() called after partial execution should not leak.
   *
   * This tests that closeJob() properly cleans up even if called at different
   * stages of the job lifecycle.
   */
  @Test
  public void testCloseJobAfterConstructorReleasesBuffer() throws Exception {
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
    originalContent.writeBytes(TestUtils.getRandomBytes(512));

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

    // Close immediately after construction
    job.closeJob(new GeneralSecurityException("Immediate abort"));

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));

    // NettyByteBufLeakHelper will catch the leak if closeJob() didn't release
  }

  /**
   * Production test: Multiple closeJob() calls should be safe (idempotent).
   *
   * After the fix, closeJob() should be safe to call multiple times.
   */
  @Test
  public void testMultipleCloseJobCallsAreSafe() throws Exception {
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    originalContent.writeBytes(TestUtils.getRandomBytes(256));

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

    // Call closeJob() multiple times
    job.closeJob(new GeneralSecurityException("First close"));
    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));

    // Second close should be safe (no double-release)
    job.closeJob(new GeneralSecurityException("Second close"));

    // NettyByteBufLeakHelper will catch any issues
  }

  /**
   * Baseline test: run() followed by normal completion should not leak.
   *
   * This verifies that the normal execution path is correct.
   */
  @Test
  public void testNormalExecutionNoLeak() throws Exception {
    ByteBuf originalContent = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    originalContent.writeBytes(TestUtils.getRandomBytes(1024));

    SecretKeySpec perBlobKey = kms.getRandomKey();
    ByteBuf encryptedContent = cryptoService.encrypt(originalContent, perBlobKey);
    originalContent.release();

    SecretKeySpec containerKey = kms.getKey(null, blobId.getAccountId(), blobId.getContainerId());
    ByteBuffer encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();

    DecryptJob job = new DecryptJob(
        blobId,
        encryptedKey,
        encryptedContent,
        null,
        cryptoService,
        kms,
        null,
        metricsTracker,
        (result, exception) -> {
          resultRef.set(result);
          latch.countDown();
        }
    );

    job.run();

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));
    assertNotNull("Should have result", resultRef.get());
    assertNotNull("Should have decrypted content", resultRef.get().getDecryptedBlobContent());

    // Caller must release the result
    resultRef.get().getDecryptedBlobContent().release();

    // NettyByteBufLeakHelper verifies no leaks
  }
}
