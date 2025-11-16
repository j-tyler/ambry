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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests to verify that MessageFormatRecord.deserializeBlob properly handles ByteBuf cleanup
 * when deserialization fails due to corrupt data (CRC mismatch).
 *
 * PRODUCTION BUG: MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord (lines 1681-1696)
 * When CRC validation fails, the ByteBuf allocated by Utils.readNettyByteBufFromCrcInputStream
 * (a slice of the input buffer) is never released, causing a leak.
 *
 * These tests use a capturing wrapper around NettyByteBufDataInputStream to intercept and track
 * ByteBuf slices created during deserialization, allowing verification of proper cleanup.
 */
public class MessageFormatCorruptDataLeakTest {
  private static final Logger logger = LoggerFactory.getLogger(MessageFormatCorruptDataLeakTest.class);

  // Track leaked buffers for cleanup in @After
  private final List<ByteBuf> leakedBuffersToClean = new ArrayList<>();

  /**
   * Custom InputStream wrapper that captures ByteBuf slices created during deserialization.
   *
   * Utils.readNettyByteBufFromCrcInputStream calls getBuffer() and then creates a slice.
   * We wrap the returned ByteBuf to intercept slice() calls and capture the created slice.
   */
  private static class CapturingNettyByteBufDataInputStream extends NettyByteBufDataInputStream {
    private final List<ByteBuf> capturedSlices = new ArrayList<>();
    private final SliceCapturingByteBuf wrappedBuffer;

    public CapturingNettyByteBufDataInputStream(ByteBuf buffer) {
      super(buffer);
      this.wrappedBuffer = new SliceCapturingByteBuf(buffer, capturedSlices);
    }

    @Override
    public ByteBuf getBuffer() {
      // Return our wrapper which will intercept slice() calls
      return wrappedBuffer;
    }

    public List<ByteBuf> getCapturedSlices() {
      return capturedSlices;
    }
  }

  /**
   * ByteBuf wrapper that intercepts slice() calls to capture created slices.
   * Delegates all other operations to the underlying buffer.
   */
  private static class SliceCapturingByteBuf extends ByteBuf {
    private final ByteBuf delegate;
    private final List<ByteBuf> sliceTracker;

    public SliceCapturingByteBuf(ByteBuf delegate, List<ByteBuf> sliceTracker) {
      this.delegate = delegate;
      this.sliceTracker = sliceTracker;
    }

    @Override
    public ByteBuf slice(int index, int length) {
      ByteBuf slice = delegate.slice(index, length);
      sliceTracker.add(slice);
      logger.debug("Captured slice: index={}, length={}, refCnt={}", index, length, slice.refCnt());
      return slice;
    }

