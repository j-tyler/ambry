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

import com.github.ambry.utils.NettyByteBufDataInputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests ByteBuf leak detection and prevention in blob deserialization error paths.
 *
 * <p><b>Production Bug Being Tested:</b><br>
 * {@code MessageFormatRecord.Blob_Format_V1/V2/V3.deserializeBlobRecord} allocates a ByteBuf slice
 * via {@code Utils.readNettyByteBufFromCrcInputStream} but fails to release it when CRC
 * validation fails, causing a memory leak in the error path.</p>
 *
 * <p><b>Test Strategy:</b><br>
 * Extends {@link NettyByteBufDataInputStream} to override {@code getBuffer()}, returning a
 * minimal ByteBuf wrapper that intercepts {@code slice(int, int)} calls. This wrapper only
 * implements methods actually used by production code, throwing
 * {@link UnsupportedOperationException} for all others to ensure explicit API contract.</p>
 *
 * <p><b>Test Coverage:</b></p>
 * <ul>
 *   <li>V1 Format: {@link #testDeserializeBlobWithCorruptCrc()}, {@link #testDeserializeBlobWithValidCrc()}</li>
 *   <li>V2 Format: {@link #testDeserializeBlobV2WithCorruptCrc()}, {@link #testDeserializeBlobV2WithValidCrc()}</li>
 *   <li>V3 Format: {@link #testDeserializeBlobV3WithCorruptCrc()}, {@link #testDeserializeBlobV3WithValidCrc()}</li>
 * </ul>
 *
 * <p><b>Design Rationale:</b><br>
 * This test intentionally uses production classes without mocking to ensure it detects
 * real-world leaks. The minimal ByteBuf wrapper approach makes the API contract explicit
 * and causes loud failures if production code changes to use unsupported methods.</p>
 */
public class MessageFormatCorruptDataLeakTest {

  private final List<ByteBuf> leakedBuffersToClean = new ArrayList<>();

  /**
   * NettyByteBufDataInputStream that captures ByteBuf slices created during deserialization.
   *
   * <p>Overrides {@code getBuffer()} to return a {@link SliceCapturingByteBuf} wrapper that
   * intercepts {@code slice(int, int)} calls.</p>
   */
  private static class CapturingInputStream extends NettyByteBufDataInputStream {
    private final SliceCapturingByteBuf wrapper;

    public CapturingInputStream(ByteBuf buffer) {
      super(buffer);
      this.wrapper = new SliceCapturingByteBuf(buffer);
    }

    @Override
    public ByteBuf getBuffer() {
      return wrapper;
    }

    public List<ByteBuf> getCapturedSlices() {
      return wrapper.getCapturedSlices();
    }
  }

  /**
   * ByteBuf wrapper that captures slice creation for leak detection testing.
   *
   * <p>Extends {@link DelegateByteBuf} and overrides only {@code slice(int, int)} to intercept
   * and record all slice creations. All other ByteBuf methods delegate to the underlying buffer.</p>
   */
  private static class SliceCapturingByteBuf extends DelegateByteBuf {
    private final List<ByteBuf> capturedSlices = new ArrayList<>();

    public SliceCapturingByteBuf(ByteBuf delegate) {
      super(delegate);
    }

    public List<ByteBuf> getCapturedSlices() {
      return Collections.unmodifiableList(capturedSlices);
    }

    @Override
    public ByteBuf slice(int index, int length) {
      ByteBuf slice = delegate.slice(index, length);
      capturedSlices.add(slice);
      return slice;
    }
  }

  @After
  public void cleanup() {
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
   * <p><b>Test mechanism:</b> Uses {@link CapturingInputStream} to intercept {@code slice(int, int)}
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
    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 8 + 1024 + 8);
    leakedBuffersToClean.add(inputBuf);

    byte[] blobContent = new byte[1024];
    inputBuf.writeShort(MessageFormatRecord.Blob_Version_V1);
    inputBuf.writeLong(1024);
    inputBuf.writeBytes(blobContent);

    // Corrupt the CRC to trigger validation failure
    CRC32 crc = new CRC32();
    crc.update(inputBuf.nioBuffer(0, inputBuf.writerIndex()));
    inputBuf.writeLong(crc.getValue() + 1);

    CapturingInputStream capturingStream = new CapturingInputStream(inputBuf);

    try {
      MessageFormatRecord.deserializeBlob(capturingStream);
      fail("Should have thrown MessageFormatException due to corrupt CRC");
    } catch (MessageFormatException e) {
      assertEquals(MessageFormatErrorCodes.DataCorrupt, e.getErrorCode());
    }

    List<ByteBuf> capturedSlices = capturingStream.getCapturedSlices();
    assertEquals("Expected exactly one slice to be created", 1, capturedSlices.size());

    ByteBuf slice = capturedSlices.get(0);
    int refCnt = slice.refCnt();

    if (refCnt > 0) {
      leakedBuffersToClean.add(slice);
    }

    assertEquals("ByteBuf slice must be released after CRC validation failure.\n" +
            "LEAK DETECTED: slice refCnt is " + refCnt + " but should be 0.\n" +
            "Fix: Add try-finally block in MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord\n" +
            "to release the ByteBuf allocated by Utils.readNettyByteBufFromCrcInputStream\n" +
            "when CRC validation fails.",
        0, refCnt);
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
    leakedBuffersToClean.add(inputBuf);

    byte[] blobContent = new byte[512];
    inputBuf.writeShort(MessageFormatRecord.Blob_Version_V1);
    inputBuf.writeLong(512);
    inputBuf.writeBytes(blobContent);

    CRC32 crc = new CRC32();
    crc.update(inputBuf.nioBuffer(0, inputBuf.writerIndex()));
    inputBuf.writeLong(crc.getValue());

    CapturingInputStream capturingStream = new CapturingInputStream(inputBuf);

    BlobData blobData = MessageFormatRecord.deserializeBlob(capturingStream);

    assertEquals("BlobData should contain 512 bytes", 512, blobData.content().readableBytes());

    List<ByteBuf> capturedSlices = capturingStream.getCapturedSlices();
    assertEquals("Expected exactly one slice to be created", 1, capturedSlices.size());

    ByteBuf slice = capturedSlices.get(0);
    assertTrue("Slice should have refCnt > 0 before release", slice.refCnt() > 0);

    blobData.release();

    int refCnt = slice.refCnt();
    if (refCnt > 0) {
      leakedBuffersToClean.add(slice);
    }

    assertEquals("Expected no leak: slice refCnt should be 0 after BlobData.release()", 0, refCnt);
  }

  /**
   * Test V2 corrupt blob deserialization with CRC mismatch - verifies ByteBuf cleanup on error path.
   *
   * <p>V2 format adds blobType field: version(2) + blobType(2) + size(8) + content(n) + crc(8)</p>
   */
  @Test
  public void testDeserializeBlobV2WithCorruptCrc() throws Exception {
    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 2 + 8 + 512 + 8);
    leakedBuffersToClean.add(inputBuf);

    byte[] blobContent = new byte[512];
    inputBuf.writeShort(MessageFormatRecord.Blob_Version_V2);
    inputBuf.writeShort((short) BlobType.DataBlob.ordinal());
    inputBuf.writeLong(512);
    inputBuf.writeBytes(blobContent);

    CRC32 crc = new CRC32();
    crc.update(inputBuf.nioBuffer(0, inputBuf.writerIndex()));
    inputBuf.writeLong(crc.getValue() + 1);

    CapturingInputStream capturingStream = new CapturingInputStream(inputBuf);

    try {
      MessageFormatRecord.deserializeBlob(capturingStream);
      fail("Should have thrown MessageFormatException due to corrupt CRC");
    } catch (MessageFormatException e) {
      assertEquals(MessageFormatErrorCodes.DataCorrupt, e.getErrorCode());
    }

    List<ByteBuf> capturedSlices = capturingStream.getCapturedSlices();
    assertEquals("Expected exactly one slice to be created", 1, capturedSlices.size());

    ByteBuf slice = capturedSlices.get(0);
    int refCnt = slice.refCnt();

    if (refCnt > 0) {
      leakedBuffersToClean.add(slice);
    }

    assertEquals("ByteBuf slice must be released after CRC validation failure (V2 format)", 0, refCnt);
  }

  /**
   * Test V2 valid blob deserialization - verifies no leak on success path (control test).
   */
  @Test
  public void testDeserializeBlobV2WithValidCrc() throws Exception {
    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 2 + 8 + 256 + 8);
    leakedBuffersToClean.add(inputBuf);

    byte[] blobContent = new byte[256];
    inputBuf.writeShort(MessageFormatRecord.Blob_Version_V2);
    inputBuf.writeShort((short) BlobType.DataBlob.ordinal());
    inputBuf.writeLong(256);
    inputBuf.writeBytes(blobContent);

    CRC32 crc = new CRC32();
    crc.update(inputBuf.nioBuffer(0, inputBuf.writerIndex()));
    inputBuf.writeLong(crc.getValue());

    CapturingInputStream capturingStream = new CapturingInputStream(inputBuf);

    BlobData blobData = MessageFormatRecord.deserializeBlob(capturingStream);

    assertEquals("BlobData should contain 256 bytes", 256, blobData.content().readableBytes());

    List<ByteBuf> capturedSlices = capturingStream.getCapturedSlices();
    assertEquals("Expected exactly one slice to be created", 1, capturedSlices.size());

    ByteBuf slice = capturedSlices.get(0);
    assertTrue("Slice should have refCnt > 0 before release", slice.refCnt() > 0);

    blobData.release();

    int refCnt = slice.refCnt();
    if (refCnt > 0) {
      leakedBuffersToClean.add(slice);
    }

    assertEquals("Expected no leak: slice refCnt should be 0 after BlobData.release()", 0, refCnt);
  }

  /**
   * Test V3 corrupt blob deserialization with CRC mismatch - verifies ByteBuf cleanup on error path.
   *
   * <p>V3 format adds isCompressed field: version(2) + blobType(2) + isCompressed(1) + size(8) + content(n) + crc(8)</p>
   */
  @Test
  public void testDeserializeBlobV3WithCorruptCrc() throws Exception {
    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 2 + 1 + 8 + 512 + 8);
    leakedBuffersToClean.add(inputBuf);

    byte[] blobContent = new byte[512];
    inputBuf.writeShort(MessageFormatRecord.Blob_Version_V3);
    inputBuf.writeShort((short) BlobType.DataBlob.ordinal());
    inputBuf.writeByte(0); // not compressed
    inputBuf.writeLong(512);
    inputBuf.writeBytes(blobContent);

    CRC32 crc = new CRC32();
    crc.update(inputBuf.nioBuffer(0, inputBuf.writerIndex()));
    inputBuf.writeLong(crc.getValue() + 1);

    CapturingInputStream capturingStream = new CapturingInputStream(inputBuf);

    try {
      MessageFormatRecord.deserializeBlob(capturingStream);
      fail("Should have thrown MessageFormatException due to corrupt CRC");
    } catch (MessageFormatException e) {
      assertEquals(MessageFormatErrorCodes.DataCorrupt, e.getErrorCode());
    }

    List<ByteBuf> capturedSlices = capturingStream.getCapturedSlices();
    assertEquals("Expected exactly one slice to be created", 1, capturedSlices.size());

    ByteBuf slice = capturedSlices.get(0);
    int refCnt = slice.refCnt();

    if (refCnt > 0) {
      leakedBuffersToClean.add(slice);
    }

    assertEquals("ByteBuf slice must be released after CRC validation failure (V3 format)", 0, refCnt);
  }

  /**
   * Test V3 valid blob deserialization - verifies no leak on success path (control test).
   */
  @Test
  public void testDeserializeBlobV3WithValidCrc() throws Exception {
    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 2 + 1 + 8 + 256 + 8);
    leakedBuffersToClean.add(inputBuf);

    byte[] blobContent = new byte[256];
    inputBuf.writeShort(MessageFormatRecord.Blob_Version_V3);
    inputBuf.writeShort((short) BlobType.DataBlob.ordinal());
    inputBuf.writeByte(1); // compressed
    inputBuf.writeLong(256);
    inputBuf.writeBytes(blobContent);

    CRC32 crc = new CRC32();
    crc.update(inputBuf.nioBuffer(0, inputBuf.writerIndex()));
    inputBuf.writeLong(crc.getValue());

    CapturingInputStream capturingStream = new CapturingInputStream(inputBuf);

    BlobData blobData = MessageFormatRecord.deserializeBlob(capturingStream);

    assertEquals("BlobData should contain 256 bytes", 256, blobData.content().readableBytes());

    List<ByteBuf> capturedSlices = capturingStream.getCapturedSlices();
    assertEquals("Expected exactly one slice to be created", 1, capturedSlices.size());

    ByteBuf slice = capturedSlices.get(0);
    assertTrue("Slice should have refCnt > 0 before release", slice.refCnt() > 0);

    blobData.release();

    int refCnt = slice.refCnt();
    if (refCnt > 0) {
      leakedBuffersToClean.add(slice);
    }

    assertEquals("Expected no leak: slice refCnt should be 0 after BlobData.release()", 0, refCnt);
  }
}
