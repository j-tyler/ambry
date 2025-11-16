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
import com.github.ambry.clustermap.Port;
import com.github.ambry.clustermap.PortType;
import com.github.ambry.clustermap.ReplicaId;
import com.github.ambry.messageformat.BlobData;
import com.github.ambry.messageformat.BlobType;
import com.github.ambry.network.NetworkClientErrorCode;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.network.ResponseInfo;
import com.github.ambry.network.SocketRequestResponseChannel;
import com.github.ambry.utils.NettyByteBufDataInputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
 * 3. SocketServerRequest (com.github.ambry.network.SocketServerRequest)
 */
public class ByteBufLeakFlowExplorationTest {

  // ================================================================================================
  // SECTION 1: BlobData Flow Tests
  // Based on LEAK-4.1 through LEAK-4.4
  // ================================================================================================

  /**
   * TEST: BlobData - Normal Flow (Safe Pattern)
   *
   * FLOW: allocate -> BlobData.<init> -> content() -> release()
   *
   * OWNERSHIP: BlobData takes ownership in constructor, caller releases BlobData wrapper
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── BlobData.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (proper pattern)
   */
  @Test
  public void testBlobData_NormalFlow_ProperRelease() {
    // Allocate ByteBuf with some data
    ByteBuf buffer = Unpooled.buffer(1024);
    buffer.writeBytes("test blob data".getBytes());

    // Create BlobData (takes ownership)
    BlobData blobData = new BlobData(BlobType.DataBlob, buffer.readableBytes(), buffer);

    // Use the content
    ByteBuf content = blobData.content();
    int dataSize = content.readableBytes();

    // Proper release via wrapper
    blobData.release();
  }

  /**
   * TEST: BlobData - BlobIdTransformer Pattern (Current Production - Manual ByteBuf Release)
   *
   * FLOW: allocate -> BlobData.<init> -> content() -> manual ByteBuf.release()
   *
   * OWNERSHIP: BlobData takes ownership, but caller manually releases the underlying ByteBuf
   * instead of releasing the BlobData wrapper
   *
   * SOURCE: BlobIdTransformer.java lines 176-247 (LEAK-4.1)
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── BlobData.<init> [ref=1]
   *       └── content [ref=1]
   *           └── release [ref=0] ✅ CLEAN (for now, with ByteBuffer blocking channel)
   *
   * LEAK EXPECTATION: Currently SAFE (blocking channel uses ByteBuffer)
   *                   Will LEAK when Netty adopted (BlobData wrapper overhead not cleaned)
   *
   * REFERENCES:
   * - BlobIdTransformer.java:244 (manual release)
   * - Comment at lines 256-258: "when netty Bytebuf is adopted... remember to release this ByteBuf"
   */
  @Test
  public void testBlobData_BlobIdTransformerPattern_ManualByteBufRelease() {
    // Replicate BlobIdTransformer pattern (lines 176-247)
    ByteBuf buffer = Unpooled.buffer(1024);
    buffer.writeBytes("metadata blob content".getBytes());

    // Create BlobData
    BlobData blobData = new BlobData(BlobType.MetadataBlob, buffer.readableBytes(), buffer);

    // Get content reference (like BlobIdTransformer does at line 177)
    ByteBuf blobDataBytes = blobData.content();

    // Process the data...
    int size = blobDataBytes.readableBytes();

    // Manual ByteBuf release (like line 244) instead of blobData.release()
    blobDataBytes.release();

    // Note: blobData wrapper never released - this is the current pattern
    // Works now because blocking channel uses ByteBuffer, but will leak when Netty adopted
  }

  /**
   * TEST: BlobData - Future-Proof Pattern (Release via Wrapper)
   *
   * FLOW: allocate -> BlobData.<init> -> use -> try-finally -> BlobData.release()
   *
   * OWNERSHIP: BlobData takes ownership, caller releases via wrapper in finally block
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── BlobData.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (recommended pattern for Netty migration)
   */
  @Test
  public void testBlobData_FutureProofPattern_WrapperRelease() {
    BlobData blobData = null;
    try {
      // Allocate and create BlobData
      ByteBuf buffer = Unpooled.buffer(1024);
      buffer.writeBytes("future proof pattern".getBytes());
      blobData = new BlobData(BlobType.DataBlob, buffer.readableBytes(), buffer);

      // Use the BlobData
      ByteBuf content = blobData.content();
      int size = content.readableBytes();

    } finally {
      // Release via wrapper in finally block (recommended pattern)
      if (blobData != null) {
        blobData.release();
      }
    }
  }