    // Delegate all methods to underlying buffer
    @Override public int capacity() { return delegate.capacity(); }
    @Override public ByteBuf capacity(int newCapacity) { return delegate.capacity(newCapacity); }
    @Override public int maxCapacity() { return delegate.maxCapacity(); }
    @Override public io.netty.buffer.ByteBufAllocator alloc() { return delegate.alloc(); }
    @Override public io.netty.buffer.ByteOrder order() { return delegate.order(); }
    @Override public ByteBuf order(io.netty.buffer.ByteOrder endianness) { return delegate.order(endianness); }
    @Override public ByteBuf unwrap() { return delegate.unwrap(); }
    @Override public boolean isDirect() { return delegate.isDirect(); }
    @Override public boolean isReadOnly() { return delegate.isReadOnly(); }
    @Override public ByteBuf asReadOnly() { return delegate.asReadOnly(); }
    @Override public int readerIndex() { return delegate.readerIndex(); }
    @Override public ByteBuf readerIndex(int readerIndex) { return delegate.readerIndex(readerIndex); }
    @Override public int writerIndex() { return delegate.writerIndex(); }
    @Override public ByteBuf writerIndex(int writerIndex) { return delegate.writerIndex(writerIndex); }
    @Override public ByteBuf setIndex(int readerIndex, int writerIndex) { return delegate.setIndex(readerIndex, writerIndex); }
    @Override public int readableBytes() { return delegate.readableBytes(); }
    @Override public int writableBytes() { return delegate.writableBytes(); }
    @Override public int maxWritableBytes() { return delegate.maxWritableBytes(); }
    @Override public boolean isReadable() { return delegate.isReadable(); }
    @Override public boolean isReadable(int size) { return delegate.isReadable(size); }
    @Override public boolean isWritable() { return delegate.isWritable(); }
    @Override public boolean isWritable(int size) { return delegate.isWritable(size); }
    @Override public ByteBuf clear() { return delegate.clear(); }
    @Override public ByteBuf markReaderIndex() { return delegate.markReaderIndex(); }
    @Override public ByteBuf resetReaderIndex() { return delegate.resetReaderIndex(); }
    @Override public ByteBuf markWriterIndex() { return delegate.markWriterIndex(); }
    @Override public ByteBuf resetWriterIndex() { return delegate.resetWriterIndex(); }
    @Override public ByteBuf discardReadBytes() { return delegate.discardReadBytes(); }
    @Override public ByteBuf discardSomeReadBytes() { return delegate.discardSomeReadBytes(); }
    @Override public ByteBuf ensureWritable(int minWritableBytes) { return delegate.ensureWritable(minWritableBytes); }
    @Override public int ensureWritable(int minWritableBytes, boolean force) { return delegate.ensureWritable(minWritableBytes, force); }
    @Override public boolean getBoolean(int index) { return delegate.getBoolean(index); }
    @Override public byte getByte(int index) { return delegate.getByte(index); }
    @Override public short getUnsignedByte(int index) { return delegate.getUnsignedByte(index); }
    @Override public short getShort(int index) { return delegate.getShort(index); }
    @Override public short getShortLE(int index) { return delegate.getShortLE(index); }
    @Override public int getUnsignedShort(int index) { return delegate.getUnsignedShort(index); }
    @Override public int getUnsignedShortLE(int index) { return delegate.getUnsignedShortLE(index); }
    @Override public int getMedium(int index) { return delegate.getMedium(index); }
    @Override public int getMediumLE(int index) { return delegate.getMediumLE(index); }
    @Override public int getUnsignedMedium(int index) { return delegate.getUnsignedMedium(index); }
    @Override public int getUnsignedMediumLE(int index) { return delegate.getUnsignedMediumLE(index); }
    @Override public int getInt(int index) { return delegate.getInt(index); }
    @Override public int getIntLE(int index) { return delegate.getIntLE(index); }
    @Override public long getUnsignedInt(int index) { return delegate.getUnsignedInt(index); }
    @Override public long getUnsignedIntLE(int index) { return delegate.getUnsignedIntLE(index); }
    @Override public long getLong(int index) { return delegate.getLong(index); }
    @Override public long getLongLE(int index) { return delegate.getLongLE(index); }
    @Override public char getChar(int index) { return delegate.getChar(index); }
    @Override public float getFloat(int index) { return delegate.getFloat(index); }
    @Override public double getDouble(int index) { return delegate.getDouble(index); }
    @Override public ByteBuf getBytes(int index, ByteBuf dst) { return delegate.getBytes(index, dst); }
    @Override public ByteBuf getBytes(int index, ByteBuf dst, int length) { return delegate.getBytes(index, dst, length); }
    @Override public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) { return delegate.getBytes(index, dst, dstIndex, length); }
    @Override public ByteBuf getBytes(int index, byte[] dst) { return delegate.getBytes(index, dst); }
    @Override public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) { return delegate.getBytes(index, dst, dstIndex, length); }
    @Override public ByteBuf getBytes(int index, java.nio.ByteBuffer dst) { return delegate.getBytes(index, dst); }
    @Override public ByteBuf getBytes(int index, java.io.OutputStream out, int length) throws IOException { return delegate.getBytes(index, out, length); }
    @Override public int getBytes(int index, java.nio.channels.GatheringByteChannel out, int length) throws IOException { return delegate.getBytes(index, out, length); }
    @Override public int getBytes(int index, java.nio.channels.FileChannel out, long position, int length) throws IOException { return delegate.getBytes(index, out, position, length); }
    @Override public CharSequence getCharSequence(int index, int length, java.nio.charset.Charset charset) { return delegate.getCharSequence(index, length, charset); }
    @Override public ByteBuf setBoolean(int index, boolean value) { return delegate.setBoolean(index, value); }
    @Override public ByteBuf setByte(int index, int value) { return delegate.setByte(index, value); }
    @Override public ByteBuf setShort(int index, int value) { return delegate.setShort(index, value); }
    @Override public ByteBuf setShortLE(int index, int value) { return delegate.setShortLE(index, value); }
    @Override public ByteBuf setMedium(int index, int value) { return delegate.setMedium(index, value); }
    @Override public ByteBuf setMediumLE(int index, int value) { return delegate.setMediumLE(index, value); }
    @Override public ByteBuf setInt(int index, int value) { return delegate.setInt(index, value); }
    @Override public ByteBuf setIntLE(int index, int value) { return delegate.setIntLE(index, value); }
    @Override public ByteBuf setLong(int index, long value) { return delegate.setLong(index, value); }
    @Override public ByteBuf setLongLE(int index, long value) { return delegate.setLongLE(index, value); }
    @Override public ByteBuf setChar(int index, int value) { return delegate.setChar(index, value); }
    @Override public ByteBuf setFloat(int index, float value) { return delegate.setFloat(index, value); }
    @Override public ByteBuf setDouble(int index, double value) { return delegate.setDouble(index, value); }
    @Override public ByteBuf setBytes(int index, ByteBuf src) { return delegate.setBytes(index, src); }
    @Override public ByteBuf setBytes(int index, ByteBuf src, int length) { return delegate.setBytes(index, src, length); }
    @Override public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) { return delegate.setBytes(index, src, srcIndex, length); }
    @Override public ByteBuf setBytes(int index, byte[] src) { return delegate.setBytes(index, src); }
    @Override public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) { return delegate.setBytes(index, src, srcIndex, length); }
    @Override public ByteBuf setBytes(int index, java.nio.ByteBuffer src) { return delegate.setBytes(index, src); }
    @Override public int setBytes(int index, java.io.InputStream in, int length) throws IOException { return delegate.setBytes(index, in, length); }
    @Override public int setBytes(int index, java.nio.channels.ScatteringByteChannel in, int length) throws IOException { return delegate.setBytes(index, in, length); }
    @Override public int setBytes(int index, java.nio.channels.FileChannel in, long position, int length) throws IOException { return delegate.setBytes(index, in, position, length); }
    @Override public ByteBuf setZero(int index, int length) { return delegate.setZero(index, length); }
    @Override public int setCharSequence(int index, CharSequence sequence, java.nio.charset.Charset charset) { return delegate.setCharSequence(index, sequence, charset); }
    @Override public boolean readBoolean() { return delegate.readBoolean(); }
    @Override public byte readByte() { return delegate.readByte(); }
    @Override public short readUnsignedByte() { return delegate.readUnsignedByte(); }
    @Override public short readShort() { return delegate.readShort(); }
    @Override public short readShortLE() { return delegate.readShortLE(); }
    @Override public int readUnsignedShort() { return delegate.readUnsignedShort(); }
    @Override public int readUnsignedShortLE() { return delegate.readUnsignedShortLE(); }
    @Override public int readMedium() { return delegate.readMedium(); }
    @Override public int readMediumLE() { return delegate.readMediumLE(); }
    @Override public int readUnsignedMedium() { return delegate.readUnsignedMedium(); }
    @Override public int readUnsignedMediumLE() { return delegate.readUnsignedMediumLE(); }
    @Override public int readInt() { return delegate.readInt(); }
    @Override public int readIntLE() { return delegate.readIntLE(); }
    @Override public long readUnsignedInt() { return delegate.readUnsignedInt(); }
    @Override public long readUnsignedIntLE() { return delegate.readUnsignedIntLE(); }
    @Override public long readLong() { return delegate.readLong(); }
    @Override public long readLongLE() { return delegate.readLongLE(); }
    @Override public char readChar() { return delegate.readChar(); }
    @Override public float readFloat() { return delegate.readFloat(); }
    @Override public double readDouble() { return delegate.readDouble(); }
    @Override public ByteBuf readBytes(int length) { return delegate.readBytes(length); }
    @Override public ByteBuf readSlice(int length) { return delegate.readSlice(length); }
    @Override public ByteBuf readRetainedSlice(int length) { return delegate.readRetainedSlice(length); }
    @Override public ByteBuf readBytes(ByteBuf dst) { return delegate.readBytes(dst); }
    @Override public ByteBuf readBytes(ByteBuf dst, int length) { return delegate.readBytes(dst, length); }
    @Override public ByteBuf readBytes(ByteBuf dst, int dstIndex, int length) { return delegate.readBytes(dst, dstIndex, length); }
    @Override public ByteBuf readBytes(byte[] dst) { return delegate.readBytes(dst); }
    @Override public ByteBuf readBytes(byte[] dst, int dstIndex, int length) { return delegate.readBytes(dst, dstIndex, length); }
    @Override public ByteBuf readBytes(java.nio.ByteBuffer dst) { return delegate.readBytes(dst); }
    @Override public ByteBuf readBytes(java.io.OutputStream out, int length) throws IOException { return delegate.readBytes(out, length); }
    @Override public int readBytes(java.nio.channels.GatheringByteChannel out, int length) throws IOException { return delegate.readBytes(out, length); }
    @Override public CharSequence readCharSequence(int length, java.nio.charset.Charset charset) { return delegate.readCharSequence(length, charset); }
    @Override public int readBytes(java.nio.channels.FileChannel out, long position, int length) throws IOException { return delegate.readBytes(out, position, length); }
    @Override public ByteBuf skipBytes(int length) { return delegate.skipBytes(length); }
    @Override public ByteBuf writeBoolean(boolean value) { return delegate.writeBoolean(value); }
    @Override public ByteBuf writeByte(int value) { return delegate.writeByte(value); }
    @Override public ByteBuf writeShort(int value) { return delegate.writeShort(value); }
    @Override public ByteBuf writeShortLE(int value) { return delegate.writeShortLE(value); }
    @Override public ByteBuf writeMedium(int value) { return delegate.writeMedium(value); }
    @Override public ByteBuf writeMediumLE(int value) { return delegate.writeMediumLE(value); }
    @Override public ByteBuf writeInt(int value) { return delegate.writeInt(value); }
    @Override public ByteBuf writeIntLE(int value) { return delegate.writeIntLE(value); }
    @Override public ByteBuf writeLong(long value) { return delegate.writeLong(value); }
    @Override public ByteBuf writeLongLE(long value) { return delegate.writeLongLE(value); }
    @Override public ByteBuf writeChar(int value) { return delegate.writeChar(value); }
    @Override public ByteBuf writeFloat(float value) { return delegate.writeFloat(value); }
    @Override public ByteBuf writeDouble(double value) { return delegate.writeDouble(value); }
    @Override public ByteBuf writeBytes(ByteBuf src) { return delegate.writeBytes(src); }
    @Override public ByteBuf writeBytes(ByteBuf src, int length) { return delegate.writeBytes(src, length); }
    @Override public ByteBuf writeBytes(ByteBuf src, int srcIndex, int length) { return delegate.writeBytes(src, srcIndex, length); }
    @Override public ByteBuf writeBytes(byte[] src) { return delegate.writeBytes(src); }
    @Override public ByteBuf writeBytes(byte[] src, int srcIndex, int length) { return delegate.writeBytes(src, srcIndex, length); }
    @Override public ByteBuf writeBytes(java.nio.ByteBuffer src) { return delegate.writeBytes(src); }
    @Override public int writeBytes(java.io.InputStream in, int length) throws IOException { return delegate.writeBytes(in, length); }
    @Override public int writeBytes(java.nio.channels.ScatteringByteChannel in, int length) throws IOException { return delegate.writeBytes(in, length); }
    @Override public int writeBytes(java.nio.channels.FileChannel in, long position, int length) throws IOException { return delegate.writeBytes(in, position, length); }
    @Override public ByteBuf writeZero(int length) { return delegate.writeZero(length); }
    @Override public int writeCharSequence(CharSequence sequence, java.nio.charset.Charset charset) { return delegate.writeCharSequence(sequence, charset); }
    @Override public int indexOf(int fromIndex, int toIndex, byte value) { return delegate.indexOf(fromIndex, toIndex, value); }
    @Override public int bytesBefore(byte value) { return delegate.bytesBefore(value); }
    @Override public int bytesBefore(int length, byte value) { return delegate.bytesBefore(length, value); }
    @Override public int bytesBefore(int index, int length, byte value) { return delegate.bytesBefore(index, length, value); }
    @Override public int forEachByte(io.netty.util.ByteProcessor processor) { return delegate.forEachByte(processor); }
    @Override public int forEachByte(int index, int length, io.netty.util.ByteProcessor processor) { return delegate.forEachByte(index, length, processor); }
    @Override public int forEachByteDesc(io.netty.util.ByteProcessor processor) { return delegate.forEachByteDesc(processor); }
    @Override public int forEachByteDesc(int index, int length, io.netty.util.ByteProcessor processor) { return delegate.forEachByteDesc(index, length, processor); }
    @Override public ByteBuf copy() { return delegate.copy(); }
    @Override public ByteBuf copy(int index, int length) { return delegate.copy(index, length); }
    @Override public ByteBuf slice() { return delegate.slice(); }
    @Override public ByteBuf retainedSlice() { return delegate.retainedSlice(); }
    @Override public ByteBuf retainedSlice(int index, int length) { return delegate.retainedSlice(index, length); }
    @Override public ByteBuf duplicate() { return delegate.duplicate(); }
    @Override public ByteBuf retainedDuplicate() { return delegate.retainedDuplicate(); }
    @Override public int nioBufferCount() { return delegate.nioBufferCount(); }
    @Override public java.nio.ByteBuffer nioBuffer() { return delegate.nioBuffer(); }
    @Override public java.nio.ByteBuffer nioBuffer(int index, int length) { return delegate.nioBuffer(index, length); }
    @Override public java.nio.ByteBuffer internalNioBuffer(int index, int length) { return delegate.internalNioBuffer(index, length); }
    @Override public java.nio.ByteBuffer[] nioBuffers() { return delegate.nioBuffers(); }
    @Override public java.nio.ByteBuffer[] nioBuffers(int index, int length) { return delegate.nioBuffers(index, length); }
    @Override public boolean hasArray() { return delegate.hasArray(); }
    @Override public byte[] array() { return delegate.array(); }
    @Override public int arrayOffset() { return delegate.arrayOffset(); }
    @Override public boolean hasMemoryAddress() { return delegate.hasMemoryAddress(); }
    @Override public long memoryAddress() { return delegate.memoryAddress(); }
    @Override public String toString(java.nio.charset.Charset charset) { return delegate.toString(charset); }
    @Override public String toString(int index, int length, java.nio.charset.Charset charset) { return delegate.toString(index, length, charset); }
    @Override public int hashCode() { return delegate.hashCode(); }
    @Override public boolean equals(Object obj) { return delegate.equals(obj); }
    @Override public int compareTo(ByteBuf buffer) { return delegate.compareTo(buffer); }
    @Override public String toString() { return delegate.toString(); }
    @Override public ByteBuf retain(int increment) { return delegate.retain(increment); }
    @Override public ByteBuf retain() { return delegate.retain(); }
    @Override public ByteBuf touch() { return delegate.touch(); }
    @Override public ByteBuf touch(Object hint) { return delegate.touch(hint); }
    @Override public int refCnt() { return delegate.refCnt(); }
    @Override public boolean release() { return delegate.release(); }
    @Override public boolean release(int decrement) { return delegate.release(decrement); }
  }

  @After
  public void cleanup() {
    // Clean up any leaked buffers to prevent cross-test contamination
    for (ByteBuf leaked : leakedBuffersToClean) {
      if (leaked.refCnt() > 0) {
        logger.warn("Cleaning up leaked buffer with refCnt={}", leaked.refCnt());
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
   * The test uses a capturing wrapper to intercept slice() calls and obtain a reference to
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

    // Use capturing stream to intercept slice creation
    CapturingNettyByteBufDataInputStream capturingStream =
        new CapturingNettyByteBufDataInputStream(inputBuf);

    try {
      // Serialize blob data with CORRUPT CRC
      byte[] blobContent = new byte[1024];
      for (int i = 0; i < 1024; i++) {
        blobContent[i] = (byte) (i % 256);
      }

      inputBuf.writeShort(MessageFormatRecord.Blob_Version_V1);
      inputBuf.writeLong(1024);
      inputBuf.writeBytes(blobContent);

      // Calculate correct CRC but write WRONG value
      CRC32 crc = new CRC32();
      crc.update(inputBuf.nioBuffer(0, inputBuf.writerIndex()));
      inputBuf.writeLong(crc.getValue() + 12345); // Corrupt CRC

      // Create CrcInputStream wrapping our capturing stream
      CrcInputStream crcStream = new CrcInputStream(capturingStream);

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
      List<ByteBuf> capturedSlices = capturingStream.getCapturedSlices();
      assertEquals("Expected exactly one slice to be created", 1, capturedSlices.size());

      ByteBuf slice = capturedSlices.get(0);
      int sliceRefCnt = slice.refCnt();

      logger.info("Slice refCnt after exception: {}", sliceRefCnt);

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

    CapturingNettyByteBufDataInputStream capturingStream =
        new CapturingNettyByteBufDataInputStream(inputBuf);

    try {
      // Serialize blob data with CORRECT CRC
      byte[] blobContent = new byte[512];
      for (int i = 0; i < 512; i++) {
        blobContent[i] = (byte) i;
      }

      inputBuf.writeShort(MessageFormatRecord.Blob_Version_V1);
      inputBuf.writeLong(512);
      inputBuf.writeBytes(blobContent);

      // Calculate and write CORRECT CRC
      CRC32 crc = new CRC32();
      crc.update(inputBuf.nioBuffer(0, inputBuf.writerIndex()));
      inputBuf.writeLong(crc.getValue());

      CrcInputStream crcStream = new CrcInputStream(capturingStream);

      // Deserialize successfully
      BlobData blobData = MessageFormatRecord.deserializeBlob(crcStream);

      // Use the data
      assertEquals(512, blobData.content().readableBytes());
      byte[] readContent = new byte[512];
      blobData.content().readBytes(readContent);

      // Verify slice was created
      List<ByteBuf> capturedSlices = capturingStream.getCapturedSlices();
      assertEquals("Expected exactly one slice to be created", 1, capturedSlices.size());

      ByteBuf slice = capturedSlices.get(0);
      int refCntBeforeRelease = slice.refCnt();
      logger.info("Slice refCnt before BlobData.release(): {}", refCntBeforeRelease);

      // Proper cleanup - this should release the slice
      blobData.release();

      int refCntAfterRelease = slice.refCnt();
      logger.info("Slice refCnt after BlobData.release(): {}", refCntAfterRelease);

      // Assert no leak: slice should be released (refCnt = 0)
      assertEquals("Expected no leak: slice refCnt should be 0 after BlobData.release()",
          0, refCntAfterRelease);

    } finally {
      inputBuf.release();
    }
  }
}
