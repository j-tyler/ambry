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
package com.github.ambry.server;

import com.github.ambry.clustermap.DataNodeId;
import com.github.ambry.clustermap.MockDataNodeId;
import com.github.ambry.clustermap.ReplicaId;
import com.github.ambry.messageformat.BlobData;
import com.github.ambry.messageformat.BlobType;
import com.github.ambry.messageformat.MessageFormatException;
import com.github.ambry.messageformat.MessageFormatRecord;
import com.github.ambry.network.NetworkClientErrorCode;
import com.github.ambry.network.Port;
import com.github.ambry.network.PortType;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.network.ResponseInfo;
import com.github.ambry.utils.ByteBufferInputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * ByteBuf Flow Exploration Tests
 *
 * Tests replicate ACTUAL production code patterns to explore ByteBuf flows with the tracer agent.
 * All tests based on real production usage, not hypothetical scenarios.
 *
 * Run with: ./gradlew :ambry-server:test --tests ByteBufLeakFlowExplorationTest -PwithByteBufTracking
 *
 * Classes explored:
 * - BlobData (ambry-messageformat)
 * - ResponseInfo (ambry-api/network)
 */
public class ByteBufLeakFlowExplorationTest {

  // ================================================================================================
  // SECTION 1: BlobData Tests - Production Patterns
  // ================================================================================================

  /**
   * TEST 1: MessageFormatRecord.deserializeBlob() Pattern
   * SOURCE: MessageFormatRecordTest.java:188-193
   * PATTERN: Standard deserialization and proper release
   */
  @Test
  public void testBlobData_DeserializeAndRelease_MessageFormatPattern() throws Exception {
    // Create serialized blob data (Version_V1 format)
    byte[] data = new byte[2000];
    for (int i = 0; i < 2000; i++) {
      data[i] = (byte) i;
    }
    ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + 2000 + 8);
    serialized.putShort(MessageFormatRecord.Blob_Version_V1);
    serialized.putLong(2000);
    serialized.put(data);

    CRC32 crc = new CRC32();
    crc.update(data);
    serialized.putLong(crc.getValue());
    serialized.flip();

