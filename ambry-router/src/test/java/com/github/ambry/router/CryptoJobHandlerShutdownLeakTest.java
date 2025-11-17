// Copyright (C) 2025. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use
// this file except in compliance with the License. You may obtain a copy of the
// License at  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied.

package com.github.ambry.router;

import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.Callback;
import com.github.ambry.commons.CommonTestUtils;
import com.github.ambry.config.CryptoServiceConfig;
import com.github.ambry.config.KMSConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import com.github.ambry.utils.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests proving DecryptJob.closeJob() leaks encryptedBlobContent during CryptoJobHandler shutdown.
 *
 * PRODUCTION BUG (DecryptJob.java:112-114):
 * closeJob() does NOT release encryptedBlobContent, unlike run() which releases it in finally block (line 97-98).
 *
 * PRODUCTION CODE PATH (CryptoJobHandler.java:64-68):
 * When CryptoJobHandler.close() is called, it gets pending jobs from scheduler.shutdownNow() and calls
 * closeJob() on each. These jobs have encryptedBlobContent that was duplicate()d by the caller, but
 * closeJob() never releases the duplicate, causing a leak.
 *
 * IMPACT:
 * In production, when CryptoJobHandler shuts down (e.g., during server shutdown or restart), any pending
 * DecryptJobs leak their encrypted input buffers. With high throughput, this accumulates to significant
 * memory leaks, potentially causing OOM.
 *
 * FIX:
 * DecryptJob.closeJob() should release encryptedBlobContent before calling callback:
 * <pre>
 * {@code
 * public void closeJob(GeneralSecurityException gse) {
 *   if (encryptedBlobContent != null) {
 *     encryptedBlobContent.release();
 *   }
 *   callback.onCompletion(null, gse);
 * }
 * }
 * </pre>
 */
public class CryptoJobHandlerShutdownLeakTest {

  private GCMCryptoService cryptoService;
  private SingleKeyManagementService kms;
  private MockClusterMap clusterMap;
  private NonBlockingRouterMetrics routerMetrics;
  private NettyByteBufLeakHelper leakHelper;
  private CryptoJobHandler cryptoJobHandler;
  private static final int DEFAULT_KEY_SIZE = 64;
  private static final int TEST_BUFFER_SIZE = 100;

  @Before
  public void setUp() throws Exception {
    leakHelper = new NettyByteBufLeakHelper();
    leakHelper.beforeTest();

    Properties props = new Properties();
    String defaultKey = TestUtils.getRandomKey(DEFAULT_KEY_SIZE);
    props.setProperty("kms.default.container.key", defaultKey);
    VerifiableProperties verifiableProperties = new VerifiableProperties(props);

    cryptoService = new GCMCryptoService(new CryptoServiceConfig(verifiableProperties));
    kms = new SingleKeyManagementService(new KMSConfig(verifiableProperties), defaultKey);
    clusterMap = new MockClusterMap();
    routerMetrics = new NonBlockingRouterMetrics(clusterMap, null);
    cryptoJobHandler = new CryptoJobHandler(1);
  }

  @After
  public void tearDown() {
    if (cryptoJobHandler != null) {
      cryptoJobHandler.close();
    }
    leakHelper.afterTest();
  }

  /**
   * TEST: Proves DecryptJob.closeJob() leaks encryptedBlobContent during CryptoJobHandler shutdown.
   *
   * CURRENT STATE: This test CURRENTLY FAILS (demonstrating the bug exists).
   *
   * PRODUCTION PATH:
   * 1. Create DecryptJob with duplicate()d ByteBuf (matches GetBlobOperation:1248)
   * 2. Submit to CryptoJobHandler (job queued but not yet executed)
   * 3. Close CryptoJobHandler immediately (before job runs)
   * 4. CryptoJobHandler.close() calls job.closeJob() (line 64-68)
   * 5. closeJob() does NOT release the duplicate - LEAK
   *
   * VERIFICATION:
   * NettyByteBufLeakHelper.afterTest() calls Assert.assertEquals() which FAILS because
   * the duplicate ByteBuf was not released by closeJob().
   *
   * Once DecryptJob.closeJob() is fixed to release encryptedBlobContent, this test will PASS.
   */
  @Test
  public void testDecryptJobLeaksInputBufferOnShutdown() throws Exception {
    // Create encrypted content using pooled direct buffer (matches production patterns)
    ByteBuf encryptedChunk = PooledByteBufAllocator.DEFAULT.directBuffer(TEST_BUFFER_SIZE);
    encryptedChunk.writeBytes("encrypted data".getBytes());

    // Retain so we have a reference to track; DecryptJob gets a duplicate()
    // In production, GetBlobOperation does: chunkBuf.retain() then passes chunkBuf.duplicate() to DecryptJob
    encryptedChunk.retain();

    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      latch.countDown();
    };

