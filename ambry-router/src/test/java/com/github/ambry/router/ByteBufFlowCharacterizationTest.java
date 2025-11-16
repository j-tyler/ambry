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

import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.Callback;
import com.github.ambry.commons.CommonTestUtils;
import com.github.ambry.config.CryptoServiceConfig;
import com.github.ambry.config.KMSConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.network.BoundedNettyByteBufReceive;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.TestUtils;
import com.github.ambry.utils.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.GeneralSecurityException;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * ByteBuf Flow Characterization Tests
 *
 * PURPOSE: These tests are NOT traditional unit tests. They do not assert correctness.
 * Instead, they exercise production code paths to observe ByteBuf flow with the tracer agent.
 *
 * USAGE: Run this class with -javaagent:bytebuf-tracker-agent.jar to see complete ByteBuf flow.
 *
 * COVERAGE: This class exercises all potential leak scenarios identified in:
 * - BYTEBUF_LEAK_ANALYSIS_PART2.md (Classes 10-12)
 *
 * Each test replicates production behavior including:
 * - Constructor calls with ByteBuf parameters
 * - Method calls that transfer ownership
 * - Release semantics (or lack thereof)
 * - Exception paths
 * - Cleanup paths
 */
public class ByteBufFlowCharacterizationTest {

  private MockCryptoService cryptoService;
  private MockKeyManagementService kms;
  private CryptoJobMetricsTracker metricsTracker;
  private ClusterMap clusterMap;
  private NonBlockingRouterMetrics routerMetrics;
  private static final int DEFAULT_KEY_SIZE = 64;

  @Before
  public void setUp() throws Exception {
    // Use existing production mock implementations
    Properties props = new Properties();
    VerifiableProperties verifiableProperties = new VerifiableProperties(props);
    cryptoService = new MockCryptoService(new CryptoServiceConfig(verifiableProperties));
    // Generate valid hex-encoded key (same pattern as CryptoJobHandlerTest)
    String defaultKey = TestUtils.getRandomKey(DEFAULT_KEY_SIZE);
    kms = new MockKeyManagementService(new KMSConfig(verifiableProperties), defaultKey);
    clusterMap = new MockClusterMap();
    routerMetrics = new NonBlockingRouterMetrics(clusterMap, null);
    metricsTracker = new CryptoJobMetricsTracker(routerMetrics.decryptJobMetrics);
  }

  @After
  public void tearDown() {
    // No assertions - let the tracer observe the flows
  }

  /**
   * Generate a new test BlobId - replicates CryptoJobHandlerTest.getNewBlobId()
   * @return newly generated {@link BlobId}
   */
  private BlobId getNewBlobId() {
    byte dc = (byte) TestUtils.RANDOM.nextInt(3);
    BlobId.BlobIdType type = TestUtils.RANDOM.nextBoolean() ? BlobId.BlobIdType.NATIVE : BlobId.BlobIdType.CRAFTED;
    PartitionId partition = clusterMap.getRandomWritablePartition(MockClusterMap.DEFAULT_PARTITION_CLASS, null);
    return new BlobId(CommonTestUtils.getCurrentBlobIdVersion(), type, dc,
        Utils.getRandomShort(TestUtils.RANDOM), Utils.getRandomShort(TestUtils.RANDOM),
        partition, false, BlobId.BlobDataType.DATACHUNK);
  }

  // ============================================================================
  // SECTION 1: DecryptJob - Normal Flow Scenarios (Production Patterns)
  // ============================================================================

