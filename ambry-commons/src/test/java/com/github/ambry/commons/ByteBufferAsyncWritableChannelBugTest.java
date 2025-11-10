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

import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test demonstrating that write(ByteBuffer) creates unreleased wrapper ByteBufs.
 *
 * BUG: write(ByteBuffer) calls Unpooled.wrappedBuffer(src) which creates a ByteBuf
 * with refCnt=1. This wrapper is never released, causing a memory leak.
 */
public class ByteBufferAsyncWritableChannelBugTest {
  private ByteBufferAsyncWritableChannel channel;

  @Before
  public void setUp() {
    channel = new ByteBufferAsyncWritableChannel();
  }

  @After
  public void tearDown() {
    if (channel != null && channel.isOpen()) {
      channel.close();
    }
  }

  /**
   * TEST: After normal write/consume/resolve flow, wrapper ByteBuf should be released (refCnt=0).
   * BUG: The wrapper still has refCnt=1 because it's never released.
   */
  @Test
  public void testWriteByteBufferLeaksWrapper() throws Exception {
    // Create a direct ByteBuffer
    ByteBuffer nioBuffer = ByteBuffer.allocateDirect(100);
    nioBuffer.put(new byte[100]);
    nioBuffer.flip();

    CountDownLatch latch = new CountDownLatch(1);

    // Call write(ByteBuffer) - this creates a wrapper via Unpooled.wrappedBuffer()
    channel.write(nioBuffer, new Callback<Long>() {
      @Override
      public void onCompletion(Long result, Exception exception) {
        assertNull("Should have no exception", exception);
        latch.countDown();
      }
    });

    // Consume the chunk (normal flow)
    ByteBuffer chunk = channel.getNextChunk();
    assertNotNull("Should have chunk", chunk);

    // Get the wrapper ByteBuf from the channel before resolving
    ByteBuf wrapper = getWrapperFromChannel();
    assertNotNull("Wrapper should exist", wrapper);
    assertEquals("Wrapper should have refCnt=1 before resolve", 1, wrapper.refCnt());

    // Resolve the chunk (normal flow - this should release the wrapper, but doesn't!)
    channel.resolveOldestChunk(null);

    // Wait for callback
    assertTrue("Callback should complete", latch.await(5, TimeUnit.SECONDS));

    // ASSERTION THAT FAILS: Wrapper should be released (refCnt=0) but still has refCnt=1
    assertEquals("BUG: Wrapper ByteBuf was not released! Still has refCnt=1 (MEMORY LEAK)",
                 0, wrapper.refCnt());
  }

  /**
   * Gets the wrapper ByteBuf from the channel's resolved chunks using reflection.
   */
  private ByteBuf getWrapperFromChannel() throws Exception {
    Field awaitingField = ByteBufferAsyncWritableChannel.class.getDeclaredField("chunksAwaitingResolution");
    awaitingField.setAccessible(true);
    Queue<?> chunksAwaiting = (Queue<?>) awaitingField.get(channel);

    Object chunkData = chunksAwaiting.peek();
    if (chunkData == null) {
      return null;
    }

    Field bufField = chunkData.getClass().getDeclaredField("buf");
    bufField.setAccessible(true);
    return (ByteBuf) bufField.get(chunkData);
  }
}