    // Create DecryptJob with duplicate (matches GetBlobOperation:1248)
    BlobId blobId = createTestBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    CryptoJobMetricsTracker metricsTracker = new CryptoJobMetricsTracker(routerMetrics.decryptJobMetrics);

    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedChunk.duplicate(), null,
        cryptoService, kms, null, metricsTracker, callback);

    // Submit to CryptoJobHandler (matches production usage)
    cryptoJobHandler.submitJob(job);

    // Close handler immediately - this ensures job hasn't run yet and will be closed via closeJob()
    // In production: server shutdown, CryptoJobHandler.close() line 64-68
    cryptoJobHandler.close();
    cryptoJobHandler = null; // Prevent double-close in tearDown

    latch.await(5, TimeUnit.SECONDS);

    // Release our original reference
    encryptedChunk.release();

    // BUG: The duplicate created by encryptedChunk.duplicate() was never released by closeJob()
    // NettyByteBufLeakHelper.afterTest() will FAIL this test with:
    // "DirectMemoryLeak: [allocation|deallocation] before test[X|Y], after test[X+1|Y]"
  }

  /**
   * TEST: Proves DecryptJob.run() properly releases encryptedBlobContent (the positive case).
   *
   * PRODUCTION PATH:
   * 1. Create DecryptJob with duplicate()d ByteBuf
   * 2. Submit to CryptoJobHandler
   * 3. Job executes via run() method
   * 4. run() releases encryptedBlobContent in finally block (line 97-98)
   * 5. No leak
   *
   * VERIFICATION:
   * NettyByteBufLeakHelper.afterTest() will PASS because run() properly releases the duplicate.
   * This proves that the leak is specific to the closeJob() path, not all DecryptJob paths.
   */
  @Test
  public void testDecryptJobProperlyReleasesInputBufferWhenRun() throws Exception {
    // Create encrypted content using pooled direct buffer
    ByteBuf encryptedChunk = PooledByteBufAllocator.DEFAULT.directBuffer(TEST_BUFFER_SIZE);
    encryptedChunk.writeBytes("encrypted data".getBytes());

    // Retain so we can track the duplicate separately
    encryptedChunk.retain();

    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      // Clean up the decrypted output if present
      if (result != null && result.getDecryptedBlobContent() != null) {
        result.getDecryptedBlobContent().release();
      }
      latch.countDown();
    };

    // Create DecryptJob with duplicate
    BlobId blobId = createTestBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    CryptoJobMetricsTracker metricsTracker = new CryptoJobMetricsTracker(routerMetrics.decryptJobMetrics);

    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedChunk.duplicate(), null,
        cryptoService, kms, null, metricsTracker, callback);

    // Submit and let it run (job will execute and call run())
    cryptoJobHandler.submitJob(job);

    latch.await(5, TimeUnit.SECONDS);

    // Release our original reference
    encryptedChunk.release();

    // CORRECT: run() released the duplicate in its finally block (line 97-98)
    // NettyByteBufLeakHelper.afterTest() will PASS
  }

  private BlobId createTestBlobId() {
    byte dc = (byte) TestUtils.RANDOM.nextInt(3);
    return new BlobId(CommonTestUtils.getCurrentBlobIdVersion(), BlobId.BlobIdType.NATIVE, dc,
        Utils.getRandomShort(TestUtils.RANDOM), Utils.getRandomShort(TestUtils.RANDOM),
        clusterMap.getRandomWritablePartition(MockClusterMap.DEFAULT_PARTITION_CLASS, null),
        false, BlobId.BlobDataType.DATACHUNK);
  }
}
