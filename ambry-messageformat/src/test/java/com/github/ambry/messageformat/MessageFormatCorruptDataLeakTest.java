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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ByteProcessor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;
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
 * {@code MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord} allocates a ByteBuf slice
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
 *   <li>{@link #testDeserializeBlobWithCorruptCrc()} - Error path (leak detection)</li>
 *   <li>{@link #testDeserializeBlobWithValidCrc()} - Success path (control test)</li>
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
   * Minimal ByteBuf wrapper that captures slice creation and delegates only necessary methods.
   *
   * <p><b>Implemented methods:</b></p>
   * <ul>
   *   <li>{@code slice(int, int)} - Intercepted to capture slices</li>
   *   <li>{@code readerIndex()} and {@code readerIndex(int)} - Used by Utils</li>
   *   <li>{@code order()} and {@code order(ByteOrder)} - Required by ByteBuf abstract class</li>
   * </ul>
   *
   * <p><b>All other methods:</b> Throw {@link UnsupportedOperationException} to ensure
   * explicit API contract. If production code calls an unsupported method, the test fails
   * loudly rather than silently delegating unexpected behavior.</p>
   */
  private static class SliceCapturingByteBuf extends ByteBuf {
    private final ByteBuf delegate;
    private final List<ByteBuf> capturedSlices = new ArrayList<>();

    public SliceCapturingByteBuf(ByteBuf delegate) {
      this.delegate = delegate;
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

    @Override
    public int readerIndex() {
      return delegate.readerIndex();
    }

    @Override
    public ByteBuf readerIndex(int readerIndex) {
      return delegate.readerIndex(readerIndex);
    }

    @Override
    public ByteOrder order() {
      return delegate.order();
    }

    @Override
    public ByteBuf order(ByteOrder endianness) {
      return delegate.order(endianness);
    }

    private static final String UNSUPPORTED_MSG =
        "This method is not implemented by SliceCapturingByteBuf. " +
        "If production code requires this method, add explicit delegation.";

    @Override public int capacity() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf capacity(int newCapacity) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int maxCapacity() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBufAllocator alloc() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf unwrap() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean isDirect() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean isReadOnly() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf asReadOnly() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int writerIndex() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writerIndex(int writerIndex) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setIndex(int readerIndex, int writerIndex) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readableBytes() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int writableBytes() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int maxWritableBytes() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean isReadable() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean isReadable(int size) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean isWritable() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean isWritable(int size) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf clear() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf markReaderIndex() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf resetReaderIndex() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf markWriterIndex() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf resetWriterIndex() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf discardReadBytes() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf discardSomeReadBytes() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf ensureWritable(int minWritableBytes) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int ensureWritable(int minWritableBytes, boolean force) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean getBoolean(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public byte getByte(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public short getUnsignedByte(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public short getShort(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public short getShortLE(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int getUnsignedShort(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int getUnsignedShortLE(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int getMedium(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int getMediumLE(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int getUnsignedMedium(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int getUnsignedMediumLE(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int getInt(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int getIntLE(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public long getUnsignedInt(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public long getUnsignedIntLE(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public long getLong(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public long getLongLE(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public char getChar(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public float getFloat(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public double getDouble(int index) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf getBytes(int index, ByteBuf dst) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf getBytes(int index, ByteBuf dst, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf getBytes(int index, byte[] dst) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf getBytes(int index, ByteBuffer dst) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf getBytes(int index, java.io.OutputStream out, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int getBytes(int index, GatheringByteChannel out, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int getBytes(int index, FileChannel out, long position, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public CharSequence getCharSequence(int index, int length, Charset charset) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setBoolean(int index, boolean value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setByte(int index, int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setShort(int index, int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setShortLE(int index, int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setMedium(int index, int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setMediumLE(int index, int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setInt(int index, int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setIntLE(int index, int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setLong(int index, long value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setLongLE(int index, long value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setChar(int index, int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setFloat(int index, float value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setDouble(int index, double value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setBytes(int index, ByteBuf src) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setBytes(int index, ByteBuf src, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setBytes(int index, byte[] src) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setBytes(int index, ByteBuffer src) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int setBytes(int index, java.io.InputStream in, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int setBytes(int index, FileChannel in, long position, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf setZero(int index, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int setCharSequence(int index, CharSequence sequence, Charset charset) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean readBoolean() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public byte readByte() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public short readUnsignedByte() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public short readShort() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public short readShortLE() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readUnsignedShort() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readUnsignedShortLE() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readMedium() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readMediumLE() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readUnsignedMedium() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readUnsignedMediumLE() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readInt() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readIntLE() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public long readUnsignedInt() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public long readUnsignedIntLE() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public long readLong() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public long readLongLE() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public char readChar() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public float readFloat() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public double readDouble() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf readBytes(int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf readSlice(int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf readRetainedSlice(int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf readBytes(ByteBuf dst) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf readBytes(ByteBuf dst, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf readBytes(ByteBuf dst, int dstIndex, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf readBytes(byte[] dst) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf readBytes(byte[] dst, int dstIndex, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf readBytes(ByteBuffer dst) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf readBytes(java.io.OutputStream out, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readBytes(GatheringByteChannel out, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public CharSequence readCharSequence(int length, Charset charset) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int readBytes(FileChannel out, long position, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf skipBytes(int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeBoolean(boolean value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeByte(int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeShort(int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeShortLE(int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeMedium(int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeMediumLE(int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeInt(int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeIntLE(int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeLong(long value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeLongLE(long value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeChar(int value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeFloat(float value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeDouble(double value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeBytes(ByteBuf src) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeBytes(ByteBuf src, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeBytes(ByteBuf src, int srcIndex, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeBytes(byte[] src) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeBytes(byte[] src, int srcIndex, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeBytes(ByteBuffer src) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int writeBytes(java.io.InputStream in, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int writeBytes(ScatteringByteChannel in, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int writeBytes(FileChannel in, long position, int length) throws IOException { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf writeZero(int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int writeCharSequence(CharSequence sequence, Charset charset) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int indexOf(int fromIndex, int toIndex, byte value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int bytesBefore(byte value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int bytesBefore(int length, byte value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int bytesBefore(int index, int length, byte value) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int forEachByte(ByteProcessor processor) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int forEachByte(int index, int length, ByteProcessor processor) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int forEachByteDesc(ByteProcessor processor) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int forEachByteDesc(int index, int length, ByteProcessor processor) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf copy() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf copy(int index, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf slice() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf retainedSlice() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf retainedSlice(int index, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf duplicate() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf retainedDuplicate() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int nioBufferCount() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuffer nioBuffer() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuffer nioBuffer(int index, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuffer internalNioBuffer(int index, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuffer[] nioBuffers() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuffer[] nioBuffers(int index, int length) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean hasArray() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public byte[] array() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int arrayOffset() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean hasMemoryAddress() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public long memoryAddress() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public String toString(Charset charset) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public String toString(int index, int length, Charset charset) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int hashCode() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean equals(Object obj) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int compareTo(ByteBuf buffer) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public String toString() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf retain(int increment) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf retain() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf touch() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public ByteBuf touch(Object hint) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public int refCnt() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean release() { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
    @Override public boolean release(int decrement) { throw new UnsupportedOperationException(UNSUPPORTED_MSG); }
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
}
