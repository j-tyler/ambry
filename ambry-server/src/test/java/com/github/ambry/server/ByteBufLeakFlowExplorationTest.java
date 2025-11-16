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
import com.github.ambry.network.NetworkClientErrorCode;
import com.github.ambry.network.Port;
import com.github.ambry.network.PortType;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.network.ResponseInfo;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * ByteBuf Flow Exploration Tests
 *
 * PURPOSE: These tests are NOT designed to fail on leaks. Instead, they replicate production code
 * patterns to explore ByteBuf flow through the codebase using the ByteBuf tracer agent.
 *
 * USAGE: Run with: ./gradlew :ambry-server:test --tests ByteBufLeakFlowExplorationTest -PwithByteBufTracking
 *
 * IMPORTANT: Tests will PASS even if leaks are present. The tracer output shows the actual flows.
 *
 * Based on analysis from: BYTEBUF_LEAK_ANALYSIS_PART4.md
 *
 * Classes under investigation:
 * 1. BlobData (com.github.ambry.messageformat.BlobData)
 * 2. ResponseInfo (com.github.ambry.network.ResponseInfo)
 *
 * Note: SocketServerRequest tests are in ambry-network module (package-private class)
 */
public class ByteBufLeakFlowExplorationTest {

  // ================================================================================================
  // SECTION 1: BlobData Flow Tests (8 tests)
  // Based on LEAK-4.1 through LEAK-4.4
  // ================================================================================================

  /**
   * TEST 1: BlobData - Normal Flow (Safe Pattern)
   */
  @Test
  public void testBlobData_NormalFlow_ProperRelease() {
    ByteBuf buffer = Unpooled.buffer(1024);
    buffer.writeBytes("test blob data".getBytes());
    BlobData blobData = new BlobData(BlobType.DataBlob, buffer.readableBytes(), buffer);
    ByteBuf content = blobData.content();
    int dataSize = content.readableBytes();
    blobData.release();
  }

  /**
   * TEST 2: BlobData - BlobIdTransformer Pattern (Manual ByteBuf Release)
   */
  @Test
  public void testBlobData_BlobIdTransformerPattern_ManualByteBufRelease() {
    ByteBuf buffer = Unpooled.buffer(1024);
    buffer.writeBytes("metadata blob content".getBytes());
    BlobData blobData = new BlobData(BlobType.MetadataBlob, buffer.readableBytes(), buffer);
    ByteBuf blobDataBytes = blobData.content();
    int size = blobDataBytes.readableBytes();
    blobDataBytes.release();
    // INTENTIONAL: blobData wrapper never released - current production pattern
  }

  /**
   * TEST 3: BlobData - Future-Proof Pattern (Wrapper Release)
   */
  @Test
  public void testBlobData_FutureProofPattern_WrapperRelease() {
    BlobData blobData = null;
    try {
      ByteBuf buffer = Unpooled.buffer(1024);
      buffer.writeBytes("future proof pattern".getBytes());
      blobData = new BlobData(BlobType.DataBlob, buffer.readableBytes(), buffer);
      ByteBuf content = blobData.content();
      int size = content.readableBytes();
    } finally {
      if (blobData != null) {
        blobData.release();
      }
    }
  }

  /**
   * TEST 4: BlobData - replace() Both Released
   */
  @Test
  public void testBlobData_Replace_BothReleased() {
    ByteBuf buffer1 = Unpooled.buffer(512);
    buffer1.writeBytes("original content".getBytes());
    BlobData original = new BlobData(BlobType.DataBlob, buffer1.readableBytes(), buffer1);

    ByteBuf buffer2 = Unpooled.buffer(512);
    buffer2.writeBytes("replaced content".getBytes());
    BlobData replaced = original.replace(buffer2);

    original.release();
    replaced.release();
  }

  /**
   * TEST 5: BlobData - replace() Only New Released (LEAK)
   */
  @Test
  public void testBlobData_Replace_OnlyNewReleased_LEAK() {
    ByteBuf buffer1 = Unpooled.buffer(512);
    buffer1.writeBytes("original content - will leak".getBytes());
    BlobData original = new BlobData(BlobType.DataBlob, buffer1.readableBytes(), buffer1);

    ByteBuf buffer2 = Unpooled.buffer(512);
    buffer2.writeBytes("replaced content".getBytes());
    BlobData replaced = original.replace(buffer2);

    replaced.release();
    // INTENTIONAL LEAK: original never released
  }

