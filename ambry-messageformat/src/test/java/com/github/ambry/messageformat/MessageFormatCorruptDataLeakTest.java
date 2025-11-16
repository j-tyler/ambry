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
import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Test;

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
 * These tests use NettyByteBufDataInputStream backed by PooledByteBufAllocator to trigger
 * the production code path and directly check allocator metrics to detect leaks.
 */
public class MessageFormatCorruptDataLeakTest {

  /**
   * Get current active heap allocations from PooledByteBufAllocator.
   */
  private long getActiveHeapAllocations() {
    PooledByteBufAllocatorMetric metric = PooledByteBufAllocator.DEFAULT.metric();
    List<PoolArenaMetric> heaps = metric.heapArenas();
    return heaps.stream().mapToLong(PoolArenaMetric::numActiveAllocations).sum();
  }

  /**
   * Get current active direct allocations from PooledByteBufAllocator.
   */
  private long getActiveDirectAllocations() {
    PooledByteBufAllocatorMetric metric = PooledByteBufAllocator.DEFAULT.metric();
    List<PoolArenaMetric> directs = metric.directArenas();
    return directs.stream().mapToLong(PoolArenaMetric::numActiveAllocations).sum();
  }

  /**
   * Test corrupt blob deserialization with CRC mismatch - demonstrates the ByteBuf leak.
   *
   * This test exposes the leak in MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord.
   * When using NettyByteBufDataInputStream, Utils.readNettyByteBufFromCrcInputStream creates
   * a slice of the input ByteBuf. When CRC validation fails, this slice is never released.
   *
   * The test checks PooledByteBufAllocator metrics to detect the leak:
   * - Records active allocations before deserialization
   * - Calls deserializeBlob which throws exception (leaking the slice)
   * - Releases parent buffer
   * - Verifies active allocations increased (proving leak exists)
   *
   * Expected behavior:
   * - FAILS (shows leak) when bug exists
   * - PASSES when bug is fixed (ByteBuf released in try-finally)
   */
  @Test
  public void testDeserializeBlobWithCorruptCrc() throws Exception {
    // Record active allocations BEFORE test
    long heapBefore = getActiveHeapAllocations();
    long directBefore = getActiveDirectAllocations();

    // Allocate pooled heap ByteBuf
    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 8 + 1024 + 8);

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

      // Create NettyByteBufDataInputStream (triggers production code path)
      NettyByteBufDataInputStream inputStream = new NettyByteBufDataInputStream(inputBuf);
      CrcInputStream crcStream = new CrcInputStream(inputStream);

      // Attempt deserialization - this will leak the sliced ByteBuf
      try {
        BlobData blobData = MessageFormatRecord.deserializeBlob(crcStream);
        fail("Should have thrown MessageFormatException due to corrupt CRC");
      } catch (MessageFormatException e) {
        // Expected - CRC validation failed
        assertEquals(MessageFormatErrorCodes.DataCorrupt, e.getErrorCode());
        // BUG: ByteBuf slice created at Utils.java:413 is never released
      }
    } finally {
      // Release the original input buffer
      inputBuf.release();
    }

    // Check allocations AFTER releasing parent
    // If bug exists: slice is still active (leak detected)
    // If bug fixed: slice was released in try-finally (no leak)
    long heapAfter = getActiveHeapAllocations();
    long directAfter = getActiveDirectAllocations();

    System.out.println("Heap allocations: before=" + heapBefore + ", after=" + heapAfter + ", diff=" + (heapAfter - heapBefore));
    System.out.println("Direct allocations: before=" + directBefore + ", after=" + directAfter + ", diff=" + (directAfter - directBefore));

    // Assert that we have a leak (active allocations increased)
    assertTrue("Expected ByteBuf leak to be detected: heap allocations should have increased",
        heapAfter > heapBefore);
  }

  /**
   * Test valid blob deserialization (control test).
   *
   * This verifies that valid blobs do NOT leak when properly released.
   * The valid path should show allocations return to baseline after cleanup.
   */
  @Test
  public void testDeserializeBlobWithValidCrc() throws Exception {
    // Record active allocations BEFORE test
    long heapBefore = getActiveHeapAllocations();
    long directBefore = getActiveDirectAllocations();

    ByteBuf inputBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(2 + 8 + 512 + 8);

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

      NettyByteBufDataInputStream inputStream = new NettyByteBufDataInputStream(inputBuf);
      CrcInputStream crcStream = new CrcInputStream(inputStream);

      // Deserialize successfully
      BlobData blobData = MessageFormatRecord.deserializeBlob(crcStream);

      // Use the data
      assertEquals(512, blobData.content().readableBytes());
      byte[] readContent = new byte[512];
      blobData.content().readBytes(readContent);

      // Proper cleanup
      blobData.release();
    } finally {
      inputBuf.release();
    }

    // Check allocations AFTER cleanup
    // All buffers should be released, returning to baseline
    long heapAfter = getActiveHeapAllocations();
    long directAfter = getActiveDirectAllocations();

    System.out.println("Heap allocations: before=" + heapBefore + ", after=" + heapAfter + ", diff=" + (heapAfter - heapBefore));
    System.out.println("Direct allocations: before=" + directBefore + ", after=" + directAfter + ", diff=" + (directAfter - directBefore));

    // Assert no leak (allocations returned to baseline)
    assertEquals("Expected no leak: heap allocations should return to baseline", heapBefore, heapAfter);
  }
}
