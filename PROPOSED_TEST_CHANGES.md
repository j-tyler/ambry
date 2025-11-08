# Proposed Test Changes for BoundedNettyByteBufReceiveLeakTest

## Overview

The failing test `testReadFromIOExceptionAfterSizeBufferAllocation` needs to be rewritten to:
1. Explicitly test production code's ByteBuf cleanup on exceptions
2. Be resilient to bytecode instrumentation
3. Clearly separate test responsibilities from production responsibilities

## Root Issue Analysis

The test is failing because:
1. **ByteBuddy instrumentation interferes with mock behavior**, preventing the expected IOException
2. When the IOException doesn't occur, the production code doesn't execute its cleanup path
3. The test's implicit assumptions about exception timing are broken by instrumentation

## Production Code Responsibilities

In `BoundedNettyByteBufReceive.java`, the production code is responsible for:

### Scenario 1: IOException during size buffer read (lines 72-84)
```java
if (sizeBuffer == null) {
    sizeBuffer = ByteBufAllocator.DEFAULT.heapBuffer(Long.BYTES);  // ALLOCATION
}
try {
    bytesRead = readBytesFromReadableByteChannel(channel, sizeBuffer);
    if (bytesRead < 0) {
        throw new EOFException();
    }
} catch (IOException e) {
    sizeBuffer.release();  // CLEANUP ON ERROR
    sizeBuffer = null;
    throw e;
}
```

### Scenario 2: IOException when size exceeds max (lines 85-92)
```java
if (sizeBuffer.writerIndex() == sizeBuffer.capacity()) {
    sizeToRead = sizeBuffer.readLong();
    sizeRead += Long.BYTES;
    sizeBuffer.release();  // NORMAL CLEANUP
    if (sizeToRead > maxRequestSize) {
        throw new IOException("Request size larger than the maximum size!");  // No ByteBuf to clean
    }
    buffer = ByteBufAllocator.DEFAULT.heapBuffer((int) sizeToRead - Long.BYTES);
}
```

### Scenario 3: IOException during main buffer read (lines 95-109)
```java
if (buffer != null && sizeRead < sizeToRead) {
    try {
        bytesReadFromChannel = readBytesFromReadableByteChannel(channel, buffer);
        if (bytesReadFromChannel < 0) {
            throw new EOFException();
        }
    } catch (IOException e) {
        buffer.release();  // CLEANUP ON ERROR
        buffer = null;
        throw e;
    }
    // ...
}
```

## Proposed Test Implementation

Here's the complete test file with explicit scenario testing:

```java
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
   * - BoundedNettyByteBufReceive allocates sizeBuffer
   * - BoundedNettyByteBufReceive must release sizeBuffer on IOException
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
   * - BoundedNettyByteBufReceive allocates and releases sizeBuffer
   * - BoundedNettyByteBufReceive throws IOException for oversized requests
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
   * - BoundedNettyByteBufReceive allocates sizeBuffer and buffer
   * - BoundedNettyByteBufReceive must release buffer on IOException
   * - sizeBuffer should already be released after reading size
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
    ReadableByteChannel faultingChannel = new ReadableByteChannel() {
      private boolean sizeRead = false;

      @Override
      public int read(ByteBuffer dst) throws IOException {
        if (!sizeRead) {
          // First read: return the size header
          dst.putLong(requestSize);
          sizeRead = true;
          return Long.BYTES;
        } else {
          // Second read: throw IOException during content read
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

    // First call reads size successfully
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

    ReadableByteChannel eofChannel = new ReadableByteChannel() {
      private boolean sizeRead = false;

      @Override
      public int read(ByteBuffer dst) throws IOException {
        if (!sizeRead) {
          dst.putLong(requestSize);
          sizeRead = true;
          return Long.BYTES;
        }
        return -1; // EOF during content read
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

    // First call reads size
    receive.readFrom(eofChannel);

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
```

## Key Changes Explained

### 1. Explicit Channel Implementations
**Instead of:** Using Mockito mocks that may be affected by ByteBuddy instrumentation
**Now:** Custom `ReadableByteChannel` implementations that explicitly control behavior

### 2. Clear Responsibility Documentation
Each test documents:
- What the production code is responsible for (allocation and cleanup)
- What the test is responsible for (creating scenarios and cleaning up test-owned resources)

### 3. Multi-step Exception Scenarios
Tests like `testReadFromIOExceptionAfterSizeBufferAllocation` now:
- First call triggers allocation (returns 0)
- Second call triggers exception
- This makes the "after allocation" aspect explicit

### 4. Exception Verification
Every test that expects an exception:
- Captures the exception in a variable
- Asserts it's not null
- Checks exception message when relevant
- This makes test intent crystal clear

### 5. Success Path Testing
Includes `testSuccessfulReadWithProperCleanup` that shows:
- Production code releases internal buffers (sizeBuffer)
- Test releases buffers it receives (payload from content())
- Clear separation of responsibilities

## Benefits

1. **Resilient to instrumentation**: No Mockito dependencies that ByteBuddy can interfere with
2. **Explicit behavior**: Each test clearly states what it's testing
3. **Clear ownership**: Documentation shows who owns what ByteBuf
4. **Better diagnostics**: Detailed assertions provide better failure messages
5. **Comprehensive coverage**: Tests all error paths in production code

## Migration Path

1. Apply Mockito exclusions to ByteBuddy (from Option 1)
2. Replace existing test with this new implementation
3. Verify all tests pass with `-PwithByteBufTracking`
4. Use this pattern for other leak tests
