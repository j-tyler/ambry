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
package com.github.ambry.network;

import com.github.ambry.utils.NettyByteBufLeakHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for BoundedNettyByteBufReceive ByteBuf memory leak detection.
 * Tests MEDIUM-RISK paths:
 * - readFrom() IOException after sizeBuffer allocation (lines 80-84)
 * - readFrom() IOException after buffer allocation (lines 102-106)
 * - EOFException handling (lines 78, 100)
 */
public class BoundedNettyByteBufReceiveLeakTest {
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();
  private static final long MAX_REQUEST_SIZE = 1024 * 1024; // 1MB

  @Before
  public void setUp() {
    leakHelper.beforeTest();
  }

  @After
  public void tearDown() {
    leakHelper.afterTest();
  }

  /**
   * MEDIUM-RISK PATH #1: readFrom() IOException after sizeBuffer allocation
   *
   * Tests that if IOException occurs after sizeBuffer is allocated (line 73),
   * the catch block on lines 80-84 properly releases it.
   *
   * Line 81: sizeBuffer.release();
   * Line 82: sizeBuffer = null;
   *
   * Expected: No leak - sizeBuffer is released
   */
  @Test
  public void testReadFromIOExceptionAfterSizeBufferAllocation() throws Exception {
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(MAX_REQUEST_SIZE);

    // Create a channel that throws IOException after providing some bytes
    ReadableByteChannel faultyChannel = new ReadableByteChannel() {
      private int readCount = 0;

      @Override
      public int read(ByteBuffer dst) throws IOException {
        readCount++;
        if (readCount == 1) {
          // First read: provide partial size data (4 bytes out of 8)
          dst.putInt(0);
          return 4;
        } else {
          // Second read: throw IOException
          throw new IOException("Simulated channel read failure");
        }
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() {
      }
    };

    // First readFrom() call: reads 4 bytes successfully
    long firstRead = receive.readFrom(faultyChannel);
    assertEquals("First read should return 4 bytes", 4, firstRead);

    // Second readFrom() call: triggers IOException
    try {
      receive.readFrom(faultyChannel);
      fail("Should have thrown IOException");
    } catch (IOException e) {
      assertEquals("Simulated channel read failure", e.getMessage());
    }

    // sizeBuffer was allocated on line 73
    // Exception occurred on line 76
    // Catch block released it on line 81
    // No leak should be detected
  }

  /**
   * MEDIUM-RISK PATH #2: readFrom() IOException after buffer allocation
   *
   * Tests that if IOException occurs after buffer is allocated (line 92),
   * the catch block on lines 102-106 properly releases it.
   *
   * Line 103: buffer.release();
   * Line 104: buffer = null;
   *
   * Expected: No leak - buffer is released
   */
  @Test
  public void testReadFromIOExceptionAfterBufferAllocation() throws Exception {
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(MAX_REQUEST_SIZE);

    // Create a channel that provides size header then throws on data read
    ReadableByteChannel faultyChannel = new ReadableByteChannel() {
      private int readCount = 0;

      @Override
      public int read(ByteBuffer dst) throws IOException {
        readCount++;
        if (readCount == 1) {
          // First read: provide full size header (8 bytes)
          dst.putLong(100); // Request size = 100 bytes
          return 8;
        } else {
          // Second read: throw IOException when reading actual data
          throw new IOException("Simulated data read failure");
        }
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() {
      }
    };

    try {
      receive.readFrom(faultyChannel);
      fail("Should have thrown IOException");
    } catch (IOException e) {
      assertEquals("Simulated data read failure", e.getMessage());
    }

    // buffer was allocated on line 92 (100 - 8 = 92 bytes)
    // Exception occurred on line 98
    // Catch block released it on line 103
    // No leak should be detected
  }

  /**
   * MEDIUM-RISK PATH #3: EOFException during size header read
   *
   * Tests that EOFException thrown on line 78 doesn't leak sizeBuffer.
   *
   * Expected: No leak - exception occurs before full allocation
   */
  @Test
  public void testEOFExceptionDuringSizeHeaderRead() throws Exception {
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(MAX_REQUEST_SIZE);

    // Create a channel that returns -1 (EOF) immediately
    ReadableByteChannel eofChannel = new ReadableByteChannel() {
      @Override
      public int read(ByteBuffer dst) {
        return -1; // EOF
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() {
      }
    };

    try {
      receive.readFrom(eofChannel);
      fail("Should have thrown EOFException");
    } catch (EOFException e) {
      // Expected
    }

    // sizeBuffer was allocated but EOF occurred
    // Catch block on line 80-84 releases it
    // No leak
  }

  /**
   * MEDIUM-RISK PATH #4: EOFException during data read
   *
   * Tests that EOFException thrown on line 100 doesn't leak buffer.
   *
   * Expected: No leak - buffer is released in catch block
   */
  @Test
  public void testEOFExceptionDuringDataRead() throws Exception {
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(MAX_REQUEST_SIZE);

    // Create a channel that provides size then EOF on data
    ReadableByteChannel eofChannel = new ReadableByteChannel() {
      private int readCount = 0;

      @Override
      public int read(ByteBuffer dst) {
        readCount++;
        if (readCount == 1) {
          // Provide size header
          dst.putLong(100);
          return 8;
        } else {
          // EOF when trying to read data
          return -1;
        }
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() {
      }
    };

    try {
      receive.readFrom(eofChannel);
      fail("Should have thrown EOFException");
    } catch (EOFException e) {
      // Expected
    }

    // buffer was allocated on line 92
    // EOF occurred on line 99
    // EOFException thrown on line 100
    // Catch block on line 102-106 releases buffer
    // No leak
  }

  /**
   * MEDIUM-RISK PATH #5: Request size exceeds maximum
   *
   * Tests that if request size is larger than max (line 89-91),
   * IOException is thrown AFTER sizeBuffer is released (line 88).
   *
   * Expected: No leak - sizeBuffer released before exception
   */
  @Test
  public void testRequestSizeExceedsMaximum() throws Exception {
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(1000);

    // Create a channel that reports huge size
    ReadableByteChannel channel = new ReadableByteChannel() {
      @Override
      public int read(ByteBuffer dst) {
        dst.putLong(10000); // Way over the 1000 byte limit
        return 8;
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() {
      }
    };

    try {
      receive.readFrom(channel);
      fail("Should have thrown IOException for oversized request");
    } catch (IOException e) {
      assertTrue("Error should mention size limit", e.getMessage().contains("larger than the maximum"));
    }

    // sizeBuffer was released on line 88 BEFORE exception on line 90
    // No leak
  }

  /**
   * Baseline test: Successful read with no errors
   */
  @Test
  public void testSuccessfulReadNoLeak() throws Exception {
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(MAX_REQUEST_SIZE);

    // Create data to read
    byte[] data = new byte[100];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) i;
    }

    // Create a channel that provides size + data
    ReadableByteChannel channel = new ReadableByteChannel() {
      private int position = 0;
      private ByteBuffer source;

      {
        source = ByteBuffer.allocate(8 + data.length);
        source.putLong(8 + data.length); // Total size including header
        source.put(data);
        source.flip();
      }

      @Override
      public int read(ByteBuffer dst) {
        if (!source.hasRemaining()) {
          return -1;
        }
        int toRead = Math.min(dst.remaining(), source.remaining());
        for (int i = 0; i < toRead; i++) {
          dst.put(source.get());
        }
        return toRead;
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() {
      }
    };

    // Read all data
    while (!receive.isReadComplete()) {
      long read = receive.readFrom(channel);
      if (read == 0) {
        break;
      }
    }

    assertTrue("Read should be complete", receive.isReadComplete());
    assertEquals("Size should match", 8 + data.length, receive.sizeRead());

    // Get the content
    ByteBuf content = receive.content();
    assertNotNull("Content should not be null", content);
    assertEquals("Content size should match", data.length, content.readableBytes());

    // Clean up - caller is responsible for releasing content
    content.release();

    // No leak
  }

  /**
   * Test multiple partial reads
   */
  @Test
  public void testPartialReadsNoLeak() throws Exception {
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(MAX_REQUEST_SIZE);

    byte[] data = new byte[1000];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) (i % 256);
    }

    // Channel that provides data in small chunks
    ReadableByteChannel channel = new ReadableByteChannel() {
      private ByteBuffer source;

      {
        source = ByteBuffer.allocate(8 + data.length);
        source.putLong(8 + data.length);
        source.put(data);
        source.flip();
      }

      @Override
      public int read(ByteBuffer dst) {
        if (!source.hasRemaining()) {
          return -1;
        }
        // Read only 10 bytes at a time
        int toRead = Math.min(10, Math.min(dst.remaining(), source.remaining()));
        for (int i = 0; i < toRead; i++) {
          dst.put(source.get());
        }
        return toRead;
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() {
      }
    };

    // Read in multiple calls
    int reads = 0;
    while (!receive.isReadComplete()) {
      long read = receive.readFrom(channel);
      reads++;
      if (read < 0) {
        break;
      }
    }

    assertTrue("Should have taken multiple reads", reads > 10);
    assertTrue("Read should be complete", receive.isReadComplete());

    // Clean up
    ByteBuf content = receive.content();
    content.release();

    // No leak
  }
}