  /**
   * TEST: BlobData - replace() Method (Proper Release of Both)
   *
   * FLOW: allocate buffer1 -> BlobData(buffer1) -> allocate buffer2 -> replace(buffer2)
   *       -> release original -> release replaced
   *
   * OWNERSHIP: original.replace(buf2) creates NEW BlobData with buf2
   *            Original still owns buffer1, replaced owns buffer2
   *            Caller must release BOTH
   *
   * SOURCE: BlobData.java lines 80-83, LEAK-4.2
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate (buffer1) [count=1]
   *   └── BlobData.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   * - ROOT: allocate (buffer2) [count=1]
   *   └── BlobData.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (both released)
   */
  @Test
  public void testBlobData_Replace_BothReleased() {
    // Create original BlobData
    ByteBuf buffer1 = Unpooled.buffer(512);
    buffer1.writeBytes("original content".getBytes());
    BlobData original = new BlobData(BlobType.DataBlob, buffer1.readableBytes(), buffer1);

    // Replace with new content
    ByteBuf buffer2 = Unpooled.buffer(512);
    buffer2.writeBytes("replaced content".getBytes());
    BlobData replaced = original.replace(buffer2);

    // Proper pattern: release BOTH original and replaced
    original.release();  // Must release original
    replaced.release();  // Must release replaced
  }

  /**
   * TEST: BlobData - replace() Method (Leak - Only New Released)
   *
   * FLOW: allocate buffer1 -> BlobData(buffer1) -> allocate buffer2 -> replace(buffer2)
   *       -> release ONLY replaced -> buffer1 LEAKED
   *
   * OWNERSHIP: Common mistake - developer releases only the new BlobData, forgets original
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate (buffer1) [count=1]
   *   └── BlobData.<init> [ref=1] ⚠️ LEAK (never released)
   * - ROOT: allocate (buffer2) [count=1]
   *   └── BlobData.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: YES - buffer1 leaked (original BlobData not released)
   */
  @Test
  public void testBlobData_Replace_OnlyNewReleased_LEAK() {
    // Create original BlobData
    ByteBuf buffer1 = Unpooled.buffer(512);
    buffer1.writeBytes("original content - will leak".getBytes());
    BlobData original = new BlobData(BlobType.DataBlob, buffer1.readableBytes(), buffer1);

    // Replace with new content
    ByteBuf buffer2 = Unpooled.buffer(512);
    buffer2.writeBytes("replaced content".getBytes());
    BlobData replaced = original.replace(buffer2);

    // Common mistake: only release the new one, forget original
    replaced.release();

    // INTENTIONAL LEAK: original never released (simulating the bug)
  }

  /**
   * TEST: BlobData - Metadata Blob Transformation (BlobIdTransformer Pattern)
   *
   * FLOW: Deserialize metadata blob -> release old ByteBuf -> create new wrapped buffer
   *       -> create new BlobData -> return in InputStream
   *
   * OWNERSHIP: Complex flow from BlobIdTransformer.newMessage() lines 230-247
   *
   * SOURCE: BlobIdTransformer.java lines 230-247
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate (original metadata) [count=1]
   *   └── BlobData.<init> [ref=1]
   *       └── content [ref=1]
   *           └── release [ref=0] ✅ CLEAN
   * - ROOT: wrappedBuffer (new metadata) [count=1]
   *   └── BlobData.<init> [ref=1]
   *       └── NettyByteBufDataInputStream.<init> [ref=1]
   *           └── (used by caller)
   *
   * LEAK EXPECTATION: Depends on caller - if InputStream closed, clean. If not, leaks.
   */
  @Test
  public void testBlobData_MetadataTransformation_BlobIdTransformerFlow() {
    // Step 1: Deserialize original metadata blob
    ByteBuf originalMetadata = Unpooled.buffer(256);
    originalMetadata.writeBytes("original metadata".getBytes());
    BlobData originalBlobData = new BlobData(BlobType.MetadataBlob, originalMetadata.readableBytes(), originalMetadata);

    // Step 2: Extract content
    ByteBuf blobDataBytes = originalBlobData.content();

    // Step 3: Process and transform metadata (simulated)
    byte[] newMetadataContent = "transformed metadata".getBytes();

    // Step 4: Release old ByteBuf (like line 244)
    blobDataBytes.release();

    // Step 5: Wrap new content (like line 245)
    ByteBuf newBlobDataBytes = Unpooled.wrappedBuffer(newMetadataContent);

    // Step 6: Create new BlobData
    BlobData newBlobData = new BlobData(BlobType.MetadataBlob, newMetadataContent.length, newBlobDataBytes);

    // Step 7: Would be passed to ByteBufInputStream with autoRelease=true in production
    // For this test, we manually release
    newBlobData.release();
  }

