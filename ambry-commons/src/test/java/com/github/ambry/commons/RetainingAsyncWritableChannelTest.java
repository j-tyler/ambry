/*
 * Copyright 2018 LinkedIn Corp. All rights reserved.
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
 *
 */

package com.github.ambry.commons;

import com.github.ambry.rest.RestServiceErrorCode;
import com.github.ambry.rest.RestServiceException;
import com.github.ambry.router.FutureResult;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;


/**
 * Tests for {@link RetainingAsyncWritableChannel}.
 */
public class RetainingAsyncWritableChannelTest {
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
   * Test that {@link RetainingAsyncWritableChannel} behaves as expected: chunks are copied, callback completed
   * immediately after {@link RetainingAsyncWritableChannel#write} method completes.
   */
  @Test
  public void basicsTest() throws Exception {
    List<byte[]> inputBuffers = getBuffers(1000, 20, 201, 0, 79, 1005);
    RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel();
    for (int i = 0; i < inputBuffers.size(); i++) {
      ByteBuffer buf = ByteBuffer.wrap(inputBuffers.get(i));
      writeAndCheckCallback(buf, channel, buf.remaining(), null, null);
    }
    checkStream(inputBuffers, channel);
    channel.close();
    writeAndCheckCallback(ByteBuffer.allocate(0), channel, 0, ClosedChannelException.class, null);
  }

  /**
   * Test that {@link RetainingAsyncWritableChannel} behaves as expected: chunks are copied, callback completed
   * immediately after {@link RetainingAsyncWritableChannel#write} method completes.
   */
  @Test
  public void basicsTestWithNettyByteBuf() throws Exception {
    for (boolean useCompositeByteBuf : Arrays.asList(false, true)) {
      List<byte[]> inputBuffers = getBuffers(1000, 20, 201, 0, 79, 1005);
      RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel();
      for (int i = 0; i < inputBuffers.size(); i++) {
        byte[] data = inputBuffers.get(i);
        ByteBuf chunk;
        if (data.length == 0) {
          chunk = Unpooled.wrappedBuffer(data);
        } else if (!useCompositeByteBuf) {
          chunk = ByteBufAllocator.DEFAULT.heapBuffer(data.length);
          chunk.writeBytes(data);
        } else {
          CompositeByteBuf composite = ByteBufAllocator.DEFAULT.compositeHeapBuffer(100);
          ByteBuf c = ByteBufAllocator.DEFAULT.heapBuffer(data.length / 2);
          c.writeBytes(data, 0, data.length / 2);
          composite.addComponent(true, c);
          c = ByteBufAllocator.DEFAULT.heapBuffer(data.length - data.length / 2);
          c.writeBytes(data, data.length / 2, data.length - data.length / 2);
          composite.addComponent(true, c);
          chunk = composite;
        }
        writeAndCheckCallback(chunk, channel, chunk.readableBytes(), null, null);
      }
      checkStream(inputBuffers, channel);
      channel.close();
      writeAndCheckCallback(ByteBuffer.allocate(0), channel, 0, ClosedChannelException.class, null);
    }
  }

  /**
   * Ensure that buffers are copied and changes to the input buffers after a write call are not reflected in the
   * returned stream.
   */
  @Test
  public void bufferModificationTest() throws Exception {
    byte[] inputBuffer = TestUtils.getRandomBytes(100);
    try (RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel()) {
      writeAndCheckCallback(ByteBuffer.wrap(inputBuffer), channel, inputBuffer.length, null, null);
      // mutate the input array and check that stream still matches the original content.
      byte[] originalBuffer = Arrays.copyOf(inputBuffer, inputBuffer.length);
      inputBuffer[50]++;
      checkStream(Collections.singletonList(originalBuffer), channel);
    }
  }

  /**
   * Test that the size limit for bytes received is obeyed.
   */
  @Test
  public void sizeLimitTest() throws Exception {
    List<byte[]> inputBuffers = getBuffers(1000, 20, 5);
    try (RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel(1023)) {
      for (Iterator<byte[]> iter = inputBuffers.iterator(); iter.hasNext(); ) {
        ByteBuffer buf = ByteBuffer.wrap(iter.next());
        if (iter.hasNext()) {
          writeAndCheckCallback(buf, channel, buf.remaining(), null, null);
        } else {
          writeAndCheckCallback(buf, channel, buf.remaining(), RestServiceException.class,
              RestServiceErrorCode.RequestTooLarge);
        }
      }
      // test that no more writes are accepted after size limit exceeded.
      writeAndCheckCallback(ByteBuffer.wrap(TestUtils.getRandomBytes(10)), channel, 0, RestServiceException.class,
          RestServiceErrorCode.RequestTooLarge);
    }
  }

  /**
   * Get a list of byte arrays of the provided sizes.
   * @param chunkSizes the size in bytes for each chunk.
   * @return the list of buffers.
   */
  private static List<byte[]> getBuffers(int... chunkSizes) {
    return Arrays.stream(chunkSizes).mapToObj(TestUtils::getRandomBytes).collect(Collectors.toList());
  }

  /**
   * Test the behavior of {@link RetainingAsyncWritableChannel#write(ByteBuffer, Callback)}.
   * @param buf the buffer to write.
   * @param channel the channel to write to.
   * @param bytesWritten the expected number of bytes written.
   * @param exceptionClass if non-null, check that the write operation encountered this exception.
   * @param errorCode if non-null, check that the cause {@link RestServiceException} has this error code.
   */
  private static <E extends Exception> void writeAndCheckCallback(Object buf, RetainingAsyncWritableChannel channel,
      long bytesWritten, Class<E> exceptionClass, RestServiceErrorCode errorCode) throws Exception {
    int remainingBeforeWrite = remainingBytes(buf);
    FutureResult<Long> callbackResult = new FutureResult<>();
    FutureResult<Long> futureResult = writeBufferToChannel(buf, channel, callbackResult);
    assertEquals("Unexpected number of bytes read from buffer", bytesWritten,
        remainingBeforeWrite - remainingBytes(buf));
    for (FutureResult<Long> f : Arrays.asList(futureResult, callbackResult)) {
      // operation should be completed within method body.
      assertTrue("Operation not completed", f.isDone());
      assertEquals("Bytes written incorrect in callback", bytesWritten, (long) f.result());
      if (exceptionClass != null) {
        TestUtils.assertException(ExecutionException.class, f::get, e -> {
          E exceptionCause = exceptionClass.cast(e.getCause());
          if (errorCode != null && exceptionCause instanceof RestServiceException) {
            assertEquals("Unexpected error code", errorCode, ((RestServiceException) exceptionCause).getErrorCode());
          }
        });
      }
    }
  }

  /**
   * Return the remaining bytes from object buf. When buf is a {@link ByteBuffer}, then call {@link ByteBuffer#remaining()}.
   * When buf is a {@link ByteBuf}, then call {@link ByteBuf#readableBytes()};
   * @param buf The buffer.
   * @return The remaining bytes.
   */
  private static int remainingBytes(Object buf) {
    if (buf instanceof ByteBuffer) {
      return ((ByteBuffer) buf).remaining();
    } else {
      return ((ByteBuf) buf).readableBytes();
    }
  }

  /**
   * Write the buffer to the given {@link RetainingAsyncWritableChannel}.
   * @param buf The buffer.
   * @param channel The given channel.
   * @param callbackResult The callback function to invoke when finishing writing.
   * @return The {@link FutureResult} returned from write method.
   */
  private static FutureResult<Long> writeBufferToChannel(Object buf, RetainingAsyncWritableChannel channel,
      FutureResult<Long> callbackResult) {
    if (buf instanceof ByteBuffer) {
      return (FutureResult<Long>) channel.write((ByteBuffer) buf, callbackResult::done);
    } else {
      return (FutureResult<Long>) channel.write((ByteBuf) buf, (result, exception) -> {
        ((ByteBuf) buf).release();
        callbackResult.done(result, exception);
      });
    }
  }

  /**
   * Test that {@link RetainingAsyncWritableChannel#consumeContentAsBytes()} properly releases the underlying buffer.
   */
  @Test
  public void testConsumeContentAsBytesReleasesBuffer() {
    // Arrange: Create a ByteBuf with test data
    ByteBuf testBuf = Unpooled.buffer();
    byte[] testData = TestUtils.getRandomBytes(1024);
    testBuf.writeBytes(testData);

    // Wrap the consumeContentAsByteBuf method to return the test buffer
    RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel() {
      @Override
      public ByteBuf consumeContentAsByteBuf() {
        return testBuf;
      }
    };

    // Act: Call consumeContentAsBytes which should copy and release
    byte[] result = channel.consumeContentAsBytes();

    // Assert: Verify the buffer was released and the data matches
    assertArrayEquals("Content should match", testData, result);
    assertEquals("Buffer should be released after consumption", 0, testBuf.refCnt());
  }

  /**
   * Test that content cannot be consumed twice.
   *
   * WHY: consumeContentAsByteBuf() nulls out the internal buffer after consumption. Attempting to consume
   * again should throw IllegalStateException. This validates the API contract and ensures single-consumer semantics.
   */
  @Test
  public void testCannotConsumeTwice() throws Exception {
    RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel();
    byte[] data = TestUtils.getRandomBytes(100);
    channel.write(ByteBuffer.wrap(data), null);

    // First consumption should succeed
    try (InputStream is = channel.consumeContentAsInputStream()) {
      assertNotNull("First consumption should succeed", is);
    }

    // Second consumption should fail
    try {
      channel.consumeContentAsInputStream();
      fail("Should not be able to consume content twice");
    } catch (IllegalStateException e) {
      assertTrue("Expected 'already consumed' message",
          e.getMessage().contains("already consumed") || e.getMessage().contains("channel was closed"));
    }
  }

  /**
   * Test that writes are not allowed after content has been consumed.
   *
   * WHY: After consumption, the internal compositeBuffer is set to null. Writes check for this and throw
   * IllegalStateException, ensuring single-consumer semantics.
   */
  @Test
  public void testCannotWriteAfterConsumption() throws Exception {
    RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel();
    byte[] data1 = TestUtils.getRandomBytes(100);
    channel.write(ByteBuffer.wrap(data1), null);

    // Consume the content
    try (InputStream is = channel.consumeContentAsInputStream()) {
      assertNotNull("Consumption should succeed", is);
    }

    // Try to write after consumption - should fail
    byte[] data2 = TestUtils.getRandomBytes(50);
    FutureResult<Long> result = new FutureResult<>();
    channel.write(ByteBuffer.wrap(data2), result::done);

    // Verify the write failed with IllegalStateException
    assertTrue("Write operation should be completed", result.isDone());
    TestUtils.assertException(ExecutionException.class, result::get, e -> {
      IllegalStateException cause = (IllegalStateException) e.getCause();
      assertTrue("Expected 'cannot write' message",
          cause.getMessage().contains("Cannot write") || cause.getMessage().contains("already consumed"));
    });
  }

  /**
   * Test that {@link RetainingAsyncWritableChannel#consumeContentAsInputStream()} properly releases
   * the underlying ByteBuf when the InputStream is closed.
   *
   * WHY: consumeContentAsInputStream() creates a ByteBufInputStream with releaseOnClose=true.
   * This test validates the critical resource management contract: closing the InputStream MUST release
   * the underlying ByteBuf to prevent memory leaks. This is essential for the InputStream API to be safe
   * when used with try-with-resources (as in S3BatchDeleteHandler.deserializeRequest()).
   */
  @Test
  public void testInputStreamReleasesByteBufOnClose() throws Exception {
    RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel();
    byte[] data = TestUtils.getRandomBytes(1024);
    ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer(data.length);
    byteBuf.writeBytes(data);

    // Write using ByteBuf so we can track refcount
    FutureResult<Long> writeResult = new FutureResult<>();
    channel.write(byteBuf, (result, exception) -> {
      byteBuf.release(); // Release our reference
      writeResult.done(result, exception);
    });

    // At this point, channel holds one reference
    assertEquals("Channel should hold reference to buffer", 1, byteBuf.refCnt());

    // Get InputStream (transfers ownership to InputStream)
    InputStream is = channel.consumeContentAsInputStream();

    // Read some data to verify it works
    byte[] readBuf = new byte[100];
    int read = is.read(readBuf);
    assertEquals("Should read 100 bytes", 100, read);

    // Close the InputStream - should release the ByteBuf
    is.close();

    // Verify the ByteBuf was released
    assertEquals("ByteBuf should be released after InputStream close", 0, byteBuf.refCnt());
  }

  /**
   * Test consuming content from an empty channel (no writes).
   *
   * WHY: Edge case validation. An empty CompositeByteBuf is still valid and should return an InputStream
   * that immediately returns EOF (-1 on read). This ensures the API doesn't NPE or fail on empty payloads.
   */
  @Test
  public void testConsumeEmptyChannel() throws Exception {
    RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel();

    // Don't write anything, just consume
    try (InputStream is = channel.consumeContentAsInputStream()) {
      assertNotNull("InputStream should be created even for empty channel", is);
      assertEquals("Empty channel should return 0 bytes available", 0, is.available());
      assertEquals("Reading from empty stream should return -1", -1, is.read());
    }
  }

  /**
   * Test that consuming content after closing the channel fails.
   *
   * WHY: close() releases the internal buffer and sets it to null. Consumption after close should fail
   * with IllegalStateException. This validates proper lifecycle management.
   */
  @Test
  public void testCannotConsumeAfterClose() throws Exception {
    RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel();
    byte[] data = TestUtils.getRandomBytes(100);
    channel.write(ByteBuffer.wrap(data), null);

    // Close the channel
    channel.close();

    // Try to consume - should fail
    try {
      channel.consumeContentAsInputStream();
      fail("Should not be able to consume after channel is closed");
    } catch (IllegalStateException e) {
      assertTrue("Expected 'channel was closed' message",
          e.getMessage().contains("channel was closed") || e.getMessage().contains("already consumed"));
    }
  }

  /**
   * Test single-byte reads from InputStream work correctly.
   *
   * WHY: Validates the InputStream.read() single-byte API works correctly on ByteBufInputStream.
   * Some XML parsers may read byte-by-byte, so this ensures compatibility with all InputStream consumers.
   */
  @Test
  public void testInputStreamSingleByteReads() throws Exception {
    RetainingAsyncWritableChannel channel = new RetainingAsyncWritableChannel();
    byte[] data = new byte[]{1, 2, 3, 4, 5};
    channel.write(ByteBuffer.wrap(data), null);

    try (InputStream is = channel.consumeContentAsInputStream()) {
      for (byte expected : data) {
        int read = is.read();
        assertTrue("Should read valid byte", read >= 0);
        assertEquals("Byte should match", expected, (byte) read);
      }
      assertEquals("Should reach end of stream", -1, is.read());
    }
  }

  /**
   * Check that the content in the stream returned by {@link RetainingAsyncWritableChannel#consumeContentAsInputStream()}
   * matches expectations.
   * @param expectedContent the expected content.
   * @param channel the channel that contains the copied content.
   */
  private static void checkStream(List<byte[]> expectedContent, RetainingAsyncWritableChannel channel)
      throws Exception {
    assertEquals("Bytes written does not match expected content length",
        expectedContent.stream().mapToLong(buf -> buf.length).sum(), channel.getBytesWritten());
    try (InputStream inputStream = channel.consumeContentAsInputStream()) {
      for (byte[] buf : expectedContent) {
        byte[] readBuf = new byte[buf.length];
        int read = 0;
        while (read < buf.length) {
          read += inputStream.read(readBuf, read, buf.length - read);
        }
        assertArrayEquals("Read content should match expected", buf, readBuf);
      }
      assertEquals("Stream should be fully read", -1, inputStream.read());
    }
  }
}
