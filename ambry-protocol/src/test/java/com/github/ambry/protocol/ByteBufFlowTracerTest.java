/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.ambry.protocol;

import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.DataNodeId;
import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.commons.BlobId;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.messageformat.BlobType;
import com.github.ambry.network.NetworkClientErrorCode;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.network.ResponseInfo;
import com.github.ambry.network.Send;
import com.github.ambry.utils.NettyByteBufDataInputStream;
import com.github.ambry.utils.ByteBufferChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * ByteBuf Flow Tracer Test Suite
 *
 * PURPOSE: These tests are designed to exercise all ByteBuf flow paths identified in BYTEBUF_LEAK_ANALYSIS.md.
 * They are NOT traditional assertion-based tests. Instead, they demonstrate production-like usage patterns
 * to generate flow traces when run with the ByteBuddy ByteBuf Tracer agent.
 *
 * USAGE: Run this test class with the tracer agent:
 *   -javaagent:bytebuddy-bytebuf-tracer.jar=include=com.github.ambry.*
 *
 * Then use JMX to call getTreeView() or getLLMView() to see the complete ByteBuf flow paths.
 *
 * EXPECTATIONS:
 * - All tests should PASS (no exceptions thrown unless part of the flow being tested)
 * - Some tests intentionally leak ByteBufs to demonstrate leak paths
 * - Some tests properly clean up to demonstrate safe paths
 * - Tests exercise all branches: happy paths, exception paths, ownership transfer, etc.
 *
 * ANALYZED CLASSES:
 * 1. com.github.ambry.protocol.PutRequest
 * 2. com.github.ambry.utils.NettyByteBufDataInputStream
 * 3. com.github.ambry.network.ResponseInfo
 */
public class ByteBufFlowTracerTest {
  private ClusterMap clusterMap;
  private BlobId blobId;
  private static final int BLOB_SIZE = 1024;
  private static final int USER_METADATA_SIZE = 256;
  private static final Random RANDOM = new Random();

  @Before
  public void setUp() throws Exception {
    clusterMap = new MockClusterMap();
    blobId = new BlobId(BlobId.BLOB_ID_V6, BlobId.BlobIdType.NATIVE, (byte) 0,
        (short) 1, (short) 1, clusterMap.getWritablePartitionIds(null).get(0), false,
        BlobId.BlobDataType.DATACHUNK);
  }

  @After
  public void tearDown() {
    // Note: Some tests intentionally leak to demonstrate leak paths
    // In production, all ByteBufs must be released
  }

  // ========================================
  // SECTION 1: PutRequest Tests
  // Tests based on BYTEBUF_LEAK_ANALYSIS.md Class 1 analysis
  // ========================================

  /**
   * TEST 1.1: PutRequest Happy Path - Proper Release
   *
   * Flow: Constructor → prepareBuffer() → writeTo() → release()
   * Expected: NO LEAK - All ByteBufs properly released
   */
  @Test
  public void testPutRequest_HappyPath_ProperRelease() throws Exception {
    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = createSamplePutRequest(materializedBlob);
    request.prepareBuffer();

    ByteBufferChannel channel = new ByteBufferChannel(ByteBuffer.allocate((int) request.sizeInBytes()));
    request.writeTo(channel);

    // Proper cleanup
    request.release();
  }

  /**
   * TEST 1.2: PutRequest - LEAK-1.1 - Exception Before prepareBuffer()
   *
   * Flow: Constructor → [EXCEPTION] → (no release)
   * Expected: LEAK - blob ByteBuf never released
   */
  @Test
  public void testPutRequest_Leak_ExceptionBeforePrepareBuffer_NoRelease() throws Exception {
    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = createSamplePutRequest(materializedBlob);

    // Simulate exception scenario - validation error, etc.
    // In production, caller might throw exception here

    // DO NOT call release() - intentionally leak to show the path
    // This simulates caller error handling failure
    // materializedBlob is now leaked
  }