  /**
   * TEST: BlobData - Exception During Deserialization (No Cleanup)
   *
   * FLOW: Start deserializing -> allocate ByteBuf -> CRC check fails -> exception thrown
   *       -> ByteBuf LEAKED
   *
   * OWNERSHIP: ByteBuf allocated but BlobData never created (exception before constructor)
   *
   * SOURCE: MessageFormatRecord.deserializeBlob() lines 1650-1696, LEAK-4.3
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── (no further calls) ⚠️ LEAK (exception thrown, buffer never released)
   *
   * LEAK EXPECTATION: YES - ByteBuf allocated but never wrapped in BlobData or released
   */
  @Test
  public void testBlobData_DeserializationException_NoCleanup_LEAK() {
    // Simulate deserialization WITHOUT try-finally
    ByteBuf byteBuf = Unpooled.buffer(128);
    byteBuf.writeBytes("some data".getBytes());

    try {
      // Simulate CRC check or other validation
      // In real code, this would be: if (crc.getValue() != streamCrc) throw exception
      boolean validationFails = true;
      if (validationFails) {
        throw new RuntimeException("CRC mismatch - simulating MessageFormatException");
      }

      // Would create BlobData here if validation passed
      BlobData blobData = new BlobData(BlobType.DataBlob, byteBuf.readableBytes(), byteBuf);
      blobData.release();

    } catch (Exception e) {
      // No cleanup in catch block
      // INTENTIONAL LEAK: byteBuf never released (simulating the bug)
    }
  }

  /**
   * TEST: BlobData - Exception During Deserialization (With Cleanup)
   *
   * FLOW: Start deserializing -> allocate ByteBuf -> CRC check fails -> try-finally
   *       -> release in finally block
   *
   * OWNERSHIP: Proper exception handling with cleanup
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── release (in finally) [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (proper cleanup in finally block)
   */
  @Test
  public void testBlobData_DeserializationException_WithCleanup() {
    ByteBuf byteBuf = null;
    try {
      // Allocate buffer
      byteBuf = Unpooled.buffer(128);
      byteBuf.writeBytes("some data".getBytes());

      // Simulate CRC check failure
      boolean validationFails = true;
      if (validationFails) {
        throw new RuntimeException("CRC mismatch - simulating MessageFormatException");
      }

      // Would create BlobData here if validation passed
      BlobData blobData = new BlobData(BlobType.DataBlob, byteBuf.readableBytes(), byteBuf);
      byteBuf = null;  // Ownership transferred
      blobData.release();

    } catch (Exception e) {
      // Exception caught
    } finally {
      // Proper cleanup: release if not transferred to BlobData
      if (byteBuf != null) {
        byteBuf.release();
      }
    }
  }

  /**
   * TEST: BlobData - Caller Never Releases (Forgotten Release)
   *
   * FLOW: allocate -> BlobData.<init> -> content() -> use content -> forget to release anything
   *
   * OWNERSHIP: Developer error - created BlobData but never released wrapper or ByteBuf
   *
   * SOURCE: LEAK-4.4
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── BlobData.<init> [ref=1]
   *       └── content [ref=1] ⚠️ LEAK (never released)
   *
   * LEAK EXPECTATION: YES - both BlobData wrapper and ByteBuf leaked
   */
  @Test
  public void testBlobData_CallerNeverReleases_LEAK() {
    // Allocate and create BlobData
    ByteBuf buffer = Unpooled.buffer(1024);
    buffer.writeBytes("forgotten blob data".getBytes());
    BlobData blobData = new BlobData(BlobType.DataBlob, buffer.readableBytes(), buffer);

    // Use the content
    ByteBuf content = blobData.content();
    int dataSize = content.readableBytes();

    // INTENTIONAL LEAK: Forget to call blobData.release() or content.release()
  }

  // ================================================================================================
  // SECTION 2: ResponseInfo Flow Tests
  // Based on LEAK-3.1, LEAK-3.2, LEAK-3.3 (from earlier parts of analysis)
  // ================================================================================================

  /**
   * TEST: ResponseInfo - Normal Flow (Router Pattern - Proper Release)
   *
   * FLOW: allocate -> ResponseInfo.<init> -> process -> release
   *
   * OWNERSHIP: ResponseInfo takes ownership, caller (Router) properly releases
   *
   * SOURCE: Router properly releases ResponseInfo (analysis Part 3)
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── ResponseInfo.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (Router pattern is correct)
   */
  @Test
  public void testResponseInfo_RouterPattern_ProperRelease() {
    // Create a mock RequestInfo
    RequestInfo requestInfo = createMockRequestInfo();

    // Allocate response content
    ByteBuf responseContent = Unpooled.buffer(512);
    responseContent.writeBytes("response data".getBytes());

    // Create ResponseInfo (like Router does)
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, NetworkClientErrorCode.NetworkError, responseContent);

    // Process the response
    ByteBuf content = responseInfo.content();
    int size = content != null ? content.readableBytes() : 0;

