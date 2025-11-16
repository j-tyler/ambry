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
import com.github.ambry.utils.NettyByteBufLeakHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.zip.CRC32;
import org.junit.After;
import org.junit.Before;
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
 * These tests use NettyByteBufDataInputStream backed by PooledByteBufAllocator to trigger
 * the production code path and NettyByteBufLeakHelper to detect leaks.
 */
public class MessageFormatCorruptDataLeakTest {

  private final NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();

  @Before
  public void before() {
    nettyByteBufLeakHelper.beforeTest();
  }

  @After
  public void after() {
    nettyByteBufLeakHelper.afterTest();
  }

  /**
   * Test corrupt blob deserialization with CRC mismatch.
   *
   * This test exposes the leak in MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord.
   * When using NettyByteBufDataInputStream, Utils.readNettyByteBufFromCrcInputStream creates
   * a slice of the input ByteBuf. When CRC validation fails, this slice is never released.
   *
   * Expected behavior:
   * - FAILS when bug exists (NettyByteBufLeakHelper detects leak)
   * - PASSES when bug is fixed (ByteBuf released in try-finally)
   */
  @Test
  public void testDeserializeBlobWithCorruptCrc() throws Exception {
    // Allocate pooled heap ByteBuf (required for NettyByteBufLeakHelper detection)
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
      crc.update(inputBuf.array(), inputBuf.arrayOffset(), inputBuf.writerIndex());
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

    // NettyByteBufLeakHelper.afterTest() will detect the leaked slice
  }

  /**
   * Test valid blob deserialization (control test).
   *
   * This verifies that valid blobs do NOT leak when properly released.
   * Only the error path (corrupt CRC) should leak.
   */
  @Test
  public void testDeserializeBlobWithValidCrc() throws Exception {
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
      crc.update(inputBuf.array(), inputBuf.arrayOffset(), inputBuf.writerIndex());
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

    // NettyByteBufLeakHelper.afterTest() should pass - no leak
  }
}