  /**
   * TEST 1.3: PutRequest - LEAK-1.1 Mitigation - Exception Before prepareBuffer() WITH Release
   *
   * Flow: Constructor → [EXCEPTION] → release() in finally block
   * Expected: NO LEAK - release() cleans up blob field
   */
  @Test
  public void testPutRequest_Safe_ExceptionBeforePrepareBuffer_WithRelease() throws Exception {
    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = null;
    try {
      request = createSamplePutRequest(materializedBlob);
      // Simulate exception before prepareBuffer()
      if (RANDOM.nextBoolean() || true) {  // Always true, just to show flow
        // throw new RuntimeException("Validation failed");
        // For tracer purposes, we don't actually throw, just show the pattern
      }
    } finally {
      if (request != null) {
        request.release();  // Proper cleanup in finally block
      }
    }
  }

  /**
   * TEST 1.4: PutRequest - LEAK-1.2 - Exception During prepareBuffer()
   *
   * Flow: Constructor → prepareBuffer() [EXCEPTION mid-execution]
   * Expected: POTENTIAL LEAK - crcByteBuf and/or bufferToSend may leak
   */
  @Test
  public void testPutRequest_Leak_ExceptionDuringPrepareBuffer() throws Exception {
    // Note: This is difficult to test without modifying PutRequest
    // We demonstrate the pattern where prepareBuffer might throw

    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = createSamplePutRequest(materializedBlob);

    try {
      request.prepareBuffer();
      // If exception occurred mid-prepareBuffer (e.g., OutOfMemoryError)
      // crcByteBuf and bufferToSend could leak
    } catch (Exception e) {
      // Exception caught but no cleanup - demonstrates the vulnerability
      // DO NOT call release() to show the leak path
      return;
    }

    // If we get here, clean up normally
    request.release();
  }

  /**
   * TEST 1.5: PutRequest - LEAK-1.3 - Caller Never Calls release()
   *
   * Flow: Constructor → prepareBuffer() → writeTo() → [NO RELEASE]
   * Expected: LEAK - All ByteBufs (blob, crcByteBuf, bufferToSend) leak
   */
  @Test
  public void testPutRequest_Leak_NoReleaseCall() throws Exception {
    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = createSamplePutRequest(materializedBlob);
    request.prepareBuffer();

    ByteBufferChannel channel = new ByteBufferChannel(ByteBuffer.allocate((int) request.sizeInBytes()));
    request.writeTo(channel);

    // DO NOT call release() - most common leak scenario
    // Caller simply forgets or error path doesn't include cleanup
    // All ByteBufs leak
  }

  /**
   * TEST 1.6: PutRequest - LEAK-1.4 - Partial Write With Network Error
   *
   * Flow: Constructor → prepareBuffer() → writeTo() [IOException] → release() in finally
   * Expected: NO LEAK - release() called despite IOException
   */
  @Test
  public void testPutRequest_PartialWrite_WithRelease() throws Exception {
    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = createSamplePutRequest(materializedBlob);
    request.prepareBuffer();

    try {
      WritableByteChannel failingChannel = new FailingChannel(512);
      request.writeTo(failingChannel);
    } catch (IOException e) {
      // Network error during write
    } finally {
      // Proper cleanup despite IOException
      request.release();
    }
  }

  /**
   * TEST 1.7: PutRequest - LEAK-1.4 - Partial Write WITHOUT Release
   *
   * Flow: Constructor → prepareBuffer() → writeTo() [IOException] → (no release)
   * Expected: LEAK - caller assumes failure means no cleanup needed
   */
  @Test
  public void testPutRequest_PartialWrite_NoRelease() throws Exception {
    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = createSamplePutRequest(materializedBlob);
    request.prepareBuffer();

    try {
      WritableByteChannel failingChannel = new FailingChannel(512);
      request.writeTo(failingChannel);
    } catch (IOException e) {
      // Caller catches exception but doesn't release
      // Assumes failed write means no cleanup needed
      // This is WRONG - ByteBufs still leak
      return;
    }

    // DO NOT call release() to demonstrate leak path
  }

  /**
   * TEST 1.8: PutRequest - Multiple Calls to prepareBuffer()
   *
   * Flow: Constructor → prepareBuffer() → prepareBuffer() again
   * Expected: Depends on implementation - may leak or handle gracefully
   */
  @Test
  public void testPutRequest_MultiplePrepareBufferCalls() throws Exception {
    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = createSamplePutRequest(materializedBlob);
    request.prepareBuffer();

    // Call prepareBuffer() again - edge case
    // Does it leak the first composite? Replace it?
    try {
      request.prepareBuffer();
    } catch (Exception e) {
      // May throw exception or handle gracefully
    }

    request.release();
  }

