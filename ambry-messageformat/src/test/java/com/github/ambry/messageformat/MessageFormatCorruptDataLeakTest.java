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

import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import java.io.IOException;
import java.nio.ByteBuffer;
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
 * allocates a ByteBuf but throws MessageFormatException on CRC mismatch without releasing it.
 *
 * PRODUCTION CODE PATH: GetBlobOperation.java:1202-1214
 *   GetBlobOperation calls processGetBlobResponse() which calls handleBody() which calls
 *   MessageFormatRecord.deserializeBlob(). When corrupt data is received (disk corruption,
 *   network corruption, etc.), the CRC check fails and MessageFormatException is thrown.
 *   The exception is caught in GetBlobOperation but the ByteBuf is already leaked inside
 *   deserializeBlob().
 *
 * These tests currently FAIL because the leak exists. They will PASS once the production code
 * is fixed to release ByteBuf in error cases.
 */
public class MessageFormatCorruptDataLeakTest {

  private final NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();

  @Before
  public void before() {
    nettyByteBufLeakHelper.beforeTest();
  }

  @After
  public void after() {
    // This will currently FAIL due to the leak, but will PASS once we fix the production code
    nettyByteBufLeakHelper.afterTest();
  }

  /**
   * TEST: Corrupt Blob - GetBlobOperation Production Code Path
   *
   * REPLICATES: GetBlobOperation.java:1119
   *   BlobData blobData = MessageFormatRecord.deserializeBlob(payload);
   *
   * When GetBlobOperation receives corrupt blob data from storage/network (CRC mismatch),
   * deserializeBlob() throws MessageFormatException. The exception is caught at line 1204,
   * but the ByteBuf allocated at MessageFormatRecord.java:1687 is already leaked.
   *
   * PRODUCTION SCENARIO:
   * - Disk corruption (bit flips)
   * - Network corruption during replication
   * - Partial writes causing CRC mismatch
   */
  @Test
  public void testGetBlobOperation_CorruptDataFromNetwork_LeaksByteBuffer() throws Exception {
    // Simulate blob data received from network with corrupt CRC
    byte[] blobContent = new byte[1024];
    for (int i = 0; i < 1024; i++) {
      blobContent[i] = (byte) (i % 256);
    }

    ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + 1024 + 8);
    serialized.putShort(MessageFormatRecord.Blob_Version_V1);
    serialized.putLong(1024);
    serialized.put(blobContent);

    // Calculate correct CRC
    CRC32 crc = new CRC32();
    crc.update(serialized.array(), 0, serialized.position());

    // Write WRONG CRC (simulate corruption from disk/network)
    serialized.putLong(crc.getValue() + 12345);
    serialized.flip();

    // GetBlobOperation.handleBody() calls deserializeBlob with this corrupt data
    // Line 1119: BlobData blobData = MessageFormatRecord.deserializeBlob(payload);
    try {
      BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));
      fail("Should have thrown MessageFormatException due to corrupt CRC");
    } catch (MessageFormatException e) {
      // GetBlobOperation catches this at line 1204:
      // catch (IOException | MessageFormatException e)
      // But ByteBuf allocated at MessageFormatRecord.java:1687 is already leaked!
      assertEquals(MessageFormatErrorCodes.DataCorrupt, e.getErrorCode());
    }

    // NettyByteBufLeakHelper.afterTest() will detect the leak and fail this test
    // Once we fix the production code to release ByteBuf on error, this test will pass
  }

  /**
   * TEST: Valid Blob - No Leak (Control Test)
   *
   * REPLICATES: GetBlobOperation.java:1119 with VALID data
   *   BlobData blobData = MessageFormatRecord.deserializeBlob(payload);
   *
   * When GetBlobOperation receives VALID blob data, deserializeBlob() succeeds and returns
   * BlobData. The caller properly releases it later. This test verifies that valid blobs
   * do NOT leak - the leak is only in the error path (CRC mismatch).
   *
   * PRODUCTION SCENARIO:
   * - Normal successful blob retrieval from storage/network
   */
  @Test
  public void testGetBlobOperation_ValidData_NoLeak() throws Exception {
    // Simulate valid blob data received from network
    byte[] blobContent = new byte[512];
    for (int i = 0; i < 512; i++) {
      blobContent[i] = (byte) i;
    }

    ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + 512 + 8);
    serialized.putShort(MessageFormatRecord.Blob_Version_V1);
    serialized.putLong(512);
    serialized.put(blobContent);

    // CORRECT CRC (no corruption)
    CRC32 crc = new CRC32();
    crc.update(serialized.array(), 0, serialized.position());
    serialized.putLong(crc.getValue());
    serialized.flip();

    // GetBlobOperation.handleBody() calls deserializeBlob with valid data
    // Line 1119: BlobData blobData = MessageFormatRecord.deserializeBlob(payload);
    BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));

    // Verify data (simulates GetBlobOperation using the blob)
    byte[] readContent = new byte[512];
    blobData.content().readBytes(readContent);

    // GetBlobOperation properly releases BlobData later
    blobData.release();

    // NettyByteBufLeakHelper.afterTest() will verify no leak - this test should PASS
  }
}