    // Deserialize like production does
    BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));

    // Use the data
    byte[] verify = new byte[2000];
    blobData.content().readBytes(verify);

    // Proper release (MessageFormatRecordTest pattern)
    blobData.release();
  }

  /**
   * TEST 2: BlobIdTransformer Pattern - Manual ByteBuf Release
   * SOURCE: BlobIdTransformer.java:176-246
   * PATTERN: Get content(), manually release ByteBuf, create new BlobData
   */
  @Test
  public void testBlobData_BlobIdTransformer_ManualByteBufRelease() throws Exception {
    // Simulate metadata blob deserialization (line 176)
    byte[] metadataContent = new byte[100];
    ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + 100 + 8);
    serialized.putShort(MessageFormatRecord.Blob_Version_V1);
    serialized.putLong(100);
    serialized.put(metadataContent);
    CRC32 crc = new CRC32();
    crc.update(metadataContent);
    serialized.putLong(crc.getValue());
    serialized.flip();

    BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));

    // Line 177: Get content reference
    ByteBuf blobDataBytes = blobData.content();

    // Process metadata... (simulated)

    // Line 244: Manual ByteBuf release (EXACT production pattern)
    blobDataBytes.release();

    // Line 245-246: Create new BlobData with transformed content
    byte[] newContent = new byte[120];
    ByteBuf newBlobDataBytes = Unpooled.wrappedBuffer(newContent);
    BlobData newBlobData = new BlobData(BlobType.MetadataBlob, newContent.length, newBlobDataBytes);

    // Clean up new one
    newBlobData.release();
  }

  /**
   * TEST 3: MessageFormatSendTest Pattern - Proper Wrapper Release
   * SOURCE: MessageFormatSendTest.java:511
   * PATTERN: deserializedBlob.getBlobData().release()
   */
  @Test
  public void testBlobData_MessageFormatSend_WrapperRelease() throws Exception {
    byte[] data = new byte[500];
    ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + 500 + 8);
    serialized.putShort(MessageFormatRecord.Blob_Version_V1);
    serialized.putLong(500);
    serialized.put(data);
    CRC32 crc = new CRC32();
    crc.update(data);
    serialized.putLong(crc.getValue());
    serialized.flip();

    BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));

    // Use content
    byte[] readBlob = new byte[500];
    blobData.content().readBytes(readBlob);

    // MessageFormatSendTest pattern - release wrapper
    blobData.release();
  }

  /**
   * TEST 4: Corrupt Data Exception - No Finally Block (Real Bug Pattern)
   * SOURCE: MessageFormatRecordTest.java:195-204
   * PATTERN: Corruption detected, exception thrown, no cleanup
   */
  @Test
  public void testBlobData_CorruptData_NoCleanup_LEAK() {
    byte[] data = new byte[100];
    ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + 100 + 8);
    serialized.putShort(MessageFormatRecord.Blob_Version_V1);
    serialized.putLong(100);
    serialized.put(data);

    // Corrupt the CRC
    CRC32 crc = new CRC32();
    crc.update(data);
    serialized.putLong(crc.getValue() + 1); // Wrong CRC
    serialized.flip();

    try {
      // This allocates ByteBuf but throws before creating BlobData
      BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));
      blobData.release();
    } catch (MessageFormatException | IOException e) {
      // INTENTIONAL LEAK: ByteBuf allocated inside deserializeBlob but never released
      // This represents the real bug pattern when deserialization fails
    }
  }

  /**
   * TEST 5: Multiple BlobData With Proper Release
   * PATTERN: Loop processing like MessageFormatSendTest
   */
  @Test
  public void testBlobData_MultipleDeserialize_AllReleased() throws Exception {
    for (int i = 0; i < 3; i++) {
      byte[] data = new byte[100 + i * 10];
      ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + data.length + 8);
      serialized.putShort(MessageFormatRecord.Blob_Version_V1);
      serialized.putLong(data.length);
      serialized.put(data);
      CRC32 crc = new CRC32();
      crc.update(data);
      serialized.putLong(crc.getValue());
      serialized.flip();

      BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));
      byte[] readData = new byte[data.length];
      blobData.content().readBytes(readData);
      blobData.release();
    }
  }

  /**
   * TEST 6: Forgotten Release After Use (Real Bug Pattern)
   */
  @Test
  public void testBlobData_ForgottenRelease_LEAK() throws Exception {
    byte[] data = new byte[256];
    ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + 256 + 8);
    serialized.putShort(MessageFormatRecord.Blob_Version_V1);
    serialized.putLong(256);
    serialized.put(data);
    CRC32 crc = new CRC32();
    crc.update(data);
    serialized.putLong(crc.getValue());
    serialized.flip();

    BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));
    byte[] readData = new byte[256];
    blobData.content().readBytes(readData);
    // INTENTIONAL LEAK: Forgot to call release()
  }

  // ================================================================================================
  // SECTION 2: ResponseInfo Tests - Production Patterns
  // ================================================================================================

  /**
   * TEST 7: Router Pattern - List Processing with forEach Release
   * SOURCE: OperationController.java:627
   * PATTERN: responseInfoList.forEach(ResponseInfo::release)
   */
  @Test
  public void testResponseInfo_Router_ForEachRelease() {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    // Simulate network responses
    for (int i = 0; i < 5; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf content = Unpooled.buffer(256);
      content.writeBytes(("router response " + i).getBytes());
      responseInfoList.add(new ResponseInfo(requestInfo, null, content));
    }

    // Process responses...
    for (ResponseInfo responseInfo : responseInfoList) {
      ByteBuf content = responseInfo.content();
      if (content != null) {
        int size = content.readableBytes();
      }
    }

    // Router pattern: Proper cleanup (OperationController.java:627)
    responseInfoList.forEach(ResponseInfo::release);
  }

  /**
   * TEST 8: ReplicaThread Pattern - Timeout Responses
   * SOURCE: ReplicaThread.java:832, 903
   * PATTERN: Create timeout ResponseInfo, add to list, release all in forEach
   */
  @Test
  public void testResponseInfo_ReplicaThread_TimeoutAndRelease() {
    List<ResponseInfo> responseInfos = new ArrayList<>();
    List<ResponseInfo> responseInfosForTimedOutRequests = new ArrayList<>();

    // Line 832: Create timeout ResponseInfo (content is null for timeouts)
    RequestInfo timedOutRequest = createMockRequestInfo();
    responseInfosForTimedOutRequests.add(
        new ResponseInfo(timedOutRequest, NetworkClientErrorCode.TimeoutError, null));

    // Regular responses
    for (int i = 0; i < 3; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf content = Unpooled.buffer(128);
      content.writeBytes(("response " + i).getBytes());
      responseInfos.add(new ResponseInfo(requestInfo, null, content));
    }

    // Line 471: Add timeout responses to main list
    responseInfos.addAll(responseInfosForTimedOutRequests);

    // Process all responses (line 473: onResponses)
    for (ResponseInfo responseInfo : responseInfos) {
      ByteBuf content = responseInfo.content();
      // Content may be null for timeout responses
      if (content != null) {
        int size = content.readableBytes();
      }
    }

    // Line 903: Release all (including timeouts)
    responseInfos.forEach(ResponseInfo::release);
  }

  /**
   * TEST 9: Network Error ResponseInfo (Null Content)
   * SOURCE: ReplicaThread.java:832
   * PATTERN: ResponseInfo with error and null content
   */
  @Test
  public void testResponseInfo_NetworkError_NullContent() {
    RequestInfo requestInfo = createMockRequestInfo();

    // Network error - content is null
    ResponseInfo errorResponse = new ResponseInfo(requestInfo, NetworkClientErrorCode.NetworkError, null);

    // Process
    ByteBuf content = errorResponse.content();
    // Content is null for errors

    // Still need to release
    errorResponse.release();
  }

  /**
   * TEST 10: ResponseInfo List - Partial Release (Bug Pattern)
   */
  @Test
  public void testResponseInfo_PartialRelease_LEAK() {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    for (int i = 0; i < 4; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf content = Unpooled.buffer(128);
      content.writeBytes(("response " + i).getBytes());
      responseInfoList.add(new ResponseInfo(requestInfo, null, content));
    }

    // Process all
    for (ResponseInfo responseInfo : responseInfoList) {
      ByteBuf content = responseInfo.content();
    }

    // BUG: Only release first 3
    for (int i = 0; i < 3; i++) {
      responseInfoList.get(i).release();
    }
    // INTENTIONAL LEAK: 4th ResponseInfo never released
  }

  /**
   * TEST 11: ResponseInfo - Exception During Processing Without Finally
   */
  @Test
  public void testResponseInfo_ExceptionNoFinally_LEAK() {
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf content = Unpooled.buffer(256);
    content.writeBytes("response data".getBytes());
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, content);

    try {
      // Simulate processing that throws
      if (responseInfo.content() != null) {
        throw new RuntimeException("Simulated processing error");
      }
    } catch (Exception e) {
      // No finally block to clean up
    }
    // INTENTIONAL LEAK: responseInfo never released
  }

  /**
   * TEST 12: ResponseInfo - Proper Exception Handling With Finally
   * PATTERN: try-finally like OperationController
   */
  @Test
  public void testResponseInfo_ExceptionWithFinally_Proper() {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    try {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf content = Unpooled.buffer(256);
      content.writeBytes("response data".getBytes());
      ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, content);
      responseInfoList.add(responseInfo);

      // Processing that throws
      throw new RuntimeException("Simulated error");

    } catch (Exception e) {
      // Handle error
    } finally {
      // Proper cleanup regardless of exception
      responseInfoList.forEach(ResponseInfo::release);
    }
  }

  // ================================================================================================
  // SECTION 3: Integration Tests - Real Production Flows
  // ================================================================================================

  /**
   * TEST 13: End-to-End - Deserialize BlobData, Create ResponseInfo, Release Both
   * PATTERN: Combines MessageFormat deserialization with network response
   */
  @Test
  public void testIntegration_BlobDataToResponse_Complete() throws Exception {
    // Step 1: Deserialize blob (like server receiving blob)
    byte[] blobContent = new byte[512];
    ByteBuffer serialized = ByteBuffer.allocate(2 + 8 + 512 + 8);
    serialized.putShort(MessageFormatRecord.Blob_Version_V1);
    serialized.putLong(512);
    serialized.put(blobContent);
    CRC32 crc = new CRC32();
    crc.update(blobContent);
    serialized.putLong(crc.getValue());
    serialized.flip();

    BlobData blobData = MessageFormatRecord.deserializeBlob(new ByteBufferInputStream(serialized));
    byte[] readData = new byte[512];
    blobData.content().readBytes(readData);
    blobData.release();

    // Step 2: Create response (like server sending response)
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf responseContent = Unpooled.buffer(128);
    responseContent.writeBytes("success".getBytes());
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, responseContent);
    responseInfo.release();
  }

  /**
   * TEST 14: Replication Cycle - Multiple Responses
   * PATTERN: ReplicaThread processing batch of responses
   */
  @Test
  public void testIntegration_ReplicationCycle_BatchProcessing() {
    List<ResponseInfo> responseInfos = new ArrayList<>();

    // Simulate replication responses
    for (int i = 0; i < 10; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf content = Unpooled.buffer(256);
      content.writeBytes(("replication data " + i).getBytes());
      responseInfos.add(new ResponseInfo(requestInfo, null, content));
    }

    // Process batch
    for (ResponseInfo responseInfo : responseInfos) {
      ByteBuf content = responseInfo.content();
      if (content != null) {
        int size = content.readableBytes();
      }
    }

    // Release all (ReplicaThread pattern)
    responseInfos.forEach(ResponseInfo::release);
  }

  /**
   * TEST 15: Mixed Success and Error Responses
   * PATTERN: Real network client returns mix of successes and errors
   */
  @Test
  public void testIntegration_MixedResponses_AllReleased() {
    List<ResponseInfo> responseInfos = new ArrayList<>();

    // Some successful responses with content
    for (int i = 0; i < 3; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf content = Unpooled.buffer(128);
      content.writeBytes(("success " + i).getBytes());
      responseInfos.add(new ResponseInfo(requestInfo, null, content));
    }

    // Some error responses (null content)
    for (int i = 0; i < 2; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      responseInfos.add(new ResponseInfo(requestInfo, NetworkClientErrorCode.NetworkError, null));
    }

    // Process mixed responses
    for (ResponseInfo responseInfo : responseInfos) {
      if (responseInfo.getError() != null) {
        // Handle error
      } else {
        ByteBuf content = responseInfo.content();
        if (content != null) {
          int size = content.readableBytes();
        }
      }
    }

    // Release all
    responseInfos.forEach(ResponseInfo::release);
  }

  // ================================================================================================
  // Helper Methods
  // ================================================================================================

  private RequestInfo createMockRequestInfo() {
    ReplicaId mockReplicaId = Mockito.mock(ReplicaId.class);
    DataNodeId mockDataNode = new MockDataNodeId("localhost",
        new Port(6667, PortType.PLAINTEXT),
        new Port(6668, PortType.SSL),
        new Port(6669, PortType.HTTP2));
    Mockito.when(mockReplicaId.getDataNodeId()).thenReturn(mockDataNode);
    return new RequestInfo("localhost", new Port(6667, PortType.PLAINTEXT), null, mockReplicaId, null);
  }

  @Before
  public void setUp() {
    // Setup if needed
  }

  @After
  public void tearDown() {
    // Intentionally NOT cleaning up leaks - tracer needs to see them
  }
}