  /**
   * TEST 6: BlobData - Metadata Transformation
   */
  @Test
  public void testBlobData_MetadataTransformation_BlobIdTransformerFlow() {
    ByteBuf originalMetadata = Unpooled.buffer(256);
    originalMetadata.writeBytes("original metadata".getBytes());
    BlobData originalBlobData = new BlobData(BlobType.MetadataBlob, originalMetadata.readableBytes(),
        originalMetadata);

    ByteBuf blobDataBytes = originalBlobData.content();
    byte[] newMetadataContent = "transformed metadata".getBytes();
    blobDataBytes.release();

    ByteBuf newBlobDataBytes = Unpooled.wrappedBuffer(newMetadataContent);
    BlobData newBlobData = new BlobData(BlobType.MetadataBlob, newMetadataContent.length, newBlobDataBytes);
    newBlobData.release();
  }

  /**
   * TEST 7: BlobData - Deserialization Exception No Cleanup (LEAK)
   */
  @Test
  public void testBlobData_DeserializationException_NoCleanup_LEAK() {
    ByteBuf byteBuf = Unpooled.buffer(128);
    byteBuf.writeBytes("some data".getBytes());

    try {
      boolean validationFails = true;
      if (validationFails) {
        throw new RuntimeException("CRC mismatch");
      }
      BlobData blobData = new BlobData(BlobType.DataBlob, byteBuf.readableBytes(), byteBuf);
      blobData.release();
    } catch (Exception e) {
      // INTENTIONAL LEAK: byteBuf never released
    }
  }

  /**
   * TEST 8: BlobData - Deserialization Exception With Cleanup
   */
  @Test
  public void testBlobData_DeserializationException_WithCleanup() {
    ByteBuf byteBuf = null;
    try {
      byteBuf = Unpooled.buffer(128);
      byteBuf.writeBytes("some data".getBytes());
      boolean validationFails = true;
      if (validationFails) {
        throw new RuntimeException("CRC mismatch");
      }
      BlobData blobData = new BlobData(BlobType.DataBlob, byteBuf.readableBytes(), byteBuf);
      byteBuf = null;
      blobData.release();
    } catch (Exception e) {
      // Exception caught
    } finally {
      if (byteBuf != null) {
        byteBuf.release();
      }
    }
  }

  /**
   * TEST 9: BlobData - Caller Never Releases (LEAK)
   */
  @Test
  public void testBlobData_CallerNeverReleases_LEAK() {
    ByteBuf buffer = Unpooled.buffer(1024);
    buffer.writeBytes("forgotten blob data".getBytes());
    BlobData blobData = new BlobData(BlobType.DataBlob, buffer.readableBytes(), buffer);
    ByteBuf content = blobData.content();
    int dataSize = content.readableBytes();
    // INTENTIONAL LEAK: Never release
  }

  // ================================================================================================
  // SECTION 2: ResponseInfo Flow Tests (8 tests)
  // Based on LEAK-3.1, LEAK-3.2, LEAK-3.3
  // ================================================================================================