  /**
   * TEST 1.9: PutRequest - Release Before prepareBuffer()
   *
   * Flow: Constructor → release() → prepareBuffer()
   * Expected: May crash or handle gracefully depending on implementation
   */
  @Test
  public void testPutRequest_ReleaseBeforePrepareBuffer() throws Exception {
    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = createSamplePutRequest(materializedBlob);
    request.release();  // Release immediately

    // Try to call prepareBuffer() after release - what happens?
    try {
      request.prepareBuffer();
      // Should fail gracefully or throw exception
    } catch (Exception e) {
      // Expected - blob is already released
    }
  }

  /**
   * TEST 1.10: PutRequest - Double Release
   *
   * Flow: Constructor → prepareBuffer() → release() → release()
   * Expected: Second release() should be safe (ReferenceCountUtil.safeRelease handles this)
   */
  @Test
  public void testPutRequest_DoubleRelease() throws Exception {
    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = createSamplePutRequest(materializedBlob);
    request.prepareBuffer();

    request.release();  // First release
    request.release();  // Second release - should not crash
  }

  // ========================================
  // SECTION 2: NettyByteBufDataInputStream Tests
  // Tests based on BYTEBUF_LEAK_ANALYSIS.md Class 2 analysis
  // ========================================

  /**
   * TEST 2.1: NettyByteBufDataInputStream - Happy Path - Caller Owns ByteBuf
   *
   * Flow: Allocate ByteBuf → Create stream → Use stream → Release ByteBuf
   * Expected: NO LEAK - caller properly manages ByteBuf lifecycle
   */
  @Test
  public void testNettyByteBufDataInputStream_HappyPath_CallerOwns() throws Exception {
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    buf.writeInt(12345);
    buf.writeLong(67890L);
    buf.writeBytes("test data".getBytes());

    NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(buf);

    // Read data from stream
    int intVal = stream.readInt();
    long longVal = stream.readLong();
    byte[] bytes = new byte[9];
    stream.read(bytes);

    // Stream doesn't manage lifecycle, so close() does nothing to buf
    stream.close();

    // Caller's responsibility to release ByteBuf
    buf.release();
  }

  /**
   * TEST 2.2: NettyByteBufDataInputStream - LEAK-2.2 - Caller Assumes Stream Owns ByteBuf
   *
   * Flow: Allocate ByteBuf → Create stream → Use stream → Close stream → [NO BUFFER RELEASE]
   * Expected: LEAK - ByteBuf never released
   */
  @Test
  public void testNettyByteBufDataInputStream_Leak_CallerConfusion() throws Exception {
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    buf.writeInt(12345);
    buf.writeLong(67890L);

    NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(buf);

    int intVal = stream.readInt();
    long longVal = stream.readLong();

    // Caller thinks stream owns buf and will release it
    stream.close();  // Does nothing to buf!

    // DO NOT release buf - simulating caller confusion
    // buf is leaked
  }

  /**
   * TEST 2.3: NettyByteBufDataInputStream - Production Pattern from NettyServerRequest
   *
   * Flow: Owner allocates → Creates stream wrapper → Processes → Owner releases
   * Expected: NO LEAK - follows correct pattern
   */
  @Test
  public void testNettyByteBufDataInputStream_ProductionPattern_NettyServerRequest() throws Exception {
    // Simulate NettyServerRequest pattern
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    content.writeInt(100);
    content.writeLong(200L);

    // NettyServerRequest owns content, creates stream as wrapper
    NettyByteBufDataInputStream inputStream = new NettyByteBufDataInputStream(content);

    // Use stream to read data
    int value1 = inputStream.readInt();
    long value2 = inputStream.readLong();

    // Owner (NettyServerRequest) releases content, not the stream
    content.release();
  }

  /**
   * TEST 2.4: NettyByteBufDataInputStream - Used in ResponseInfo Processing
   *
   * Flow: ResponseInfo has content → Create stream → Deserialize → Release ResponseInfo
   * Expected: NO LEAK if ResponseInfo.release() is called
   */
  @Test
  public void testNettyByteBufDataInputStream_ResponseInfoPattern_WithRelease() throws Exception {
    ByteBuf responseBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    responseBuf.writeInt(42);
    responseBuf.writeLong(123456L);

    ResponseInfo responseInfo = new ResponseInfo(null, null, responseBuf);

    // Create stream to read response
    NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
    int statusCode = stream.readInt();
    long timestamp = stream.readLong();

    // Must call responseInfo.release() to clean up content
    responseInfo.release();
  }

