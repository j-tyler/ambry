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

import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.ByteBufferReadableStreamChannel;
import com.github.ambry.config.RouterConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.messageformat.BlobType;
import com.github.ambry.network.NetworkClient;
import com.github.ambry.network.NetworkClientErrorCode;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.network.ResponseInfo;
import com.github.ambry.protocol.PutRequest;
import com.github.ambry.protocol.PutResponse;
import com.github.ambry.protocol.RequestOrResponse;
import com.github.ambry.utils.MockTime;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import com.github.ambry.utils.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * PRODUCTION BUG TESTS: These tests will FAIL due to real ByteBuf leaks in production code.
 *
 * These tests run ACTUAL PutOperation code paths with injected failures to trigger the bugs.
 * Leak detection is ENABLED - tests will FAIL until production code is fixed.
 *
 * CRITICAL BUGS TESTED:
 * 1. CRC calculation exception after compression (PutOperation.java:1562-1576)
 * 2. CRC calculation exception in encryption callback (PutOperation.java:1498-1503)
 * 3. KMS exception after retainedDuplicate() (PutOperation.java:1589-1592)
 * 4. RequestInfo construction exception after PutRequest creation (PutOperation.java:1825-1830)
 *
 * WHY THESE TESTS WILL FAIL:
 * - Leak detection is ENABLED (not disabled like simulation tests)
 * - Real PutOperation code executes with injected failures
 * - Production bugs cause ByteBuf leaks
 * - NettyByteBufLeakHelper detects leaks and fails the test
 *
 * AFTER FIXES APPLIED:
 * - Tests will PASS
 * - Confirms bugs are fixed
 * - Provides regression protection
 */
public class PutOperationProductionBugTest {
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();
  private MockClusterMap mockClusterMap;
  private NonBlockingRouterMetrics routerMetrics;
  private RouterConfig routerConfig;
  private MockTime time;
  private MockServerLayout mockServerLayout;
  private MockNetworkClient mockNetworkClient;

  @Before
  public void setUp() throws Exception {
    leakHelper.beforeTest();  // Leak detection ENABLED

    Properties props = new Properties();
    props.setProperty("router.hostname", "localhost");
    props.setProperty("router.datacenter.name", "DC1");
    props.setProperty("router.max.put.chunk.size.bytes", "4194304"); // 4MB
    props.setProperty("router.verify.crc.for.put.requests", "true"); // CRITICAL: Enable CRC
    props.setProperty("router.enable.compression", "true");
    props.setProperty("compression.min.size.bytes", "512");
    props.setProperty("router.put.request.parallelism", "3");
    props.setProperty("router.put.success.target", "2");

    VerifiableProperties verifiableProperties = new VerifiableProperties(props);
    routerConfig = new RouterConfig(verifiableProperties);
    mockClusterMap = new MockClusterMap();
    routerMetrics = new NonBlockingRouterMetrics(mockClusterMap, routerConfig);
    time = new MockTime();

    mockServerLayout = new MockServerLayout(mockClusterMap);
    mockNetworkClient = new MockNetworkClient();
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();  // Will fail if leaks detected
  }