  /**
   * TEST 10: ResponseInfo - Router Pattern (Proper Release)
   */
  @Test
  public void testResponseInfo_RouterPattern_ProperRelease() {
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf responseContent = Unpooled.buffer(512);
    responseContent.writeBytes("response data".getBytes());
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, NetworkClientErrorCode.NetworkError, responseContent);
    ByteBuf content = responseInfo.content();
    int size = content != null ? content.readableBytes() : 0;
    responseInfo.release();
  }

  /**
   * TEST 11: ResponseInfo - ReplicaThread Pattern No Release (HIGH SEVERITY LEAK)
   */
  @Test
  public void testResponseInfo_ReplicaThreadPattern_NoRelease_LEAK() {
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf responseContent = Unpooled.buffer(512);
    responseContent.writeBytes("replication response data".getBytes());
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, responseContent);
    ByteBuf content = responseInfo.content();
    // INTENTIONAL LEAK: ReplicaThread never calls responseInfo.release()
  }

  /**
   * TEST 12: ResponseInfo - List Processing All Released
   */
  @Test
  public void testResponseInfo_ListProcessing_AllReleased() {
    List<ResponseInfo> responseInfoList = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf content = Unpooled.buffer(256);
      content.writeBytes(("response " + i).getBytes());
      responseInfoList.add(new ResponseInfo(requestInfo, null, content));
    }

    for (ResponseInfo responseInfo : responseInfoList) {
      ByteBuf content = responseInfo.content();
    }

    responseInfoList.forEach(ResponseInfo::release);
  }

  /**
   * TEST 13: ResponseInfo - List Processing Partial Release (LEAK)
   */
  @Test
  public void testResponseInfo_ListProcessing_PartialRelease_LEAK() {
    List<ResponseInfo> responseInfoList = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf content = Unpooled.buffer(256);
      content.writeBytes(("response " + i).getBytes());
      responseInfoList.add(new ResponseInfo(requestInfo, null, content));
    }

    responseInfoList.get(0).release();
    responseInfoList.get(1).release();
    // INTENTIONAL LEAK: Third one never released
  }

  /**
   * TEST 14: ResponseInfo - Exception No Finally (LEAK)
   */
  @Test
  public void testResponseInfo_ExceptionDuringProcessing_NoCleanup_LEAK() {
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf content = Unpooled.buffer(256);
    content.writeBytes("response data".getBytes());
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, content);

    try {
      throw new RuntimeException("Processing failed");
    } catch (Exception e) {
      // INTENTIONAL LEAK: No cleanup
    }
  }

  /**
   * TEST 15: ResponseInfo - Exception With Finally
   */
  @Test
  public void testResponseInfo_ExceptionDuringProcessing_WithFinally() {
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf content = Unpooled.buffer(256);
    content.writeBytes("response data".getBytes());
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, content);

    try {
      throw new RuntimeException("Processing failed");
    } catch (Exception e) {
      // Exception caught
    } finally {
      responseInfo.release();
    }
  }

  /**
   * TEST 16: ResponseInfo - replace() Both Released
   */
  @Test
  public void testResponseInfo_Replace_BothReleased() {
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf buf1 = Unpooled.buffer(256);
    buf1.writeBytes("original response".getBytes());
    ResponseInfo original = new ResponseInfo(requestInfo, null, buf1);

    ByteBuf buf2 = Unpooled.buffer(256);
    buf2.writeBytes("replaced response".getBytes());
    ResponseInfo replaced = original.replace(buf2);

    original.release();
    replaced.release();
  }

  /**
   * TEST 17: ResponseInfo - replace() Only New Released (LEAK)
   */
  @Test
  public void testResponseInfo_Replace_OnlyNewReleased_LEAK() {
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf buf1 = Unpooled.buffer(256);
    buf1.writeBytes("original response - will leak".getBytes());
    ResponseInfo original = new ResponseInfo(requestInfo, null, buf1);

    ByteBuf buf2 = Unpooled.buffer(256);
    buf2.writeBytes("replaced response".getBytes());
    ResponseInfo replaced = original.replace(buf2);

    replaced.release();
    // INTENTIONAL LEAK: original never released
  }

  // ================================================================================================
  // SECTION 3: Integration Tests (2 tests)
  // ================================================================================================

  /**
   * TEST 18: Integration - BlobData to ResponseInfo All Released
   */
  @Test
  public void testIntegration_BlobDataToResponse_AllReleased() {
    ByteBuf blobBuffer = Unpooled.buffer(512);
    blobBuffer.writeBytes("blob content".getBytes());
    BlobData blobData = new BlobData(BlobType.DataBlob, blobBuffer.readableBytes(), blobBuffer);
    ByteBuf blobContent = blobData.content();
    int blobSize = blobContent.readableBytes();
    blobData.release();

    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf responseBuffer = Unpooled.buffer(256);
    responseBuffer.writeBytes("success response".getBytes());
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, responseBuffer);
    responseInfo.release();
  }

  /**
   * TEST 19: Integration - Request Pipeline Partial Cleanup (LEAK)
   */
  @Test
  public void testIntegration_RequestPipeline_PartialCleanup_LEAK() {
    List<BlobData> blobDataList = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ByteBuf buffer = Unpooled.buffer(256);
      buffer.writeBytes(("blob " + i).getBytes());
      blobDataList.add(new BlobData(BlobType.DataBlob, buffer.readableBytes(), buffer));
    }

    blobDataList.get(0).release();
    blobDataList.get(1).release();
    // LEAK: Third BlobData never released

    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf responseBuffer = Unpooled.buffer(256);
    responseBuffer.writeBytes("response".getBytes());
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, responseBuffer);
    // LEAK: ResponseInfo never released
  }

  /**
   * TEST 20: Integration - Replication Cycle No Release (HIGH SEVERITY LEAK)
   */
  @Test
  public void testIntegration_ReplicationCycle_NoRelease_LEAK() {
    List<ResponseInfo> replicationResponses = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf responseContent = Unpooled.buffer(512);
      responseContent.writeBytes(("replication response " + i).getBytes());
      replicationResponses.add(new ResponseInfo(requestInfo, null, responseContent));
    }

    for (ResponseInfo responseInfo : replicationResponses) {
      ByteBuf content = responseInfo.content();
    }
    // INTENTIONAL LEAK: ReplicaThread never releases ResponseInfo
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
