package com.github.ambry.network;

import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Random;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for ByteBuf leak detection in BoundedNettyByteBufReceive.
 * These tests verify that production code properly releases ByteBufs in error scenarios.
 *
 * Design Principles:
 * 1. Production code allocates → Production code releases (on error paths)
 * 2. Production code returns ByteBuf → Caller releases
 * 3. Tests use explicit channel implementations (not Mockito) to avoid instrumentation interference
 * 4. Each test clearly documents what it validates
 */
public class BoundedNettyByteBufReceiveLeakTest {

  private final NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();

  @Before
  public void setUp() {
    nettyByteBufLeakHelper.beforeTest();
  }

  @After
  public void tearDown() {
    nettyByteBufLeakHelper.afterTest();
  }

  /**
   * Test that production code releases sizeBuffer when IOException occurs during size reading.
   *
   * Production code responsibility:
   * - BoundedNettyByteBufReceive allocates sizeBuffer (line 73)
   * - BoundedNettyByteBufReceive must release sizeBuffer on IOException (line 81)
   *
   * Test responsibility:
   * - Create a channel that throws IOException during read
   * - Verify IOException is propagated
   * - NettyByteBufLeakHelper verifies no leaks (production code cleaned up)
   */
  @Test
  public void testReadFromIOExceptionAfterSizeBufferAllocation() throws Exception {
    // Create a channel that will throw IOException when read
    ReadableByteChannel faultingChannel = new ReadableByteChannel() {
      private boolean firstCall = true;

      @Override
      public int read(ByteBuffer dst) throws IOException {
        if (firstCall) {
          firstCall = false;
          // Allow partial read of size header to trigger allocation
          // but not complete it, simulating network error
          return 0;
        }
        // Second call throws IOException
        throw new IOException("Simulated network error during size read");
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() throws IOException {
      }
    };

    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);

    // First call allocates sizeBuffer but doesn't complete
    long bytesRead = receive.readFrom(faultingChannel);
    Assert.assertEquals("First read should return 0", 0, bytesRead);

    // Second call should trigger IOException and production code should cleanup
    IOException caughtException = null;
    try {
      receive.readFrom(faultingChannel);
      Assert.fail("Should have thrown IOException");
    } catch (IOException e) {
      caughtException = e;
    }

    // Verify exception was thrown and has expected message
    Assert.assertNotNull("IOException should have been thrown", caughtException);
    Assert.assertTrue("Exception should mention network error",
        caughtException.getMessage().contains("network error"));

    // NettyByteBufLeakHelper.afterTest() will verify no leaks
    // This validates that production code released the sizeBuffer in the catch block
  }

  /**
   * Test that production code handles size-too-large scenario without leaks.
   *
   * Production code responsibility:
   * - BoundedNettyByteBufReceive allocates and releases sizeBuffer (lines 73, 88)
   * - BoundedNettyByteBufReceive throws IOException for oversized requests (line 90)
   * - No ByteBuf leak (sizeBuffer released before exception)
   *
   * Test responsibility:
   * - Provide data with size larger than maxRequestSize
   * - Verify IOException is thrown
   * - NettyByteBufLeakHelper verifies no leaks
   */
  @Test
  public void testReadFromIOExceptionOnRequestTooLarge() throws Exception {
    int maxRequestSize = 100;
    int actualRequestSize = 2000;

    // Create request with size larger than limit
    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
    buffer.putLong(actualRequestSize);
    buffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(buffer));
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(maxRequestSize);

    // Read should throw IOException after reading size
    IOException caughtException = null;
    try {
      receive.readFrom(channel);
      Assert.fail("Should have thrown IOException for oversized request");
    } catch (IOException e) {
      caughtException = e;
    }

    // Verify exception details
    Assert.assertNotNull("IOException should have been thrown", caughtException);
    Assert.assertTrue("Exception should mention request size",
        caughtException.getMessage().contains("Request size larger"));