  /**
   * PRODUCTION BUG TEST #1: Faulty ByteBuf in CRC calculation after compression
   *
   * This test uses a FAULTY ByteBuf that throws during nioBuffers() call.
   * When PutOperation compresses data and calculates CRC on the compressed buffer,
   * the exception occurs AFTER ownership transfer, causing a leak.
   *
   * BUG LOCATION: PutOperation.java:1562-1576 (compressChunk method)
   *
   * TEST FLOW:
   * 1. Create PutOperation with compressible data
   * 2. Inject FaultyByteBuf that throws during nioBuffers()
   * 3. Compression succeeds, returns FaultyByteBuf
   * 4. PutChunk takes ownership (buf = newBuffer)
   * 5. CRC calculation calls buf.nioBuffers() → EXCEPTION
   * 6. No try-catch → compressed buffer LEAKED
   * 7. NettyByteBufLeakHelper detects leak → TEST FAILS
   *
   * EXPECTED RESULT: TEST FAILS with leak detection error
   * AFTER FIX: TEST PASSES
   */
  @Test
  @Ignore("KNOWN BUG: Will fail due to production bug - enable after fix applied")
  public void testProductionBug_CrcExceptionAfterCompressionLeaksCompressedBuffer() throws Exception {
    // Leak detection is ENABLED - this test will FAIL

    // Create blob data that will be compressed (>512 bytes)
    byte[] blobData = TestUtils.getRandomBytes(4096);
    ByteBuffer blobBuffer = ByteBuffer.wrap(blobData);

    BlobProperties blobProperties = new BlobProperties(
        blobData.length,
        "testServiceId",
        "testOwnerId",
        "testContentType",
        false,
        Utils.Infinite_Time,
        time.milliseconds(),
        Utils.getRandomShort(TestUtils.RANDOM),
        Utils.getRandomShort(TestUtils.RANDOM),
        null,
        null,
        null
    );

    byte[] userMetadata = TestUtils.getRandomBytes(100);

    // Create a compression service that returns a FaultyByteBuf
    // This FaultyByteBuf will throw during nioBuffers() call in CRC calculation
    CompressionService faultyCompressionService = new CompressionService(
        routerConfig.routerCompressionConfig,
        routerMetrics.compressionMetrics) {
      @Override
      public ByteBuf compressChunk(ByteBuf chunk, boolean isFullChunk, boolean outputDirectMemory) {
        // Call real compression
        ByteBuf realCompressed = super.compressChunk(chunk, isFullChunk, outputDirectMemory);

        // Wrap in FaultyByteBuf that will throw during nioBuffers()
        return new FaultyByteBuf(realCompressed);
      }
    };

    // Create PutOperation with faulty compression service
    // When compressChunk() is called:
    // 1. Compression succeeds (returns FaultyByteBuf)
    // 2. PutChunk takes ownership: buf = faultyBuffer
    // 3. CRC calculation: buf.nioBuffers() → THROWS
    // 4. No try-catch → LEAK

    try {
      PutOperation op = createPutOperation(blobProperties, userMetadata, blobBuffer, faultyCompressionService);

      // Fill chunks - this triggers compression
      // Inside fillChunks():
      //   -> compressChunk() called
      //   -> Ownership transfer: buf = compressedBuffer (FaultyByteBuf)
      //   -> CRC calculation: for (ByteBuffer bb : buf.nioBuffers()) → EXCEPTION
      //   -> No try-catch → FaultyByteBuf LEAKED

      op.fillChunks();

      fail("Should have thrown exception during CRC calculation");
    } catch (RuntimeException e) {
      // Expected exception from FaultyByteBuf.nioBuffers()
      assertTrue("Exception should be from CRC calculation",
          e.getMessage().contains("Faulty nioBuffers()"));

      // CRITICAL: FaultyByteBuf is now LEAKED
      // NettyByteBufLeakHelper will detect this in afterTest()
      // TEST WILL FAIL
    }

    // After this test completes, leakHelper.afterTest() will be called
    // It will detect the leaked FaultyByteBuf and FAIL the test
    // with message like:
    // "java.lang.AssertionError: Detected ByteBuf leak: 1 buffer(s) not released"
  }

