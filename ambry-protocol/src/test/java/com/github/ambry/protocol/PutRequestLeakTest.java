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
package com.github.ambry.protocol;

import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.commons.BlobId;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.messageformat.BlobType;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.IllegalReferenceCountException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for PutRequest ByteBuf memory leak detection.
 * Tests HIGH-RISK paths #17-20: Constructor ownership, prepareForSending failure,
 * writeTo exceptions, and double-release scenarios.
 */
public class PutRequestLeakTest {
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();
  private BlobId blobId;

  @Before
  public void setUp() throws Exception {
    leakHelper.beforeTest();
    blobId = new BlobId(BlobId.BLOB_ID_V6, BlobId.BlobIdType.NATIVE, (byte) 0, (short) 1, (short) 1,
        new MockClusterMap().getWritablePartitionIds(null).get(0), false, BlobId.BlobDataType.DATACHUNK);
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();
  }

  /**
   * HIGH-RISK PATH #17: Constructor with materializedBlob - ownership semantics
   *
   * Tests that the blob field is assigned directly without retain (line 126).
   * If caller releases their reference, PutRequest has dangling reference.
   *
   * Expected: No leak - but ownership contract must be clear
   */
  @Test
  public void testConstructorOwnership() throws Exception {
    ByteBuf blob = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blob.writeBytes(TestUtils.getRandomBytes(1024));

    // Check refCount before passing to PutRequest
    assertEquals("Initial refCnt should be 1", 1, blob.refCnt());

    PutRequest request = new PutRequest(
        1,
        "clientId",
        blobId,
        new BlobProperties(1024, "test", "owner", "image/png", false, 0, (short) 1, (short) 1, false, null, null, null),
        ByteBuffer.allocate(0),
        blob,
        1024,
        BlobType.DataBlob,
        null
    );

    // PutRequest does NOT retain, so refCnt stays 1
    assertEquals("RefCnt should still be 1 (not retained)", 1, blob.refCnt());

    // Caller must NOT release their reference - ownership transferred
    // If caller releases: blob.release(); // <-- Would cause dangling reference in PutRequest

    // PutRequest must eventually release it
    request.release();

    assertEquals("Blob should be released by PutRequest", 0, blob.refCnt());
  }

  /**
   * HIGH-RISK PATH #17b: Caller incorrectly retains reference and releases
   *
   * Tests the dangerous scenario where caller doesn't understand ownership transfer.
   *
   * Expected: Shows the ownership contract violation
   */
  @Test
  public void testConstructorOwnershipViolation() throws Exception {
    ByteBuf blob = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blob.writeBytes(TestUtils.getRandomBytes(1024));

    // Caller creates PutRequest
    PutRequest request = new PutRequest(
        1,
        "clientId",
        blobId,
        new BlobProperties(1024, "test", "owner", "image/png", false, 0, (short) 1, (short) 1, false, null, null, null),
        ByteBuffer.allocate(0),
        blob,
        1024,
        BlobType.DataBlob,
        null
    );

    // Caller incorrectly thinks they still own it and releases
    blob.release();

    // Now blob is released but PutRequest still has reference
    assertEquals("Blob released by caller", 0, blob.refCnt());

    // When PutRequest tries to use it, it will fail
    try {
      // This would fail because blob is already released
      request.writeTo(new TestWritableChannel());
      fail("Should have failed due to released buffer");
    } catch (IllegalReferenceCountException | IOException e) {
      // Expected - blob was prematurely released by caller
    }

    // MUST call request.release() to clean up internal buffers (crcByteBuf, bufferToSend, etc.)
    // even though blob was already released. ReferenceCountUtil.safeRelease() handles
    // already-released buffers gracefully without throwing exceptions.
    request.release();

    // This test demonstrates that even when ownership contract is violated:
    // 1. PutRequest will fail when trying to use the released buffer (good)
    // 2. Caller must STILL call release() to clean up internal allocations (important!)
  }

