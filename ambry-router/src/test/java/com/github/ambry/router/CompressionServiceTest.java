/*
 * Copyright 2022 LinkedIn Corp. All rights reserved.
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
package com.github.ambry.router;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.compression.CompressionException;
import com.github.ambry.compression.LZ4Compression;
import com.github.ambry.compression.ZstdCompression;
import com.github.ambry.config.CompressionConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class CompressionServiceTest {

  private final CompressionConfig config = new CompressionConfig(new VerifiableProperties(new Properties()));

  @Test
  public void testIsBlobCompressible() {
    BlobProperties blobProperties = new BlobProperties(1234L, "testServiceID", (short) 2222, (short) 3333, true);
    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
    CompressionService service = new CompressionService(config, metrics);

    // Test: compression disabled.  Compression does nothing.
    service.isCompressionEnabled = false;
    Assert.assertFalse(service.isBlobCompressible(blobProperties));
    service.isCompressionEnabled = true;

    // Test: Disable compression due to content-ending.
    service.isSkipWithContentEncoding = true;
    blobProperties = new BlobProperties(1234L, "testServiceID", "2222", "contentType", true, 12345L, (short) 111, (short) 222, true,
            "testTag", "testEncoding", "testFileName");
    service.isSkipWithContentEncoding = true;
    Assert.assertFalse(service.isBlobCompressible(blobProperties));
    Assert.assertEquals(1, metrics.compressSkipRate.getCount());
    Assert.assertEquals(1, metrics.compressSkipContentEncoding.getCount());
    service.isSkipWithContentEncoding = false;

    // Test: Disable compression due to content-type (image/* is blocked, text/* is allowed)
    blobProperties = new BlobProperties(1234L, "testServiceID", "2222", "image/123", true, 12345L, (short) 111, (short) 222, true,
            "testTag", null, "testFileName");
    Assert.assertFalse(service.isBlobCompressible(blobProperties));
    Assert.assertEquals(2, metrics.compressSkipRate.getCount());
    Assert.assertEquals(1, metrics.compressSkipContentTypeFiltering.getCount());

    // Test: success case.
    blobProperties = new BlobProperties(1234L, "testServiceID", "2222", "text/123", true, 12345L, (short) 111, (short) 222, true,
        "testTag", null, "testFileName");
    Assert.assertTrue(service.isBlobCompressible(blobProperties));
  }

  @Test
  public void testCompressFailDueToSourceTooSmall() {
    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
    CompressionService service = new CompressionService(config, metrics);
    byte[] sourceBuffer = "Test Message for testing purpose.  The Message is part of the testing message.".getBytes();
    ByteBuf sourceByteBuf = Unpooled.wrappedBuffer(sourceBuffer);

    // Test: Disable compression due to data size too small.
    service.isCompressionEnabled = true;
    service.minimalSourceDataSizeInBytes = 1000;
    ByteBuf compressedBuffer = service.compressChunk(sourceByteBuf, true, false);
    Assert.assertNull(compressedBuffer);
    Assert.assertEquals(1, metrics.compressSkipRate.getCount());
    Assert.assertEquals(1, metrics.compressSkipSizeTooSmall.getCount());
    service.minimalSourceDataSizeInBytes = 1;
  }

  @Test
  public void testCompressThrow() throws CompressionException {
    byte[] sourceBuffer = "Test Message for testing purpose.  The Message is part of the testing message.".getBytes();
    ByteBuf sourceByteBuf = Unpooled.wrappedBuffer(sourceBuffer);
    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());

    // Test: Disable compression due to compression failed.
    // Create a test CompressionService and replace the compressor with a mock.
    CompressionService compressionService = new CompressionService(config, metrics);
    compressionService.minimalSourceDataSizeInBytes = 1;
    LZ4Compression mockCompression = Mockito.mock(LZ4Compression.class, Mockito.CALLS_REAL_METHODS);
    Mockito.doThrow(new RuntimeException("Compress failed.")).when(mockCompression).compress(
        Mockito.any(ByteBuffer.class), Mockito.any(ByteBuffer.class));
    compressionService.defaultCompressor = mockCompression;
    ByteBuf compressedBuffer = compressionService.compressChunk(sourceByteBuf, true, false);
    Assert.assertNull(compressedBuffer);
    Assert.assertEquals(1, metrics.compressErrorRate.getCount());
    Assert.assertEquals(1, metrics.compressErrorCompressFailed.getCount());
  }

  @Test
  public void testCompressWithLowRatio() throws CompressionException {
    byte[] sourceBuffer = "Test Message for testing purpose.  The Message is part of the testing message.".getBytes();
    ByteBuf sourceByteBuf = Unpooled.wrappedBuffer(sourceBuffer);
    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
    CompressionService compressionService = new CompressionService(config, metrics);
    compressionService.minimalSourceDataSizeInBytes = 1;

    // Test: Compression skipped because compression ratio is too low.
    LZ4Compression mockCompression = Mockito.mock(LZ4Compression.class);
    Mockito.when(mockCompression.compress(Mockito.any(ByteBuffer.class), Mockito.any(ByteBuffer.class))).thenReturn(10);
    compressionService.allCompressions.put(LZ4Compression.ALGORITHM_NAME, mockCompression);
    ByteBuf compressedBuffer = compressionService.compressChunk(sourceByteBuf, true, false);
    Assert.assertNull(compressedBuffer);
    Assert.assertEquals(1, metrics.compressSkipRate.getCount());
    Assert.assertEquals(1, metrics.compressSkipRatioTooSmall.getCount());
  }

  @Test
  public void testCompressSucceeded() {
    byte[] sourceBuffer = ("Test Message for testing purpose.  The Message is part of the testing message." +
        "Test Message for testing purpose.  The Message is part of the testing message." +
        "Test Message for testing purpose.  The Message is part of the testing message.").getBytes();
    ByteBuf sourceByteBuf = Unpooled.wrappedBuffer(sourceBuffer);
    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());

    // Test: Happy case - compression succeed partial full.
    CompressionService service = new CompressionService(config, metrics);
    service.isCompressionEnabled = true;
    service.minimalCompressRatio = 1.0;
    service.minimalSourceDataSizeInBytes = 1;

    ByteBuf compressedBuffer = service.compressChunk(sourceByteBuf, false, false);
    Assert.assertNotEquals(sourceByteBuf.readableBytes(), compressedBuffer.readableBytes());
    Assert.assertEquals(1, metrics.compressAcceptRate.getCount());
    compressedBuffer.release();

    CompressionMetrics.AlgorithmMetrics algorithmMetrics = metrics.getAlgorithmMetrics(ZstdCompression.ALGORITHM_NAME);
    Assert.assertTrue(algorithmMetrics.compressRatioPercent.getCount() > 0);
    Assert.assertEquals(1, algorithmMetrics.compressRate.getCount());
    Assert.assertTrue(algorithmMetrics.smallSizeCompressTimeInMicroseconds.getCount() > 0);
    Assert.assertTrue(algorithmMetrics.smallSizeCompressSpeedMBPerSec.getCount() > 0);

    // Happy case - compression succeed full chunk.
    service.defaultCompressor = service.allCompressions.get(LZ4Compression.ALGORITHM_NAME);
    Assert.assertEquals(LZ4Compression.ALGORITHM_NAME, service.defaultCompressor.getAlgorithmName());
    compressedBuffer = service.compressChunk(sourceByteBuf, true, false);
    Assert.assertNotEquals(sourceByteBuf.readableBytes(), compressedBuffer.readableBytes());
    Assert.assertEquals(2, metrics.compressAcceptRate.getCount());
    compressedBuffer.release();

    algorithmMetrics = metrics.getAlgorithmMetrics(LZ4Compression.ALGORITHM_NAME);
    Assert.assertTrue(algorithmMetrics.compressRatioPercent.getCount() > 0);
    Assert.assertEquals(1, algorithmMetrics.compressRate.getCount());
    Assert.assertTrue(algorithmMetrics.fullSizeCompressTimeInMicroseconds.getCount() > 0);
    Assert.assertTrue(algorithmMetrics.fullSizeCompressSpeedMBPerSec.getCount() > 0);

    sourceByteBuf.release();
  }

  @Test
  public void testDecompressWithInvalidBuffer() {
    // Test: Decompress failed due to byte conversion throws.
    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
    CompressionService service = new CompressionService(config, metrics);

    // Test: buffer does not contain algorithm name. (name size is 100);
    final byte[] badCompressedBuffer = new byte[]{1, 100, 1, 2, 3, 4, 5};
    Exception ex = TestUtils.getException(
        () -> service.decompress(Unpooled.wrappedBuffer(badCompressedBuffer), badCompressedBuffer.length, false));
    Assert.assertTrue(ex instanceof CompressionException);
    Assert.assertEquals(1, metrics.decompressErrorRate.getCount());
    Assert.assertEquals(1, metrics.decompressErrorBufferTooSmall.getCount());

    // Test: algorithm name does not exist.
    final byte[] badCompressedBuffer2 = new byte[]{1, 4, 65, 66, 67, 68, 5, 1, 2, 3, 4, 5};
    ex = TestUtils.getException(
        () -> service.decompress(Unpooled.wrappedBuffer(badCompressedBuffer2), badCompressedBuffer2.length, false));
    Assert.assertTrue(ex instanceof CompressionException);
    Assert.assertEquals(2, metrics.decompressErrorRate.getCount());
    Assert.assertEquals(1, metrics.decompressErrorUnknownAlgorithmName.getCount());
  }

  @Test
  public void testDecompressWithAlgorithmThrows() throws CompressionException {
    byte[] sourceBuffer = ("Test Message for testing purpose.  The Message is part of the testing message."
        + "Test Message for testing purpose.  The Message is part of the testing message."
        + "Test Message for testing purpose.  The Message is part of the testing message.").getBytes();
    LZ4Compression lz4 = new LZ4Compression();
    ByteBuffer compressedByteBuffer = ByteBuffer.allocate(lz4.getCompressBufferSize(sourceBuffer.length));
    lz4.compress(ByteBuffer.wrap(sourceBuffer), compressedByteBuffer);
    compressedByteBuffer.flip();
    ByteBuf compressedBuffer = Unpooled.wrappedBuffer(compressedByteBuffer);

    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
    CompressionService service = new CompressionService(config, metrics);

    // Test: decompress() throws exception.
    // Create a test CompressionService and replace the compressor with a mock.
    LZ4Compression mockCompression = Mockito.mock(LZ4Compression.class, Mockito.CALLS_REAL_METHODS);
    Mockito.doThrow(new RuntimeException("Decompress failed."))
        .when(mockCompression)
        .decompress(Mockito.any(ByteBuffer.class), Mockito.any(ByteBuffer.class));
    service.allCompressions.add(mockCompression);
    Exception ex = TestUtils.getException(() -> service.decompress(compressedBuffer, compressedBuffer.readableBytes(), false));
    Assert.assertTrue(ex instanceof CompressionException);
    Assert.assertEquals(1, metrics.decompressErrorRate.getCount());
    Assert.assertEquals(1, metrics.decompressErrorDecompressFailed.getCount());
  }

  @Test
  public void testDecompressSucceeded() throws CompressionException {
    byte[] sourceBuffer = ("Test Message for testing purpose.  The Message is part of the testing message."
        + "Test Message for testing purpose.  The Message is part of the testing message."
        + "Test Message for testing purpose.  The Message is part of the testing message.").getBytes();
    LZ4Compression lz4 = new LZ4Compression();
    ByteBuffer compressedByteBuffer = ByteBuffer.allocate(lz4.getCompressBufferSize(sourceBuffer.length));
    lz4.compress(ByteBuffer.wrap(sourceBuffer), compressedByteBuffer);
    compressedByteBuffer.flip();
    ByteBuf compressedBuffer = Unpooled.wrappedBuffer(compressedByteBuffer);

    // Decompressed full chunk.
    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
    CompressionService service = new CompressionService(config, metrics);
    ByteBuf decompressedBuffer = service.decompress(compressedBuffer, sourceBuffer.length, false);
    Assert.assertEquals(decompressedBuffer.readableBytes(), sourceBuffer.length);
    Assert.assertEquals(1, metrics.decompressSuccessRate.getCount());
    Assert.assertTrue(metrics.decompressExpandSizeBytes.getCount() > 0);

    CompressionMetrics.AlgorithmMetrics algorithmMetrics = metrics.getAlgorithmMetrics(LZ4Compression.ALGORITHM_NAME);
    Assert.assertEquals(1, algorithmMetrics.decompressRate.getCount());
    Assert.assertTrue(algorithmMetrics.fullSizeDecompressTimeInMicroseconds.getCount() > 0);

    // Decompress partial chunk.
    decompressedBuffer = service.decompress(compressedBuffer, sourceBuffer.length - 1, false);
    Assert.assertEquals(decompressedBuffer.readableBytes(), sourceBuffer.length);
    Assert.assertEquals(2, metrics.decompressSuccessRate.getCount());
    Assert.assertTrue(metrics.decompressExpandSizeBytes.getCount() > 0);

    algorithmMetrics = metrics.getAlgorithmMetrics(LZ4Compression.ALGORITHM_NAME);
    Assert.assertEquals(2, algorithmMetrics.decompressRate.getCount());
    Assert.assertTrue(algorithmMetrics.smallSizeDecompressTimeInMicroseconds.getCount() > 0);
  }

  @Test
  public void testCompressThenDecompress() throws CompressionException {
    byte[] sourceBuffer = ("Test Message for testing purpose.  The Message is part of the testing message."
        + "Test Message for testing purpose.  The Message is part of the testing message."
        + "Test Message for testing purpose.  The Message is part of the testing message.").getBytes();

    ByteBuf sourceBufferHeap = PooledByteBufAllocator.DEFAULT.buffer(sourceBuffer.length);
    ByteBuf sourceBufferDirect = PooledByteBufAllocator.DEFAULT.directBuffer(sourceBuffer.length);
    try {
      sourceBufferHeap.writeBytes(sourceBuffer);
      sourceBufferDirect.writeBytes(sourceBuffer);

      // Source is a Heap, test compressed and decompress using various combination of memory types.
      runCompressedThenDecompress(sourceBufferHeap, false, false);
      runCompressedThenDecompress(sourceBufferHeap, false, true);
      runCompressedThenDecompress(sourceBufferHeap, true, false);
      runCompressedThenDecompress(sourceBufferHeap, true, true);

      // Source is a Direct, test compressed and decompress using various combination of memory types.
      runCompressedThenDecompress(sourceBufferDirect, false, false);
      runCompressedThenDecompress(sourceBufferDirect, false, true);
      runCompressedThenDecompress(sourceBufferDirect, true, false);
      runCompressedThenDecompress(sourceBufferDirect, true, true);
    } finally {
      sourceBufferHeap.release();
      sourceBufferDirect.release();
    }
  }

  private void runCompressedThenDecompress(ByteBuf sourceBuffer, boolean isCompressedBufferDirect,
      boolean isDecompressedBufferDirect) throws CompressionException {
    byte[] sourceBufferArray = new byte[sourceBuffer.readableBytes()];
    sourceBuffer.getBytes(0, sourceBufferArray);

    // Decompressed full chunk.  Note: algorithm is not specified in config.  It would use the Zstd as default.
    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
    CompressionService service = new CompressionService(config, metrics);
    service.minimalSourceDataSizeInBytes = 1;
    service.minimalCompressRatio = 1.0;

    // Compress the source buffer.
    int sourceBufferLength = sourceBuffer.readableBytes();
    ByteBuf compressedBuffer = service.compressChunk(sourceBuffer, false, isCompressedBufferDirect);
    try {
      Assert.assertNotEquals(sourceBufferLength, compressedBuffer.readableBytes());
      Assert.assertEquals(1, metrics.compressAcceptRate.getCount());

      // Decompress the source buffer.
      ByteBuf decompressedBuffer = service.decompress(compressedBuffer, sourceBufferLength, isDecompressedBufferDirect);
      try {
        Assert.assertEquals(sourceBufferLength, decompressedBuffer.readableBytes());
        Assert.assertEquals(1, metrics.decompressSuccessRate.getCount());
        Assert.assertTrue(metrics.decompressExpandSizeBytes.getCount() > 0);
        Assert.assertEquals(decompressedBuffer.readableBytes(), sourceBufferLength);

        // Read the decompressed content and compare with original source array.
        byte[] decompressedArray = new byte[decompressedBuffer.readableBytes()];
        decompressedBuffer.readBytes(decompressedArray);
        Assert.assertArrayEquals(decompressedArray, sourceBufferArray);
      } finally {
        decompressedBuffer.release();
      }
    } finally {
      compressedBuffer.release();
    }

    // Zstd is the default compression algorithm.
    CompressionMetrics.AlgorithmMetrics algorithmMetrics = metrics.getAlgorithmMetrics(ZstdCompression.ALGORITHM_NAME);
    Assert.assertEquals(1, algorithmMetrics.decompressRate.getCount());
    Assert.assertTrue(algorithmMetrics.fullSizeDecompressTimeInMicroseconds.getCount() > 0);
  }

  @Test
  public void testIsCompressibleContentType() {
    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
    Properties properties = new Properties();
    properties.put("router.compression.compress.content.types", "text/111, text/222, comp1, comp2  , comp3");
    properties.put("router.compression.algorithm.name", "BADNAME");
    CompressionConfig config = new CompressionConfig(new VerifiableProperties(properties));
    CompressionService compressionService = new CompressionService(config, metrics);

    // Test content-type specified in compressible content-type.
    Assert.assertTrue(compressionService.isCompressibleContentType("text/111"));
    Assert.assertTrue(compressionService.isCompressibleContentType("TEXT/222"));
    Assert.assertTrue(compressionService.isCompressibleContentType("Text/222; charset=UTF8"));

    // Test content-type specified in compressible context-type prefix.
    Assert.assertTrue(compressionService.isCompressibleContentType("comp1/111"));
    Assert.assertTrue(compressionService.isCompressibleContentType("Comp2/222"));
    Assert.assertTrue(compressionService.isCompressibleContentType("COMP3/222; charset=UTF8"));

    // Test unknown content-type.
    Assert.assertFalse(compressionService.isCompressibleContentType("image/111"));
    Assert.assertFalse(compressionService.isCompressibleContentType("unknown/111"));
    Assert.assertFalse(compressionService.isCompressibleContentType(""));
  }

  /**
   * Tests that CompositeByteBuf (multiple underlying buffers) is properly handled.
   * CompositeByteBuf must be consolidated into a single contiguous buffer for compression.
   */
  @Test
  public void testCompressWithCompositeByteBuf() throws CompressionException {
    byte[] part1 = "First part of message. ".getBytes();
    byte[] part2 = "Second part of message. ".getBytes();
    byte[] part3 = "Third part of message.".getBytes();

    // Create CompositeByteBuf with multiple components
    ByteBuf composite = PooledByteBufAllocator.DEFAULT.compositeBuffer();
    composite.addComponent(true, Unpooled.wrappedBuffer(part1));
    composite.addComponent(true, Unpooled.wrappedBuffer(part2));
    composite.addComponent(true, Unpooled.wrappedBuffer(part3));
    Assert.assertTrue(composite.nioBufferCount() > 1); // Verify it's actually composite

    try {
      CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
      CompressionService service = new CompressionService(config, metrics);
      service.minimalSourceDataSizeInBytes = 1;
      service.minimalCompressRatio = 1.0;

      // Compress CompositeByteBuf
      int originalReaderIndex = composite.readerIndex();
      int originalWriterIndex = composite.writerIndex();
      int originalRefCount = composite.refCnt();

      ByteBuf compressed = service.compressChunk(composite, false, false);
      try {
        Assert.assertNotNull(compressed);
        // Verify contract: indices unchanged
        Assert.assertEquals(originalReaderIndex, composite.readerIndex());
        Assert.assertEquals(originalWriterIndex, composite.writerIndex());
        // Verify contract: refCount unchanged
        Assert.assertEquals(originalRefCount, composite.refCnt());

        // Verify decompression works
        ByteBuf decompressed = service.decompress(compressed, composite.readableBytes(), false);
        try {
          Assert.assertEquals(composite.readableBytes(), decompressed.readableBytes());
          byte[] originalData = new byte[composite.readableBytes()];
          byte[] decompressedData = new byte[decompressed.readableBytes()];
          composite.getBytes(composite.readerIndex(), originalData);
          decompressed.readBytes(decompressedData);
          Assert.assertArrayEquals(originalData, decompressedData);
        } finally {
          decompressed.release();
        }
      } finally {
        compressed.release();
      }
    } finally {
      composite.release();
    }
  }

  /**
   * Tests LZ4 compression with mismatched buffer types (heap input, direct output).
   * LZ4 allows type mixing, so this should use the optimized path (no intermediate copy).
   */
  @Test
  public void testLZ4CompressWithTypeMismatch() throws CompressionException {
    byte[] sourceBuffer = ("Test Message for testing purpose. The Message is part of the testing message."
        + "Test Message for testing purpose. The Message is part of the testing message."
        + "Test Message for testing purpose. The Message is part of the testing message.").getBytes();

    // Create heap buffer
    ByteBuf heapBuffer = PooledByteBufAllocator.DEFAULT.heapBuffer(sourceBuffer.length);
    heapBuffer.writeBytes(sourceBuffer);
    Assert.assertFalse(heapBuffer.isDirect()); // Verify it's heap
    Assert.assertEquals(1, heapBuffer.nioBufferCount()); // Verify it's single buffer

    try {
      CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
      CompressionService service = new CompressionService(config, metrics);
      service.minimalSourceDataSizeInBytes = 1;
      service.minimalCompressRatio = 1.0;
      service.defaultCompressor = service.allCompressions.get(LZ4Compression.ALGORITHM_NAME);

      // Verify LZ4 doesn't require matching buffer types
      Assert.assertFalse(service.defaultCompressor.requireMatchingBufferType());

      int originalReaderIndex = heapBuffer.readerIndex();
      int originalWriterIndex = heapBuffer.writerIndex();
      int originalRefCount = heapBuffer.refCnt();

      // Compress: heap → direct (type mismatch, but LZ4 allows it)
      ByteBuf compressed = service.compressChunk(heapBuffer, false, true);
      try {
        Assert.assertNotNull(compressed);
        Assert.assertTrue(compressed.isDirect()); // Output is direct as requested
        // Verify contract: indices unchanged
        Assert.assertEquals(originalReaderIndex, heapBuffer.readerIndex());
        Assert.assertEquals(originalWriterIndex, heapBuffer.writerIndex());
        // Verify contract: refCount unchanged (optimization doesn't leak references)
        Assert.assertEquals(originalRefCount, heapBuffer.refCnt());

        // Verify decompression works
        ByteBuf decompressed = service.decompress(compressed, sourceBuffer.length, false);
        try {
          byte[] decompressedData = new byte[decompressed.readableBytes()];
          decompressed.readBytes(decompressedData);
          Assert.assertArrayEquals(sourceBuffer, decompressedData);
        } finally {
          decompressed.release();
        }
      } finally {
        compressed.release();
      }
    } finally {
      heapBuffer.release();
    }
  }

  /**
   * Tests Zstd compression with mismatched buffer types (heap input, direct output).
   * Zstd requires matching buffer types, so this must create an intermediate copy.
   */
  @Test
  public void testZstdCompressWithTypeMismatch() throws CompressionException {
    byte[] sourceBuffer = ("Test Message for testing purpose. The Message is part of the testing message."
        + "Test Message for testing purpose. The Message is part of the testing message."
        + "Test Message for testing purpose. The Message is part of the testing message.").getBytes();

    // Create heap buffer
    ByteBuf heapBuffer = PooledByteBufAllocator.DEFAULT.heapBuffer(sourceBuffer.length);
    heapBuffer.writeBytes(sourceBuffer);
    Assert.assertFalse(heapBuffer.isDirect()); // Verify it's heap

    try {
      CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
      CompressionService service = new CompressionService(config, metrics);
      service.minimalSourceDataSizeInBytes = 1;
      service.minimalCompressRatio = 1.0;
      service.defaultCompressor = service.allCompressions.get(ZstdCompression.ALGORITHM_NAME);

      // Verify Zstd requires matching buffer types
      Assert.assertTrue(service.defaultCompressor.requireMatchingBufferType());

      int originalReaderIndex = heapBuffer.readerIndex();
      int originalWriterIndex = heapBuffer.writerIndex();
      int originalRefCount = heapBuffer.refCnt();

      // Compress: heap → direct (type mismatch, Zstd must copy to match types)
      ByteBuf compressed = service.compressChunk(heapBuffer, false, true);
      try {
        Assert.assertNotNull(compressed);
        Assert.assertTrue(compressed.isDirect()); // Output is direct as requested
        // Verify contract: indices unchanged
        Assert.assertEquals(originalReaderIndex, heapBuffer.readerIndex());
        Assert.assertEquals(originalWriterIndex, heapBuffer.writerIndex());
        // Verify contract: refCount unchanged
        Assert.assertEquals(originalRefCount, heapBuffer.refCnt());

        // Verify decompression works
        ByteBuf decompressed = service.decompress(compressed, sourceBuffer.length, false);
        try {
          byte[] decompressedData = new byte[decompressed.readableBytes()];
          decompressed.readBytes(decompressedData);
          Assert.assertArrayEquals(sourceBuffer, decompressedData);
        } finally {
          decompressed.release();
        }
      } finally {
        compressed.release();
      }
    } finally {
      heapBuffer.release();
    }
  }

  /**
   * Tests that compression preserves input buffer indices across all paths.
   * This verifies the documented contract that indices remain unchanged.
   */
  @Test
  public void testCompressionPreservesIndices() {
    byte[] sourceBuffer = ("Test Message for testing purpose. The Message is part of the testing message."
        + "Test Message for testing purpose. The Message is part of the testing message.").getBytes();

    ByteBuf buffer = PooledByteBufAllocator.DEFAULT.heapBuffer(sourceBuffer.length + 20);
    buffer.writeBytes(new byte[10]); // Add some offset
    int startIndex = buffer.writerIndex();
    buffer.writeBytes(sourceBuffer);
    buffer.writeBytes(new byte[10]); // Add trailing bytes

    // Set reader/writer to specific positions
    buffer.readerIndex(startIndex);
    buffer.writerIndex(startIndex + sourceBuffer.length);

    try {
      CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
      CompressionService service = new CompressionService(config, metrics);
      service.minimalSourceDataSizeInBytes = 1;
      service.minimalCompressRatio = 1.0;

      int originalReaderIndex = buffer.readerIndex();
      int originalWriterIndex = buffer.writerIndex();

      ByteBuf compressed = service.compressChunk(buffer, false, false);
      try {
        Assert.assertNotNull(compressed);
        // Verify indices completely unchanged
        Assert.assertEquals(originalReaderIndex, buffer.readerIndex());
        Assert.assertEquals(originalWriterIndex, buffer.writerIndex());
        // Verify data still readable from original indices
        Assert.assertEquals(sourceBuffer.length, buffer.readableBytes());
      } finally {
        if (compressed != null) {
          compressed.release();
        }
      }
    } finally {
      buffer.release();
    }
  }

  /**
   * Tests decompression also preserves input buffer state.
   */
  @Test
  public void testDecompressionPreservesIndices() throws CompressionException {
    byte[] sourceBuffer = ("Test Message for testing purpose. The Message is part of the testing message."
        + "Test Message for testing purpose. The Message is part of the testing message.").getBytes();

    CompressionMetrics metrics = new CompressionMetrics(new MetricRegistry());
    CompressionService service = new CompressionService(config, metrics);
    service.minimalSourceDataSizeInBytes = 1;
    service.minimalCompressRatio = 1.0;

    ByteBuf original = PooledByteBufAllocator.DEFAULT.heapBuffer(sourceBuffer.length);
    original.writeBytes(sourceBuffer);

    try {
      ByteBuf compressed = service.compressChunk(original, false, false);
      try {
        Assert.assertNotNull(compressed);

        int originalReaderIndex = compressed.readerIndex();
        int originalWriterIndex = compressed.writerIndex();
        int originalRefCount = compressed.refCnt();

        ByteBuf decompressed = service.decompress(compressed, sourceBuffer.length, false);
        try {
          // Verify indices unchanged on compressed buffer
          Assert.assertEquals(originalReaderIndex, compressed.readerIndex());
          Assert.assertEquals(originalWriterIndex, compressed.writerIndex());
          // Verify refCount unchanged
          Assert.assertEquals(originalRefCount, compressed.refCnt());
        } finally {
          decompressed.release();
        }
      } finally {
        compressed.release();
      }
    } finally {
      original.release();
    }
  }
}