    // Proper release (Router pattern)
    responseInfo.release();
  }

  /**
   * TEST: ResponseInfo - ReplicaThread Pattern (NO Release - Active Production Leak!)
   *
   * FLOW: allocate -> ResponseInfo.<init> -> process in ReplicaThread -> NEVER RELEASED
   *
   * OWNERSHIP: ResponseInfo created but ReplicaThread never calls release()
   *
   * SOURCE: ReplicaThread.java - LEAK-3.1 (HIGH SEVERITY - ACTIVE PRODUCTION LEAK)
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── ResponseInfo.<init> [ref=1] ⚠️ LEAK (ReplicaThread never releases)
   *
   * LEAK EXPECTATION: YES - This is the HIGH priority production leak identified in analysis
   *
   * PRODUCTION IMPACT: Happens on every replication cycle
   * FIX NEEDED: Add .forEach(ResponseInfo::release) in ReplicaThread
   */
  @Test
  public void testResponseInfo_ReplicaThreadPattern_NoRelease_LEAK() {
    // Simulate ReplicaThread creating ResponseInfo
    RequestInfo requestInfo = createMockRequestInfo();

    ByteBuf responseContent = Unpooled.buffer(512);
    responseContent.writeBytes("replication response data".getBytes());

    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, responseContent);

    // ReplicaThread processes the response
    ByteBuf content = responseInfo.content();
    // ... process content ...

    // INTENTIONAL LEAK: ReplicaThread never calls responseInfo.release()
    // This replicates the HIGH SEVERITY production bug
  }

  /**
   * TEST: ResponseInfo - Multiple Responses (List Processing - Router)
   *
   * FLOW: Create list of ResponseInfo -> process each -> release all in forEach
   *
   * OWNERSHIP: Router pattern processes list and releases all
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=3]
   *   └── ResponseInfo.<init> [ref=1, count=3]
   *       └── release [ref=0, count=3] ✅ CLEAN (all 3 released)
   *
   * LEAK EXPECTATION: NO LEAK (proper batch processing)
   */
  @Test
  public void testResponseInfo_ListProcessing_AllReleased() {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    // Create multiple ResponseInfo
    for (int i = 0; i < 3; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf content = Unpooled.buffer(256);
      content.writeBytes(("response " + i).getBytes());
      responseInfoList.add(new ResponseInfo(requestInfo, null, content));
    }

    // Process each response
    for (ResponseInfo responseInfo : responseInfoList) {
      ByteBuf content = responseInfo.content();
      // ... process ...
    }

    // Proper cleanup: release all (Router pattern)
    responseInfoList.forEach(ResponseInfo::release);
  }

  /**
   * TEST: ResponseInfo - Multiple Responses (Partial Release - ReplicaThread)
   *
   * FLOW: Create list of ResponseInfo -> process each -> release SOME, forget others
   *
   * OWNERSHIP: Inconsistent release pattern (some released, some not)
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=3]
   *   ├── ResponseInfo.<init> [ref=1] → release [ref=0] ✅ CLEAN
   *   ├── ResponseInfo.<init> [ref=1] → release [ref=0] ✅ CLEAN
   *   └── ResponseInfo.<init> [ref=1] ⚠️ LEAK (not released)
   *
   * LEAK EXPECTATION: PARTIAL - Some leaked, some clean
   */
  @Test
  public void testResponseInfo_ListProcessing_PartialRelease_LEAK() {
    List<ResponseInfo> responseInfoList = new ArrayList<>();

    // Create multiple ResponseInfo
    for (int i = 0; i < 3; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf content = Unpooled.buffer(256);
      content.writeBytes(("response " + i).getBytes());
      responseInfoList.add(new ResponseInfo(requestInfo, null, content));
    }

    // Release only first two
    responseInfoList.get(0).release();
    responseInfoList.get(1).release();

    // INTENTIONAL LEAK: Third one never released
  }

  /**
   * TEST: ResponseInfo - Exception During Processing (No Finally Block)
   *
   * FLOW: allocate -> ResponseInfo.<init> -> processing throws exception -> no cleanup
   *
   * OWNERSHIP: Exception path doesn't release ResponseInfo
   *
   * SOURCE: LEAK-3.2
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── ResponseInfo.<init> [ref=1] ⚠️ LEAK (exception thrown, no finally)
   *
   * LEAK EXPECTATION: YES - exception prevents release
   */
  @Test
  public void testResponseInfo_ExceptionDuringProcessing_NoCleanup_LEAK() {
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf content = Unpooled.buffer(256);
    content.writeBytes("response data".getBytes());

    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, content);

    try {
      // Simulate processing that throws exception
      throw new RuntimeException("Processing failed");
    } catch (Exception e) {
      // No cleanup in catch
    }

    // INTENTIONAL LEAK: responseInfo never released due to exception
  }

  /**
   * TEST: ResponseInfo - Exception During Processing (With Finally Block)
   *
   * FLOW: allocate -> ResponseInfo.<init> -> exception -> finally -> release
   *
   * OWNERSHIP: Proper exception handling with cleanup
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── ResponseInfo.<init> [ref=1]
   *       └── release (in finally) [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (finally block ensures release)
   */
  @Test
  public void testResponseInfo_ExceptionDuringProcessing_WithFinally() {
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf content = Unpooled.buffer(256);
    content.writeBytes("response data".getBytes());

    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, content);

    try {
      // Simulate processing that throws exception
      throw new RuntimeException("Processing failed");
    } catch (Exception e) {
      // Exception caught
    } finally {
      // Proper cleanup
      responseInfo.release();
    }
  }

  /**
   * TEST: ResponseInfo - replace() Method (Proper Release of Both)
   *
   * FLOW: allocate buf1 -> ResponseInfo(buf1) -> allocate buf2 -> replace(buf2)
   *       -> release original -> release new
   *
   * OWNERSHIP: Same as BlobData - both must be released
   *
   * SOURCE: LEAK-3.3
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate (buf1) [count=1]
   *   └── ResponseInfo.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   * - ROOT: allocate (buf2) [count=1]
   *   └── ResponseInfo.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (both released)
   */
  @Test
  public void testResponseInfo_Replace_BothReleased() {
    RequestInfo requestInfo = createMockRequestInfo();

    // Create original ResponseInfo
    ByteBuf buf1 = Unpooled.buffer(256);
    buf1.writeBytes("original response".getBytes());
    ResponseInfo original = new ResponseInfo(requestInfo, null, buf1);

    // Replace with new content
    ByteBuf buf2 = Unpooled.buffer(256);
    buf2.writeBytes("replaced response".getBytes());
    ResponseInfo replaced = original.replace(buf2);

    // Proper pattern: release both
    original.release();
    replaced.release();
  }

  /**
   * TEST: ResponseInfo - replace() Method (Only New Released - Leak)
   *
   * FLOW: allocate buf1 -> ResponseInfo(buf1) -> replace(buf2) -> release ONLY new
   *
   * OWNERSHIP: Common mistake - original not released
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate (buf1) [count=1]
   *   └── ResponseInfo.<init> [ref=1] ⚠️ LEAK (original not released)
   * - ROOT: allocate (buf2) [count=1]
   *   └── ResponseInfo.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: YES - original leaked
   */
  @Test
  public void testResponseInfo_Replace_OnlyNewReleased_LEAK() {
    RequestInfo requestInfo = createMockRequestInfo();

    // Create original ResponseInfo
    ByteBuf buf1 = Unpooled.buffer(256);
    buf1.writeBytes("original response - will leak".getBytes());
    ResponseInfo original = new ResponseInfo(requestInfo, null, buf1);

    // Replace with new content
    ByteBuf buf2 = Unpooled.buffer(256);
    buf2.writeBytes("replaced response".getBytes());
    ResponseInfo replaced = original.replace(buf2);

    // Common mistake: only release new
    replaced.release();

    // INTENTIONAL LEAK: original never released
  }

  // ================================================================================================
  // SECTION 3: SocketServerRequest Flow Tests
  // Based on LEAK-5.1 through LEAK-5.6
  // ================================================================================================

  /**
   * TEST: SocketServerRequest - Normal RequestHandler Pattern (Safe - Finally Block)
   *
   * FLOW: allocate -> SocketServerRequest.<init> -> sendRequest -> RequestHandler.receiveRequest
   *       -> process -> finally -> release
   *
   * OWNERSHIP: RequestHandler has proper finally block (lines 56-61)
   *
   * SOURCE: RequestHandler.java lines 40-62, SAFE-5.1
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── SocketServerRequest.<init> [ref=1]
   *       └── release (in finally) [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (RequestHandler pattern is correct)
   */
  @Test
  public void testSocketServerRequest_RequestHandlerPattern_ProperFinally() {
    SocketRequestResponseChannel.SocketServerRequest request = null;
    try {
      // Allocate buffer
      ByteBuf buffer = Unpooled.buffer(512);
      buffer.writeBytes("request data".getBytes());

      // Create SocketServerRequest
      request = new SocketRequestResponseChannel.SocketServerRequest(0, "conn-123", buffer);

      // Simulate processing
      DataInputStream inputStream = request.getInputStream();
      // ... handle request ...

    } catch (Exception e) {
      // Handle exceptions
    } finally {
      // Proper cleanup in finally (like RequestHandler does)
      if (request != null) {
        request.release();
      }
    }
  }

  /**
   * TEST: SocketServerRequest - Exception Before sendRequest (No Cleanup)
   *
   * FLOW: allocate -> SocketServerRequest.<init> -> exception thrown -> sendRequest never called
   *       -> RequestHandler never receives it -> LEAKED
   *
   * OWNERSHIP: Request created but exception prevents queuing, no cleanup
   *
   * SOURCE: SocketServer.java Processor.run() lines 406-412, LEAK-5.2
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── SocketServerRequest.<init> [ref=1] ⚠️ LEAK (exception before sendRequest)
   *
   * LEAK EXPECTATION: YES - request created but never sent to queue
   */
  @Test
  public void testSocketServerRequest_ExceptionBeforeSend_NoCleanup_LEAK() {
    // Simulate Processor creating request
    ByteBuf buffer = Unpooled.buffer(512);
    buffer.writeBytes("request data".getBytes());

    SocketRequestResponseChannel.SocketServerRequest req =
        new SocketRequestResponseChannel.SocketServerRequest(0, "conn-123", buffer);

    try {
      // Simulate exception before sendRequest (e.g., OutOfMemoryError, InterruptedException)
      throw new RuntimeException("Exception before sendRequest");

      // Would call channel.sendRequest(req) here, but exception prevents it
    } catch (Exception e) {
      // No cleanup
    }

    // INTENTIONAL LEAK: req never sent to queue, never released
  }

  /**
   * TEST: SocketServerRequest - Exception Before sendRequest (With Cleanup)
   *
   * FLOW: allocate -> SocketServerRequest.<init> -> exception -> catch -> release in catch
   *
   * OWNERSHIP: Proper exception handling in Processor
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── SocketServerRequest.<init> [ref=1]
   *       └── release (in catch) [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (exception handling with cleanup)
   */
  @Test
  public void testSocketServerRequest_ExceptionBeforeSend_WithCleanup() {
    SocketRequestResponseChannel.SocketServerRequest req = null;
    try {
      ByteBuf buffer = Unpooled.buffer(512);
      buffer.writeBytes("request data".getBytes());

      req = new SocketRequestResponseChannel.SocketServerRequest(0, "conn-123", buffer);

      // Simulate exception before sendRequest
      throw new RuntimeException("Exception before sendRequest");

      // Would call channel.sendRequest(req) here
    } catch (Exception e) {
      // Proper cleanup on exception
      if (req != null) {
        req.release();
      }
    }
  }

  /**
   * TEST: SocketServerRequest - replace() Method (Both Released)
   *
   * FLOW: allocate buf1 -> SocketServerRequest(buf1) -> replace(buf2) -> release both
   *
   * OWNERSHIP: Both original and new must be released
   *
   * SOURCE: SocketServerRequest.java lines 72-74, LEAK-5.3
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate (buf1) [count=1]
   *   └── SocketServerRequest.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   * - ROOT: allocate (buf2) [count=1]
   *   └── SocketServerRequest.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (both released)
   */
  @Test
  public void testSocketServerRequest_Replace_BothReleased() {
    // Create original request
    ByteBuf buf1 = Unpooled.buffer(256);
    buf1.writeBytes("original request".getBytes());
    SocketRequestResponseChannel.SocketServerRequest original =
        new SocketRequestResponseChannel.SocketServerRequest(0, "conn-123", buf1);

    // Replace with new content
    ByteBuf buf2 = Unpooled.buffer(256);
    buf2.writeBytes("replaced request".getBytes());
    SocketRequestResponseChannel.SocketServerRequest replaced = original.replace(buf2);

    // Proper pattern: release both
    original.release();
    replaced.release();
  }

  /**
   * TEST: SocketServerRequest - replace() Method (Only New Released - Leak)
   *
   * FLOW: allocate buf1 -> SocketServerRequest(buf1) -> replace(buf2) -> release ONLY new
   *
   * OWNERSHIP: Common mistake with replace()
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate (buf1) [count=1]
   *   └── SocketServerRequest.<init> [ref=1] ⚠️ LEAK (original not released)
   * - ROOT: allocate (buf2) [count=1]
   *   └── SocketServerRequest.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: YES - original request leaked
   */
  @Test
  public void testSocketServerRequest_Replace_OnlyNewReleased_LEAK() {
    // Create original request
    ByteBuf buf1 = Unpooled.buffer(256);
    buf1.writeBytes("original request - will leak".getBytes());
    SocketRequestResponseChannel.SocketServerRequest original =
        new SocketRequestResponseChannel.SocketServerRequest(0, "conn-123", buf1);

    // Replace with new content
    ByteBuf buf2 = Unpooled.buffer(256);
    buf2.writeBytes("replaced request".getBytes());
    SocketRequestResponseChannel.SocketServerRequest replaced = original.replace(buf2);

    // Common mistake: only release new
    replaced.release();

    // INTENTIONAL LEAK: original never released
  }

  /**
   * TEST: SocketServerRequest - Queue Shutdown with Pending Requests (No Cleanup)
   *
   * FLOW: Create multiple requests -> queue them -> shutdown queue -> pending requests leaked
   *
   * OWNERSHIP: Queue closed but pending requests not drained and released
   *
   * SOURCE: SocketRequestResponseChannel.shutdown() line 214, LEAK-5.6
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=3]
   *   └── SocketServerRequest.<init> [ref=1, count=3] ⚠️ LEAK (shutdown without drain)
   *
   * LEAK EXPECTATION: YES - all queued requests leaked on shutdown
   *
   * PRODUCTION IMPACT: Happens on every server shutdown
   * SEVERITY: MEDIUM (happens on shutdown, not during normal operation)
   */
  @Test
  public void testSocketServerRequest_QueueShutdown_NoDrain_LEAK() {
    BlockingQueue<SocketRequestResponseChannel.SocketServerRequest> queue = new LinkedBlockingQueue<>(10);

    // Create and queue multiple requests
    for (int i = 0; i < 3; i++) {
      ByteBuf buffer = Unpooled.buffer(256);
      buffer.writeBytes(("request " + i).getBytes());
      SocketRequestResponseChannel.SocketServerRequest req =
          new SocketRequestResponseChannel.SocketServerRequest(0, "conn-" + i, buffer);
      queue.offer(req);
    }

    // Simulate shutdown WITHOUT draining
    queue.clear();  // Just clear, don't release

    // INTENTIONAL LEAK: All 3 requests in queue never released (simulating shutdown bug)
  }

  /**
   * TEST: SocketServerRequest - Queue Shutdown with Proper Drain
   *
   * FLOW: Create multiple requests -> queue them -> shutdown -> drain queue -> release all
   *
   * OWNERSHIP: Proper shutdown pattern - drain and release pending requests
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=3]
   *   └── SocketServerRequest.<init> [ref=1, count=3]
   *       └── forEach(release) [ref=0, count=3] ✅ CLEAN (all drained and released)
   *
   * LEAK EXPECTATION: NO LEAK (proper shutdown cleanup)
   */
  @Test
  public void testSocketServerRequest_QueueShutdown_WithDrain() {
    BlockingQueue<SocketRequestResponseChannel.SocketServerRequest> queue = new LinkedBlockingQueue<>(10);

    // Create and queue multiple requests
    for (int i = 0; i < 3; i++) {
      ByteBuf buffer = Unpooled.buffer(256);
      buffer.writeBytes(("request " + i).getBytes());
      SocketRequestResponseChannel.SocketServerRequest req =
          new SocketRequestResponseChannel.SocketServerRequest(0, "conn-" + i, buffer);
      queue.offer(req);
    }

    // Proper shutdown: drain queue and release all
    List<SocketRequestResponseChannel.SocketServerRequest> pendingRequests = new ArrayList<>();
    queue.drainTo(pendingRequests);
    pendingRequests.forEach(SocketRequestResponseChannel.SocketServerRequest::release);
  }

  /**
   * TEST: SocketServerRequest - InterruptedException During sendRequest
   *
   * FLOW: allocate -> SocketServerRequest.<init> -> sendRequest() blocks -> interrupted
   *       -> InterruptedException -> no cleanup -> LEAKED
   *
   * OWNERSHIP: Request created but enqueue interrupted, not cleaned up
   *
   * SOURCE: SocketRequestResponseChannel.sendRequest() line 160, LEAK-5.5
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── SocketServerRequest.<init> [ref=1] ⚠️ LEAK (interrupted, not queued, not released)
   *
   * LEAK EXPECTATION: YES - request never queued, never released
   */
  @Test
  public void testSocketServerRequest_InterruptedDuringSend_NoCleanup_LEAK() {
    ByteBuf buffer = Unpooled.buffer(512);
    buffer.writeBytes("request data".getBytes());

    SocketRequestResponseChannel.SocketServerRequest req =
        new SocketRequestResponseChannel.SocketServerRequest(0, "conn-123", buffer);

    // Simulate InterruptedException during sendRequest
    // In production, this would happen if queue.offer() is interrupted
    // No cleanup performed

    // INTENTIONAL LEAK: req created but never queued, never released
  }

  /**
   * TEST: SocketServerRequest - InterruptedException with Cleanup
   *
   * FLOW: allocate -> SocketServerRequest.<init> -> sendRequest() interrupted
   *       -> catch InterruptedException -> release request -> rethrow
   *
   * OWNERSHIP: Proper interrupt handling with cleanup
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=1]
   *   └── SocketServerRequest.<init> [ref=1]
   *       └── release (in catch) [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (interrupt handled with cleanup)
   */
  @Test
  public void testSocketServerRequest_InterruptedDuringSend_WithCleanup() {
    SocketRequestResponseChannel.SocketServerRequest req = null;
    try {
      ByteBuf buffer = Unpooled.buffer(512);
      buffer.writeBytes("request data".getBytes());

      req = new SocketRequestResponseChannel.SocketServerRequest(0, "conn-123", buffer);

      // Simulate sending to queue (could throw InterruptedException)
      // In this test, we just simulate the cleanup pattern

    } catch (Exception e) {
      // Proper cleanup on interrupt
      if (req != null) {
        req.release();
        req = null;
      }
    }

    // If we reach here and req is still set, release it
    if (req != null) {
      req.release();
    }
  }

  // ================================================================================================
  // SECTION 4: Cross-Class Integration Flows
  // Complex flows that involve multiple wrapper classes
  // ================================================================================================

  /**
   * TEST: End-to-End Flow - BlobData -> ResponseInfo (All Released)
   *
   * FLOW: Process blob -> create BlobData -> generate response -> create ResponseInfo
   *       -> release all
   *
   * OWNERSHIP: Complete request/response cycle with proper cleanup
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate (blob data) [count=1]
   *   └── BlobData.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   * - ROOT: allocate (response) [count=1]
   *   └── ResponseInfo.<init> [ref=1]
   *       └── release [ref=0] ✅ CLEAN
   *
   * LEAK EXPECTATION: NO LEAK (proper end-to-end cleanup)
   */
  @Test
  public void testIntegration_BlobDataToResponse_AllReleased() {
    // Step 1: Create BlobData
    ByteBuf blobBuffer = Unpooled.buffer(512);
    blobBuffer.writeBytes("blob content".getBytes());
    BlobData blobData = new BlobData(BlobType.DataBlob, blobBuffer.readableBytes(), blobBuffer);

    // Step 2: Process blob data
    ByteBuf blobContent = blobData.content();
    int blobSize = blobContent.readableBytes();

    // Step 3: Release BlobData
    blobData.release();

    // Step 4: Create ResponseInfo
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf responseBuffer = Unpooled.buffer(256);
    responseBuffer.writeBytes("success response".getBytes());
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, responseBuffer);

    // Step 5: Release ResponseInfo
    responseInfo.release();
  }

  /**
   * TEST: End-to-End Flow - Request Pipeline Leak (Partial Cleanup)
   *
   * FLOW: Process multiple requests -> release some BlobData -> forget ResponseInfo
   *       -> partial leak
   *
   * OWNERSHIP: Realistic scenario - some releases, some forgotten
   *
   * EXPECTED TRACER OUTPUT:
   * - Multiple allocation roots with mixed clean/leak status
   *
   * LEAK EXPECTATION: PARTIAL - Some components leaked, some clean
   */
  @Test
  public void testIntegration_RequestPipeline_PartialCleanup_LEAK() {
    // Process multiple blobs
    List<BlobData> blobDataList = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ByteBuf buffer = Unpooled.buffer(256);
      buffer.writeBytes(("blob " + i).getBytes());
      blobDataList.add(new BlobData(BlobType.DataBlob, buffer.readableBytes(), buffer));
    }

    // Release first two BlobData
    blobDataList.get(0).release();
    blobDataList.get(1).release();
    // LEAK: Third BlobData never released

    // Create ResponseInfo
    RequestInfo requestInfo = createMockRequestInfo();
    ByteBuf responseBuffer = Unpooled.buffer(256);
    responseBuffer.writeBytes("response".getBytes());
    ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, responseBuffer);

    // LEAK: ResponseInfo never released
  }

  /**
   * TEST: Replication Flow - ResponseInfo List (ReplicaThread Pattern)
   *
   * FLOW: Replication cycle -> create multiple ResponseInfo -> process -> NONE released
   *
   * OWNERSHIP: Replicates the HIGH SEVERITY production leak in ReplicaThread
   *
   * EXPECTED TRACER OUTPUT:
   * - ROOT: allocate [count=10]
   *   └── ResponseInfo.<init> [ref=1, count=10] ⚠️ LEAK (ReplicaThread pattern - none released)
   *
   * LEAK EXPECTATION: YES - All ResponseInfo leaked (PRODUCTION BUG)
   */
  @Test
  public void testIntegration_ReplicationCycle_NoRelease_LEAK() {
    List<ResponseInfo> replicationResponses = new ArrayList<>();

    // Simulate ReplicaThread creating multiple ResponseInfo during replication
    for (int i = 0; i < 10; i++) {
      RequestInfo requestInfo = createMockRequestInfo();
      ByteBuf responseContent = Unpooled.buffer(512);
      responseContent.writeBytes(("replication response " + i).getBytes());
      replicationResponses.add(new ResponseInfo(requestInfo, null, responseContent));
    }

    // Process all responses
    for (ResponseInfo responseInfo : replicationResponses) {
      ByteBuf content = responseInfo.content();
      // ... process replication response ...
    }

    // INTENTIONAL LEAK: ReplicaThread never releases ResponseInfo
    // This is the HIGH SEVERITY production bug identified in the analysis
  }

  // ================================================================================================
  // Test Lifecycle Hooks
  // ================================================================================================

  @Before
  public void setUp() {
    // Setup code if needed
  }

  @After
  public void tearDown() {
    // Note: We intentionally do NOT clean up leaks here - we want the tracer to see them
    // In production tests you would clean up, but these are flow exploration tests
  }

  // ================================================================================================
  // Helper Methods
  // ================================================================================================

  /**
   * Create a mock RequestInfo for testing ResponseInfo
   */
  private RequestInfo createMockRequestInfo() {
    // Create mock ReplicaId
    ReplicaId mockReplicaId = Mockito.mock(ReplicaId.class);
    DataNodeId mockDataNode = new MockDataNodeId("localhost", new Port(6667, PortType.PLAINTEXT),
        new Port(6668, PortType.SSL), new Port(6669, PortType.HTTP2));
    Mockito.when(mockReplicaId.getDataNodeId()).thenReturn(mockDataNode);

    // Create RequestInfo with minimal parameters
    return new RequestInfo("localhost", new Port(6667, PortType.PLAINTEXT), null, mockReplicaId, null);
  }
}
