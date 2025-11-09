/**
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
package com.github.ambry.router;

import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.clustermap.MockPartitionId;
import com.github.ambry.clustermap.MockReplicaId;
import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.clustermap.ReplicaId;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.Callback;
import com.github.ambry.commons.LoggingNotificationSystem;
import com.github.ambry.commons.ResponseHandler;
import com.github.ambry.config.RouterConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.account.InMemAccountService;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.network.ResponseInfo;
import com.github.ambry.protocol.DeleteRequest;
import com.github.ambry.protocol.DeleteResponse;
import com.github.ambry.protocol.RequestOrResponse;
import com.github.ambry.server.ServerErrorCode;
import com.github.ambry.utils.MockTime;
import com.github.ambry.utils.NettyByteBufLeakHelper;
import com.github.ambry.utils.TestUtils;
import com.github.ambry.utils.Time;
import com.github.ambry.utils.Utils;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests to verify that DeleteRequest ByteBuf leaks in DeleteOperation are fixed.
 *
 * BEFORE FIX: Test will fail with "DirectMemoryLeak" or "HeapMemoryLeak" assertion error
 * AFTER FIX: Test will pass with no leaks detected
 */
public class DeleteRequestLeakTest {
  private final NettyByteBufLeakHelper nettyByteBufLeakHelper = new NettyByteBufLeakHelper();
  private MockClusterMap clusterMap;
  private RouterConfig routerConfig;
  private NonBlockingRouterMetrics routerMetrics;
  private Time mockTime;
  private DeleteOperation deleteOperation;
  private BlobId blobId;
  private ResponseHandler responseHandler;
  private final InMemAccountService accountService = new InMemAccountService(false, true);
  private final LoggingNotificationSystem notificationSystem = new LoggingNotificationSystem();

  @Before
  public void before() throws Exception {
    nettyByteBufLeakHelper.beforeTest();

    // Setup real components (minimal mocking)
    clusterMap = new MockClusterMap();
    Properties props = new Properties();
    props.setProperty("router.hostname", "localhost");
    props.setProperty("router.datacenter.name", "DC1");
    props.setProperty("router.delete.request.parallelism", "3");
    props.setProperty("router.delete.success.target", "2");
    VerifiableProperties vProps = new VerifiableProperties(props);
    routerConfig = new RouterConfig(vProps);
    routerMetrics = new NonBlockingRouterMetrics(clusterMap, routerConfig);
    mockTime = new MockTime();
    responseHandler = new ResponseHandler(clusterMap);

    // Create a real BlobId
    List<? extends PartitionId> partitions = clusterMap.getWritablePartitionIds(MockClusterMap.DEFAULT_PARTITION_CLASS);
    PartitionId partition = partitions.get(0);
    blobId = new BlobId(routerConfig.routerBlobidCurrentVersion, BlobId.BlobIdType.NATIVE,
        clusterMap.getLocalDatacenterId(), Utils.getRandomShort(TestUtils.RANDOM),
        Utils.getRandomShort(TestUtils.RANDOM), partition, false, BlobId.BlobDataType.DATACHUNK);
  }

  @After
  public void after() {
    // This will FAIL if DeleteRequest buffers are not released
    nettyByteBufLeakHelper.afterTest();
  }