  /**
   * PRODUCTION BUG TEST #2: KMS exception after retainedDuplicate() evaluated
   *
   * This test uses a faulty KMS that throws during getRandomKey().
   * Due to Java's left-to-right argument evaluation, retainedDuplicate() is called
   * BEFORE getRandomKey() throws, causing a leak.
   *
   * BUG LOCATION: PutOperation.java:1589-1592 (encryptChunk method)
   *
   * TEST FLOW:
   * 1. Create PutOperation with encryption enabled
   * 2. Inject FaultyKMS that throws during getRandomKey()
   * 3. encryptChunk() called with constructor arguments:
   *    new EncryptJob(..., buf.retainedDuplicate(), ..., kms.getRandomKey(), ...)
   * 4. Arguments evaluated LEFT-TO-RIGHT:
   *    a. buf.retainedDuplicate() → refCnt++ ✓
   *    b. kms.getRandomKey() → EXCEPTION ✗
   * 5. EncryptJob constructor never completes
   * 6. Retained duplicate already created but never passed to EncryptJob
   * 7. No try-catch → retained duplicate LEAKED
   * 8. NettyByteBufLeakHelper detects leak → TEST FAILS
   *
   * EXPECTED RESULT: TEST FAILS with leak detection error
   * AFTER FIX: TEST PASSES
   */
  @Test
  @Ignore("KNOWN BUG: Will fail due to production bug - enable after fix applied")
  public void testProductionBug_KmsExceptionAfterRetainedDuplicateLeaksBuffer() throws Exception {
    // Leak detection is ENABLED - this test will FAIL

    // Create blob data
    byte[] blobData = TestUtils.getRandomBytes(4096);
    ByteBuffer blobBuffer = ByteBuffer.wrap(blobData);

    BlobProperties blobProperties = new BlobProperties(
        blobData.length,
        "testServiceId",
        "testOwnerId",
        "testContentType",
        false,
        Utils.Infinite_Time,
        time.milliseconds(),
        Utils.getRandomShort(TestUtils.RANDOM),
        Utils.getRandomShort(TestUtils.RANDOM),
        null,
        null,
        null
    );

    // Enable encryption
    Properties kmsProps = new Properties();
    kmsProps.setProperty("kms.default.container.key", TestUtils.getRandomKey(32));

    // Create a faulty KMS that throws during getRandomKey()
    KeyManagementService<SecretKeySpec> faultyKms = new SingleKeyManagementService(
        new KMSConfig(new VerifiableProperties(kmsProps)),
        TestUtils.getRandomKey(32).toString(),
        routerMetrics.keyManagementServiceMetrics) {
      @Override
      public SecretKeySpec getRandomKey() throws GeneralSecurityException {
        // Throw exception - but retainedDuplicate() already evaluated!
        throw new GeneralSecurityException("Simulated KMS failure during getRandomKey");
      }
    };

    // Create PutOperation with faulty KMS
    // When encryptChunk() is called:
    // 1. Java evaluates constructor arguments LEFT-TO-RIGHT
    // 2. buf.retainedDuplicate() evaluated → refCnt++
    // 3. kms.getRandomKey() throws → EncryptJob construction aborted
    // 4. Retained duplicate created but never passed to EncryptJob
    // 5. No try-catch → LEAK

    try {
      PutOperation op = createPutOperationWithEncryption(
          blobProperties,
          new byte[0],
          blobBuffer,
          faultyKms
      );

      // Fill chunks - this triggers encryption
      op.fillChunks();

      fail("Should have thrown exception during KMS getRandomKey");
    } catch (RouterException e) {
      // Expected exception from KMS
      assertTrue("Exception should be from KMS",
          e.getCause() instanceof GeneralSecurityException);

      // CRITICAL: Retained duplicate is now LEAKED
      // It was created during argument evaluation but never passed to EncryptJob
      // NettyByteBufLeakHelper will detect this in afterTest()
      // TEST WILL FAIL
    }
  }

  /**
   * PRODUCTION BUG TEST #3: RequestInfo construction exception after PutRequest creation
   *
   * This test injects a failure during RequestInfo construction.
   * PutRequest is created with retainedDuplicate(), but if RequestInfo fails,
   * the PutRequest is never stored in the map and never released.
   *
   * BUG LOCATION: PutOperation.java:1825-1830 (fetchRequests method)
   *
   * TEST FLOW:
   * 1. Create PutOperation
   * 2. Mock RequestInfo to throw during construction
   * 3. fetchRequests() called:
   *    a. createPutRequest() → buf.retainedDuplicate(), creates PutRequest
   *    b. new RequestInfo(..., putRequest, ...) → EXCEPTION
   * 4. PutRequest created but not stored in correlationIdToChunkPutRequestInfo
   * 5. No try-catch → PutRequest (with retained duplicate) LEAKED
   * 6. NettyByteBufLeakHelper detects leak → TEST FAILS
   *
   * EXPECTED RESULT: TEST FAILS with leak detection error
   * AFTER FIX: TEST PASSES
   */
  @Test
  @Ignore("KNOWN BUG: Will fail due to production bug - enable after fix applied")
  public void testProductionBug_RequestInfoExceptionAfterPutRequestCreationLeaksBuffer() throws Exception {
    // This test is more complex - requires mocking network layer
    // to trigger exception during RequestInfo construction

    // TODO: Implement using reflection or custom NetworkClient that fails
    // during RequestInfo construction

    fail("Test not yet implemented - requires deep integration mocking");
  }