  /**
   * TEST: DecryptJob normal success path - replicates GetBlobOperation.maybeLaunchCryptoJob()
   *
   * PRODUCTION FLOW (GetBlobOperation:1124-1126):
   * 1. chunkBuf.retain() - increment refCount for crypto job
   * 2. maybeLaunchCryptoJob(chunkBuf, ...)
   * 3. Inside: new DecryptJob(..., dataBuf.duplicate(), ...)
   * 4. DecryptJob.run() releases duplicate (decrements refCount from retain)
   * 5. Callback receives DecryptJobResult with decrypted ByteBuf
   *
   * OBSERVES:
   * - chunkBuf.retain() increments refCount
   * - duplicate() shares refCount with original
   * - DecryptJob releases duplicate in finally block
   * - Decrypted ByteBuf creation by cryptoService
   *
   * LEAK SCENARIOS COVERED:
   * - SAFE-10.1: Input ByteBuf properly managed via retain/release pattern
   */
  @Test
  public void testDecryptJob_ProductionFlow_RetainAndDuplicate() throws Exception {
    // Simulate encrypted chunk from network (as in GetBlobOperation)
    ByteBuf encryptedChunk = Unpooled.buffer(100);
    encryptedChunk.writeBytes("encrypted chunk data".getBytes());

    // PRODUCTION PATTERN: retain() before passing to crypto job (line 1124)
    encryptedChunk.retain();

    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    // PRODUCTION PATTERN: pass buffer, DecryptJob will duplicate() it (line 1248)
    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    DecryptJob job = new DecryptJob(getNewBlobId(), encryptedKey, encryptedChunk.duplicate(), null,
        cryptoService, kms, null, metricsTracker, callback);

    // Job runs (simulating ExecutorService.execute)
    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // Clean up result
    DecryptJob.DecryptJobResult result = resultRef.get();
    if (result != null && result.getDecryptedBlobContent() != null) {
      result.getDecryptedBlobContent().release();
    }

    // Release original chunk (still has refCount from our explicit retain)
    encryptedChunk.release();
  }