  /**
   * TEST 2.5: NettyByteBufDataInputStream - Used in ResponseInfo Processing WITHOUT Release
   *
   * Flow: ResponseInfo has content → Create stream → Deserialize → [NO RELEASE]
   * Expected: LEAK - this is the ReplicaThread bug
   */
  @Test
  public void testNettyByteBufDataInputStream_ResponseInfoPattern_NoRelease() throws Exception {
    ByteBuf responseBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    responseBuf.writeInt(42);
    responseBuf.writeLong(123456L);

    ResponseInfo responseInfo = new ResponseInfo(null, null, responseBuf);

    // Create stream to read response - ReplicaThread pattern
    NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
    int statusCode = stream.readInt();
    long timestamp = stream.readLong();

    // DO NOT call responseInfo.release() - simulating ReplicaThread bug
    // Content ByteBuf leaks
  }

  /**
   * TEST 2.6: NettyByteBufDataInputStream - Exception During Deserialization
   *
   * Flow: Create stream → Read data → [Exception] → Cleanup in finally
   * Expected: NO LEAK if caller has try-finally
   */
  @Test
  public void testNettyByteBufDataInputStream_ExceptionDuringRead_WithFinally() throws Exception {
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(16);
    buf.writeInt(100);
    // Not enough data for readLong() - will cause exception

    try {
      NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(buf);
      int value = stream.readInt();
      long value2 = stream.readLong();  // Will throw - not enough bytes
    } catch (Exception e) {
      // Exception during read
    } finally {
      // Caller's responsibility to release ByteBuf
      buf.release();
    }
  }

  /**
   * TEST 2.7: NettyByteBufDataInputStream - Exception During Deserialization WITHOUT Cleanup
   *
   * Flow: Create stream → Read data → [Exception] → (no cleanup)
   * Expected: LEAK - exception breaks flow, ByteBuf never released
   */
  @Test
  public void testNettyByteBufDataInputStream_ExceptionDuringRead_NoCleanup() throws Exception {
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(16);
    buf.writeInt(100);

    try {
      NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(buf);
      int value = stream.readInt();
      long value2 = stream.readLong();  // Will throw
    } catch (Exception e) {
      // Exception caught but DON'T release ByteBuf
      // Simulates poor error handling leading to leak
      return;
    }

    // DO NOT release buf
  }

  // ========================================
  // SECTION 3: ResponseInfo Tests
  // Tests based on BYTEBUF_LEAK_ANALYSIS.md Class 3 analysis
  // ========================================

  /**
   * TEST 3.1: ResponseInfo - Happy Path - Router Pattern (OperationController)
   *
   * Flow: Create ResponseInfo → Process → release() in forEach
   * Expected: NO LEAK - proper cleanup
   */
  @Test
  public void testResponseInfo_HappyPath_RouterPattern() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    content.writeInt(200);  // Status code
    content.writeBytes(generateRandomBytes(100));

    ResponseInfo responseInfo = new ResponseInfo(null, null, content);

    // Process response (simulate onResponse logic)
    NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
    int status = stream.readInt();