  /**
   * Test that verifies DeleteRequest buffers are released in the normal success path.
   *
   * BEFORE FIX: DeleteRequest.content() allocates a pooled buffer that is never released
   * AFTER FIX: DeleteRequest.release() is called, properly freeing the buffer
   */
  @Test
  public void testDeleteRequestReleasedOnSuccess() throws Exception {
    // Create DeleteOperation
    AtomicReference<Exception> operationException = new AtomicReference<>();
    Callback<Void> callback = (result, exception) -> {
      if (exception != null) {
        operationException.set(exception);
      }
    };

    deleteOperation = new DeleteOperation(clusterMap, routerConfig, routerMetrics, null,
        notificationSystem, accountService, blobId, blobId.getID(), null,
        mockTime, callback, mockTime.milliseconds(), null, null, false);

    // Track requests created
    List<RequestInfo> requestsCreated = new ArrayList<>();
    RequestRegistrationCallback<DeleteOperation> requestRegistrationCallback =
        new RequestRegistrationCallback<DeleteOperation>() {
          @Override
          public void registerRequestToSend(DeleteOperation deleteOperation, RequestInfo requestInfo) {
            requestsCreated.add(requestInfo);
          }

          @Override
          public void registerRequestToDrop(int correlationId) {
            // Not needed for this test
          }
        };

    // Poll to create DeleteRequests
    deleteOperation.poll(requestRegistrationCallback);

    // Verify requests were created
    Assert.assertTrue("Should have created delete requests", requestsCreated.size() > 0);

    // Each request has a DeleteRequest with a pooled ByteBuf
    // Verify the buffer was allocated by calling content()
    for (RequestInfo requestInfo : requestsCreated) {
      Assert.assertTrue("Request should be DeleteRequest", requestInfo.getRequest() instanceof DeleteRequest);
      DeleteRequest deleteRequest = (DeleteRequest) requestInfo.getRequest();

      // Calling content() allocates the pooled buffer (via prepareBuffer)
      ByteBuf content = deleteRequest.content();
      Assert.assertNotNull("DeleteRequest content should not be null", content);
      Assert.assertTrue("DeleteRequest content should have data", content.readableBytes() > 0);

      // The buffer is now allocated but NOT released yet
      // This is where the leak occurs in the buggy code
    }

    // Simulate successful responses from servers
    for (RequestInfo requestInfo : requestsCreated) {
      DeleteRequest deleteRequest = (DeleteRequest) requestInfo.getRequest();

      // Create success response
      DeleteResponse deleteResponse = new DeleteResponse(deleteRequest.getCorrelationId(), "test-client",
          ServerErrorCode.No_Error);

      ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, deleteResponse);

      // Handle response - this is where DeleteRequest should be released
      deleteOperation.handleResponse(responseInfo, deleteResponse);

      // CRITICAL: After handleResponse(), the DeleteRequest should be released
      // BEFORE FIX: deleteRequest.release() is never called -> leak
      // AFTER FIX: deleteRequest.release() is called in handleResponse()
    }

    // Verify operation completed successfully
    Assert.assertNull("Operation should complete without error", operationException.get());