  /**
   * TEST: DecryptJob with metadata-only (null content) - replicates GetBlobInfoOperation:429
   *
   * PRODUCTION FLOW (GetBlobInfoOperation:429):
   * new DecryptJob(blobId, encryptionKey.duplicate(), null, userMetadata, ...)
   *
   * OBSERVES:
   * - Null ByteBuf handling in DecryptJob
   * - Line 97 null check in finally block
   * - Metadata-only decryption path
   *
   * LEAK SCENARIOS COVERED:
   * - Edge case: null ByteBuf content, only metadata
   */
  @Test
  public void testDecryptJob_MetadataOnly_NullContent() throws Exception {
    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    ByteBuffer encryptedMetadata = ByteBuffer.wrap("metadata".getBytes());

    // PRODUCTION PATTERN: null blob content (GetBlobInfoOperation line 429)
    DecryptJob job = new DecryptJob(getNewBlobId(), encryptedKey.duplicate(), null, encryptedMetadata,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // No ByteBuf to release (null content)
  }

  /**
   * TEST: DecryptJob result processing - replicates GetBlobOperation callback (lines 1250-1263)
   *
   * PRODUCTION FLOW (GetBlobOperation:1254-1257):
   * if (isOperationComplete() || operationException.get() != null) {
   *   if (exception == null && result.getDecryptedBlobContent() != null) {
   *     ReferenceCountUtil.safeRelease(result.getDecryptedBlobContent());
   *   }
   *   return;
   * }
   *
   * OBSERVES:
   * - Early-exit cleanup pattern
   * - ReferenceCountUtil.safeRelease() usage
   * - DecryptJobResult has no release() method
   *
   * LEAK SCENARIOS COVERED:
   * - LEAK-10.7: No release() method - must manually release content
   */
  @Test
  public void testDecryptJob_CallbackEarlyExit_ProperCleanup() throws Exception {
    ByteBuf encryptedChunk = Unpooled.buffer(100);
    encryptedChunk.writeBytes("encrypted".getBytes());
    encryptedChunk.retain();

    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    // Simulate operation already complete (early exit scenario)
    final boolean operationComplete = true;

    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      // PRODUCTION PATTERN: Early exit cleanup (GetBlobOperation lines 1254-1258)
      if (operationComplete) {
        if (exception == null && result != null && result.getDecryptedBlobContent() != null) {
          ReferenceCountUtil.safeRelease(result.getDecryptedBlobContent());
        }
        latch.countDown();
        return;
      }
      resultRef.set(result);
      latch.countDown();
    };

    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    DecryptJob job = new DecryptJob(getNewBlobId(), encryptedKey, encryptedChunk.duplicate(), null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // Encrypted chunk still needs cleanup
    encryptedChunk.release();
  }

  // ============================================================================
  // SECTION 2: DecryptJob - Exception Paths
  // ============================================================================

  /**
   * TEST: DecryptJob exception during decryption - production exception handling
   *
   * PRODUCTION FLOW (DecryptJob:85-93):
   * try {
   *   decryptedBlobContent = cryptoService.decrypt(...);
   * } catch (GeneralSecurityException e) {
   *   if (decryptedBlobContent != null) {
   *     decryptedBlobContent.release(); // Line 92
   *   }
   * } finally {
   *   if (encryptedBlobContent != null) {
   *     encryptedBlobContent.release(); // Line 98
   *   }
   * }
   *
   * OBSERVES:
   * - Exception during metadata decryption
   * - Cleanup of partially created decryptedBlobContent
   * - Cleanup of encryptedBlobContent in finally
   *
   * LEAK SCENARIOS COVERED:
   * - SAFE-10.4: Exception handling releases both buffers
   */
  @Test
  public void testDecryptJob_DecryptionException_BothBuffersReleased() throws Exception {
    ByteBuf encryptedChunk = Unpooled.buffer(100);
    encryptedChunk.writeBytes("encrypted".getBytes());
    encryptedChunk.retain();

    // Configure mock to throw during decryption
    cryptoService.exceptionOnDecryption.set(new GeneralSecurityException("Simulated failure"));

    AtomicReference<Exception> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      exceptionRef.set(exception);
      latch.countDown();
    };

    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    ByteBuffer metadata = ByteBuffer.wrap("metadata".getBytes());
    DecryptJob job = new DecryptJob(getNewBlobId(), encryptedKey, encryptedChunk.duplicate(), metadata,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // Both buffers released by DecryptJob exception handling
    encryptedChunk.release();

    // Clear exception for other tests
    cryptoService.clearStates();
  }

  /**
   * TEST: DecryptJob exception before decryption - KMS failure
   *
   * PRODUCTION FLOW:
   * Exception thrown before any ByteBuf allocation
   * Only finally block runs (no decryptedBlobContent to cleanup)
   *
   * OBSERVES:
   * - Exception from KMS.getKey()
   * - Only encryptedBlobContent released
   * - No decryptedBlobContent created
   */
  @Test
  public void testDecryptJob_KMSException_OnlyInputReleased() throws Exception {
    ByteBuf encryptedChunk = Unpooled.buffer(100);
    encryptedChunk.writeBytes("encrypted".getBytes());
    encryptedChunk.retain();

    // Configure KMS to throw
    kms.exceptionToThrow.set(new GeneralSecurityException("KMS failure"));

    AtomicReference<Exception> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      exceptionRef.set(exception);
      latch.countDown();
    };

    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    DecryptJob job = new DecryptJob(getNewBlobId(), encryptedKey, encryptedChunk.duplicate(), null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // Only encrypted buffer needs cleanup
    encryptedChunk.release();

    // Clear exception
    kms.exceptionToThrow.set(null);
  }

  // ============================================================================
  // SECTION 3: DecryptJob - Critical Leak Scenario
  // ============================================================================

  /**
   * TEST: DecryptJob closeJob() - replicates CryptoJobHandler.close() (line 68)
   *
   * PRODUCTION FLOW (CryptoJobHandler:64-68):
   * List<Runnable> pendingTasks = scheduler.shutdownNow();
   * for (Runnable task : pendingTasks) {
   *   if (task instanceof CryptoJob) {
   *     ((CryptoJob) task).closeJob(CLOSED_EXCEPTION);
   *   }
   * }
   *
   * OBSERVES:
   * - closeJob() method (DecryptJob:112-114)
   * - BUG: closeJob() does NOT release encryptedBlobContent
   * - This is the actual production shutdown path
   *
   * LEAK SCENARIOS COVERED:
   * - 🚨 LEAK-10.2: closeJob() missing release (HIGH RISK)
   *
   * NOTE: Demonstrates actual production bug during CryptoJobHandler shutdown
   */
  @Test
  public void testDecryptJob_CryptoJobHandlerShutdown_InputLeaked() throws Exception {
    ByteBuf encryptedChunk = Unpooled.buffer(100);
    encryptedChunk.writeBytes("pending job data".getBytes());
    encryptedChunk.retain();

    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      latch.countDown();
    };

    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    DecryptJob job = new DecryptJob(getNewBlobId(), encryptedKey, encryptedChunk.duplicate(), null,
        cryptoService, kms, null, metricsTracker, callback);

    // PRODUCTION PATTERN: CryptoJobHandler.close() calls closeJob() on pending jobs
    job.closeJob(new GeneralSecurityException("CryptoJobHandler closed"));
    latch.await(1, TimeUnit.SECONDS);

    // BUG: encryptedChunk duplicate NOT released by closeJob()
    // encryptedChunk.release(); // Would need this to properly cleanup, but it's leaked!
    // Tracer will show this ByteBuf is leaked
  }

