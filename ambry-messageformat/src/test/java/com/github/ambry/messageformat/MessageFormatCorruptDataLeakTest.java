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
package com.github.ambry.messageformat;

import com.github.ambry.utils.CrcInputStream;
import com.github.ambry.utils.NettyByteBufDataInputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.WrappedByteBuf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Tests ByteBuf leak detection and prevention in blob deserialization error paths.
 *
 * <p><b>Production Bug Being Tested:</b><br>
 * {@code MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord} allocates a ByteBuf slice
 * via {@code Utils.readNettyByteBufFromCrcInputStream} but fails to release it when CRC
 * validation fails, causing a memory leak in the error path.</p>
 *
 * <p><b>Test Strategy:</b><br>
 * Uses {@link ByteBufSliceCapture}, a {@link WrappedByteBuf} subclass that intercepts
 * {@code slice(int, int)} calls to capture created slices. After deserialization (successful
 * or failed), verifies slice reference counts to detect leaks.</p>
 *
 * <p><b>Test Coverage:</b></p>
 * <ul>
 *   <li>{@link #testDeserializeBlobWithCorruptCrc()} - Error path (leak detection)</li>
 *   <li>{@link #testDeserializeBlobWithValidCrc()} - Success path (control test)</li>
 * </ul>
 *
 * <p><b>Design Rationale:</b><br>
 * This test intentionally uses production classes ({@code MessageFormatRecord},
 * {@code Utils}, {@code NettyByteBufDataInputStream}) without mocking to ensure it detects
 * real-world leaks. ByteBufSliceCapture provides minimal interception while delegating
 * all other behavior to Netty's {@link WrappedByteBuf}.</p>
 */
public class MessageFormatCorruptDataLeakTest {

  // Track leaked buffers for cleanup in @After
  private final List<ByteBuf> leakedBuffersToClean = new ArrayList<>();

  /**
   * Wrapper that captures ByteBuf slices created during operations.
   *
   * Extends {@link WrappedByteBuf} to automatically delegate all ByteBuf methods to the underlying buffer.
   * Only overrides slice creation methods to intercept and track created slices.
   *
   * <p><b>Methods used by this test:</b></p>
   * <ul>
   *   <li>{@code writeShort(int)} - Write blob version</li>
   *   <li>{@code writeLong(long)} - Write blob size and CRC</li>
   *   <li>{@code writeBytes(byte[])} - Write blob content</li>
   *   <li>{@code nioBuffer(int, int)} - Get NIO buffer for CRC calculation</li>
   *   <li>{@code writerIndex()} - Get current write position for CRC calculation</li>
   * </ul>
   *
   * <p><b>Methods used by production code (Utils.readNettyByteBufFromCrcInputStream):</b></p>
   * <ul>
   *   <li>{@code slice(int, int)} - Create slice (INTERCEPTED - this is what we're testing)</li>
   *   <li>{@code readerIndex(int)} - Update read position after slice</li>
   * </ul>
   *
   * <p>All other WrappedByteBuf methods are inherited but not used by this test.</p>
   */
  private static class ByteBufSliceCapture extends WrappedByteBuf {
    private final List<ByteBuf> capturedSlices = new ArrayList<>();

    public ByteBufSliceCapture(ByteBuf delegate) {
      super(delegate);
    }

    public List<ByteBuf> getCapturedSlices() {
      return Collections.unmodifiableList(capturedSlices);
    }

    @Override
    public ByteBuf slice(int index, int length) {
      ByteBuf slice = super.slice(index, length);
      capturedSlices.add(slice);
      return slice;
    }

    @Override
    public ByteBuf slice() {
      ByteBuf slice = super.slice();
      capturedSlices.add(slice);
      return slice;
    }

    @Override
    public ByteBuf retainedSlice() {
      ByteBuf slice = super.retainedSlice();
      capturedSlices.add(slice);
      return slice;
    }

    @Override
    public ByteBuf retainedSlice(int index, int length) {
      ByteBuf slice = super.retainedSlice(index, length);
      capturedSlices.add(slice);
      return slice;
    }

    @Override
    public ByteBuf readSlice(int length) {
      ByteBuf slice = super.readSlice(length);
      capturedSlices.add(slice);
      return slice;
    }

    @Override
    public ByteBuf readRetainedSlice(int length) {
      ByteBuf slice = super.readRetainedSlice(length);
      capturedSlices.add(slice);
      return slice;
    }
  }

  @After
  public void cleanup() {
    // Clean up any leaked buffers to prevent cross-test contamination
    for (ByteBuf leaked : leakedBuffersToClean) {
      if (leaked.refCnt() > 0) {
        leaked.release(leaked.refCnt());
      }
    }
    leakedBuffersToClean.clear();
  }

  /**
   * Test corrupt blob deserialization with CRC mismatch - verifies ByteBuf cleanup on error path.
   *
   * <p><b>Production bug:</b> {@code MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord}
   * allocates a ByteBuf slice via {@code Utils.readNettyByteBufFromCrcInputStream} but doesn't release
   * it when CRC validation fails, causing a memory leak.</p>
   *
   * <p><b>Test mechanism:</b> Uses {@link ByteBufSliceCapture} to intercept {@code slice(int, int)}
   * calls during deserialization. After the exception is thrown, verifies the captured slice has
   * {@code refCnt() == 0}, proving it was properly released.</p>
   *
   * <p><b>Expected behavior:</b></p>
   * <ul>
   *   <li>FAILS with "refCnt is 1 but should be 0" when bug exists (leak detected)</li>
   *   <li>PASSES when bug is fixed (slice released in try-finally block)</li>
   * </ul>
   */
  @Test
  public void testDeserializeBlobWithCorruptCrc() throws Exception {
    // Allocate pooled ByteBuf (pooled to test production scenario)
    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 8 + 1024 + 8);

    // Wrap in slice capture to intercept slice creation
    ByteBufSliceCapture capture = new ByteBufSliceCapture(inputBuf);

    try {
      // Serialize blob data with CORRUPT CRC
      // Note: Content doesn't matter since CRC will be corrupted anyway
      byte[] blobContent = new byte[1024];

      capture.writeShort(MessageFormatRecord.Blob_Version_V1);
      capture.writeLong(1024);
      capture.writeBytes(blobContent);

      // Calculate correct CRC but write WRONG value to trigger validation failure
      CRC32 crc = new CRC32();
      crc.update(capture.nioBuffer(0, capture.writerIndex()));
      capture.writeLong(crc.getValue() + 1); // Corrupt CRC (any non-zero offset corrupts it)

      // Create NettyByteBufDataInputStream with our capture wrapper
      NettyByteBufDataInputStream inputStream = new NettyByteBufDataInputStream(capture);
      CrcInputStream crcStream = new CrcInputStream(inputStream);

      // Attempt deserialization - this creates a ByteBuf slice via Utils.readNettyByteBufFromCrcInputStream
      try {
        BlobData blobData = MessageFormatRecord.deserializeBlob(crcStream);
        fail("Should have thrown MessageFormatException due to corrupt CRC");
      } catch (MessageFormatException e) {
        // Expected - CRC validation failed
        assertEquals(MessageFormatErrorCodes.DataCorrupt, e.getErrorCode());
        // BUG: ByteBuf slice created by Utils.readNettyByteBufFromCrcInputStream is never released
      }

      // Verify exactly one slice was created during deserialization
      List<ByteBuf> capturedSlices = capture.getCapturedSlices();
      assertEquals("Expected exactly one slice to be created by Utils.readNettyByteBufFromCrcInputStream",
          1, capturedSlices.size());

      ByteBuf slice = capturedSlices.get(0);
      int sliceRefCnt = slice.refCnt();

      // Track for cleanup to prevent cross-test contamination
      if (sliceRefCnt > 0) {
        leakedBuffersToClean.add(slice);
      }

      // Assert slice is properly released even when exception is thrown
      // When bug exists: refCnt = 1 (leak)
      // When bug fixed: refCnt = 0 (properly released in try-finally)
      assertEquals("ByteBuf slice must be released after CRC validation failure.\n" +
              "LEAK DETECTED: slice refCnt is " + sliceRefCnt + " but should be 0.\n" +
              "Fix: Add try-finally block in MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord\n" +
              "to release the ByteBuf allocated by Utils.readNettyByteBufFromCrcInputStream\n" +
              "when CRC validation fails.",
          0, sliceRefCnt);

    } finally {
      // Release parent buffer
      inputBuf.release();
    }
  }

  /**
   * Test valid blob deserialization - verifies no leak on success path (control test).
   *
   * <p>Verifies that when deserialization succeeds, the ByteBuf slice is properly managed:</p>
   * <ul>
   *   <li>Slice is created by {@code Utils.readNettyByteBufFromCrcInputStream}</li>
   *   <li>Slice is wrapped in {@link BlobData} and returned to caller</li>
   *   <li>Slice is released when {@code BlobData.release()} is called</li>
   *   <li>Final refCount is 0 (no leak)</li>
   * </ul>
   *
   * <p>This control test ensures the leak detection mechanism doesn't produce false positives.</p>
   */
  @Test
  public void testDeserializeBlobWithValidCrc() throws Exception {
    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 8 + 512 + 8);

    ByteBufSliceCapture capture = new ByteBufSliceCapture(inputBuf);

    try {
      // Serialize blob data with CORRECT CRC
      byte[] blobContent = new byte[512];

      capture.writeShort(MessageFormatRecord.Blob_Version_V1);
      capture.writeLong(512);
      capture.writeBytes(blobContent);

      // Calculate and write CORRECT CRC (enables successful deserialization)
      CRC32 crc = new CRC32();
      crc.update(capture.nioBuffer(0, capture.writerIndex()));
      capture.writeLong(crc.getValue());

      NettyByteBufDataInputStream inputStream = new NettyByteBufDataInputStream(capture);
      CrcInputStream crcStream = new CrcInputStream(inputStream);

      // Deserialize successfully
      BlobData blobData = MessageFormatRecord.deserializeBlob(crcStream);

      // Verify deserialized content
      assertEquals("BlobData should contain 512 bytes", 512, blobData.content().readableBytes());
      byte[] readContent = new byte[512];
      blobData.content().readBytes(readContent);

      // Verify exactly one slice was created
      List<ByteBuf> capturedSlices = capture.getCapturedSlices();
      assertEquals("Expected exactly one slice to be created by Utils.readNettyByteBufFromCrcInputStream",
          1, capturedSlices.size());

      ByteBuf slice = capturedSlices.get(0);
      int refCntBeforeRelease = slice.refCnt();

      // Sanity check: slice should be retained at this point (refCnt > 0)
      assertTrue("Slice should have refCnt > 0 before BlobData.release(), was: " + refCntBeforeRelease,
          refCntBeforeRelease > 0);

      // Release BlobData - this should release the slice
      blobData.release();

      int refCntAfterRelease = slice.refCnt();

      // Assert no leak: slice should be released (refCnt = 0)
      assertEquals("Expected no leak: slice refCnt should be 0 after BlobData.release(), " +
              "but was: " + refCntAfterRelease,
          0, refCntAfterRelease);

    } finally {
      inputBuf.release();
    }
  }
}