    // The afterTest() will verify all DeleteRequest buffers were released
    // BEFORE FIX: Will fail with "DirectMemoryLeak" or "HeapMemoryLeak"
    // AFTER FIX: Will pass with no leaks
  }

  /**
   * Test that verifies DeleteRequest buffers are released when requests expire/timeout.
   *
   * BEFORE FIX: Expired DeleteRequests are removed from map but never released
   * AFTER FIX: DeleteRequests are released in cleanupExpiredInflightRequests()
   */
  @Test
  public void testDeleteRequestReleasedOnTimeout() throws Exception {
    // Create DeleteOperation
    Callback<Void> callback = (result, exception) -> {};

    deleteOperation = new DeleteOperation(clusterMap, routerConfig, routerMetrics, null,
        notificationSystem, accountService, blobId, blobId.getID(), null,
        mockTime, callback, mockTime.milliseconds(), null, null, false);

    List<RequestInfo> requestsCreated = new ArrayList<>();
    RequestRegistrationCallback<DeleteOperation> requestRegistrationCallback =
        new RequestRegistrationCallback<DeleteOperation>() {
          @Override
          public void registerRequestToSend(DeleteOperation deleteOperation, RequestInfo requestInfo) {
            requestsCreated.add(requestInfo);
          }

          @Override
          public void registerRequestToDrop(int correlationId) {
            // Track dropped requests
          }
        };

    // Poll to create requests
    deleteOperation.poll(requestRegistrationCallback);
    Assert.assertTrue("Should have created delete requests", requestsCreated.size() > 0);

    // Force buffers to be allocated
    for (RequestInfo requestInfo : requestsCreated) {
      DeleteRequest deleteRequest = (DeleteRequest) requestInfo.getRequest();
      deleteRequest.content(); // Allocates pooled buffer
    }

    // Advance time beyond request timeout
    mockTime.sleep(routerConfig.routerRequestTimeoutMs + 1000);

    // Poll again - this triggers cleanupExpiredInflightRequests()
    deleteOperation.poll(requestRegistrationCallback);

    // CRITICAL: cleanupExpiredInflightRequests() should release DeleteRequests
    // BEFORE FIX: Requests removed from map but never released -> leak
    // AFTER FIX: deleteRequest.release() called for expired requests

    // afterTest() verifies all expired DeleteRequest buffers were released
  }

  /**
   * Test that verifies multiple DeleteOperations don't accumulate leaks.
   *
   * This simulates multiple delete operations to ensure leaks don't accumulate.
   */
  @Test
  public void testMultipleDeleteOperationsNoAccumulatedLeaks() throws Exception {
    // Create and complete 5 delete operations
    for (int i = 0; i < 5; i++) {
      Callback<Void> callback = (result, exception) -> {};

      DeleteOperation op = new DeleteOperation(clusterMap, routerConfig, routerMetrics, null,
          notificationSystem, accountService, blobId, blobId.getID(), null,
          mockTime, callback, mockTime.milliseconds(), null, null, false);

      List<RequestInfo> requests = new ArrayList<>();
      op.poll(new RequestRegistrationCallback<DeleteOperation>() {
        @Override
        public void registerRequestToSend(DeleteOperation deleteOperation, RequestInfo requestInfo) {
          requests.add(requestInfo);
        }

        @Override
        public void registerRequestToDrop(int correlationId) {}
      });

      // Allocate buffers and handle responses
      for (RequestInfo requestInfo : requests) {
        DeleteRequest deleteRequest = (DeleteRequest) requestInfo.getRequest();
        deleteRequest.content(); // Allocate buffer

        DeleteResponse deleteResponse = new DeleteResponse(deleteRequest.getCorrelationId(), "test",
            ServerErrorCode.No_Error);
        ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, deleteResponse);
        op.handleResponse(responseInfo, deleteResponse);

        // DeleteRequest should be released here
      }
    }

    // afterTest() verifies no accumulated leaks from 5 operations
    // BEFORE FIX: Shows 5+ leaked buffers (one per operation, possibly more)
    // AFTER FIX: Shows 0 leaked buffers
  }

  /**
   * Test that verifies DeleteRequest buffers are released even when operation fails.
   *
   * BEFORE FIX: Failed DeleteRequests may not be released
   * AFTER FIX: DeleteRequests are released in error paths
   */
  @Test
  public void testDeleteRequestReleasedOnError() throws Exception {
    Callback<Void> callback = (result, exception) -> {};

    deleteOperation = new DeleteOperation(clusterMap, routerConfig, routerMetrics, null,
        notificationSystem, accountService, blobId, blobId.getID(), null,
        mockTime, callback, mockTime.milliseconds(), null, null, false);

    List<RequestInfo> requests = new ArrayList<>();
    deleteOperation.poll(new RequestRegistrationCallback<DeleteOperation>() {
      @Override
      public void registerRequestToSend(DeleteOperation deleteOperation, RequestInfo requestInfo) {
        requests.add(requestInfo);
      }

      @Override
      public void registerRequestToDrop(int correlationId) {}
    });

    // Simulate error responses
    for (RequestInfo requestInfo : requests) {
      DeleteRequest deleteRequest = (DeleteRequest) requestInfo.getRequest();
      deleteRequest.content(); // Allocate buffer

      // Return error response
      DeleteResponse deleteResponse = new DeleteResponse(deleteRequest.getCorrelationId(), "test",
          ServerErrorCode.Blob_Not_Found);
      ResponseInfo responseInfo = new ResponseInfo(requestInfo, null, deleteResponse);
      deleteOperation.handleResponse(responseInfo, deleteResponse);

      // DeleteRequest should still be released even on error
    }

    // afterTest() verifies buffers were released despite errors
  }
}
