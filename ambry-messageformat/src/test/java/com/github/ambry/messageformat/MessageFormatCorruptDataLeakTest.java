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
   * TEST: Corrupt Blob V1 - CRC Mismatch Causes ByteBuf Leak
   *
   * PRODUCTION CODE PATH:
   * 1. MessageFormatRecord.deserializeBlob() called with corrupt data
   * 2. Blob_Format_V1.deserializeBlobRecord() line 1687: ByteBuf allocated
   * 3. Line 1690-1694: CRC check fails, MessageFormatException thrown
   * 4. ByteBuf never released (NO try-finally block)
   * 5. LEAK: ByteBuf remains allocated
   *
   * This test exposes the bug by using NettyByteBufLeakHelper to detect the leaked ByteBuf.
   */
  @Test
  public void testCorruptBlobV1_CrcMismatch_LeaksByteBuffer() throws Exception {
    // Create blob data with INCORRECT CRC (production corruption scenario)
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

    // Write WRONG CRC (simulate corruption)
    serialized.putLong(crc.getValue() + 12345);
    serialized.flip();

    // Attempt deserialization - this will trigger the leak
    try {
      BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));
      fail("Should have thrown MessageFormatException due to corrupt CRC");
    } catch (MessageFormatException e) {
      // Expected exception
      assertEquals(MessageFormatErrorCodes.DataCorrupt, e.getErrorCode());
      // ByteBuf allocated at line 1687 is now leaked (not released before exception)
    }

    // NettyByteBufLeakHelper.afterTest() will detect the leak and fail this test
    // Once we fix the production code to release ByteBuf on error, this test will pass
  }

  /**
   * TEST: Multiple Corrupt Blobs - Leak Accumulation
   *
   * PRODUCTION SCENARIO: During replication or recovery, multiple corrupt blobs
   * may be encountered (disk corruption, network errors, etc.).
   * Each corrupt blob leaks a ByteBuf, accumulating over time.
   */
  @Test
  public void testMultipleCorruptBlobs_AccumulatesLeaks() throws Exception {
    // Simulate processing 3 corrupt blobs (production pattern)
    for (int i = 0; i < 3; i++) {
      byte[] blobContent = new byte[512 + i * 100];

      ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + blobContent.length + 8);
      serialized.putShort(MessageFormatRecord.Blob_Version_V1);
      serialized.putLong(blobContent.length);
      serialized.put(blobContent);

      CRC32 crc = new CRC32();
      crc.update(serialized.array(), 0, serialized.position());

      // Corrupt CRC
      serialized.putLong(crc.getValue() ^ 0xDEADBEEF);
      serialized.flip();

      try {
        MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));
        fail("Should have thrown MessageFormatException");
      } catch (MessageFormatException e) {
        // Each iteration leaks a ByteBuf
        assertEquals(MessageFormatErrorCodes.DataCorrupt, e.getErrorCode());
      }
    }

    // NettyByteBufLeakHelper will detect 3 leaked ByteBufs
  }

  /**
   * TEST: Large Blob Corruption - Significant Memory Leak
   *
   * PRODUCTION SCENARIO: Corruption in large blobs (multi-MB) causes significant
   * native memory leaks. This is HIGH SEVERITY as it can quickly exhaust memory.
   */
  @Test
  public void testLargeCorruptBlob_SignificantLeak() throws Exception {
    // 1MB blob (realistic production size)
    byte[] largeBlobContent = new byte[1024 * 1024];

    ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + largeBlobContent.length + 8);
    serialized.putShort(MessageFormatRecord.Blob_Version_V1);
    serialized.putLong(largeBlobContent.length);
    serialized.put(largeBlobContent);

    CRC32 crc = new CRC32();
    crc.update(serialized.array(), 0, serialized.position());

    // Corrupt CRC
    serialized.putLong(~crc.getValue());
    serialized.flip();

    try {
      MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));
      fail("Should have thrown MessageFormatException");
    } catch (MessageFormatException e) {
      assertEquals(MessageFormatErrorCodes.DataCorrupt, e.getErrorCode());
      // 1MB ByteBuf is now leaked - severe memory leak!
    }

    // NettyByteBufLeakHelper will detect the 1MB leaked ByteBuf
  }

  /**
   * TEST: Corrupt Blob in Try-Catch Without Finally
   *
   * PRODUCTION PATTERN: Code that catches MessageFormatException but doesn't
   * have a finally block to clean up. This is the exact bug pattern in
   * MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord.
   */
  @Test
  public void testCorruptBlob_TryCatchWithoutFinally_Leaks() throws Exception {
    byte[] blobContent = new byte[256];

    ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + 256 + 8);
    serialized.putShort(MessageFormatRecord.Blob_Version_V1);
    serialized.putLong(256);
    serialized.put(blobContent);

    CRC32 crc = new CRC32();
    crc.update(serialized.array(), 0, serialized.position());

    // Zero out CRC (simulate corruption)
    serialized.putLong(0L);
    serialized.flip();

    // Pattern: try-catch without finally (mirrors production bug)
    try {
      BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));
      // If we get here, something is wrong with the test
      fail("Should have thrown MessageFormatException");
    } catch (MessageFormatException | IOException e) {
      // Production code catches exception here but ByteBuf is already leaked
      // The ByteBuf was allocated at line 1687 but never released
    }
    // No finally block to release ByteBuf - LEAK!

    // NettyByteBufLeakHelper will detect the leak
  }

  /**
   * TEST: Valid Blob - No Leak (Control Test)
   *
   * This test verifies that VALID blobs do NOT leak. This proves that the leak
   * is specifically in the error path (CRC mismatch), not in normal operation.
   */
  @Test
  public void testValidBlob_NoLeak() throws Exception {
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

    // Deserialize successfully
    BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));

    // Verify data
    byte[] readContent = new byte[512];
    blobData.content().readBytes(readContent);

    // Proper cleanup
    blobData.release();

    // NettyByteBufLeakHelper.afterTest() will verify no leak - this test should PASS
  }

  /**
   * TEST: Corrupt Blob Followed By Valid Blob - Mixed Scenario
   *
   * PRODUCTION SCENARIO: System processes both corrupt and valid blobs.
   * Only corrupt ones should leak in current buggy code.
   */
  @Test
  public void testCorruptThenValid_OnlyCorruptLeaks() throws Exception {
    // First: Process corrupt blob (will leak)
    byte[] corruptContent = new byte[128];
    ByteBuffer corruptSerialized = ByteBuffer.allocate(2 + 8 + 128 + 8);
    corruptSerialized.putShort(MessageFormatRecord.Blob_Version_V1);
    corruptSerialized.putLong(128);
    corruptSerialized.put(corruptContent);
    CRC32 corruptCrc = new CRC32();
    corruptCrc.update(corruptSerialized.array(), 0, corruptSerialized.position());
    corruptSerialized.putLong(corruptCrc.getValue() + 999); // Corrupt
    corruptSerialized.flip();

    try {
      MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(corruptSerialized));
      fail("Should have thrown MessageFormatException");
    } catch (MessageFormatException e) {
      // Leaked ByteBuf from corrupt blob
    }

    // Second: Process valid blob (should NOT leak)
    byte[] validContent = new byte[128];
    ByteBuffer validSerialized = ByteBuffer.allocate(2 + 8 + 128 + 8);
    validSerialized.putShort(MessageFormatRecord.Blob_Version_V1);
    validSerialized.putLong(128);
    validSerialized.put(validContent);
    CRC32 validCrc = new CRC32();
    validCrc.update(validSerialized.array(), 0, validSerialized.position());
    validSerialized.putLong(validCrc.getValue()); // CORRECT
    validSerialized.flip();

    BlobData validBlob = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(validSerialized));
    validBlob.release(); // Properly released

    // NettyByteBufLeakHelper will detect 1 leak (from corrupt blob only)
  }
}