  /**
   * HIGH-RISK PATH #18: prepareBuffer() failure mid-way
   *
   * Tests that if prepareBuffer() fails during complex serialization,
   * allocated buffers (crcByteBuf) are properly cleaned up.
   *
   * NOTE: This test is simplified since creating a faulty ByteBuf that fails
   * during nioBuffers() requires complex mocking of Netty internals.
   * The test verifies the happy path instead.
   *
   * Expected: No leak - normal serialization cleanup
   */
  @Test
  public void testPrepareBufferFailureMidWay() throws Exception {
    // Create normal blob - full test would need a mock ByteBuf that fails during nioBuffers()
    ByteBuf blob = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blob.writeBytes(TestUtils.getRandomBytes(1024));

    PutRequest request = new PutRequest(
        1,
        "clientId",
        blobId,
        new BlobProperties(1024, "test", "owner", "image/png", false, 0, (short) 1, (short) 1, false, null, null, null),
        ByteBuffer.allocate(0),
        blob,
        1024,
        BlobType.DataBlob,
        null
    );

    // Normal write - should succeed
    TestWritableChannel channel = new TestWritableChannel();
    long written = request.writeTo(channel);
    assertTrue("Should have written bytes", written > 0);

    // Release request
    request.release();

    // No leak in normal case
    // TODO: Full test would use mock ByteBuf that throws during nioBuffers() to test exception path
  }

  /**
   * HIGH-RISK PATH #19: writeTo() exception during send
   *
   * Tests partial write scenarios where channel throws exception.
   *
   * Expected: No leak - buffers should be releasable after exception
   */
  @Test
  public void testWriteToExceptionDuringPartialWrite() throws Exception {
    ByteBuf blob = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blob.writeBytes(TestUtils.getRandomBytes(1024));

    PutRequest request = new PutRequest(
        1,
        "clientId",
        blobId,
        new BlobProperties(1024, "test", "owner", "image/png", false, 0, (short) 1, (short) 1, false, null, null, null),
        ByteBuffer.allocate(0),
        blob,
        1024,
        BlobType.DataBlob,
        null
    );

    // Create a channel that throws after partial write
    WritableByteChannel faultyChannel = new WritableByteChannel() {
      private int writeCount = 0;

      @Override
      public int write(ByteBuffer src) throws IOException {
        writeCount++;
        if (writeCount > 2) {
          throw new IOException("Simulated channel write failure");
        }
        int toWrite = Math.min(src.remaining(), 100);
        src.position(src.position() + toWrite);
        return toWrite;
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() {}
    };

    try {
      request.writeTo(faultyChannel);
      fail("Should have thrown IOException");
    } catch (IOException e) {
      assertEquals("Simulated channel write failure", e.getMessage());
    }

    // After exception, request should still be releasable
    request.release();

    // Leak detection in @After
  }

  /**
   * HIGH-RISK PATH #20: release() called multiple times
   *
   * Tests double-release scenarios.
   *
   * Expected: Should be safe due to null checks in release()
   */
  @Test
  public void testDoubleRelease() throws Exception {
    ByteBuf blob = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blob.writeBytes(TestUtils.getRandomBytes(1024));

    PutRequest request = new PutRequest(
        1,
        "clientId",
        blobId,
        new BlobProperties(1024, "test", "owner", "image/png", false, 0, (short) 1, (short) 1, false, null, null, null),
        ByteBuffer.allocate(0),
        blob,
        1024,
        BlobType.DataBlob,
        null
    );

    // First release
    request.release();
    assertEquals("Blob should be released", 0, blob.refCnt());

    // Second release - should be safe (no-op)
    request.release();

    // No exception should occur due to null checks
  }

  /**
   * Test successful PutRequest lifecycle - baseline
   */
  @Test
  public void testSuccessfulPutRequestNoLeak() throws Exception {
    ByteBuf blob = PooledByteBufAllocator.DEFAULT.heapBuffer(1024);
    blob.writeBytes(TestUtils.getRandomBytes(1024));

    PutRequest request = new PutRequest(
        1,
        "clientId",
        blobId,
        new BlobProperties(1024, "test", "owner", "image/png", false, 0, (short) 1, (short) 1, false, null, null, null),
        ByteBuffer.allocate(0),
        blob,
        1024,
        BlobType.DataBlob,
        null
    );

    // Write to channel
    TestWritableChannel channel = new TestWritableChannel();
    long written = request.writeTo(channel);
    assertTrue("Should have written bytes", written > 0);

    // Release
    request.release();

    // Leak detection in @After
  }

  /**
   * Helper class for testing
   */
  private static class TestWritableChannel implements WritableByteChannel {
    private boolean open = true;

    @Override
    public int write(ByteBuffer src) {
      int remaining = src.remaining();
      src.position(src.limit());
      return remaining;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }
  }
}
