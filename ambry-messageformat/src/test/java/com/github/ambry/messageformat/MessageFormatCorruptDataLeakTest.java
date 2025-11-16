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
 * Tests to verify that MessageFormatRecord.deserializeBlob properly handles ByteBuf cleanup
 * when deserialization fails due to corrupt data (CRC mismatch).
 *
 * PRODUCTION BUG: MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord (lines 1681-1696)
 * When CRC validation fails, the ByteBuf allocated by Utils.readNettyByteBufFromCrcInputStream
 * (a slice of the input buffer) is never released, causing a leak.
 *
 * These tests use ByteBufSliceCapture to intercept and track ByteBuf slices created during
 * deserialization, allowing verification of proper cleanup.
 */
public class MessageFormatCorruptDataLeakTest {

  // Track leaked buffers for cleanup in @After
  private final List<ByteBuf> leakedBuffersToClean = new ArrayList<>();

  /**
   * Wrapper that captures ByteBuf slices created during operations.
   * Extends WrappedByteBuf for automatic delegation of all ByteBuf methods.
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
   * This test exposes the leak in MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord (line 1687).
   * When using NettyByteBufDataInputStream, Utils.readNettyByteBufFromCrcInputStream creates
   * a slice of the input ByteBuf. When CRC validation fails, this slice is never released.
   *
   * The test uses ByteBufSliceCapture to intercept slice() calls and obtain a reference to
   * the created slice, then verifies its refCount = 0 after deserialization fails (proving cleanup).
   *
   * Expected behavior:
   * - FAILS with "refCnt is 1 but should be 0" when bug exists (leak detected)
   * - PASSES when bug is fixed (slice properly released in try-finally block)
   */
  @Test
  public void testDeserializeBlobWithCorruptCrc() throws Exception {
    // Allocate pooled ByteBuf (pooled to test production scenario)
    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 8 + 1024 + 8);

    // Wrap in slice capture to intercept slice creation
    ByteBufSliceCapture capture = new ByteBufSliceCapture(inputBuf);

    try {
      // Serialize blob data with CORRUPT CRC
      byte[] blobContent = new byte[1024];
      for (int i = 0; i < 1024; i++) {
        blobContent[i] = (byte) (i % 256);
      }

      capture.writeShort(MessageFormatRecord.Blob_Version_V1);
      capture.writeLong(1024);
      capture.writeBytes(blobContent);

      // Calculate correct CRC but write WRONG value
      CRC32 crc = new CRC32();
      crc.update(capture.nioBuffer(0, capture.writerIndex()));
      capture.writeLong(crc.getValue() + 12345); // Corrupt CRC

      // Create NettyByteBufDataInputStream with our capture wrapper
      NettyByteBufDataInputStream inputStream = new NettyByteBufDataInputStream(capture);
      CrcInputStream crcStream = new CrcInputStream(inputStream);

      // Attempt deserialization - this will create and leak a ByteBuf slice
      try {
        BlobData blobData = MessageFormatRecord.deserializeBlob(crcStream);
        fail("Should have thrown MessageFormatException due to corrupt CRC");
      } catch (MessageFormatException e) {
        // Expected - CRC validation failed
        assertEquals(MessageFormatErrorCodes.DataCorrupt, e.getErrorCode());
        // BUG: ByteBuf slice created at Utils.java:413 is never released
      }

      // Verify slice was created during deserialization
      List<ByteBuf> capturedSlices = capture.getCapturedSlices();
      assertEquals("Expected exactly one slice to be created", 1, capturedSlices.size());

      ByteBuf slice = capturedSlices.get(0);
      int sliceRefCnt = slice.refCnt();

      // Track for cleanup if leaked
      if (sliceRefCnt > 0) {
        leakedBuffersToClean.add(slice);
      }

      // Assert slice is properly released even when exception is thrown
      // BUG: Currently this will FAIL because deserializeBlobRecord doesn't release the slice
      // FIX: Add try-finally block in deserializeBlobRecord to release ByteBuf on error
      assertEquals("ByteBuf slice must be released after CRC validation failure. " +
              "LEAK DETECTED: slice refCnt is " + sliceRefCnt + " but should be 0. " +
              "Fix: Add try-finally block in MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord (line 1687) " +
              "to release the ByteBuf when CRC validation fails.",
          0, sliceRefCnt);

    } finally {
      // Release parent buffer
      inputBuf.release();
    }
  }

  /**
   * Test valid blob deserialization (control test).
   *
   * This verifies that valid blobs do NOT leak when properly released.
   * The slice should be wrapped in BlobData and released when BlobData.release() is called.
   */
  @Test
  public void testDeserializeBlobWithValidCrc() throws Exception {
    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 8 + 512 + 8);

    ByteBufSliceCapture capture = new ByteBufSliceCapture(inputBuf);

    try {
      // Serialize blob data with CORRECT CRC
      byte[] blobContent = new byte[512];
      for (int i = 0; i < 512; i++) {
        blobContent[i] = (byte) i;
      }

      capture.writeShort(MessageFormatRecord.Blob_Version_V1);
      capture.writeLong(512);
      capture.writeBytes(blobContent);

      // Calculate and write CORRECT CRC
      CRC32 crc = new CRC32();
      crc.update(capture.nioBuffer(0, capture.writerIndex()));
      capture.writeLong(crc.getValue());

      NettyByteBufDataInputStream inputStream = new NettyByteBufDataInputStream(capture);
      CrcInputStream crcStream = new CrcInputStream(inputStream);

      // Deserialize successfully
      BlobData blobData = MessageFormatRecord.deserializeBlob(crcStream);

      // Use the data
      assertEquals(512, blobData.content().readableBytes());
      byte[] readContent = new byte[512];
      blobData.content().readBytes(readContent);

      // Verify slice was created
      List<ByteBuf> capturedSlices = capture.getCapturedSlices();
      assertEquals("Expected exactly one slice to be created", 1, capturedSlices.size());

      ByteBuf slice = capturedSlices.get(0);

      // Proper cleanup - this should release the slice
      blobData.release();

      int refCntAfterRelease = slice.refCnt();

      // Assert no leak: slice should be released (refCnt = 0)
      assertEquals("Expected no leak: slice refCnt should be 0 after BlobData.release()",
          0, refCntAfterRelease);

    } finally {
      inputBuf.release();
    }
  }
}