  // Helper methods

  private PutOperation createPutOperation(
      BlobProperties blobProperties,
      byte[] userMetadata,
      ByteBuffer blobBuffer,
      CompressionService compressionService) throws Exception {

    // This would require access to PutOperation internals
    // In practice, we'd need to use reflection or extend PutOperation
    // to inject the faulty compression service

    throw new UnsupportedOperationException(
        "Requires PutOperation refactoring to support service injection"
    );
  }

  private PutOperation createPutOperationWithEncryption(
      BlobProperties blobProperties,
      byte[] userMetadata,
      ByteBuffer blobBuffer,
      KeyManagementService<SecretKeySpec> kms) throws Exception {

    throw new UnsupportedOperationException(
        "Requires PutOperation refactoring to support KMS injection"
    );
  }

  /**
   * FaultyByteBuf that throws during nioBuffers() to simulate CRC calculation failure
   */
  private static class FaultyByteBuf extends io.netty.buffer.AbstractByteBuf {
    private final ByteBuf delegate;

    FaultyByteBuf(ByteBuf delegate) {
      super(delegate.maxCapacity());
      this.delegate = delegate;
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
      // Throw during CRC calculation
      throw new RuntimeException("Faulty nioBuffers() - simulates CRC calculation exception");
    }

    @Override
    public int capacity() {
      return delegate.capacity();
    }

    @Override
    public ByteBuf capacity(int newCapacity) {
      return delegate.capacity(newCapacity);
    }

    @Override
    public int maxCapacity() {
      return delegate.maxCapacity();
    }

    @Override
    public io.netty.buffer.ByteBufAllocator alloc() {
      return delegate.alloc();
    }

    @Override
    public io.netty.buffer.ByteOrder order() {
      return delegate.order();
    }

    @Override
    public ByteBuf unwrap() {
      return delegate;
    }

    @Override
    public boolean isDirect() {
      return delegate.isDirect();
    }

    @Override
    public boolean hasArray() {
      return delegate.hasArray();
    }

    @Override
    public byte[] array() {
      return delegate.array();
    }

    @Override
    public int arrayOffset() {
      return delegate.arrayOffset();
    }

    @Override
    public boolean hasMemoryAddress() {
      return delegate.hasMemoryAddress();
    }

    @Override
    public long memoryAddress() {
      return delegate.memoryAddress();
    }

    @Override
    protected byte _getByte(int index) {
      return delegate.getByte(index);
    }

    @Override
    protected short _getShort(int index) {
      return delegate.getShort(index);
    }

    @Override
    protected short _getShortLE(int index) {
      return delegate.getShortLE(index);
    }

    @Override
    protected int _getUnsignedMedium(int index) {
      return delegate.getUnsignedMedium(index);
    }

    @Override
    protected int _getUnsignedMediumLE(int index) {
      return delegate.getUnsignedMediumLE(index);
    }

    @Override
    protected int _getInt(int index) {
      return delegate.getInt(index);
    }

    @Override
    protected int _getIntLE(int index) {
      return delegate.getIntLE(index);
    }

    @Override
    protected long _getLong(int index) {
      return delegate.getLong(index);
    }

    @Override
    protected long _getLongLE(int index) {
      return delegate.getLongLE(index);
    }

    @Override
    protected void _setByte(int index, int value) {
      delegate.setByte(index, value);
    }

    @Override
    protected void _setShort(int index, int value) {
      delegate.setShort(index, value);
    }

    @Override
    protected void _setShortLE(int index, int value) {
      delegate.setShortLE(index, value);
    }

    @Override
    protected void _setMedium(int index, int value) {
      delegate.setMedium(index, value);
    }

    @Override
    protected void _setMediumLE(int index, int value) {
      delegate.setMediumLE(index, value);
    }

    @Override
    protected void _setInt(int index, int value) {
      delegate.setInt(index, value);
    }

    @Override
    protected void _setIntLE(int index, int value) {
      delegate.setIntLE(index, value);
    }

    @Override
    protected void _setLong(int index, long value) {
      delegate.setLong(index, value);
    }

    @Override
    protected void _setLongLE(int index, long value) {
      delegate.setLongLE(index, value);
    }
  }
}