    // NettyByteBufLeakHelper.afterTest() will verify no leaks
    // This validates that production code released sizeBuffer before throwing
  }

  /**
   * Test that production code releases main buffer when IOException occurs during content reading.
   *
   * Production code responsibility:
   * - BoundedNettyByteBufReceive allocates sizeBuffer and buffer (lines 73, 92)
   * - BoundedNettyByteBufReceive must release buffer on IOException (line 103)
   * - sizeBuffer should already be released after reading size (line 88)
   *
   * Test responsibility:
   * - Create a channel that throws IOException during content read
   * - Verify IOException is propagated
   * - NettyByteBufLeakHelper verifies no leaks
   */
  @Test
  public void testReadFromIOExceptionDuringContentRead() throws Exception {
    int requestSize = 100;

    // Create a channel that succeeds for size header but fails for content
    // IMPORTANT: Must return 0 after size to force readFrom() to return before trying content
    ReadableByteChannel faultingChannel = new ReadableByteChannel() {
      private int readCount = 0;

      @Override
      public int read(ByteBuffer dst) throws IOException {
        if (readCount == 0) {
          // First read: return the size header
          dst.putLong(requestSize);
          readCount++;
          return Long.BYTES;
        } else if (readCount == 1) {
          // Second read: return 0 to simulate no data available yet
          // This causes readFrom() to return without reading content
          readCount++;
          return 0;
        } else {
          // Third read: throw IOException during content read
          throw new IOException("Simulated network error during content read");
        }
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() throws IOException {
      }
    };

    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);

    // First call reads size successfully and returns when no content available
    long bytesRead = receive.readFrom(faultingChannel);
    Assert.assertEquals("Should have read size header", Long.BYTES, bytesRead);

    // Second call should trigger IOException during content read
    IOException caughtException = null;
    try {
      receive.readFrom(faultingChannel);
      Assert.fail("Should have thrown IOException");
    } catch (IOException e) {
      caughtException = e;
    }

    // Verify exception was thrown
    Assert.assertNotNull("IOException should have been thrown", caughtException);
    Assert.assertTrue("Exception should mention content read error",
        caughtException.getMessage().contains("content read"));

    // NettyByteBufLeakHelper.afterTest() will verify no leaks
    // This validates that production code released the buffer in the catch block
  }

  /**
   * Test successful read path to verify normal cleanup.
   *
   * Production code responsibility:
   * - BoundedNettyByteBufReceive allocates sizeBuffer and buffer
   * - After successful read, sizeBuffer is released
   * - buffer is returned via content() and caller must release it
   *
   * Test responsibility:
   * - Provide valid data
   * - Call content() to get buffer
   * - Release the buffer (test's responsibility as the caller)
   */
  @Test
  public void testSuccessfulReadWithProperCleanup() throws Exception {
    int bufferSize = 100;
    ByteBuffer inputBuffer = ByteBuffer.allocate(bufferSize);
    inputBuffer.putLong(bufferSize);
    byte[] content = new byte[bufferSize - Long.BYTES];
    new Random().nextBytes(content);
    inputBuffer.put(content);
    inputBuffer.flip();

    ReadableByteChannel channel = Channels.newChannel(new ByteBufferInputStream(inputBuffer));
    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);

    // Read all data
    long totalBytesRead = 0;
    while (!receive.isReadComplete()) {
      long bytesRead = receive.readFrom(channel);
      if (bytesRead == 0) {
        break;
      }
      totalBytesRead += bytesRead;
    }

    Assert.assertEquals("Should have read all bytes", bufferSize, totalBytesRead);
    Assert.assertTrue("Read should be complete", receive.isReadComplete());

    // Get the content buffer - this is now the TEST's responsibility to release
    ByteBuf payload = receive.content();
    Assert.assertNotNull("Content should not be null", payload);
    Assert.assertEquals("Content size should match", bufferSize - Long.BYTES, payload.readableBytes());

    // Verify content matches
    for (int i = 0; i < content.length; i++) {
      Assert.assertEquals("Content byte mismatch at index " + i,
          content[i], payload.readByte());
    }

    // TEST's responsibility: release the buffer we got from content()
    payload.release();

    // NettyByteBufLeakHelper.afterTest() will verify no leaks
    // Production code should have released sizeBuffer
    // Test released the payload buffer
  }

  /**
   * Test that verifies EOFException handling during size read.
   *
   * Production code responsibility:
   * - BoundedNettyByteBufReceive allocates sizeBuffer
   * - BoundedNettyByteBufReceive must release sizeBuffer on EOFException
   *
   * Test responsibility:
   * - Create a channel that returns -1 (EOF) during size read
   * - Verify EOFException is thrown
   * - NettyByteBufLeakHelper verifies no leaks
   */
  @Test
  public void testEOFExceptionDuringSizeRead() throws Exception {
    ReadableByteChannel eofChannel = new ReadableByteChannel() {
      private boolean firstCall = true;

      @Override
      public int read(ByteBuffer dst) throws IOException {
        if (firstCall) {
          firstCall = false;
          return 0; // First call returns 0 to trigger allocation
        }
        return -1; // Second call returns EOF
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() throws IOException {
      }
    };

    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);

    // First call allocates sizeBuffer
    receive.readFrom(eofChannel);

    // Second call should throw EOFException
    IOException caughtException = null;
    try {
      receive.readFrom(eofChannel);
      Assert.fail("Should have thrown EOFException");
    } catch (IOException e) {
      caughtException = e;
    }

    Assert.assertNotNull("Exception should have been thrown", caughtException);

    // NettyByteBufLeakHelper.afterTest() will verify no leaks
  }

  /**
   * Test that verifies EOFException handling during content read.
   *
   * Production code responsibility:
   * - BoundedNettyByteBufReceive allocates buffer
   * - BoundedNettyByteBufReceive must release buffer on EOFException
   *
   * Test responsibility:
   * - Create a channel that returns -1 (EOF) during content read
   * - Verify EOFException is thrown
   * - NettyByteBufLeakHelper verifies no leaks
   */
  @Test
  public void testEOFExceptionDuringContentRead() throws Exception {
    int requestSize = 100;

    // Must return 0 after size to force readFrom() to return before trying content
    ReadableByteChannel eofChannel = new ReadableByteChannel() {
      private int readCount = 0;

      @Override
      public int read(ByteBuffer dst) throws IOException {
        if (readCount == 0) {
          dst.putLong(requestSize);
          readCount++;
          return Long.BYTES;
        } else if (readCount == 1) {
          // Return 0 to simulate no data available yet
          // This causes readFrom() to return without reading content
          readCount++;
          return 0;
        }
        return -1; // EOF during content read on third call
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public void close() throws IOException {
      }
    };

    BoundedNettyByteBufReceive receive = new BoundedNettyByteBufReceive(100000);

    // First call reads size and returns when no content available
    long bytesRead = receive.readFrom(eofChannel);
    Assert.assertEquals("Should have read size header", Long.BYTES, bytesRead);

    // Second call should throw EOFException during content read
    IOException caughtException = null;
    try {
      receive.readFrom(eofChannel);
      Assert.fail("Should have thrown EOFException");
    } catch (IOException e) {
      caughtException = e;
    }

    Assert.assertNotNull("Exception should have been thrown", caughtException);

    // NettyByteBufLeakHelper.afterTest() will verify no leaks
  }
}
