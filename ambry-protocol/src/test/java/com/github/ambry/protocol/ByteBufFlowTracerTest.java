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
import io.netty.buffer.ByteBufInputStream;
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
 * ByteBuf Flow Tracer Test Suite - Production Pattern Validation
 *
 * PURPOSE: Validate that ACTUAL production code patterns correctly manage ByteBuf lifecycle.
 * These tests replicate the real patterns used in Router, Server, and Replication code to verify
 * whether they leak ByteBufs or properly clean them up.
 *
 * USAGE: Run this test class with the tracer agent:
 *   gradle :ambry-protocol:test --tests ByteBufFlowTracerTest -PwithByteBufTracking
 *
 * WHAT THIS TESTS:
 * - Router's retainedDuplicate() pattern (PutOperation.java:1854, 2364)
 * - Server's InputStream wrapper pattern (AmbryRequests.java:285, ServerRequestResponseHelper.java:118)
 * - Router's ResponseInfo cleanup pattern (OperationController.java:627)
 * - ReplicaThread's ResponseInfo usage (ReplicaThread.java:473) - suspected leak
 * - NettyByteBufDataInputStream non-owning wrapper semantics
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
    // All tests should properly clean up - no intentional leaks
  }

  // ========================================
  // SECTION 1: PutRequest Production Patterns
  // Replicates actual Router and Server code
  // ========================================

  /**
   * TEST 1: Router Pattern - retainedDuplicate() Ownership Transfer
   *
   * PRODUCTION CODE: PutOperation.java:1854 and 2364
   * Pattern: buf.retainedDuplicate() creates new ByteBuf with independent refCount
   *
   * Flow:
   * 1. Router allocates chunk buffer (refCount=1)
   * 2. Creates PutRequest with chunk.retainedDuplicate() (creates new buf with refCount=1)
   * 3. PutRequest takes ownership of duplicate
   * 4. NetworkClient sends request and calls request.release() (duplicate refCount=0)
   * 5. Router releases original chunk (original refCount=0)
   *
   * Expected: NO LEAK - Both original and duplicate properly released
   */
  @Test
  public void testPutRequest_RouterPattern_RetainedDuplicate() throws Exception {
    // Simulate Router's encrypted chunk buffer
    ByteBuf originalChunk = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    originalChunk.writeBytes(generateRandomBytes(BLOB_SIZE));

    // Router pattern: Pass retainedDuplicate to PutRequest
    ByteBuf duplicate = originalChunk.retainedDuplicate();
    PutRequest request = createPutRequest(duplicate);

    // Simulate NetworkClient sending the request
    request.prepareBuffer();
    ByteBufferChannel channel = new ByteBufferChannel(ByteBuffer.allocate((int) request.sizeInBytes()));
    request.writeTo(channel);

    // NetworkClient releases the request after send
    request.release();  // Releases duplicate (refCount=0)

    // Router releases original chunk
    originalChunk.release();  // Releases original (refCount=0)

    // Both ByteBufs should be fully released - no leak
  }

  /**
   * TEST 2: Server Pattern - InputStream Wrapper (Non-Owning)
   *
   * PRODUCTION CODE: AmbryRequests.java:285-288, ServerRequestResponseHelper.java:118-121
   * Pattern: Create wrapper PutRequest with InputStream constructor
   *
   * Flow:
   * 1. Original PutRequest (sentRequest) owns ByteBuf blob field
   * 2. Server creates new PutRequest with ByteBufInputStream wrapping sentRequest.getBlob()
   * 3. New PutRequest does NOT own ByteBuf (InputStream constructor sets blob=null)
   * 4. Original sentRequest is released via LocalChannelRequest.release()
   * 5. This releases the ByteBuf
   *
   * Expected: NO LEAK - Original PutRequest releases ByteBuf, wrapper is non-owning
   */
  @Test
  public void testPutRequest_ServerPattern_InputStreamWrapper() throws Exception {
    // Original PutRequest sent over network (owns ByteBuf)
    ByteBuf blob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    blob.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest sentRequest = createPutRequest(blob);

    // Server creates wrapper PutRequest with InputStream constructor
    // This constructor does NOT take ownership of ByteBuf
    PutRequest receivedRequest = new PutRequest(
        sentRequest.getCorrelationId(),
        sentRequest.getClientId(),
        sentRequest.getBlobId(),
        sentRequest.getBlobProperties(),
        sentRequest.getUsermetadata(),
        sentRequest.getBlobSize(),
        sentRequest.getBlobType(),
        sentRequest.getBlobEncryptionKey(),
        new ByteBufInputStream(sentRequest.getBlob()),  // Wraps but doesn't own
        null  // crc
    );

    // receivedRequest is just a wrapper - doesn't own ByteBuf
    // sentRequest still owns the ByteBuf

    // Server processes request, then releases original (which owns ByteBuf)
    sentRequest.release();  // This releases the ByteBuf

    // receivedRequest has nothing to release (blob field is null)
    // No leak - ByteBuf released via sentRequest
  }

  /**
   * TEST 3: Router Pattern - Full Lifecycle with prepareBuffer()
   *
   * PRODUCTION CODE: Complete Router flow through NetworkClient
   * Pattern: retainedDuplicate → prepareBuffer → writeTo → release
   *
   * Flow:
   * 1. Allocate chunk buffer
   * 2. Pass retainedDuplicate to PutRequest
   * 3. Call prepareBuffer() which creates composite buffer
   * 4. Write to channel
   * 5. Release request (releases composite and all components)
   * 6. Release original chunk
   *
   * Expected: NO LEAK - All buffers properly released
   */
  @Test
  public void testPutRequest_RouterPattern_FullLifecycle() throws Exception {
    ByteBuf originalChunk = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    originalChunk.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest request = createPutRequest(originalChunk.retainedDuplicate());

    // Router prepares and sends
    request.prepareBuffer();  // Creates composite with blob, crcByteBuf, bufferToSend
    ByteBufferChannel channel = new ByteBufferChannel(ByteBuffer.allocate((int) request.sizeInBytes()));
    request.writeTo(channel);

    // NetworkClient cleanup
    request.release();

    // Router cleanup
    originalChunk.release();
  }

  /**
   * TEST 4: Direct Allocation Pattern
   *
   * PRODUCTION CODE: Test utilities and simple cases
   * Pattern: Allocate ByteBuf directly and pass to PutRequest
   *
   * Flow:
   * 1. Allocate ByteBuf (refCount=1)
   * 2. Pass to PutRequest constructor (ownership transfers, refCount still 1)
   * 3. PutRequest.release() releases ByteBuf (refCount=0)
   *
   * Expected: NO LEAK - Simple ownership transfer
   */
  @Test
  public void testPutRequest_DirectAllocation_OwnershipTransfer() throws Exception {
    ByteBuf blob = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    blob.writeBytes(generateRandomBytes(BLOB_SIZE));

    // Direct ownership transfer
    PutRequest request = createPutRequest(blob);
    request.prepareBuffer();

    ByteBufferChannel channel = new ByteBufferChannel(ByteBuffer.allocate((int) request.sizeInBytes()));
    request.writeTo(channel);

    // Release transfers ownership back and frees
    request.release();
  }

  /**
   * TEST 5: Composite ByteBuf Pattern (Router with Multiple Chunks)
   *
   * PRODUCTION CODE: Router creating composite blobs
   * Pattern: Create composite ByteBuf and pass to PutRequest
   *
   * Expected: NO LEAK - PutRequest handles composite buffers correctly
   */
  @Test
  public void testPutRequest_CompositeByteBuffer() throws Exception {
    // Create composite blob (simulates encryption producing multiple buffers)
    ByteBuf part1 = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
    part1.writeBytes(generateRandomBytes(512));

    ByteBuf part2 = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
    part2.writeBytes(generateRandomBytes(512));

    CompositeByteBuf composite = PooledByteBufAllocator.DEFAULT.compositeHeapBuffer(2);
    composite.addComponent(true, part1);
    composite.addComponent(true, part2);

    // Pass to PutRequest
    PutRequest request = createPutRequest(composite.retainedDuplicate());
    request.prepareBuffer();

    ByteBufferChannel channel = new ByteBufferChannel(ByteBuffer.allocate((int) request.sizeInBytes()));
    request.writeTo(channel);

    request.release();
    composite.release();
  }

  // ========================================
  // SECTION 2: ResponseInfo Production Patterns
  // Replicates Router and ReplicaThread usage
  // ========================================

  /**
   * TEST 6: Router Pattern - OperationController ResponseInfo Cleanup
   *
   * PRODUCTION CODE: OperationController.java:622-627
   * Pattern: sendAndPoll returns ResponseInfo list, forEach(ResponseInfo::release)
   *
   * Flow:
   * 1. NetworkClient.sendAndPoll() returns List<ResponseInfo>
   * 2. Each ResponseInfo owns a ByteBuf content
   * 3. Process responses
   * 4. responseInfoList.forEach(ResponseInfo::release)
   *
   * Expected: NO LEAK - All ResponseInfo properly released
   */
  @Test
  public void testResponseInfo_RouterPattern_ProperCleanup() throws Exception {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    // Simulate NetworkClient returning multiple responses
    for (int i = 0; i < 5; i++) {
      ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
      content.writeInt(200);  // Success status
      content.writeBytes(generateRandomBytes(100));

      responseInfoList.add(new ResponseInfo(null, null, content));
    }

    // Process responses (Router's onResponse logic)
    for (ResponseInfo responseInfo : responseInfoList) {
      ByteBuf content = responseInfo.content();
      // Process response data...
    }

    // Router cleanup pattern - OperationController.java:627
    responseInfoList.forEach(ResponseInfo::release);

    // All ByteBufs released - no leak
  }

  /**
   * TEST 7: ReplicaThread Pattern - ResponseInfo with NettyByteBufDataInputStream
   *
   * PRODUCTION CODE: ReplicaThread.java:473, 865-889
   * Pattern: Create NettyByteBufDataInputStream from ResponseInfo.content(), process, then release
   *
   * This is the CRITICAL test - does ReplicaThread properly release ResponseInfo?
   *
   * Flow:
   * 1. NetworkClient.sendAndPoll() returns ResponseInfo with content ByteBuf
   * 2. Create NettyByteBufDataInputStream(responseInfo.content())
   * 3. Deserialize response data
   * 4. Must call responseInfo.release() to clean up content
   *
   * Expected: Test both patterns to see which one production uses
   */
  @Test
  public void testResponseInfo_ReplicaThreadPattern_WithRelease() throws Exception {
    // NetworkClient returns ResponseInfo
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    content.writeInt(200);
    content.writeLong(System.currentTimeMillis());
    content.writeBytes(generateRandomBytes(100));

    ResponseInfo responseInfo = new ResponseInfo(null, null, content);

    // ReplicaThread pattern - create stream to read response
    NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());

    // Deserialize response
    int status = stream.readInt();
    long timestamp = stream.readLong();

    // CRITICAL: Must release ResponseInfo (which releases content ByteBuf)
    // NettyByteBufDataInputStream is non-owning wrapper
    responseInfo.release();

    // If this pattern is followed, no leak
  }

  /**
   * TEST 8: ReplicaThread Pattern - WITHOUT Release (Suspected Bug)
   *
   * PRODUCTION CODE: ReplicaThread.java:473, 865-889
   * Based on analysis, ReplicaThread may NOT be releasing ResponseInfo
   *
   * This test replicates what the code ACTUALLY does to detect the leak
   */
  @Test
  public void testResponseInfo_ReplicaThreadPattern_ActualCodePath() throws Exception {
    // Simulate what ReplicaThread.java:473 actually does
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    content.writeInt(200);
    content.writeLong(System.currentTimeMillis());
    content.writeBytes(generateRandomBytes(100));

    ResponseInfo responseInfo = new ResponseInfo(null, null, content);

    // Line 865-889: Create stream and deserialize
    NettyByteBufDataInputStream dis = new NettyByteBufDataInputStream(responseInfo.content());
    int status = dis.readInt();
    long timestamp = dis.readLong();

    // Process response...

    // Based on analysis: ReplicaThread does NOT call responseInfo.release() here
    // The ByteBuf leaks
    //
    // To validate: Run with tracer and check if this shows a leak
  }

  /**
   * TEST 9: ResponseInfo with Multiple Items - Exception Safety
   *
   * PRODUCTION CODE: OperationController handling batch responses
   * Pattern: Process list with try-finally to ensure all released
   *
   * Expected: NO LEAK - finally ensures cleanup even with exceptions
   */
  @Test
  public void testResponseInfo_MultipleItems_ExceptionSafety() throws Exception {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
      content.writeInt(i == 5 ? 500 : 200);  // 6th item has error
      responseInfoList.add(new ResponseInfo(null, null, content));
    }

    try {
      for (ResponseInfo responseInfo : responseInfoList) {
        NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
        int status = stream.readInt();

        if (status == 500) {
          // Simulate exception during processing
          // Exception would break the loop in naive implementation
        }
      }
    } finally {
      // Router pattern ensures all released even if exception
      responseInfoList.forEach(ResponseInfo::release);
    }

    // All ResponseInfo released despite exception
  }

  /**
   * TEST 10: ResponseInfo - Replace Pattern
   *
   * PRODUCTION CODE: ResponseInfo.replace() usage
   * Pattern: Create new ResponseInfo with different content
   *
   * Expected: Must release BOTH original and replaced ResponseInfo
   */
  @Test
  public void testResponseInfo_ReplacePattern_BothReleased() throws Exception {
    ByteBuf contentA = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    contentA.writeInt(100);
    ResponseInfo original = new ResponseInfo(null, null, contentA);

    ByteBuf contentB = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    contentB.writeInt(200);
    ResponseInfo replaced = original.replace(contentB);

    // Must release both
    original.release();   // Releases contentA
    replaced.release();   // Releases contentB
  }

  // ========================================
  // SECTION 3: NettyByteBufDataInputStream Patterns
  // Non-owning wrapper semantics
  // ========================================

  /**
   * TEST 11: NettyByteBufDataInputStream - Non-Owning Wrapper Pattern
   *
   * PRODUCTION CODE: Throughout codebase
   * Pattern: Stream wraps ByteBuf but doesn't own it, caller must release ByteBuf
   *
   * Expected: NO LEAK - Caller releases ByteBuf, stream is just a view
   */
  @Test
  public void testNettyByteBufDataInputStream_NonOwningWrapper() throws Exception {
    // Caller allocates and owns ByteBuf
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(128);
    buf.writeInt(12345);
    buf.writeLong(67890L);

    // Create stream (non-owning wrapper)
    NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(buf);

    // Read data
    int intVal = stream.readInt();
    long longVal = stream.readLong();

    // Close stream does nothing to ByteBuf
    stream.close();

    // Caller must release ByteBuf
    buf.release();
  }

  /**
   * TEST 12: NettyByteBufDataInputStream - Used with ResponseInfo
   *
   * PRODUCTION CODE: ResponseInfo content processing
   * Pattern: ResponseInfo owns content, stream wraps it, ResponseInfo.release() frees it
   *
   * Expected: NO LEAK - ResponseInfo owns ByteBuf, stream is wrapper
   */
  @Test
  public void testNettyByteBufDataInputStream_WithResponseInfo() throws Exception {
    ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
    content.writeInt(42);
    content.writeLong(123456L);
    content.writeBytes(generateRandomBytes(100));

    // ResponseInfo owns content
    ResponseInfo responseInfo = new ResponseInfo(null, null, content);

    // Stream wraps ResponseInfo.content() but doesn't own it
    NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());

    // Read data
    int statusCode = stream.readInt();
    long timestamp = stream.readLong();

    // ResponseInfo.release() releases content
    responseInfo.release();

    // Stream doesn't need to be released - it's just a wrapper
  }

  /**
   * TEST 13: NettyByteBufDataInputStream - Exception During Read
   *
   * PRODUCTION CODE: Deserialization with potential exceptions
   * Pattern: Exception during stream reading, must still release underlying ByteBuf
   *
   * Expected: NO LEAK - ByteBuf released in finally block
   */
  @Test
  public void testNettyByteBufDataInputStream_ExceptionDuringRead() throws Exception {
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(16);
    buf.writeInt(100);
    // Not enough data for readLong() - will throw exception

    try {
      NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(buf);
      int value = stream.readInt();
      long value2 = stream.readLong();  // Throws - not enough bytes
    } catch (Exception e) {
      // Expected exception
    } finally {
      // Caller must release ByteBuf even if exception
      buf.release();
    }
  }

  // ========================================
  // SECTION 4: Integration Tests
  // Multi-class production patterns
  // ========================================

  /**
   * TEST 14: Full Roundtrip - Router to Server
   *
   * PRODUCTION CODE: Complete request/response cycle
   * Pattern: Router creates PutRequest → Server wraps → Both release properly
   *
   * Expected: NO LEAK - Complete lifecycle with proper cleanup
   */
  @Test
  public void testIntegration_RouterToServer_FullRoundtrip() throws Exception {
    // Router side: Create chunk and PutRequest with retainedDuplicate
    ByteBuf routerChunk = PooledByteBufAllocator.DEFAULT.heapBuffer(BLOB_SIZE);
    routerChunk.writeBytes(generateRandomBytes(BLOB_SIZE));

    PutRequest routerRequest = createPutRequest(routerChunk.retainedDuplicate());
    routerRequest.prepareBuffer();

    // Server side: Wrap in InputStream PutRequest
    PutRequest serverRequest = new PutRequest(
        routerRequest.getCorrelationId(),
        routerRequest.getClientId(),
        routerRequest.getBlobId(),
        routerRequest.getBlobProperties(),
        routerRequest.getUsermetadata(),
        routerRequest.getBlobSize(),
        routerRequest.getBlobType(),
        routerRequest.getBlobEncryptionKey(),
        new ByteBufInputStream(routerRequest.getBlob()),
        null
    );

    // Process on server...

    // Cleanup: Router releases its request, server's wrapper has nothing to release
    routerRequest.release();  // Releases duplicate
    routerChunk.release();     // Releases original

    // serverRequest has blob=null (InputStream constructor), nothing to release
  }

  /**
   * TEST 15: ResponseInfo Processing - Router Pattern
   *
   * PRODUCTION CODE: OperationController complete flow
   * Pattern: sendAndPoll → onResponse → forEach release
   *
   * Expected: NO LEAK
   */
  @Test
  public void testIntegration_ResponseProcessing_CompleteRouterFlow() throws Exception {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    // Simulate sendAndPoll returning responses
    for (int i = 0; i < 3; i++) {
      ByteBuf content = PooledByteBufAllocator.DEFAULT.heapBuffer(256);
      content.writeInt(200);
      content.writeLong(System.currentTimeMillis());
      content.writeBytes(generateRandomBytes(100));

      responseInfoList.add(new ResponseInfo(null, null, content));
    }

    // Process all responses (onResponse)
    for (ResponseInfo responseInfo : responseInfoList) {
      NettyByteBufDataInputStream stream = new NettyByteBufDataInputStream(responseInfo.content());
      int status = stream.readInt();
      long timestamp = stream.readLong();
      // Handle response...
    }

    // Router cleanup - line 627 in OperationController
    responseInfoList.forEach(ResponseInfo::release);
  }

  // ========================================
  // Helper Methods
  // ========================================

  private PutRequest createPutRequest(ByteBuf materializedBlob) throws Exception {
    int correlationId = RANDOM.nextInt();
    String clientId = "test-client";

    BlobProperties properties = new BlobProperties(BLOB_SIZE, "service-id",
        "owner-id", "image/jpeg", false, 3600,
        System.currentTimeMillis(), (short) 1, (short) 1, false, null, null, null, null);

    ByteBuffer userMetadata = ByteBuffer.wrap(generateRandomBytes(USER_METADATA_SIZE));

    return new PutRequest(correlationId, clientId, blobId, properties, userMetadata,
        materializedBlob, BLOB_SIZE, BlobType.DataBlob, null);
  }

  private byte[] generateRandomBytes(int size) {
    byte[] bytes = new byte[size];
    RANDOM.nextBytes(bytes);
    return bytes;
  }
}