  /**
   * TEST: Multiple pending jobs at shutdown - demonstrates leak multiplier
   *
   * PRODUCTION SCENARIO:
   * CryptoJobHandler shutting down with multiple queued DecryptJobs
   * Each job has retained encrypted buffer
   * All leaked when closeJob() is called
   */
  @Test
  public void testDecryptJob_MultipleJobsShutdown_MultipleLeaks() throws Exception {
    int numJobs = 3;
    ByteBuf[] buffers = new ByteBuf[numJobs];
    CountDownLatch latch = new CountDownLatch(numJobs);

    for (int i = 0; i < numJobs; i++) {
      buffers[i] = Unpooled.buffer(100);
      buffers[i].writeBytes(("job " + i).getBytes());
      buffers[i].retain();

      Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
        latch.countDown();
      };

      ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
      DecryptJob job = new DecryptJob(getNewBlobId(), encryptedKey, buffers[i].duplicate(), null,
          cryptoService, kms, null, metricsTracker, callback);

      // Simulate shutdown
      job.closeJob(new GeneralSecurityException("Shutdown"));
    }

    latch.await(1, TimeUnit.SECONDS);

    // BUG: ALL encrypted buffers leaked!
    // Tracer will show 3 leaked ByteBufs
  }

  // ============================================================================
  // SECTION 4: DecryptJobResult - No Release Method
  // ============================================================================

  /**
   * TEST: DecryptJobResult forgotten cleanup - common mistake
   *
   * SCENARIO:
   * Developer receives DecryptJobResult
   * Uses decryptedBlobContent
   * Forgets to call ReferenceCountUtil.safeRelease()
   *
   * LEAK SCENARIOS COVERED:
   * - 🚨 LEAK-10.5: Forgot to release decryptedBlobContent
   *
   * NOTE: Easy mistake since DecryptJobResult has no release() method
   */
  @Test
  public void testDecryptJobResult_ForgottenCleanup_Leaked() throws Exception {
    ByteBuf encryptedChunk = Unpooled.buffer(100);
    encryptedChunk.writeBytes("encrypted".getBytes());
    encryptedChunk.retain();

    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    DecryptJob job = new DecryptJob(getNewBlobId(), encryptedKey, encryptedChunk.duplicate(), null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    DecryptJob.DecryptJobResult result = resultRef.get();
    if (result != null && result.getDecryptedBlobContent() != null) {
      // Use the content
      ByteBuf decrypted = result.getDecryptedBlobContent();
      byte[] data = new byte[decrypted.readableBytes()];
      decrypted.readBytes(data);

      // BUG: Forgot to release!
      // ReferenceCountUtil.safeRelease(decrypted); // MISSING!
      // Tracer will show decryptedBlobContent is leaked
    }

    encryptedChunk.release();
  }

  // ============================================================================
  // SECTION 5: BoundedNettyByteBufReceive - Normal Flow
  // ============================================================================

  /**
   * TEST: BoundedNettyByteBufReceive in Transmission - replicates production pattern
   *
   * PRODUCTION FLOW (Transmission:79-80):
   * networkReceive = new NetworkReceive(getConnectionId(),
   *     new BoundedNettyByteBufReceive(config.socketRequestMaxBytes), time);
   *
   * CLEANUP (Transmission:181-183):
   * if (networkReceive != null) {
   *   networkReceive.getReceivedBytes().release();
   * }
   *
   * OBSERVES:
   * - Dual ByteBuf lifecycle (sizeBuffer + buffer)
   * - sizeBuffer immediate release (line 88)
   * - buffer release via Transmission.release()
   *
   * LEAK SCENARIOS COVERED:
   * - ✅ SAFE-12.1: Proper production cleanup pattern
   */
  @Test
  public void testBoundedNettyByteBufReceive_ProductionTransmissionPattern() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    ByteBuffer networkData = ByteBuffer.allocate(totalSize);
    networkData.putLong(totalSize);
    byte[] payload = new byte[payloadSize];
    new Random().nextBytes(payload);
    networkData.put(payload);
    networkData.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(networkData));

    // PRODUCTION PATTERN: Transmission creates BoundedNettyByteBufReceive
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);
    receive.readFrom(channel);

    // Use content (simulating request processing)
    ByteBuf content = receive.content();
    if (content != null) {
      // Process data
    }

    // PRODUCTION PATTERN: Transmission.release() (line 182)
    receive.release();
  }

  /**
   * TEST: BoundedNettyByteBufReceive size exceeds max - production validation
   *
   * PRODUCTION FLOW (BoundedNettyByteBufReceive:89-91):
   * if (sizeToRead > maxRequestSize) {
   *   throw new IOException(...);
   * }
   *
   * OBSERVES:
   * - sizeBuffer released before validation (line 88)
   * - IOException thrown before buffer allocation
   * - No leak since buffer not allocated
   *
   * LEAK SCENARIOS COVERED:
   * - ✅ SAFE-12.2: Validation happens after sizeBuffer cleanup
   */
  @Test
  public void testBoundedNettyByteBufReceive_SizeExceedsMax_NoLeak() throws Exception {
    long hugeSize = 10000;

    ByteBuffer networkData = ByteBuffer.allocate(Long.BYTES);
    networkData.putLong(hugeSize);
    networkData.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(networkData));
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(1024);

    try {
      receive.readFrom(channel);
    } catch (IOException e) {
      // Expected: size exceeds max
      // sizeBuffer already released (line 88)
      // buffer never allocated
    }
  }

  // ============================================================================
  // SECTION 6: BoundedNettyByteBufReceive - Exception Paths
  // ============================================================================

  /**
   * TEST: BoundedNettyByteBufReceive IOException during payload read
   *
   * PRODUCTION FLOW (BoundedNettyByteBufReceive:100-105):
   * try {
   *   ...payload read...
   * } catch (IOException e) {
   *   buffer.release(); // Line 103
   *   throw e;
   * }
   *
   * OBSERVES:
   * - Buffer allocated but payload read fails
   * - Exception handler releases buffer
   *
   * LEAK SCENARIOS COVERED:
   * - ✅ SAFE-12.3: Exception handling releases buffer
   */
  @Test
  public void testBoundedNettyByteBufReceive_PayloadReadException_BufferReleased() throws Exception {
    int totalSize = 1000;

    // Size header but no payload
    ByteBuffer networkData = ByteBuffer.allocate(Long.BYTES);
    networkData.putLong(totalSize);
    networkData.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(networkData));
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);

    try {
      receive.readFrom(channel);
      // Try to read more (triggers EOF)
      receive.readFrom(channel);
    } catch (IOException e) {
      // Expected exception
      // buffer was released in exception handler (line 103)
    }
  }

  // ============================================================================
  // SECTION 7: BoundedNettyByteBufReceive - Leak Scenarios
  // ============================================================================

  /**
   * TEST: BoundedNettyByteBufReceive forgotten release - common production bug
   *
   * SCENARIO:
   * Developer reads data from receive
   * Uses content()
   * Forgets to call release()
   *
   * LEAK SCENARIOS COVERED:
   * - 🚨 LEAK-12.4: Caller never calls release()
   *
   * NOTE: Common mistake when not using Transmission wrapper
   */
  @Test
  public void testBoundedNettyByteBufReceive_ForgottenRelease_Leaked() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    ByteBuffer networkData = ByteBuffer.allocate(totalSize);
    networkData.putLong(totalSize);
    byte[] payload = new byte[payloadSize];
    new Random().nextBytes(payload);
    networkData.put(payload);
    networkData.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(networkData));

    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);
    receive.readFrom(channel);

    // Use content
    ByteBuf content = receive.content();
    if (content != null) {
      byte[] data = new byte[Math.min(100, content.readableBytes())];
      content.readBytes(data);
    }

    // BUG: Forgot to call receive.release()!
    // Tracer will show buffer is leaked
  }

  /**
   * TEST: BoundedNettyByteBufReceive incomplete read abandoned
   *
   * SCENARIO:
   * Partial payload read (buffer allocated but not full)
   * Operation abandoned without release
   *
   * LEAK SCENARIOS COVERED:
   * - ⚠️ LEAK-12.6: Incomplete read, buffer partially filled, abandoned
   */
  @Test
  public void testBoundedNettyByteBufReceive_IncompleteRead_Leaked() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    // Partial payload
    ByteBuffer networkData = ByteBuffer.allocate(Long.BYTES + 100);
    networkData.putLong(totalSize);
    byte[] partialPayload = new byte[100];
    new Random().nextBytes(partialPayload);
    networkData.put(partialPayload);
    networkData.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(networkData));

    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);
    receive.readFrom(channel);

    // Check incomplete
    if (!receive.isReadComplete()) {
      // BUG: Abandon without releasing!
      // receive.release(); // MISSING!
      // Tracer will show buffer is leaked
    }
  }

  /**
   * TEST: BoundedNettyByteBufReceive replace() orphan buffer
   *
   * PRODUCTION FLOW (BoundedNettyByteBufReceive:129-131):
   * public BoundedNettyByteBufReceive replace(ByteBuf content) {
   *   return new BoundedNettyByteBufReceive(content);
   * }
   *
   * OBSERVES:
   * - replace() creates new instance
   * - Original buffer ownership unclear
   * - Need to release BOTH original and replacement
   *
   * LEAK SCENARIOS COVERED:
   * - ⚠️ LEAK-12.5: replace() orphans original buffer
   */
  @Test
  public void testBoundedNettyByteBufReceive_Replace_OrphanBuffer() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    ByteBuffer networkData = ByteBuffer.allocate(totalSize);
    networkData.putLong(totalSize);
    byte[] payload = new byte[payloadSize];
    new Random().nextBytes(payload);
    networkData.put(payload);
    networkData.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(networkData));

    BoundedNettyByteBufReceive original = new BoundedNettyByteBufReceive(100000);
    original.readFrom(channel);

    // Create replacement
    ByteBuf replacementBuf = Unpooled.buffer(100);
    replacementBuf.writeBytes("replacement".getBytes());

    BoundedNettyByteBufReceive replaced = original.replace(replacementBuf);

    // BUG: Only release replacement
    replaced.release();

    // original.release(); // MISSING!
    // Tracer will show original buffer is leaked
  }

  /**
   * TEST: BoundedNettyByteBufReceive replace() proper cleanup - correct pattern
   *
   * DEMONSTRATES: Correct usage of replace() - release both instances
   */
  @Test
  public void testBoundedNettyByteBufReceive_Replace_ProperCleanup() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    ByteBuffer networkData = ByteBuffer.allocate(totalSize);
    networkData.putLong(totalSize);
    byte[] payload = new byte[payloadSize];
    new Random().nextBytes(payload);
    networkData.put(payload);
    networkData.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(networkData));

    BoundedNettyByteBufReceive original = new BoundedNettyByteBufReceive(100000);
    original.readFrom(channel);

    ByteBuf replacementBuf = Unpooled.buffer(100);
    replacementBuf.writeBytes("replacement".getBytes());

    BoundedNettyByteBufReceive replaced = original.replace(replacementBuf);

    // CORRECT: Release both
    original.release();
    replaced.release();
  }
}
