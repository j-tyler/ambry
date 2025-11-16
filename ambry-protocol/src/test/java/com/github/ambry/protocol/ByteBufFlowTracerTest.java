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
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
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
   *
   * This demonstrates the correct production usage pattern where:
   * - PutRequest takes ownership of materializedBlob
   * - prepareBuffer() creates composite buffer
   * - writeTo() sends data to channel
   * - release() cleans up all ByteBufs
   */
  @Test
  public void testPutRequest_HappyPath_ProperRelease() throws Exception {
    // TODO: Implement
    // 1. Create materializedBlob using PooledByteBufAllocator
    // 2. Create PutRequest with all required parameters
    // 3. Call prepareBuffer() to create composite buffer
    // 4. Call writeTo() with ByteBufferChannel
    // 5. Call release() to clean up
    // 6. Verify refCnt() == 0 for debugging (optional, not assertion)
  }

  /**
   * TEST 1.2: PutRequest - LEAK-1.1 - Exception Before prepareBuffer()
   *
   * Flow: Constructor → [EXCEPTION] → (no release)
   * Expected: LEAK - blob ByteBuf never released
   *
   * This demonstrates the leak scenario where an exception occurs after construction
   * but before prepareBuffer() is called, and the caller fails to release the PutRequest.
   *
   * In production, this would happen if:
   * - Validation fails after PutRequest creation
   * - Caller doesn't use try-finally or try-with-resources
   */
  @Test
  public void testPutRequest_Leak_ExceptionBeforePrepareBuffer_NoRelease() throws Exception {
    // TODO: Implement
    // 1. Create materializedBlob using PooledByteBufAllocator
    // 2. Create PutRequest with materializedBlob
    // 3. Simulate exception scenario (e.g., validation error)
    // 4. DO NOT call release() - intentionally leak to show the path
    // 5. This simulates caller error handling failure
  }

  /**
   * TEST 1.3: PutRequest - LEAK-1.1 Mitigation - Exception Before prepareBuffer() WITH Release
   *
   * Flow: Constructor → [EXCEPTION] → release() in finally block
   * Expected: NO LEAK - release() cleans up blob field
   *
   * This demonstrates the safe pattern using try-finally to ensure cleanup.
   */
  @Test
  public void testPutRequest_Safe_ExceptionBeforePrepareBuffer_WithRelease() throws Exception {
    // TODO: Implement
    // 1. Create materializedBlob using PooledByteBufAllocator
    // 2. Use try-finally block
    // 3. In try: Create PutRequest, simulate exception
    // 4. In finally: Call release()
    // 5. Demonstrates proper error handling pattern
  }

  /**
   * TEST 1.4: PutRequest - LEAK-1.2 - Exception During prepareBuffer()
   *
   * Flow: Constructor → prepareBuffer() [EXCEPTION mid-execution]
   * Expected: POTENTIAL LEAK - crcByteBuf and/or bufferToSend may leak
   *
   * This demonstrates the critical leak scenario identified in the analysis where
   * an exception occurs after allocating crcByteBuf but before adding it to the
   * composite buffer. Current code doesn't handle this.
   *
   * Simulated scenario:
   * - crcByteBuf is allocated (line 265)
   * - Exception occurs before composite creation (line 269)
   * - crcByteBuf and bufferToSend are both leaked
   */
  @Test
  public void testPutRequest_Leak_ExceptionDuringPrepareBuffer() throws Exception {
    // TODO: Implement
    // 1. Create materializedBlob with special characteristics that might cause exception
    // 2. Create PutRequest
    // 3. Call prepareBuffer() - may need to use reflection or subclass to inject exception
    // 4. Catch exception but DON'T call release()
    // 5. This shows the vulnerability in prepareBuffer() implementation
    //
    // NOTE: This is tricky to test without modifying PutRequest code
    // Alternative: Create a custom WritableByteChannel that throws during write
  }

  /**
   * TEST 1.5: PutRequest - LEAK-1.3 - Caller Never Calls release()
   *
   * Flow: Constructor → prepareBuffer() → writeTo() → [NO RELEASE]
   * Expected: LEAK - All ByteBufs (blob, crcByteBuf, bufferToSend) leak
   *
   * This is the most common leak scenario - caller simply forgets to call release().
   * Very easy to happen in complex error paths or when developers misunderstand ownership.
   */
  @Test
  public void testPutRequest_Leak_NoReleaseCall() throws Exception {
    // TODO: Implement
    // 1. Create complete PutRequest
    // 2. Call prepareBuffer()
    // 3. Call writeTo()
    // 4. DO NOT call release()
    // 5. Let test complete with leak
  }

  /**
   * TEST 1.6: PutRequest - LEAK-1.4 - Partial Write With Network Error
   *
   * Flow: Constructor → prepareBuffer() → writeTo() [IOException] → (unclear if release called)
   * Expected: POTENTIAL LEAK depending on caller error handling
   *
   * This demonstrates the scenario where writeTo() throws an IOException (network error)
   * and the caller may not properly handle cleanup.
   */
  @Test
  public void testPutRequest_PartialWrite_WithRelease() throws Exception {
    // TODO: Implement
    // 1. Create PutRequest and prepare buffer
    // 2. Create a WritableByteChannel that throws IOException after partial write
    // 3. Call writeTo() - catch IOException
    // 4. In finally: Call release() (safe pattern)
    // 5. Demonstrates proper cleanup despite IOException
  }

  /**
   * TEST 1.7: PutRequest - LEAK-1.4 - Partial Write WITHOUT Release
   *
   * Flow: Constructor → prepareBuffer() → writeTo() [IOException] → (no release)
   * Expected: LEAK - caller assumes failure means no cleanup needed
   */
  @Test
  public void testPutRequest_PartialWrite_NoRelease() throws Exception {
    // TODO: Implement
    // 1. Create PutRequest and prepare buffer
    // 2. Create a WritableByteChannel that throws IOException after partial write
    // 3. Call writeTo() - catch IOException
    // 4. DO NOT call release() - simulating caller assumption that failed write means no cleanup
    // 5. Demonstrates the leak path
  }

  /**
   * TEST 1.8: PutRequest - Multiple Calls to prepareBuffer()
   *
   * Flow: Constructor → prepareBuffer() → prepareBuffer() again
   * Expected: Depends on implementation - may leak or handle gracefully
   *
   * Tests whether calling prepareBuffer() multiple times causes issues.
   */
  @Test
  public void testPutRequest_MultiplePrepareBufferCalls() throws Exception {
    // TODO: Implement
    // 1. Create PutRequest
    // 2. Call prepareBuffer()
    // 3. Call prepareBuffer() again (edge case)
    // 4. Check behavior - does it leak the first composite? Replace it?
    // 5. Call release()
  }

  /**
   * TEST 1.9: PutRequest - Release Before prepareBuffer()
   *
   * Flow: Constructor → release() → prepareBuffer()
   * Expected: May crash or handle gracefully depending on implementation
   *
   * Edge case: What if release() is called before prepareBuffer()?
   */
  @Test
  public void testPutRequest_ReleaseBeforePrepareBuffer() throws Exception {
    // TODO: Implement
    // 1. Create PutRequest with materializedBlob
    // 2. Call release() immediately (blob is released)
    // 3. Try to call prepareBuffer() - what happens?
    // 4. Should fail gracefully or throw exception
  }

  /**
   * TEST 1.10: PutRequest - Double Release
   *
   * Flow: Constructor → prepareBuffer() → release() → release()
   * Expected: Second release() should be safe (ReferenceCountUtil.safeRelease handles this)
   *
   * Tests resilience to double-release errors.
   */
  @Test
  public void testPutRequest_DoubleRelease() throws Exception {
    // TODO: Implement
    // 1. Create PutRequest and prepare buffer
    // 2. Call release() first time
    // 3. Call release() second time
    // 4. Should not crash - safeRelease() handles null and zero refCount
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
   *
   * This demonstrates the CORRECT usage pattern where:
   * - Caller allocates and retains ownership of ByteBuf
   * - NettyByteBufDataInputStream is just a view/wrapper
   * - Caller releases ByteBuf after use
   * - Stream does not manage lifecycle
   */
  @Test
  public void testNettyByteBufDataInputStream_HappyPath_CallerOwns() throws Exception {
    // TODO: Implement
    // 1. Allocate ByteBuf using PooledByteBufAllocator
    // 2. Write some data to ByteBuf
    // 3. Create NettyByteBufDataInputStream(buf)
    // 4. Read data from stream (readInt, readLong, etc.)
    // 5. Close stream (does nothing to buf)
    // 6. Release buf explicitly - caller's responsibility
  }

  /**
   * TEST 2.2: NettyByteBufDataInputStream - LEAK-2.2 - Caller Assumes Stream Owns ByteBuf
   *
   * Flow: Allocate ByteBuf → Create stream → Use stream → Close stream → [NO BUFFER RELEASE]
   * Expected: LEAK - ByteBuf never released
   *
   * This demonstrates the dangerous misunderstanding where caller thinks the stream
   * owns the ByteBuf and will release it. This is WRONG but easy to assume.
   */
  @Test
  public void testNettyByteBufDataInputStream_Leak_CallerConfusion() throws Exception {
    // TODO: Implement
    // 1. Allocate ByteBuf
    // 2. Create NettyByteBufDataInputStream
    // 3. Read from stream
    // 4. Call stream.close() - caller thinks this releases buf
    // 5. DO NOT release buf - simulating caller confusion
    // 6. ByteBuf leaks
  }

  /**
   * TEST 2.3: NettyByteBufDataInputStream - Production Pattern from NettyServerRequest
   *
   * Flow: Owner allocates → Creates stream wrapper → Processes → Owner releases
   * Expected: NO LEAK - follows correct pattern
   *
   * This replicates the pattern used in NettyServerRequest where:
   * - NettyServerRequest owns the content ByteBuf
   * - Creates NettyByteBufDataInputStream as a wrapper for reading
   * - NettyServerRequest.release() releases the content
   */
  @Test
  public void testNettyByteBufDataInputStream_ProductionPattern_NettyServerRequest() throws Exception {
    // TODO: Implement
    // 1. Simulate NettyServerRequest pattern
    // 2. Class holds ByteBuf content field
    // 3. Creates NettyByteBufDataInputStream(content) for reading
    // 4. Uses stream to deserialize data
    // 5. Release content ByteBuf (not the stream)
  }

  /**
   * TEST 2.4: NettyByteBufDataInputStream - Used in ResponseInfo Processing
   *
   * Flow: ResponseInfo has content → Create stream → Deserialize → Release ResponseInfo
   * Expected: NO LEAK if ResponseInfo.release() is called
   *
   * This replicates how ReplicaThread processes ResponseInfo:
   * - ResponseInfo owns content ByteBuf
   * - Creates NettyByteBufDataInputStream to read response
   * - MUST call ResponseInfo.release() to clean up content
   */
  @Test
  public void testNettyByteBufDataInputStream_ResponseInfoPattern_WithRelease() throws Exception {
    // TODO: Implement
    // 1. Create ByteBuf with serialized response data
    // 2. Create ResponseInfo with content
    // 3. Create NettyByteBufDataInputStream(responseInfo.content())
    // 4. Read/deserialize data from stream
    // 5. Call responseInfo.release() - proper cleanup
  }

  /**
   * TEST 2.5: NettyByteBufDataInputStream - Used in ResponseInfo Processing WITHOUT Release
   *
   * Flow: ResponseInfo has content → Create stream → Deserialize → [NO RELEASE]
   * Expected: LEAK - this is the ReplicaThread bug
   *
   * This demonstrates LEAK-3.1 from the analysis - ReplicaThread creates the stream
   * but never releases the ResponseInfo.
   */
  @Test
  public void testNettyByteBufDataInputStream_ResponseInfoPattern_NoRelease() throws Exception {
    // TODO: Implement
    // 1. Create ByteBuf with serialized response data
    // 2. Create ResponseInfo with content
    // 3. Create NettyByteBufDataInputStream(responseInfo.content())
    // 4. Read/deserialize data from stream
    // 5. DO NOT call responseInfo.release() - simulating ReplicaThread bug
    // 6. Content ByteBuf leaks
  }

  /**
   * TEST 2.6: NettyByteBufDataInputStream - Exception During Deserialization
   *
   * Flow: Create stream → Read data → [Exception] → Cleanup in finally
   * Expected: NO LEAK if caller has try-finally
   *
   * Tests exception safety when using NettyByteBufDataInputStream.
   */
  @Test
  public void testNettyByteBufDataInputStream_ExceptionDuringRead_WithFinally() throws Exception {
    // TODO: Implement
    // 1. Create ByteBuf with invalid/corrupt data
    // 2. Create NettyByteBufDataInputStream
    // 3. Try to read - will throw exception (e.g., EOFException)
    // 4. Catch exception in try-catch
    // 5. In finally: Release ByteBuf (caller's responsibility)
  }

  /**
   * TEST 2.7: NettyByteBufDataInputStream - Exception During Deserialization WITHOUT Cleanup
   *
   * Flow: Create stream → Read data → [Exception] → (no cleanup)
   * Expected: LEAK - exception breaks flow, ByteBuf never released
   */
  @Test
  public void testNettyByteBufDataInputStream_ExceptionDuringRead_NoCleanup() throws Exception {
    // TODO: Implement
    // 1. Create ByteBuf with invalid/corrupt data
    // 2. Create NettyByteBufDataInputStream
    // 3. Try to read - throws exception
    // 4. Catch exception but DON'T release ByteBuf
    // 5. Simulates poor error handling leading to leak
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
   *
   * This replicates the CORRECT pattern from OperationController.java:622-627
   * where all ResponseInfo objects are released after processing.
   */
  @Test
  public void testResponseInfo_HappyPath_RouterPattern() throws Exception {
    // TODO: Implement
    // 1. Create ByteBuf with response content
    // 2. Create ResponseInfo with content
    // 3. Process response (simulate onResponse logic)
    // 4. Call responseInfo.release() - Router pattern
    // 5. Verify proper cleanup
  }

  /**
   * TEST 3.2: ResponseInfo - LEAK-3.1 - ReplicaThread Pattern WITHOUT Release
   *
   * Flow: Create ResponseInfo → Create NettyByteBufDataInputStream → Process → [NO RELEASE]
   * Expected: LEAK - content ByteBuf never released
   *
   * This demonstrates the CRITICAL BUG in ReplicaThread.java:473 where ResponseInfo
   * is processed but never released. This is a HIGH SEVERITY production leak.
   */
  @Test
  public void testResponseInfo_Leak_ReplicaThreadPattern_NoRelease() throws Exception {
    // TODO: Implement
    // 1. Create ByteBuf with serialized ReplicaMetadataResponse
    // 2. Create ResponseInfo with content
    // 3. Create NettyByteBufDataInputStream(responseInfo.content()) - ReplicaThread pattern
    // 4. Deserialize response
    // 5. Process response
    // 6. DO NOT call responseInfo.release() - bug simulation
    // 7. Content leaks
  }

  /**
   * TEST 3.3: ResponseInfo - ReplicaThread Pattern WITH Release (Fixed)
   *
   * Flow: Create ResponseInfo → Process → release() in finally
   * Expected: NO LEAK - demonstrates the fix
   *
   * This shows what ReplicaThread SHOULD do - release ResponseInfo after processing.
   */
  @Test
  public void testResponseInfo_Safe_ReplicaThreadPattern_WithRelease() throws Exception {
    // TODO: Implement
    // 1. Create ByteBuf with serialized response
    // 2. Create ResponseInfo
    // 3. Use try-finally block
    // 4. In try: Create stream, deserialize, process
    // 5. In finally: Call responseInfo.release()
    // 6. Demonstrates proper fix for LEAK-3.1
  }

  /**
   * TEST 3.4: ResponseInfo - LEAK-3.2 - Exception During Response Processing
   *
   * Flow: Create ResponseInfo → Process → [Exception] → (no release)
   * Expected: LEAK - exception breaks flow
   *
   * This demonstrates what happens when an exception occurs during response processing
   * and the caller doesn't use try-finally for cleanup.
   */
  @Test
  public void testResponseInfo_Leak_ExceptionDuringProcessing_NoFinally() throws Exception {
    // TODO: Implement
    // 1. Create ResponseInfo with content
    // 2. Start processing response
    // 3. Throw exception (e.g., MessageFormatException)
    // 4. Catch exception but DON'T release ResponseInfo
    // 5. Content leaks
  }

  /**
   * TEST 3.5: ResponseInfo - Exception During Processing WITH Finally
   *
   * Flow: Create ResponseInfo → Process → [Exception] → release() in finally
   * Expected: NO LEAK - finally ensures cleanup
   */
  @Test
  public void testResponseInfo_Safe_ExceptionDuringProcessing_WithFinally() throws Exception {
    // TODO: Implement
    // 1. Create ResponseInfo with content
    // 2. Use try-finally
    // 3. In try: Process response, throw exception
    // 4. In finally: Call responseInfo.release()
    // 5. Cleanup happens despite exception
  }

  /**
   * TEST 3.6: ResponseInfo - LEAK-3.3 - Caller Confusion About Ownership (ByteBuf)
   *
   * Flow: Allocate ByteBuf → Create ResponseInfo → Caller still has ByteBuf reference
   * Expected: Confusion about who owns ByteBuf
   *
   * This demonstrates the ownership confusion scenario where caller creates a ByteBuf,
   * passes it to ResponseInfo constructor, and doesn't understand ownership transferred.
   */
  @Test
  public void testResponseInfo_OwnershipConfusion_ByteBufRetained() throws Exception {
    // TODO: Implement
    // 1. Caller allocates ByteBuf responseBuf
    // 2. Caller creates ResponseInfo(requestInfo, null, responseBuf)
    // 3. Caller still has responseBuf reference
    // 4. Option A: Caller releases responseBuf (wrong - ResponseInfo owns it now)
    // 5. Option B: Caller releases ResponseInfo (correct)
    // 6. Show different outcomes
  }

  /**
   * TEST 3.7: ResponseInfo - LEAK-3.3 - replace() Creates Orphan
   *
   * Flow: Create ResponseInfo → replace(newBuf) → Release only new one
   * Expected: LEAK - original content orphaned
   *
   * This demonstrates the dangerous replace() method where creating a new ResponseInfo
   * doesn't automatically release the old one's content.
   */
  @Test
  public void testResponseInfo_Leak_ReplaceCreatesOrphan() throws Exception {
    // TODO: Implement
    // 1. Create ResponseInfo original with bufferA
    // 2. Call original.replace(bufferB) → creates new ResponseInfo
    // 3. Release only the new ResponseInfo
    // 4. Original content (bufferA) leaks because original was never released
  }

  /**
   * TEST 3.8: ResponseInfo - replace() Proper Usage
   *
   * Flow: Create ResponseInfo → replace(newBuf) → Release BOTH
   * Expected: NO LEAK - both released
   *
   * Shows the correct way to use replace() - must release both objects.
   */
  @Test
  public void testResponseInfo_Safe_ReplaceWithBothReleases() throws Exception {
    // TODO: Implement
    // 1. Create ResponseInfo original with bufferA
    // 2. Call replaced = original.replace(bufferB)
    // 3. Release original (releases bufferA)
    // 4. Release replaced (releases bufferB)
    // 5. Both ByteBufs properly cleaned up
  }

  /**
   * TEST 3.9: ResponseInfo - LEAK-3.4 - Send Object Not Released
   *
   * Flow: Create ResponseInfo with Send → (no release)
   * Expected: LEAK - Send object and its internal ByteBufs leak
   *
   * This tests the LocalNetworkClient path where ResponseInfo contains a Send object
   * instead of a ByteBuf.
   */
  @Test
  public void testResponseInfo_Leak_SendObjectNotReleased() throws Exception {
    // TODO: Implement
    // 1. Create a Send object (may contain ByteBufs internally)
    // 2. Create ResponseInfo using constructor that takes Send
    // 3. Process response
    // 4. DO NOT call responseInfo.release()
    // 5. Send object leaks
  }

  /**
   * TEST 3.10: ResponseInfo - Send Object Properly Released
   *
   * Flow: Create ResponseInfo with Send → release()
   * Expected: NO LEAK - release() calls ReferenceCountUtil.safeRelease(response)
   */
  @Test
  public void testResponseInfo_Safe_SendObjectReleased() throws Exception {
    // TODO: Implement
    // 1. Create a Send object
    // 2. Create ResponseInfo with Send
    // 3. Process response
    // 4. Call responseInfo.release()
    // 5. Verify Send is released (line 161 in ResponseInfo.release())
  }

  /**
   * TEST 3.11: ResponseInfo - Null Content (Network Error)
   *
   * Flow: Create ResponseInfo with null content (error case) → release()
   * Expected: NO LEAK - null is safe to release
   *
   * Tests the error response path where content=null (e.g., ConnectionUnavailable).
   */
  @Test
  public void testResponseInfo_NullContent_NetworkError() throws Exception {
    // TODO: Implement
    // 1. Create ResponseInfo with null content (network error scenario)
    // 2. Set error code (e.g., NetworkClientErrorCode.ConnectionUnavailable)
    // 3. Call release()
    // 4. Should handle null safely (ReferenceCountUtil.safeRelease)
  }

  /**
   * TEST 3.12: ResponseInfo - Double Release
   *
   * Flow: Create ResponseInfo → release() → release()
   * Expected: NO LEAK - second release() should be safe
   *
   * Tests resilience to double-release errors.
   */
  @Test
  public void testResponseInfo_DoubleRelease() throws Exception {
    // TODO: Implement
    // 1. Create ResponseInfo with content
    // 2. Call release() first time (sets content=null)
    // 3. Call release() second time (safe, content already null)
    // 4. Should not crash
  }

  /**
   * TEST 3.13: ResponseInfo - Multiple ResponseInfos in List (Router Pattern)
   *
   * Flow: Create list of ResponseInfo → Process each → forEach(ResponseInfo::release)
   * Expected: NO LEAK - all released
   *
   * Replicates OperationController pattern with multiple responses.
   */
  @Test
  public void testResponseInfo_MultipleInList_AllReleased() throws Exception {
    // TODO: Implement
    // 1. Create List<ResponseInfo> with 5-10 ResponseInfo objects
    // 2. Process each response
    // 3. Call responseInfoList.forEach(ResponseInfo::release)
    // 4. All ByteBufs cleaned up
  }

  /**
   * TEST 3.14: ResponseInfo - Multiple ResponseInfos, Exception Mid-Processing
   *
   * Flow: Process list → Exception on 3rd item → Some released, some not
   * Expected: PARTIAL LEAK - items after exception not released
   *
   * Tests what happens when processing multiple responses and an exception breaks the loop.
   */
  @Test
  public void testResponseInfo_MultipleInList_ExceptionMidLoop_NoFinally() throws Exception {
    // TODO: Implement
    // 1. Create List<ResponseInfo> with 10 items
    // 2. Start processing in for loop
    // 3. Throw exception on 3rd item
    // 4. First 2 items processed and released
    // 5. Items 3-10 never released - LEAK
  }

  /**
   * TEST 3.15: ResponseInfo - Multiple ResponseInfos WITH Try-Finally
   *
   * Flow: Process list with try-finally → Exception → All released in finally
   * Expected: NO LEAK - finally ensures all released
   */
  @Test
  public void testResponseInfo_MultipleInList_ExceptionWithFinally() throws Exception {
    // TODO: Implement
    // 1. Create List<ResponseInfo>
    // 2. Use try-finally
    // 3. In try: Process items, throw exception mid-way
    // 4. In finally: responseInfoList.forEach(ResponseInfo::release)
    // 5. All items cleaned up despite exception
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
   *
   * This simulates a complete request/response cycle.
   */
  @Test
  public void testIntegration_PutRequestResponseRoundtrip_ProperRelease() throws Exception {
    // TODO: Implement
    // 1. Create PutRequest with ByteBuf
    // 2. Prepare and write to channel
    // 3. Create ResponseInfo from response bytes
    // 4. Release PutRequest
    // 5. Release ResponseInfo
    // 6. All ByteBufs cleaned up
  }

  /**
   * TEST 4.2: ResponseInfo + NettyByteBufDataInputStream Integration
   *
   * Flow: ResponseInfo content → NettyByteBufDataInputStream → Deserialize → Release ResponseInfo
   * Expected: NO LEAK - stream is view, ResponseInfo owns content
   *
   * This is the critical pattern used throughout Ambry.
   */
  @Test
  public void testIntegration_ResponseInfoWithStream_ProperPattern() throws Exception {
    // TODO: Implement
    // 1. Create ResponseInfo with serialized data in content
    // 2. Create NettyByteBufDataInputStream(responseInfo.content())
    // 3. Read and deserialize data
    // 4. Call responseInfo.release() - releases content
    // 5. Stream doesn't need cleanup
  }

  /**
   * TEST 4.3: Complex Flow - Multiple Allocations and Transfers
   *
   * Flow: Multiple ByteBuf allocations → PutRequest → ResponseInfo → Streams → Cleanup
   * Expected: NO LEAK - demonstrates complex but correct flow
   *
   * Tests a realistic complex scenario with multiple ByteBuf operations.
   */
  @Test
  public void testIntegration_ComplexFlow_MultipleAllocations() throws Exception {
    // TODO: Implement
    // 1. Allocate multiple ByteBufs for different purposes
    // 2. Create PutRequest with one ByteBuf (ownership transfer)
    // 3. Create ResponseInfo with another ByteBuf
    // 4. Use streams to read from ResponseInfo
    // 5. Release all owned objects in correct order
    // 6. Verify no leaks despite complexity
  }

  // ========================================
  // Helper Methods
  // ========================================

  /**
   * Helper: Create a sample PutRequest with realistic data
   */
  private PutRequest createSamplePutRequest() throws Exception {
    // TODO: Implement helper to create PutRequest with:
    // - Valid BlobId
    // - BlobProperties
    // - User metadata ByteBuffer
    // - Blob content as ByteBuf
    // - Encryption key (can be null)
    return null;
  }

  /**
   * Helper: Create a sample ResponseInfo with realistic data
   */
  private ResponseInfo createSampleResponseInfo() throws Exception {
    // TODO: Implement helper to create ResponseInfo with:
    // - Valid RequestInfo
    // - ByteBuf content with serialized response
    // - Error code (null for success)
    return null;
  }

  /**
   * Helper: Create a WritableByteChannel that throws IOException after N bytes
   */
  private static class FailingChannel implements WritableByteChannel {
    // TODO: Implement channel that fails after partial write
    private final int failAfterBytes;
    private int bytesWritten = 0;
    private boolean closed = false;

    public FailingChannel(int failAfterBytes) {
      this.failAfterBytes = failAfterBytes;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      // Implement: write some bytes, then throw IOException
      return 0;
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
    // TODO: Implement mock Send that may contain ByteBufs
    private ByteBuf internalBuffer;

    public MockSend(ByteBuf buffer) {
      this.internalBuffer = buffer;
    }

    @Override
    public long writeTo(WritableByteChannel channel) throws IOException {
      // Implement write logic
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
  }
}