    // Router pattern - proper cleanup
    responseInfo.release();
  }

  /**
   * TEST 3.2: ResponseInfo - LEAK-3.1 - ReplicaThread Pattern WITHOUT Release
   *
   * Flow: Create ResponseInfo → Create NettyByteBufDataInputStream → Process → [NO RELEASE]
   * Expected: LEAK - content ByteBuf never released
   */
  @Test
  public void testResponseInfo_Leak_ReplicaThreadPattern_NoRelease() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    content.writeInt(200);
    content.writeLong(System.currentTimeMillis());
    content.writeBytes(generateRandomBytes(100));

    ResponseInfo responseInfo = new ResponseInfo(null, null, content);

    // ReplicaThread pattern
    NettyByteBufDataInputStream dis = new NettyByteBufDataInputStream(responseInfo.content());
    int status = dis.readInt();
    long timestamp = dis.readLong();

    // Process response...

    // DO NOT call responseInfo.release() - bug simulation
    // This is the CRITICAL BUG in ReplicaThread
    // Content leaks on every replication cycle
  }

  /**
   * TEST 3.3: ResponseInfo - ReplicaThread Pattern WITH Release (Fixed)
   *
   * Flow: Create ResponseInfo → Process → release() in finally
   * Expected: NO LEAK - demonstrates the fix
   */
  @Test
  public void testResponseInfo_Safe_ReplicaThreadPattern_WithRelease() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    content.writeInt(200);
    content.writeLong(System.currentTimeMillis());

    ResponseInfo responseInfo = new ResponseInfo(null, null, content);

    try {
      NettyByteBufDataInputStream dis = new NettyByteBufDataInputStream(responseInfo.content());
      int status = dis.readInt();
      long timestamp = dis.readLong();
      // Process response...
    } finally {
      // Proper fix for LEAK-3.1
      responseInfo.release();
    }
  }

  /**
   * TEST 3.4: ResponseInfo - LEAK-3.2 - Exception During Response Processing
   *
   * Flow: Create ResponseInfo → Process → [Exception] → (no release)
   * Expected: LEAK - exception breaks flow
   */
  @Test
  public void testResponseInfo_Leak_ExceptionDuringProcessing_NoFinally() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    content.writeInt(500);  // Error status

    ResponseInfo responseInfo = new ResponseInfo(null, null, content);

    try {
      NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
      int status = stream.readInt();

      if (status == 500) {
        // Simulate exception during processing
        // throw new MessageFormatException("Corrupt data");
        return;  // For tracer, just return instead of throw
      }
    } catch (Exception e) {
      // Exception caught but DON'T release ResponseInfo
      return;
    }

    // DO NOT call responseInfo.release()
    // Content leaks
  }

  /**
   * TEST 3.5: ResponseInfo - Exception During Processing WITH Finally
   *
   * Flow: Create ResponseInfo → Process → [Exception] → release() in finally
   * Expected: NO LEAK - finally ensures cleanup
   */
  @Test
  public void testResponseInfo_Safe_ExceptionDuringProcessing_WithFinally() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    content.writeInt(500);

    ResponseInfo responseInfo = new ResponseInfo(null, null, content);

    try {
      NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
      int status = stream.readInt();

      if (status == 500) {
        // Exception during processing
        return;
      }
    } finally {
      // Cleanup happens despite exception
      responseInfo.release();
    }
  }

  /**
   * TEST 3.6: ResponseInfo - LEAK-3.3 - Caller Confusion About Ownership (ByteBuf)
   *
   * Flow: Allocate ByteBuf → Create ResponseInfo → Caller still has ByteBuf reference
   * Expected: Confusion about who owns ByteBuf
   */
  @Test
  public void testResponseInfo_OwnershipConfusion_ByteBufRetained() throws Exception {
    ByteBuf responseBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    responseBuf.writeInt(200);

    // Caller creates ResponseInfo with responseBuf
    ResponseInfo responseInfo = new ResponseInfo(null, null, responseBuf);

    // Ownership transferred to ResponseInfo
    // Caller should NOT use responseBuf anymore
    // But caller might be confused and think they still own it

    // Correct: Release ResponseInfo
    responseInfo.release();

    // Incorrect: Caller tries to release responseBuf separately
    // This would be a double-release error if both happened
    // responseBuf.release();  // WRONG - ResponseInfo owns it now
  }

  /**
   * TEST 3.7: ResponseInfo - LEAK-3.3 - replace() Creates Orphan
   *
   * Flow: Create ResponseInfo → replace(newBuf) → Release only new one
   * Expected: LEAK - original content orphaned
   */
  @Test
  public void testResponseInfo_Leak_ReplaceCreatesOrphan() throws Exception {
    ByteBuf bufferA = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    bufferA.writeInt(100);

    ResponseInfo original = new ResponseInfo(null, null, bufferA);

    ByteBuf bufferB = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    bufferB.writeInt(200);

    ResponseInfo replaced = original.replace(bufferB);

    // Release only the new ResponseInfo
    replaced.release();  // Releases bufferB

    // DO NOT release original - bufferA leaks
    // This demonstrates the danger of replace()
  }

  /**
   * TEST 3.8: ResponseInfo - replace() Proper Usage
   *
   * Flow: Create ResponseInfo → replace(newBuf) → Release BOTH
   * Expected: NO LEAK - both released
   */
  @Test
  public void testResponseInfo_Safe_ReplaceWithBothReleases() throws Exception {
    ByteBuf bufferA = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    bufferA.writeInt(100);

    ResponseInfo original = new ResponseInfo(null, null, bufferA);

    ByteBuf bufferB = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    bufferB.writeInt(200);

    ResponseInfo replaced = original.replace(bufferB);

    // Correct: Release both
    original.release();   // Releases bufferA
    replaced.release();   // Releases bufferB
  }

  /**
   * TEST 3.9: ResponseInfo - LEAK-3.4 - Send Object Not Released
   *
   * Flow: Create ResponseInfo with Send → (no release)
   * Expected: LEAK - Send object and its internal ByteBufs leak
   */
  @Test
  public void testResponseInfo_Leak_SendObjectNotReleased() throws Exception {
    ByteBuf internalBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    internalBuf.writeBytes(generateRandomBytes(256));

    MockSend send = new MockSend(internalBuf);

    // LocalNetworkClient path - ResponseInfo with Send
    ResponseInfo responseInfo = new ResponseInfo(null, null, (DataNodeId) null, send);

    // Process response...

    // DO NOT call responseInfo.release()
    // Send object and internalBuf leak
  }

  /**
   * TEST 3.10: ResponseInfo - Send Object Properly Released
   *
   * Flow: Create ResponseInfo with Send → release()
   * Expected: NO LEAK - release() calls ReferenceCountUtil.safeRelease(response)
   */
  @Test
  public void testResponseInfo_Safe_SendObjectReleased() throws Exception {
    ByteBuf internalBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    internalBuf.writeBytes(generateRandomBytes(256));

    MockSend send = new MockSend(internalBuf);

    ResponseInfo responseInfo = new ResponseInfo(null, null, (DataNodeId) null, send);

    // Process response...

    // Proper cleanup
    responseInfo.release();  // Calls ReferenceCountUtil.safeRelease(send)
  }

  /**
   * TEST 3.11: ResponseInfo - Null Content (Network Error)
   *
   * Flow: Create ResponseInfo with null content (error case) → release()
   * Expected: NO LEAK - null is safe to release
   */
  @Test
  public void testResponseInfo_NullContent_NetworkError() throws Exception {
    // Network error scenario - no content
    ResponseInfo responseInfo = new ResponseInfo(null, NetworkClientErrorCode.ConnectionUnavailable,
        (ByteBuf) null, (DataNodeId) null, false);

    // Release should handle null safely
    responseInfo.release();
  }

  /**
   * TEST 3.12: ResponseInfo - Double Release
   *
   * Flow: Create ResponseInfo → release() → release()
   * Expected: NO LEAK - second release() should be safe
   */
  @Test
  public void testResponseInfo_DoubleRelease() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    content.writeInt(200);

    ResponseInfo responseInfo = new ResponseInfo(null, null, content);

    responseInfo.release();  // First release - sets content=null
    responseInfo.release();  // Second release - safe, content already null
  }

  /**
   * TEST 3.13: ResponseInfo - Multiple ResponseInfos in List (Router Pattern)
   *
   * Flow: Create list of ResponseInfo → Process each → forEach(ResponseInfo::release)
   * Expected: NO LEAK - all released
   */
  @Test
  public void testResponseInfo_MultipleInList_AllReleased() throws Exception {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
      content.writeInt(200 + i);
      responseInfoList.add(new ResponseInfo(null, null, content));
    }

    // Process each response
    for (ResponseInfo responseInfo : responseInfoList) {
      NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
      int status = stream.readInt();
    }

    // OperationController pattern - release all
    responseInfoList.forEach(ResponseInfo::release);
  }

  /**
   * TEST 3.14: ResponseInfo - Multiple ResponseInfos, Exception Mid-Processing
   *
   * Flow: Process list → Exception on 3rd item → Some released, some not
   * Expected: PARTIAL LEAK - items after exception not released
   */
  @Test
  public void testResponseInfo_MultipleInList_ExceptionMidLoop_NoFinally() throws Exception {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
      content.writeInt(i == 2 ? 500 : 200);  // 3rd item has error status
      responseInfoList.add(new ResponseInfo(null, null, content));
    }

    try {
      for (int i = 0; i < responseInfoList.size(); i++) {
        ResponseInfo responseInfo = responseInfoList.get(i);
        NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
        int status = stream.readInt();

        if (status == 500) {
          // Exception on 3rd item
          return;  // Loop breaks
        }

        // Release as we process
        responseInfo.release();
      }
    } catch (Exception e) {
      // No cleanup in catch
    }

    // Items 0-1 were released
    // Items 2-9 are LEAKED
  }

  /**
   * TEST 3.15: ResponseInfo - Multiple ResponseInfos WITH Try-Finally
   *
   * Flow: Process list with try-finally → Exception → All released in finally
   * Expected: NO LEAK - finally ensures all released
   */
  @Test
  public void testResponseInfo_MultipleInList_ExceptionWithFinally() throws Exception {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
      content.writeInt(i == 2 ? 500 : 200);
      responseInfoList.add(new ResponseInfo(null, null, content));
    }

    try {
      for (ResponseInfo responseInfo : responseInfoList) {
        NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
        int status = stream.readInt();

        if (status == 500) {
          // Exception on 3rd item
          return;
        }
      }
    } finally {
      // All items cleaned up despite exception
      responseInfoList.forEach(ResponseInfo::release);
    }
  }

  // ========================================
  // SECTION 4: Integration Tests
  // Tests that combine multiple classes
  // ========================================

  /**
   * TEST 4.1: PutRequest + ResponseInfo Integration - Full Roundtrip
   *
   * Flow: Create PutRequest → Serialize → Create ResponseInfo → Deserialize → Release both
   * Expected: NO LEAK - full roundtrip with proper cleanup
   */
  @Test
  public void testIntegration_PutRequestResponseRoundtrip_ProperRelease() throws Exception {
    // Create PutRequest
    ByteBuf materializedBlob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    materializedBlob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest putRequest = createSamplePutRequest(materializedBlob);
    putRequest.prepareBuffer();

    // Serialize to channel
    ByteBufferChannel channel = new ByteBufferChannel(ByteBuffer.allocate((int) putRequest.sizeInBytes()));
    putRequest.writeTo(channel);

    // Create response
    ByteBuf responseContent = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    responseContent.writeInt(200);
    ResponseInfo responseInfo = new ResponseInfo(null, null, responseContent);

    // Clean up both
    putRequest.release();
    responseInfo.release();
  }

  /**
   * TEST 4.2: ResponseInfo + NettyByteBufDataInputStream Integration
   *
   * Flow: ResponseInfo content → NettyByteBufDataInputStream → Deserialize → Release ResponseInfo
   * Expected: NO LEAK - stream is view, ResponseInfo owns content
   */
  @Test
  public void testIntegration_ResponseInfoWithStream_ProperPattern() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    content.writeInt(200);
    content.writeLong(System.currentTimeMillis());
    content.writeBytes(generateRandomBytes(100));

    ResponseInfo responseInfo = new ResponseInfo(null, null, content);

    // Create stream - just a view
    NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());

    // Deserialize
    int status = stream.readInt();
    long timestamp = stream.readLong();

    // Release ResponseInfo - this releases content
    responseInfo.release();

    // Stream doesn't need cleanup
  }

  /**
   * TEST 4.3: Complex Flow - Multiple Allocations and Transfers
   *
   * Flow: Multiple ByteBuf allocations → PutRequest → ResponseInfo → Streams → Cleanup
   * Expected: NO LEAK - demonstrates complex but correct flow
   */
  @Test
  public void testIntegration_ComplexFlow_MultipleAllocations() throws Exception {
    // Allocate multiple ByteBufs
    ByteBuf blob1 = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
    blob1.writeBytes(generateRandomBytes(512));

    ByteBuf blob2 = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
    blob2.writeBytes(generateRandomBytes(512));

    // Create composite blob
    CompositeByteBuf compositeBuf = PooledByteBufAllocator.DEFAULT.compositeHeapBuffer(2);
    compositeBuf.addComponent(true, blob1);
    compositeBuf.addComponent(true, blob2);

    // Create PutRequest - ownership transfers
    PutRequest putRequest = createSamplePutRequest(compositeBuf);
    putRequest.prepareBuffer();

    // Create ResponseInfo
    ByteBuf responseContent = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    responseContent.writeInt(200);
    responseContent.writeBytes(generateRandomBytes(100));

    ResponseInfo responseInfo = new ResponseInfo(null, null, responseContent);

    // Use stream to read response
    NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
    int status = stream.readInt();

    // Release all owned objects
    putRequest.release();
    responseInfo.release();
  }

  // ========================================
  // Helper Methods
  // ========================================

  /**
   * Helper: Create a sample PutRequest with realistic data
   */
  private PutRequest createSamplePutRequest(ByteBuf materializedBlob) throws Exception {
    int correlationId = RANDOM.nextInt();
    String clientId = "test-client";

    BlobProperties properties = new BlobProperties(BLOB_SIZE, "service-id",
        "owner-id", "image/jpeg", false, 3600,
        System.currentTimeMillis(), (short) 1, (short) 1, false, null, null, null, null);

    ByteBuffer userMetadata = ByteBuffer.wrap(generateRandomBytes(USER_METADATA_SIZE));

    return new PutRequest(correlationId, clientId, blobId, properties, userMetadata,
        materializedBlob, BLOB_SIZE, BlobType.DataBlob, null);
  }

  /**
   * Helper: Generate random bytes
   */
  private byte[] generateRandomBytes(int size) {
    byte[] bytes = new byte[size];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  /**
   * Helper: Create a WritableByteChannel that throws IOException after N bytes
   */
  private static class FailingChannel implements WritableByteChannel {
    private final int failAfterBytes;
    private int bytesWritten = 0;
    private boolean closed = false;

    public FailingChannel(int failAfterBytes) {
      this.failAfterBytes = failAfterBytes;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      if (bytesWritten >= failAfterBytes) {
        throw new IOException("Simulated network failure");
      }

      int toWrite = Math.min(src.remaining(), failAfterBytes - bytesWritten);
      bytesWritten += toWrite;
      src.position(src.position() + toWrite);
      return toWrite;
    }

    @Override
    public boolean isOpen() {
      return !closed;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /**
   * Helper: Create a mock Send object for testing ResponseInfo Send path
   */
  private static class MockSend implements Send {
    private ByteBuf internalBuffer;

    public MockSend(ByteBuf buffer) {
      this.internalBuffer = buffer;
    }

    @Override
    public long writeTo(WritableByteChannel channel) throws IOException {
      if (internalBuffer != null) {
        int bytesToWrite = internalBuffer.readableBytes();
        ByteBuffer nioBuffer = internalBuffer.nioBuffer();
        channel.write(nioBuffer);
        return bytesToWrite;
      }
      return 0;
    }

    @Override
    public boolean isSendComplete() {
      return true;
    }

    @Override
    public long sizeInBytes() {
      return internalBuffer != null ? internalBuffer.readableBytes() : 0;
    }

    @Override
    public ByteBuf content() {
      return internalBuffer;
    }

    @Override
    public MockSend copy() {
      return this;
    }

    @Override
    public MockSend duplicate() {
      return this;
    }

    @Override
    public MockSend retainedDuplicate() {
      return this;
    }

    @Override
    public MockSend replace(ByteBuf content) {
      return new MockSend(content);
    }

    @Override
    public MockSend retain() {
      if (internalBuffer != null) {
        internalBuffer.retain();
      }
      return this;
    }

    @Override
    public MockSend retain(int increment) {
      if (internalBuffer != null) {
        internalBuffer.retain(increment);
      }
      return this;
    }

    @Override
    public MockSend touch() {
      if (internalBuffer != null) {
        internalBuffer.touch();
      }
      return this;
    }

    @Override
    public MockSend touch(Object hint) {
      if (internalBuffer != null) {
        internalBuffer.touch(hint);
      }
      return this;
    }

    @Override
    public int refCnt() {
      return internalBuffer != null ? internalBuffer.refCnt() : 0;
    }

    @Override
    public boolean release() {
      if (internalBuffer != null) {
        return internalBuffer.release();
      }
      return false;
    }

    @Override
    public boolean release(int decrement) {
      if (internalBuffer != null) {
        return internalBuffer.release(decrement);
      }
      return false;
    }
  }
}
