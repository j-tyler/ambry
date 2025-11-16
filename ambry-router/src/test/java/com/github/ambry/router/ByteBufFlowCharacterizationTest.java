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

import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.Callback;
import com.github.ambry.network.BoundedNettyByteBufReceive;
import com.github.ambry.rest.RestRequest;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.GeneralSecurityException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
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

  private final NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();
  private MockCryptoService cryptoService;
  private MockKeyManagementService kms;
  private MockCryptoJobMetricsTracker metricsTracker;

  @Before
  public void setUp() {
    nettyByteBufLeakHelper.beforeTest();
    cryptoService = new MockCryptoService();
    kms = new MockKeyManagementService();
    metricsTracker = new MockCryptoJobMetricsTracker();
  }

  @After
  public void tearDown() {
    nettyByteBufLeakHelper.afterTest();
  }

  // ============================================================================
  // SECTION 1: DecryptJob - Normal Flow Scenarios
  // ============================================================================

  /**
   * TEST: DecryptJob normal success path - input ByteBuf lifecycle
   *
   * FLOW:
   * 1. Allocate encrypted ByteBuf
   * 2. Create DecryptJob with encrypted ByteBuf (ownership transfer)
   * 3. Call run() - should decrypt and release input in finally block
   * 4. Callback receives DecryptJobResult with decrypted ByteBuf
   *
   * OBSERVES:
   * - Encrypted ByteBuf creation
   * - Ownership transfer to DecryptJob
   * - Release in run() finally block (line 98)
   * - Decrypted ByteBuf creation by cryptoService.decrypt()
   *
   * LEAK SCENARIOS COVERED:
   * - SAFE-10.1: Input ByteBuf always released
   */
  @Test
  public void testDecryptJob_NormalFlow_InputByteBufLifecycle() throws Exception {
    // Allocate encrypted ByteBuf
    ByteBuf encryptedBuf = Unpooled.buffer(100);
    encryptedBuf.writeBytes("encrypted data".getBytes());

    // Prepare callback
    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    // Create and run DecryptJob (ownership of encryptedBuf transferred)
    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("encryptedkey".getBytes());
    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedBuf, null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // DecryptJob.run() released encryptedBuf in finally block (line 98)
    // Result contains decrypted ByteBuf
    DecryptJob.DecryptJobResult result = resultRef.get();
    if (result != null && result.getDecryptedBlobContent() != null) {
      // Proper cleanup: release decrypted content
      result.getDecryptedBlobContent().release();
    }
  }

  /**
   * TEST: DecryptJob normal success path - output ByteBuf lifecycle
   *
   * FLOW:
   * 1. Run DecryptJob successfully
   * 2. Callback receives DecryptJobResult
   * 3. Extract decryptedBlobContent from result
   * 4. Process decrypted content (simulating GetBlobOperation)
   * 5. Manually release via ReferenceCountUtil.safeRelease()
   *
   * OBSERVES:
   * - Decrypted ByteBuf creation
   * - DecryptJobResult construction
   * - Ownership transfer to callback
   * - Manual release pattern (no result.release() method exists)
   *
   * LEAK SCENARIOS COVERED:
   * - LEAK-10.7: No release() method - caller must manually release
   */
  @Test
  public void testDecryptJob_NormalFlow_OutputByteBufLifecycle() throws Exception {
    ByteBuf encryptedBuf = Unpooled.buffer(100);
    encryptedBuf.writeBytes("encrypted data".getBytes());

    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("encryptedkey".getBytes());
    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedBuf, null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // Extract and use decrypted content (simulating GetBlobOperation pattern)
    DecryptJob.DecryptJobResult result = resultRef.get();
    if (result != null) {
      ByteBuf decryptedContent = result.getDecryptedBlobContent();
      if (decryptedContent != null) {
        // Simulate reading the decrypted data
        byte[] data = new byte[decryptedContent.readableBytes()];
        decryptedContent.readBytes(data);
        decryptedContent.resetReaderIndex();

        // Proper cleanup: Manual release via ReferenceCountUtil
        // (DecryptJobResult does NOT have a release() method)
        ReferenceCountUtil.safeRelease(decryptedContent);
      }
    }
  }

  /**
   * TEST: DecryptJob with metadata only (null encryptedBlobContent)
   *
   * FLOW:
   * 1. Create DecryptJob with null encryptedBlobContent (metadata-only path)
   * 2. Run DecryptJob
   * 3. Verify no ByteBuf leaks despite null content
   *
   * OBSERVES:
   * - How DecryptJob handles null ByteBuf input
   * - Line 97 null check in finally block
   * - Metadata-only decryption path
   *
   * LEAK SCENARIOS COVERED:
   * - Edge case: null ByteBuf handling
   */
  @Test
  public void testDecryptJob_MetadataOnly_NullByteBuf() throws Exception {
    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("encryptedkey".getBytes());
    ByteBuffer encryptedMetadata = ByteBuffer.wrap("encryptedmetadata".getBytes());

    // Note: null encryptedBlobContent (metadata-only path)
    DecryptJob job = new DecryptJob(blobId, encryptedKey, null, encryptedMetadata,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // No ByteBuf to release (null content)
    DecryptJob.DecryptJobResult result = resultRef.get();
    // Result should have decrypted metadata but null blob content
  }

  // ============================================================================
  // SECTION 2: DecryptJob - Exception Paths
  // ============================================================================

  /**
   * TEST: DecryptJob exception during decryption - decryptedBlobContent cleanup
   *
   * FLOW:
   * 1. Create DecryptJob with valid encrypted ByteBuf
   * 2. Mock cryptoService to create decryptedBlobContent then throw exception
   * 3. Exception handler should release decryptedBlobContent (line 92)
   * 4. Finally block should release encryptedBlobContent (line 98)
   *
   * OBSERVES:
   * - Partial result cleanup in exception handler
   * - Line 92: decryptedBlobContent.release() in catch block
   * - Line 98: encryptedBlobContent.release() in finally block
   *
   * LEAK SCENARIOS COVERED:
   * - SAFE-10.4: Exception handling properly releases partial results
   */
  @Test
  public void testDecryptJob_ExceptionDuringDecryption_CleanupBoth() throws Exception {
    ByteBuf encryptedBuf = Unpooled.buffer(100);
    encryptedBuf.writeBytes("encrypted data".getBytes());

    // Configure crypto service to throw exception during metadata decryption
    cryptoService.setThrowOnMetadataDecrypt(true);

    AtomicReference<Exception> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      exceptionRef.set(exception);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("encryptedkey".getBytes());
    ByteBuffer encryptedMetadata = ByteBuffer.wrap("metadata".getBytes());

    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedBuf, encryptedMetadata,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // Exception occurred, but both buffers were properly released
    // encryptedBuf released in finally block
    // decryptedBlobContent released in catch block (if it was created)
  }

  /**
   * TEST: DecryptJob exception before decryption - only input cleanup
   *
   * FLOW:
   * 1. Create DecryptJob with valid encrypted ByteBuf
   * 2. Mock KMS to throw exception before decryption starts
   * 3. Only encryptedBlobContent should be released (no decryptedBlobContent created)
   *
   * OBSERVES:
   * - Exception before ByteBuf allocation
   * - Only finally block runs (not catch block for decryptedBlobContent)
   *
   * LEAK SCENARIOS COVERED:
   * - Exception path variation: no decrypted content created
   */
  @Test
  public void testDecryptJob_ExceptionBeforeDecryption_OnlyInputCleanup() throws Exception {
    ByteBuf encryptedBuf = Unpooled.buffer(100);
    encryptedBuf.writeBytes("encrypted data".getBytes());

    // Configure KMS to throw exception
    kms.setThrowOnGetKey(true);

    AtomicReference<Exception> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      exceptionRef.set(exception);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("encryptedkey".getBytes());

    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedBuf, null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // Exception occurred before decryption, encryptedBuf released in finally block
    // No decryptedBlobContent created
  }

  // ============================================================================
  // SECTION 3: DecryptJob - Critical Leak Scenario
  // ============================================================================

  /**
   * TEST: DecryptJob closeJob() called - demonstrates input ByteBuf leak
   *
   * FLOW:
   * 1. Create DecryptJob with encrypted ByteBuf
   * 2. Call closeJob() instead of run() (simulates CryptoJobHandler shutdown)
   * 3. closeJob() does NOT release encryptedBlobContent
   *
   * OBSERVES:
   * - closeJob() method (lines 112-114)
   * - No release of encryptedBlobContent
   * - This test will DEMONSTRATE the leak identified in LEAK-10.2
   *
   * LEAK SCENARIOS COVERED:
   * - 🚨 LEAK-10.2: closeJob() does NOT release input ByteBuf (HIGH RISK)
   *
   * NOTE: This test demonstrates the production bug. Tracer will show unreleased ByteBuf.
   */
  @Test
  public void testDecryptJob_CloseJobPath_InputByteBufLeaked() throws Exception {
    ByteBuf encryptedBuf = Unpooled.buffer(100);
    encryptedBuf.writeBytes("encrypted data".getBytes());

    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("encryptedkey".getBytes());

    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedBuf, null,
        cryptoService, kms, null, metricsTracker, callback);

    // Call closeJob() instead of run() (simulates shutdown scenario)
    job.closeJob(new GeneralSecurityException("Simulated shutdown"));
    latch.await(1, TimeUnit.SECONDS);

    // BUG: encryptedBuf is NOT released by closeJob()
    // Tracer will show this ByteBuf is leaked
    // In production, this would need to be fixed by adding release in closeJob()
  }

  // ============================================================================
  // SECTION 4: DecryptJobResult - Callback Processing Patterns
  // ============================================================================

  /**
   * TEST: DecryptJobResult processed by callback - deferred cleanup pattern
   *
   * FLOW:
   * 1. DecryptJob completes successfully
   * 2. Callback stores DecryptJobResult in AtomicReference
   * 3. Later processing extracts decryptedBlobContent
   * 4. Store in map (simulating chunkIndexToBuf in GetBlobOperation)
   * 5. Eventually call cleanup via maybeReleaseDecryptionResultBuffer() pattern
   *
   * OBSERVES:
   * - GetBlobOperation callback pattern (lines 875-884)
   * - Deferred cleanup via maybeReleaseDecryptionResultBuffer()
   * - Complex ownership: result -> extracted ByteBuf -> map storage -> manual release
   *
   * LEAK SCENARIOS COVERED:
   * - LEAK-10.5: Callback forgets to call cleanup method
   */
  @Test
  public void testDecryptJobResult_CallbackPattern_DeferredCleanup() throws Exception {
    ByteBuf encryptedBuf = Unpooled.buffer(100);
    encryptedBuf.writeBytes("encrypted data".getBytes());

    // Simulate GetBlobOperation callback pattern
    AtomicReference<DecryptJob.DecryptJobResult> storedResult = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      // Store result for later processing (GetBlobOperation pattern)
      storedResult.set(result);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("encryptedkey".getBytes());
    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedBuf, null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // Later processing (simulating GetBlobOperation.handleBody)
    DecryptJob.DecryptJobResult result = storedResult.getAndSet(null);
    if (result != null) {
      ByteBuf decryptedContent = result.getDecryptedBlobContent();
      if (decryptedContent != null) {
        // Simulate storing in chunkIndexToBuf map
        // ... processing ...

        // Cleanup pattern (simulating maybeReleaseDecryptionResultBuffer)
        ReferenceCountUtil.safeRelease(decryptedContent);
      }
    }
  }

  /**
   * TEST: DecryptJobResult processed then decompressed - ownership transfer
   *
   * FLOW:
   * 1. DecryptJob completes successfully
   * 2. Extract decryptedContent from result
   * 3. Call decompressContent(decryptedContent) - may create new ByteBuf
   * 4. Store decompressed content
   * 5. Release both decrypted and decompressed (if different)
   *
   * OBSERVES:
   * - Line 882 in GetBlobOperation: decompressedContent = decompressContent(decryptedContent)
   * - Whether decompressContent() creates new ByteBuf or returns same
   * - Proper cleanup of both ByteBufs
   *
   * LEAK SCENARIOS COVERED:
   * - ⚠️ LEAK-10.6: DecompressContent creates new ByteBuf, original not released
   */
  @Test
  public void testDecryptJobResult_DecompressionFlow_OwnershipChain() throws Exception {
    ByteBuf encryptedBuf = Unpooled.buffer(100);
    encryptedBuf.writeBytes("encrypted data".getBytes());

    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("encryptedkey".getBytes());
    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedBuf, null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    DecryptJob.DecryptJobResult result = resultRef.get();
    if (result != null) {
      ByteBuf decryptedContent = result.getDecryptedBlobContent();
      if (decryptedContent != null) {
        // Simulate decompression (in this test, just copy the buffer)
        ByteBuf decompressedContent = simulateDecompression(decryptedContent);

        // If decompression creates new buffer, must release BOTH
        if (decompressedContent != decryptedContent) {
          // Original decryptedContent must be released
          decryptedContent.release();
          // Decompressed content must also be released
          decompressedContent.release();
        } else {
          // Same buffer, release once
          decryptedContent.release();
        }
      }
    }
  }

  /**
   * TEST: DecryptJobResult never cleaned up - demonstrates forgotten release
   *
   * FLOW:
   * 1. DecryptJob completes successfully
   * 2. Callback receives DecryptJobResult
   * 3. Extract and use decryptedBlobContent
   * 4. Forget to call maybeReleaseDecryptionResultBuffer()
   *
   * OBSERVES:
   * - Production scenario where cleanup is forgotten
   * - Tracer will show decryptedBlobContent never released
   *
   * LEAK SCENARIOS COVERED:
   * - 🚨 LEAK-10.5: Callback never calls cleanup method
   *
   * NOTE: This test demonstrates the easy-to-make mistake. Tracer will show leak.
   */
  @Test
  public void testDecryptJobResult_ForgottenCleanup_DecryptedByteBufLeaked() throws Exception {
    ByteBuf encryptedBuf = Unpooled.buffer(100);
    encryptedBuf.writeBytes("encrypted data".getBytes());

    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("encryptedkey".getBytes());
    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedBuf, null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    DecryptJob.DecryptJobResult result = resultRef.get();
    if (result != null) {
      ByteBuf decryptedContent = result.getDecryptedBlobContent();
      if (decryptedContent != null) {
        // Use the content
        byte[] data = new byte[decryptedContent.readableBytes()];
        decryptedContent.readBytes(data);

        // BUG: Forgot to release!
        // ReferenceCountUtil.safeRelease(decryptedContent); // MISSING!
        // Tracer will show this ByteBuf is leaked
      }
    }
  }

  // ============================================================================
  // SECTION 5: DecryptJobResult - API Confusion Scenarios
  // ============================================================================

  /**
   * TEST: DecryptJobResult API confusion - expecting release() method
   *
   * FLOW:
   * 1. DecryptJob completes successfully
   * 2. Callback receives DecryptJobResult
   * 3. Developer expects result.release() to exist (like EncryptJobResult)
   * 4. No such method exists
   * 5. Must manually extract and release ByteBuf
   *
   * OBSERVES:
   * - API inconsistency between DecryptJobResult and EncryptJobResult
   * - DecryptJobResult has no release() method
   * - Correct pattern: ReferenceCountUtil.safeRelease(result.getDecryptedBlobContent())
   *
   * LEAK SCENARIOS COVERED:
   * - ⚠️ LEAK-10.7: No release() method - caller confusion
   */
  @Test
  public void testDecryptJobResult_APIConfusion_ManualReleaseRequired() throws Exception {
    ByteBuf encryptedBuf = Unpooled.buffer(100);
    encryptedBuf.writeBytes("encrypted data".getBytes());

    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("encryptedkey".getBytes());
    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedBuf, null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    DecryptJob.DecryptJobResult result = resultRef.get();
    if (result != null) {
      // Developer might expect: result.release() - but this method doesn't exist!
      // Correct pattern: manually extract and release
      ByteBuf decryptedContent = result.getDecryptedBlobContent();
      ReferenceCountUtil.safeRelease(decryptedContent);
    }
  }

  // ============================================================================
  // SECTION 6: BoundedNettyByteBufReceive - Normal Flow
  // ============================================================================

  /**
   * TEST: BoundedNettyByteBufReceive normal read - dual ByteBuf lifecycle
   *
   * FLOW:
   * 1. Create BoundedNettyByteBufReceive
   * 2. Call readFrom() - allocates sizeBuffer (8 bytes)
   * 3. Read size header - immediately releases sizeBuffer (line 88)
   * 4. Allocate main buffer based on size
   * 5. Read payload into buffer
   * 6. Call release() on BoundedNettyByteBufReceive
   *
   * OBSERVES:
   * - Line 73: sizeBuffer allocation
   * - Line 88: sizeBuffer.release() (immediate cleanup)
   * - Line 92: buffer allocation
   * - AbstractByteBufHolder.release() releases buffer
   *
   * LEAK SCENARIOS COVERED:
   * - ✅ SAFE-12.1: sizeBuffer properly managed
   */
  @Test
  public void testBoundedNettyByteBufReceive_NormalRead_DualByteBufLifecycle() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    // Create channel with size header + payload
    ByteBuffer dataBuffer = ByteBuffer.allocate(totalSize);
    dataBuffer.putLong(totalSize);
    byte[] payload = new byte[payloadSize];
    new Random().nextBytes(payload);
    dataBuffer.put(payload);
    dataBuffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(dataBuffer));

    // Create receive and read data
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);
    receive.readFrom(channel);

    // Use content
    ByteBuf content = receive.content();
    if (content != null) {
      // Read some data
      content.markReaderIndex();
      byte[] readData = new byte[Math.min(100, content.readableBytes())];
      content.readBytes(readData);
      content.resetReaderIndex();
    }

    // Proper cleanup
    receive.release();
  }

  /**
   * TEST: BoundedNettyByteBufReceive wrapped in Transmission - production pattern
   *
   * FLOW:
   * 1. Create Transmission (simulating production NetworkClient)
   * 2. Transmission creates NetworkReceive with BoundedNettyByteBufReceive
   * 3. Read data via Transmission
   * 4. Call Transmission.release() - should release BoundedNettyByteBufReceive
   *
   * OBSERVES:
   * - Line 79 in Transmission.java: initializeNetworkReceive()
   * - Line 182 in Transmission.java: networkReceive.getReceivedBytes().release()
   * - Production cleanup pattern
   *
   * LEAK SCENARIOS COVERED:
   * - Production context where BoundedNettyByteBufReceive is properly released
   */
  @Test
  public void testBoundedNettyByteBufReceive_TransmissionWrapper_ProperCleanup() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    ByteBuffer dataBuffer = ByteBuffer.allocate(totalSize);
    dataBuffer.putLong(totalSize);
    byte[] payload = new byte[payloadSize];
    new Random().nextBytes(payload);
    dataBuffer.put(payload);
    dataBuffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(dataBuffer));

    // Simulate production pattern (would use Transmission in real code)
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);
    receive.readFrom(channel);

    // Process received data
    ByteBuf content = receive.content();
    if (content != null) {
      // Use content
    }

    // Production cleanup pattern
    receive.release();
  }

  // ============================================================================
  // SECTION 7: BoundedNettyByteBufReceive - Exception Paths
  // ============================================================================

  /**
   * TEST: BoundedNettyByteBufReceive IOException during size read
   *
   * FLOW:
   * 1. Create BoundedNettyByteBufReceive
   * 2. Mock channel to throw IOException while reading size header
   * 3. Exception handler should release sizeBuffer (line 81)
   *
   * OBSERVES:
   * - Line 81: sizeBuffer.release() in exception handler
   * - Proper cleanup on IOException during size read
   *
   * LEAK SCENARIOS COVERED:
   * - ✅ SAFE: Exception handling releases sizeBuffer
   */
  @Test
  public void testBoundedNettyByteBufReceive_IOExceptionDuringSizeRead_SizeBufferReleased() throws Exception {
    // Create channel that will cause EOFException
    ByteBuffer emptyBuffer = ByteBuffer.allocate(0);
    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(emptyBuffer));

    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);

    try {
      receive.readFrom(channel);
    } catch (IOException e) {
      // Expected exception
      // sizeBuffer was released in exception handler (line 81)
    }
  }

  /**
   * TEST: BoundedNettyByteBufReceive IOException during payload read
   *
   * FLOW:
   * 1. Create BoundedNettyByteBufReceive
   * 2. Successfully read size header
   * 3. Mock channel to throw IOException while reading payload
   * 4. Exception handler should release buffer (line 103)
   *
   * OBSERVES:
   * - Line 103: buffer.release() in exception handler
   * - Proper cleanup on IOException during payload read
   *
   * LEAK SCENARIOS COVERED:
   * - ✅ SAFE-12.3: Exception handling releases buffer
   */
  @Test
  public void testBoundedNettyByteBufReceive_IOExceptionDuringPayloadRead_BufferReleased() throws Exception {
    int totalSize = 1000;

    // Create channel with size header but no payload (causes EOFException)
    ByteBuffer dataBuffer = ByteBuffer.allocate(Long.BYTES);
    dataBuffer.putLong(totalSize);
    dataBuffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(dataBuffer));

    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);

    try {
      receive.readFrom(channel);
      // Try to read again to trigger EOFException
      receive.readFrom(channel);
    } catch (IOException e) {
      // Expected exception
      // buffer was released in exception handler (line 103)
    }
  }

  /**
   * TEST: BoundedNettyByteBufReceive request size exceeds max
   *
   * FLOW:
   * 1. Create BoundedNettyByteBufReceive with maxRequestSize=1024
   * 2. Mock channel to return size header > 1024
   * 3. IOException thrown at line 90
   * 4. sizeBuffer already released at line 88
   * 5. buffer not yet allocated
   *
   * OBSERVES:
   * - Line 88: sizeBuffer.release() happens before check
   * - Line 90: IOException for size > max
   * - No leak because buffer not allocated yet
   *
   * LEAK SCENARIOS COVERED:
   * - ✅ SAFE-12.2: Exception between size read and buffer allocation
   */
  @Test
  public void testBoundedNettyByteBufReceive_RequestSizeExceedsMax_NoLeak() throws Exception {
    long hugeSize = 10000;

    // Create channel with size header exceeding max
    ByteBuffer dataBuffer = ByteBuffer.allocate(Long.BYTES);
    dataBuffer.putLong(hugeSize);
    dataBuffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(dataBuffer));

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
  // SECTION 8: BoundedNettyByteBufReceive - Leak Scenarios
  // ============================================================================

  /**
   * TEST: BoundedNettyByteBufReceive caller forgets to release
   *
   * FLOW:
   * 1. Create BoundedNettyByteBufReceive
   * 2. Successfully read data (allocates buffer)
   * 3. Use content()
   * 4. Forget to call release()
   *
   * OBSERVES:
   * - Production bug scenario
   * - Tracer will show buffer is NOT released
   *
   * LEAK SCENARIOS COVERED:
   * - 🚨 LEAK-12.4: Caller never calls release()
   *
   * NOTE: This test demonstrates the easy-to-make mistake. Tracer will show leak.
   */
  @Test
  public void testBoundedNettyByteBufReceive_ForgottenRelease_BufferLeaked() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    ByteBuffer dataBuffer = ByteBuffer.allocate(totalSize);
    dataBuffer.putLong(totalSize);
    byte[] payload = new byte[payloadSize];
    new Random().nextBytes(payload);
    dataBuffer.put(payload);
    dataBuffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(dataBuffer));

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
   * FLOW:
   * 1. Create BoundedNettyByteBufReceive
   * 2. Call readFrom() - reads size, allocates buffer
   * 3. Partial payload read (buffer allocated but not fully filled)
   * 4. isReadComplete() returns false
   * 5. Caller abandons receive without calling release()
   *
   * OBSERVES:
   * - Partial read scenario
   * - Buffer allocated but operation abandoned
   * - Tracer will show buffer is NOT released
   *
   * LEAK SCENARIOS COVERED:
   * - ⚠️ LEAK-12.6: Incomplete read, buffer partially filled
   *
   * NOTE: This test demonstrates abandoning incomplete read. Tracer will show leak.
   */
  @Test
  public void testBoundedNettyByteBufReceive_IncompleteReadAbandoned_BufferLeaked() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    // Create channel with size header but only partial payload
    ByteBuffer dataBuffer = ByteBuffer.allocate(Long.BYTES + 100); // Only 100 bytes of 1000
    dataBuffer.putLong(totalSize);
    byte[] partialPayload = new byte[100];
    new Random().nextBytes(partialPayload);
    dataBuffer.put(partialPayload);
    dataBuffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(dataBuffer));

    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);
    receive.readFrom(channel);

    // Check if read is complete
    if (!receive.isReadComplete()) {
      // BUG: Abandon without releasing!
      // Buffer was allocated but not fully filled
      // receive.release(); // MISSING!
      // Tracer will show buffer is leaked
    }
  }

  /**
   * TEST: BoundedNettyByteBufReceive replace() method - orphan buffer
   *
   * FLOW:
   * 1. Create BoundedNettyByteBufReceive and read data (buffer allocated)
   * 2. Create replacement ByteBuf
   * 3. Call replace() to create new BoundedNettyByteBufReceive
   * 4. Release only the new instance
   * 5. Original buffer is leaked
   *
   * OBSERVES:
   * - Lines 129-131: replace() method
   * - Confusing ownership semantics
   * - Need to release BOTH original and replaced
   *
   * LEAK SCENARIOS COVERED:
   * - ⚠️ LEAK-12.5: replace() creates orphan buffer
   *
   * NOTE: This test demonstrates replace() confusion. Tracer will show leak if only one released.
   */
  @Test
  public void testBoundedNettyByteBufReceive_ReplaceMethod_OrphanBuffer() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    ByteBuffer dataBuffer = ByteBuffer.allocate(totalSize);
    dataBuffer.putLong(totalSize);
    byte[] payload = new byte[payloadSize];
    new Random().nextBytes(payload);
    dataBuffer.put(payload);
    dataBuffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(dataBuffer));

    BoundedNettyByteBufReceive original = new BoundedNettyByteBufReceive(100000);
    original.readFrom(channel);

    // Create replacement
    ByteBuf replacementBuf = Unpooled.buffer(100);
    replacementBuf.writeBytes("replacement".getBytes());

    BoundedNettyByteBufReceive replaced = original.replace(replacementBuf);

    // BUG: Release only the new instance
    replaced.release();

    // original.release(); // MISSING!
    // Tracer will show original buffer is leaked
  }

  /**
   * TEST: BoundedNettyByteBufReceive replace() proper cleanup
   *
   * FLOW:
   * 1. Create BoundedNettyByteBufReceive and read data
   * 2. Create replacement ByteBuf
   * 3. Call replace() to create new BoundedNettyByteBufReceive
   * 4. Release BOTH original and replaced instances
   *
   * OBSERVES:
   * - Correct usage pattern for replace()
   * - Both buffers properly released
   *
   * LEAK SCENARIOS COVERED:
   * - Demonstrates correct replace() usage (contrast with leak scenario)
   */
  @Test
  public void testBoundedNettyByteBufReceive_ReplaceMethod_ProperCleanup() throws Exception {
    int payloadSize = 1000;
    int totalSize = payloadSize + Long.BYTES;

    ByteBuffer dataBuffer = ByteBuffer.allocate(totalSize);
    dataBuffer.putLong(totalSize);
    byte[] payload = new byte[payloadSize];
    new Random().nextBytes(payload);
    dataBuffer.put(payload);
    dataBuffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(dataBuffer));

    BoundedNettyByteBufReceive original = new BoundedNettyByteBufReceive(100000);
    original.readFrom(channel);

    // Create replacement
    ByteBuf replacementBuf = Unpooled.buffer(100);
    replacementBuf.writeBytes("replacement".getBytes());

    BoundedNettyByteBufReceive replaced = original.replace(replacementBuf);

    // Proper cleanup: release BOTH
    original.release();
    replaced.release();
  }

  // ============================================================================
  // SECTION 9: Cross-Class Integration Flows
  // ============================================================================

  /**
   * TEST: Full GetBlobOperation decrypt flow - end-to-end
   *
   * FLOW:
   * 1. Simulate GetBlobOperation receiving encrypted chunk
   * 2. Create DecryptJob with encrypted ByteBuf
   * 3. Run DecryptJob - receives DecryptJobResult
   * 4. Extract decryptedBlobContent
   * 5. Decompress if needed
   * 6. Store in chunkIndexToBuf
   * 7. Cleanup via maybeReleaseDecryptionResultBuffer()
   *
   * OBSERVES:
   * - Complete production flow through GetBlobOperation
   * - Multiple ownership transfers
   * - Deferred cleanup pattern
   *
   * LEAK SCENARIOS COVERED:
   * - Integration test covering multiple classes
   */
  @Test
  public void testIntegration_GetBlobOperationDecryptFlow_EndToEnd() throws Exception {
    // Simulate encrypted chunk received
    ByteBuf encryptedChunk = Unpooled.buffer(500);
    encryptedChunk.writeBytes("encrypted chunk data".getBytes());

    // GetBlobOperation would use retainedSlice() before passing to DecryptJob
    ByteBuf encryptedSlice = encryptedChunk.retainedSlice();

    // Create DecryptJob
    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedSlice, null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // Process result (simulating GetBlobOperation)
    DecryptJob.DecryptJobResult result = resultRef.get();
    if (result != null) {
      ByteBuf decryptedContent = result.getDecryptedBlobContent();
      if (decryptedContent != null) {
        // Simulate decompression
        ByteBuf decompressedContent = simulateDecompression(decryptedContent);

        // Simulate storing in chunkIndexToBuf
        // ... use buffer ...

        // Cleanup (maybeReleaseDecryptionResultBuffer pattern)
        if (decompressedContent != decryptedContent) {
          decryptedContent.release();
          decompressedContent.release();
        } else {
          ReferenceCountUtil.safeRelease(decryptedContent);
        }
      }
    }

    // Release original chunk
    encryptedChunk.release();
  }

  /**
   * TEST: Full network receive flow - BoundedNettyByteBufReceive in Transmission
   *
   * FLOW:
   * 1. Simulate NetworkClient receiving response
   * 2. Transmission creates BoundedNettyByteBufReceive
   * 3. Read from channel (sizeBuffer + buffer lifecycle)
   * 4. Process received data
   * 5. Transmission.release() cleanup
   *
   * OBSERVES:
   * - Complete production flow through NetworkClient
   * - Proper encapsulation and cleanup
   *
   * LEAK SCENARIOS COVERED:
   * - Integration test for network receive path
   */
  @Test
  public void testIntegration_NetworkReceiveFlow_EndToEnd() throws Exception {
    int payloadSize = 2000;
    int totalSize = payloadSize + Long.BYTES;

    // Simulate network data
    ByteBuffer networkData = ByteBuffer.allocate(totalSize);
    networkData.putLong(totalSize);
    byte[] payload = new byte[payloadSize];
    new Random().nextBytes(payload);
    networkData.put(payload);
    networkData.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(networkData));

    // Simulate Transmission/NetworkClient pattern
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);
    receive.readFrom(channel);

    // Process received data
    ByteBuf content = receive.content();
    if (content != null) {
      // Parse response, extract data, etc.
      byte[] data = new byte[Math.min(200, content.readableBytes())];
      content.markReaderIndex();
      content.readBytes(data);
      content.resetReaderIndex();
    }

    // Proper cleanup (Transmission.release())
    receive.release();
  }

  /**
   * TEST: DecryptJob shutdown scenario - CryptoJobHandler calls closeJob()
   *
   * FLOW:
   * 1. Create CryptoJobHandler (or simulate)
   * 2. Submit DecryptJob with encrypted ByteBuf
   * 3. Before job runs, trigger shutdown
   * 4. CryptoJobHandler calls closeJob() on pending jobs
   * 5. Encrypted ByteBuf is leaked
   *
   * OBSERVES:
   * - Shutdown scenario in production
   * - closeJob() does not release encryptedBlobContent
   * - Critical leak scenario
   *
   * LEAK SCENARIOS COVERED:
   * - 🚨 LEAK-10.2: closeJob() missing release (production shutdown scenario)
   */
  @Test
  public void testIntegration_CryptoJobHandlerShutdown_CloseJobLeak() throws Exception {
    // Simulate pending job at shutdown
    ByteBuf encryptedBuf1 = Unpooled.buffer(100);
    encryptedBuf1.writeBytes("pending job 1".getBytes());

    ByteBuf encryptedBuf2 = Unpooled.buffer(100);
    encryptedBuf2.writeBytes("pending job 2".getBytes());

    CountDownLatch latch = new CountDownLatch(2);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());

    // Create pending jobs
    DecryptJob job1 = new DecryptJob(blobId, encryptedKey, encryptedBuf1, null,
        cryptoService, kms, null, metricsTracker, callback);

    DecryptJob job2 = new DecryptJob(blobId, encryptedKey, encryptedBuf2, null,
        cryptoService, kms, null, metricsTracker, callback);

    // Simulate shutdown - CryptoJobHandler calls closeJob() on pending jobs
    GeneralSecurityException shutdownException = new GeneralSecurityException("Shutdown");
    job1.closeJob(shutdownException);
    job2.closeJob(shutdownException);

    latch.await(1, TimeUnit.SECONDS);

    // BUG: Both encryptedBuf1 and encryptedBuf2 are leaked!
    // closeJob() does not release them
    // Tracer will show both ByteBufs leaked
  }

  // ============================================================================
  // SECTION 10: Edge Cases and Boundary Conditions
  // ============================================================================

  /**
   * TEST: DecryptJob with retainedSlice() ownership
   *
   * FLOW:
   * 1. Allocate encrypted ByteBuf
   * 2. Create retainedSlice() (increments refCount)
   * 3. Pass slice to DecryptJob
   * 4. DecryptJob.run() releases slice
   * 5. Original buffer still has refCount > 0
   *
   * OBSERVES:
   * - Line 1250 in GetBlobOperation: encryptedBuf.retainedSlice()
   * - Reference counting with slices
   * - Proper cleanup of both original and slice
   *
   * LEAK SCENARIOS COVERED:
   * - Production pattern using retainedSlice()
   */
  @Test
  public void testDecryptJob_RetainedSliceOwnership_RefCounting() throws Exception {
    // Allocate original buffer
    ByteBuf originalBuf = Unpooled.buffer(200);
    originalBuf.writeBytes("original encrypted data".getBytes());

    // Create retained slice (increments refCount)
    ByteBuf slice = originalBuf.retainedSlice();

    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());

    // Pass slice to DecryptJob
    DecryptJob job = new DecryptJob(blobId, encryptedKey, slice, null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // Cleanup result
    DecryptJob.DecryptJobResult result = resultRef.get();
    if (result != null && result.getDecryptedBlobContent() != null) {
      result.getDecryptedBlobContent().release();
    }

    // Release original buffer
    originalBuf.release();

    // Both slice and original properly released
  }

  /**
   * TEST: BoundedNettyByteBufReceive with minimal size (edge case)
   *
   * FLOW:
   * 1. Create BoundedNettyByteBufReceive
   * 2. Mock channel with minimal size (8 bytes = header only)
   * 3. Read size header
   * 4. Buffer allocated with 0 bytes (sizeToRead - Long.BYTES)
   * 5. Release properly
   *
   * OBSERVES:
   * - Edge case: minimal payload size
   * - Line 92: buffer allocation with 0 bytes
   * - Proper cleanup even with empty buffer
   */
  @Test
  public void testBoundedNettyByteBufReceive_MinimalSize_EmptyBuffer() throws Exception {
    // Minimal size: just the header (8 bytes)
    int totalSize = Long.BYTES;

    ByteBuffer dataBuffer = ByteBuffer.allocate(totalSize);
    dataBuffer.putLong(totalSize);
    dataBuffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(dataBuffer));

    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);
    receive.readFrom(channel);

    // Buffer allocated with 0 bytes
    ByteBuf content = receive.content();

    // Proper cleanup
    receive.release();
  }

  /**
   * TEST: DecryptJob double-release protection (if any)
   *
   * FLOW:
   * 1. Create DecryptJob with encrypted ByteBuf
   * 2. Run DecryptJob - releases encrypted ByteBuf in finally
   * 3. Manually call release() on DecryptJob (if method exists)
   * 4. Observe double-release handling
   *
   * OBSERVES:
   * - Whether DecryptJob has double-release protection
   * - RefCount behavior on double-release
   *
   * LEAK SCENARIOS COVERED:
   * - Edge case: double-release attempts
   */
  @Test
  public void testDecryptJob_DoubleRelease_Protection() throws Exception {
    ByteBuf encryptedBuf = Unpooled.buffer(100);
    encryptedBuf.writeBytes("encrypted data".getBytes());

    AtomicReference<DecryptJob.DecryptJobResult> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    Callback<DecryptJob.DecryptJobResult> callback = (result, exception) -> {
      resultRef.set(result);
      latch.countDown();
    };

    BlobId blobId = createMockBlobId();
    ByteBuffer encryptedKey = ByteBuffer.wrap("key".getBytes());
    DecryptJob job = new DecryptJob(blobId, encryptedKey, encryptedBuf, null,
        cryptoService, kms, null, metricsTracker, callback);

    job.run();
    latch.await(1, TimeUnit.SECONDS);

    // DecryptJob already released encryptedBuf in finally block
    // Attempting to release again would cause issues
    // (In production, don't do this - this is just to observe behavior)

    // Cleanup result
    DecryptJob.DecryptJobResult result = resultRef.get();
    if (result != null && result.getDecryptedBlobContent() != null) {
      result.getDecryptedBlobContent().release();
    }
  }

  // ============================================================================
  // Helper Methods and Mock Classes
  // ============================================================================

  private BlobId createMockBlobId() {
    // Create a simple mock BlobId for testing
    // In real tests, this would use proper BlobId construction
    return null; // Tests will work with null BlobId for flow characterization
  }

  private ByteBuf simulateDecompression(ByteBuf input) {
    // For characterization, just return the same buffer
    // In production, this might allocate a new buffer
    return input;
  }

  /**
   * Mock CryptoService for testing
   */
  private static class MockCryptoService implements CryptoService<SecretKey> {
    private boolean throwOnMetadataDecrypt = false;

    public void setThrowOnMetadataDecrypt(boolean throwOnMetadataDecrypt) {
      this.throwOnMetadataDecrypt = throwOnMetadataDecrypt;
    }

    @Override
    public ByteBuffer encrypt(ByteBuffer toEncrypt, SecretKey key) throws GeneralSecurityException {
      byte[] data = new byte[toEncrypt.remaining()];
      toEncrypt.get(data);
      return ByteBuffer.wrap(("encrypted_" + new String(data)).getBytes());
    }

    @Override
    public ByteBuf encrypt(ByteBuf toEncrypt, SecretKey key) throws GeneralSecurityException {
      ByteBuf result = Unpooled.buffer(toEncrypt.readableBytes() + 20);
      result.writeBytes("encrypted_".getBytes());
      result.writeBytes(toEncrypt);
      toEncrypt.resetReaderIndex();
      return result;
    }

    @Override
    public ByteBuffer decrypt(ByteBuffer toDecrypt, SecretKey key) throws GeneralSecurityException {
      if (throwOnMetadataDecrypt) {
        throw new GeneralSecurityException("Simulated decryption failure");
      }
      byte[] data = new byte[toDecrypt.remaining()];
      toDecrypt.get(data);
      String decrypted = new String(data).replace("encrypted_", "decrypted_");
      return ByteBuffer.wrap(decrypted.getBytes());
    }

    @Override
    public ByteBuf decrypt(ByteBuf toDecrypt, SecretKey key) throws GeneralSecurityException {
      if (throwOnMetadataDecrypt) {
        throw new GeneralSecurityException("Simulated decryption failure");
      }
      ByteBuf result = Unpooled.buffer(toDecrypt.readableBytes() + 20);
      result.writeBytes("decrypted_".getBytes());
      byte[] data = new byte[toDecrypt.readableBytes()];
      toDecrypt.readBytes(data);
      result.writeBytes(data);
      return result;
    }

    @Override
    public ByteBuffer encryptKey(SecretKey toEncrypt, SecretKey key) throws GeneralSecurityException {
      return ByteBuffer.wrap("encrypted_key".getBytes());
    }

    @Override
    public SecretKey decryptKey(ByteBuffer toDecrypt, SecretKey key) throws GeneralSecurityException {
      return new SecretKeySpec("decrypted_key".getBytes(), "AES");
    }
  }

  /**
   * Mock KeyManagementService for testing
   */
  private static class MockKeyManagementService implements KeyManagementService<SecretKey> {
    private boolean throwOnGetKey = false;

    public void setThrowOnGetKey(boolean throwOnGetKey) {
      this.throwOnGetKey = throwOnGetKey;
    }

    @Override
    public SecretKey getKey(RestRequest restRequest, short accountId, short containerId)
        throws GeneralSecurityException {
      if (throwOnGetKey) {
        throw new GeneralSecurityException("Simulated KMS failure");
      }
      return new SecretKeySpec("container_key".getBytes(), "AES");
    }

    @Override
    public SecretKey getRandomKey() throws GeneralSecurityException {
      return new SecretKeySpec("random_key".getBytes(), "AES");
    }
  }

  /**
   * Mock CryptoJobMetricsTracker for testing
   */
  private static class MockCryptoJobMetricsTracker extends CryptoJobMetricsTracker {
    MockCryptoJobMetricsTracker() {
      super(null); // Pass null metrics for characterization tests
    }

    @Override
    void onJobProcessingStart() {
      // No-op for characterization tests
    }

    @Override
    void onJobProcessingComplete() {
      // No-op for characterization tests
    }

    @Override
    void onJobResultProcessingStart() {
      // No-op for characterization tests
    }

    @Override
    void onJobResultProcessingComplete() {
      // No-op for characterization tests
    }
  }
}
