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
package com.github.ambry.commons;

import com.github.ambry.rest.RestServiceErrorCode;
import com.github.ambry.rest.RestServiceException;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for RetainingAsyncWritableChannel ByteBuf memory leak detection.
 * Tests MEDIUM-RISK paths:
 * - write() adding to composite (line 118)
 * - consumeContentAsInputStream() failure
 * - close() with unreleased composite (lines 148-151)
 */
public class RetainingAsyncWritableChannelLeakTest {
  private NettyByteBufLeakHelper leakHelper = new NettyByteBufLeakHelper();
  private RetainingAsyncWritableChannel channel;

  @Before
  public void setUp() {
    leakHelper.beforeTest();
    channel = new RetainingAsyncWritableChannel();
  }

  @After
  public void tearDown() {
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
    leakHelper.afterTest();
  }

  /**
   * MEDIUM-RISK PATH #1: write() adding ByteBuf to composite
   *
   * Tests that write() creates a retainedDuplicate() (line 85) and adds it
   * to compositeBuffer (line 118).
   *
   * The MEDIUM-risk question is: what if addComponent() fails? buf would leak.
   *
   * In practice, addComponent() rarely fails, but we test the happy path.
   *
   * Expected: No leak - composite retains all components
   */
  @Test
  public void testWriteByteBufAddsToComposite() throws Exception {
    ByteBuf src1 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);
    ByteBuf src2 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);

    src1.writeBytes(TestUtils.getRandomBytes(100));
    src2.writeBytes(TestUtils.getRandomBytes(100));

    // Initial refCnt = 1
    assertEquals("src1 initial refCnt", 1, src1.refCnt());
    assertEquals("src2 initial refCnt", 1, src2.refCnt());

    CountDownLatch latch = new CountDownLatch(2);

    // Write both buffers
    channel.write(src1, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNull("No exception", exception);
        assertEquals("Wrote 100 bytes", 100L, result.longValue());
        latch.countDown();
      }
    });

    channel.write(src2, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNull("No exception", exception);
        assertEquals("Wrote 100 bytes", 100L, result.longValue());
        latch.countDown();
      }
    });

    assertTrue("Callbacks should complete", latch.await(5, TimeUnit.SECONDS));

    // retainedDuplicate() increments the original buffer's refCnt
    // So after write(), the original buffer has refCnt=2 (original + duplicate)
    assertEquals("src1 refCnt after write (original + duplicate)", 2, src1.refCnt());
    assertEquals("src2 refCnt after write (original + duplicate)", 2, src2.refCnt());

    // Close channel - releases composite which releases all duplicates
    // This decrements refCnt from 2 to 1
    channel.close();

    // Verify refCnt back to 1 after close
    assertEquals("src1 refCnt after close (original only)", 1, src1.refCnt());
    assertEquals("src2 refCnt after close (original only)", 1, src2.refCnt());

    // Clean up our original references
    src1.release();
    src2.release();

    // No leak - composite was released
  }

  /**
   * MEDIUM-RISK PATH #2: write() with size limit exceeded after adding
   *
   * Tests that if size limit is exceeded AFTER buf is added to composite (line 120-123),
   * an exception is thrown but buf was already added.
   *
   * Expected: buf is in composite, exception returned to caller, close() releases all
   */
  @Test
  public void testWriteExceedsSizeLimitAfterAdding() throws Exception {
    // Create channel with 150 byte limit
    channel = new RetainingAsyncWritableChannel(150);

    ByteBuf src1 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);
    ByteBuf src2 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);

    src1.writeBytes(TestUtils.getRandomBytes(100));
    src2.writeBytes(TestUtils.getRandomBytes(100));

    CountDownLatch latch = new CountDownLatch(2);
    final Exception[] exceptionHolder = new Exception[1];

    // First write should succeed
    channel.write(src1, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNull("First write should succeed", exception);
        latch.countDown();
      }
    });

    // Second write should fail (total = 200 bytes > 150 limit)
    channel.write(src2, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNotNull("Second write should fail", exception);
        assertTrue("Should be RestServiceException", exception instanceof RestServiceException);
        RestServiceException rse = (RestServiceException) exception;
        assertEquals("Should be RequestTooLarge", RestServiceErrorCode.RequestTooLarge, rse.getErrorCode());
        exceptionHolder[0] = exception;
        latch.countDown();
      }
    });

    assertTrue("Callbacks should complete", latch.await(5, TimeUnit.SECONDS));
    assertNotNull("Should have exception", exceptionHolder[0]);

    // Even though second write failed, the buffer was still added to composite
    // (addComponent succeeded on line 118, but size check failed on line 120)

    // Close should release all
    channel.close();

    src1.release();
    src2.release();

    // No leak
  }

  /**
   * MEDIUM-RISK PATH #3: close() with unreleased composite
   *
   * Tests that close() releases compositeBuffer (lines 148-151).
   *
   * Expected: No leak - all components released
   */
  @Test
  public void testCloseReleasesCompositeBuffer() throws Exception {
    ByteBuf src1 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);
    ByteBuf src2 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);
    ByteBuf src3 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);

    src1.writeBytes(TestUtils.getRandomBytes(100));
    src2.writeBytes(TestUtils.getRandomBytes(100));
    src3.writeBytes(TestUtils.getRandomBytes(100));

    CountDownLatch latch = new CountDownLatch(3);

    channel.write(src1, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        latch.countDown();
      }
    });

    channel.write(src2, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        latch.countDown();
      }
    });

    channel.write(src3, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        latch.countDown();
      }
    });

    assertTrue("Writes should complete", latch.await(5, TimeUnit.SECONDS));

    // Close channel
    channel.close();

    // compositeBuffer.release() was called on line 149
    // All components should be released

    // Clean up our original references
    src1.release();
    src2.release();
    src3.release();

    // No leak
  }

  /**
   * MEDIUM-RISK PATH #4: write() to closed channel
   *
   * Tests that writing to closed channel throws ClosedChannelException
   * and doesn't leak.
   *
   * Expected: Exception thrown, no leak
   */
  @Test
  public void testWriteToClosedChannelNoLeak() throws Exception {
    ByteBuf src = PooledByteBufAllocator.DEFAULT.heapBuffer(100);
    src.writeBytes(TestUtils.getRandomBytes(100));

    // Close channel first
    channel.close();

    CountDownLatch latch = new CountDownLatch(1);

    // Try to write
    Future<Long> future = channel.write(src, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNotNull("Should have exception", exception);
        assertTrue("Should be ClosedChannelException", exception instanceof ClosedChannelException);
        latch.countDown();
      }
    });

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));

    // Verify future failed
    try {
      future.get();
      fail("Future should have failed");
    } catch (ExecutionException e) {
      assertTrue("Should be ClosedChannelException", e.getCause() instanceof ClosedChannelException);
    }

    // src should still be alive - caller must release
    assertEquals("src should still be alive", 1, src.refCnt());
    src.release();

    // No leak
  }

  /**
   * MEDIUM-RISK PATH #5: consumeContentAsInputStream()
   *
   * Tests that consuming content as InputStream properly manages ByteBuf lifecycle.
   *
   * Expected: No leak
   */
  @Test
  public void testConsumeContentAsInputStream() throws Exception {
    byte[] data1 = TestUtils.getRandomBytes(100);
    byte[] data2 = TestUtils.getRandomBytes(100);

    ByteBuf src1 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);
    ByteBuf src2 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);

    src1.writeBytes(data1);
    src2.writeBytes(data2);

    CountDownLatch latch = new CountDownLatch(2);

    channel.write(src1, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        latch.countDown();
      }
    });

    channel.write(src2, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        latch.countDown();
      }
    });

    assertTrue("Writes should complete", latch.await(5, TimeUnit.SECONDS));

    // Consume as InputStream
    InputStream inputStream = channel.consumeContentAsInputStream();
    assertNotNull("InputStream should not be null", inputStream);

    // Read all data
    byte[] read = new byte[200];
    int totalRead = 0;
    int n;
    while ((n = inputStream.read(read, totalRead, read.length - totalRead)) != -1) {
      totalRead += n;
    }

    assertEquals("Should have read all data", 200, totalRead);

    // Verify data matches
    for (int i = 0; i < 100; i++) {
      assertEquals("Data1 mismatch at " + i, data1[i], read[i]);
      assertEquals("Data2 mismatch at " + i, data2[i], read[100 + i]);
    }

    inputStream.close();

    // Clean up original references
    src1.release();
    src2.release();

    // No leak
  }

  /**
   * Baseline test: Write ByteBuffer instead of ByteBuf
   */
  @Test
  public void testWriteByteBufferNoLeak() throws Exception {
    byte[] data = TestUtils.getRandomBytes(100);
    ByteBuffer src = ByteBuffer.wrap(data);

    CountDownLatch latch = new CountDownLatch(1);

    channel.write(src, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNull("Should have no exception", exception);
        assertEquals("Should have written 100 bytes", 100L, result.longValue());
        latch.countDown();
      }
    });

    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));

    // Close channel
    channel.close();

    // No leak - channel manages the internal ByteBuf copy
  }

  /**
   * Test multiple writes and consumption
   */
  @Test
  public void testMultipleWritesAndConsumptionNoLeak() throws Exception {
    int writeCount = 10;
    CountDownLatch latch = new CountDownLatch(writeCount);
    ByteBuf[] sources = new ByteBuf[writeCount];

    for (int i = 0; i < writeCount; i++) {
      sources[i] = PooledByteBufAllocator.DEFAULT.heapBuffer(100);
      sources[i].writeBytes(TestUtils.getRandomBytes(100));

      channel.write(sources[i], new Callback<Long>() {
        @Override
        public void onCompletion(Long result, Exception exception) {
          assertNull("Should have no exception", exception);
          latch.countDown();
        }
      });
    }

    assertTrue("All writes should complete", latch.await(5, TimeUnit.SECONDS));

    // Consume content
    InputStream is = channel.consumeContentAsInputStream();
    byte[] allData = new byte[1000];
    int totalRead = 0;
    int n;
    while ((n = is.read(allData, totalRead, allData.length - totalRead)) != -1) {
      totalRead += n;
    }

    assertEquals("Should have read all data", 1000, totalRead);
    is.close();

    // Clean up sources
    for (ByteBuf src : sources) {
      src.release();
    }

    // No leak
  }

  /**
   * Test that writing after consumeContentAsInputStream fails
   */
  @Test
  public void testWriteAfterConsumeContentFails() throws Exception {
    ByteBuf src1 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);
    src1.writeBytes(TestUtils.getRandomBytes(100));

    CountDownLatch latch1 = new CountDownLatch(1);
    channel.write(src1, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        latch1.countDown();
      }
    });

    assertTrue(latch1.await(5, TimeUnit.SECONDS));

    // Consume content
    InputStream is = channel.consumeContentAsInputStream();
    is.close();

    // Try to write again - should fail
    ByteBuf src2 = PooledByteBufAllocator.DEFAULT.heapBuffer(100);
    src2.writeBytes(TestUtils.getRandomBytes(100));

    CountDownLatch latch2 = new CountDownLatch(1);
    channel.write(src2, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNotNull("Should have exception", exception);
        assertTrue("Should be IllegalStateException", exception instanceof IllegalStateException);
        latch2.countDown();
      }
    });

    assertTrue(latch2.await(5, TimeUnit.SECONDS));

    // Clean up
    src1.release();
    src2.release();

    // No leak
  }
}
