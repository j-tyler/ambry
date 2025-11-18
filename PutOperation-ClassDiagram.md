# PutOperation Class Diagram

This Mermaid.js class diagram provides a comprehensive view of the PutOperation class, including entry points, channels, internal classes, and all key dependencies.

```mermaid
classDiagram
    %% ========================================
    %% ENTRY POINTS - External Classes that call PutOperation
    %% ========================================
    class NonBlockingRouter {
        +putBlob(blobProperties, userMetadata, channel, options, callback) Future~String~
        +stitchBlob(blobProperties, userMetadata, chunksToStitch, options, callback) Future~String~
        -operationController: OperationController
    }

    class OperationController {
        +putBlob(channel, options, callback, futureResult, blobProperties, userMetadata, partitionClass) String
        +stitchBlob(chunksToStitch, options, callback, futureResult, blobProperties, userMetadata, partitionClass) String
        -putManager: PutManager
    }

    class PutManager {
        +submitPutBlobOperation(channel, options, callback, futureResult, blobProperties, userMetadata, partitionClass) void
        +submitStitchBlobOperation(chunksToStitch, options, callback, futureResult, blobProperties, userMetadata, partitionClass) void
        -operations: Map~String,PutOperation~
        -correlationIdToPutOperation: Map~Integer,PutOperation~
        -chunkFillerThread: Thread
        -routerConfig: RouterConfig
        -routerMetrics: NonBlockingRouterMetrics
        -clusterMap: ClusterMap
        -kms: KeyManagementService
        -cryptoService: CryptoService
        -cryptoJobHandler: CryptoJobHandler
        +handleResponse(responseInfo) void
        +poll(requestsToSend, requestsToDrop) void
    }

    %% ========================================
    %% CHANNELS - Data flow channels
    %% ========================================
    class ReadableStreamChannel {
        <<interface>>
        +readInto(asyncWritableChannel, callback) Future~Long~
        +getSize() long
        +isOpen() boolean
        +close() void
    }

    class ByteBufferAsyncWritableChannel {
        +write(src, callback) Future~Long~
        +getNextByteBuf(timeout) ByteBuf
        +resolveOldestChunk(exception) boolean
        +close() void
        -chunks: LinkedList~ByteBuf~
        -writeCallback: Callback~Long~
        -channelEventListener: ChannelEventListener
    }

    class ByteBuf {
        <<Netty>>
        +readableBytes() int
        +writeBytes(src) ByteBuf
        +retain() ByteBuf
        +release() boolean
        +readBytes(dst) ByteBuf
    }

    %% ========================================
    %% MAIN PUTOPERATION CLASS
    %% ========================================
    class PutOperation {
        %% Configuration & Services
        -routerConfig: RouterConfig
        -routerMetrics: NonBlockingRouterMetrics
        -clusterMap: ClusterMap
        -notificationSystem: NotificationSystem
        -accountService: AccountService
        -kms: KeyManagementService
        -cryptoService: CryptoService
        -cryptoJobHandler: CryptoJobHandler
        -compressionService: CompressionService
        -time: Time

        %% Input Parameters
        -passedInBlobProperties: BlobProperties
        -finalBlobProperties: BlobProperties
        -userMetadata: byte[]
        -partitionClass: String
        -options: PutBlobOptions

        %% Channels (for direct uploads)
        -channel: ReadableStreamChannel
        -chunkFillerChannel: ByteBufferAsyncWritableChannel
        -channelReadBuf: ByteBuf

        %% Stitching (for stitch operations)
        -chunksToStitch: List~ChunkInfo~

        %% State Management
        -putChunks: ConcurrentLinkedQueue~PutChunk~
        -metadataPutChunk: MetadataPutChunk
        -chunkToFill: PutChunk
        -blobSize: long
        -bytesFilledSoFar: long
        -chunkCounter: int

        %% Completion & Results
        -futureResult: FutureResult~String~
        -callback: Callback~String~
        -routerCallback: RouterCallback
        -operationCompleted: volatile boolean
        -operationException: AtomicReference~Exception~
        -blobId: BlobId

        %% Flags & Settings
        -isEncryptionEnabled: boolean
        -isBlobCompressible: boolean
        -chunkFillingCompletedSuccessfully: volatile boolean
        -isSimpleBlob: boolean
        -isCrcVerificationAllowedForBlob: boolean

        %% Tracking & Metrics
        -correlationIdToPutChunk: Map~Integer,PutChunk~
        -submissionTimeMs: long
        -startTimeForChunkAvailabilityWaitMs: long
        -startTimeForChannelDataAvailabilityMs: long
        -waitTimeForCurrentChunkAvailabilityMs: long
        -waitTimeForChannelDataAvailabilityMs: long
        -slippedPutBlobIds: Set~BlobId~

        %% Quota & Request Context
        -quotaChargeCallback: QuotaChargeCallback
        -restRequest: RestRequest
        -loggingContext: String
        -putBlobMetaInfo: String
        -reservedMetadataIdMetrics: ReservedMetadataIdMetrics

        %% Factory Methods
        +forUpload(routerConfig, routerMetrics, clusterMap, notificationSystem, accountService, userMetadata, channel, options, futureResult, callback, routerCallback, writableChannelEventListener, kms, cryptoService, cryptoJobHandler, time, blobProperties, partitionClass, quotaChargeCallback, compressionService)$ PutOperation
        +forStitching(routerConfig, routerMetrics, clusterMap, notificationSystem, accountService, userMetadata, chunksToStitch, futureResult, callback, routerCallback, kms, cryptoService, cryptoJobHandler, time, blobProperties, partitionClass, quotaChargeCallback, compressionService)$ PutOperation
        +forStitching(routerConfig, routerMetrics, clusterMap, notificationSystem, accountService, userMetadata, chunksToStitch, options, futureResult, callback, routerCallback, kms, cryptoService, cryptoJobHandler, time, blobProperties, partitionClass, quotaChargeCallback, compressionService)$ PutOperation

        %% Lifecycle Methods
        -PutOperation(routerConfig, routerMetrics, clusterMap, notificationSystem, accountService, userMetadata, channel, chunksToStitch, options, futureResult, callback, routerCallback, writableChannelEventListener, kms, cryptoService, cryptoJobHandler, time, blobProperties, partitionClass, quotaChargeCallback, compressionService)
        +startOperation() void
        +poll(requestsToSend, requestsToDrop) void
        +handleResponse(responseInfo) void
        +abort(abortCause) void

        %% Data Processing (ChunkFiller Thread)
        +fillChunks() void
        -startReadingFromChannel() void
        -processChunksToStitch() void
        -unwrapChunkInfo(chunkInfo, intermediateChunkSize, lastChunk) BlobId

        %% Chunk Management
        -onChunkOperationComplete(chunk, exception) void
        -maybeNotifyForBlobCreation() void
        -setOperationCompleted() void
        -setOperationExceptionAndComplete(exception) void
        -cleanupChunks() void

        %% Query Methods
        +isOperationComplete() boolean
        +getOperationException() Exception
        +getNumDataChunks() int
        +getBlobSize() long
        +getBlobIdString() String
        +isEncryptionEnabled() boolean
        -isStitchOperation() boolean
        -makeLoggingContext() String
    }

    %% ========================================
    %% PUTCHUNK - Internal class for individual chunks
    %% ========================================
    class PutChunk {
        %% Chunk Identity
        -chunkIndex: int
        -chunkBlobId: BlobId
        -chunkBlobSize: long
        -chunkBlobProperties: BlobProperties

        %% State & Data
        -state: volatile ChunkState
        -buf: volatile ByteBuf
        -isChunkCompressed: boolean
        -chunkCrc32: Crc32

        %% Encryption
        -encryptedPerBlobKey: ByteBuffer
        -encryptionJob: EncryptJob

        %% Network & Tracking
        -operationTracker: OperationTracker
        -partitionId: PartitionId
        -correlationIdToChunkPutRequestInfo: Map~Integer,RequestInfo~
        -inFlightRequestInfo: Set~RequestInfo~
        -failedAttempts: Map~Integer,RouterErrorCode~

        %% Metrics & Timing
        -chunkFillStartTimeMs: long
        -chunkReadyTimeMs: long
        -chunkException: volatile RouterException

        %% Constructor & Lifecycle
        +PutChunk()
        +clear() void

        %% State Transitions - Building Phase
        +prepareForBuilding(chunkIndex) void
        +fillFrom(byteBuf) boolean
        +onFillComplete(exception) void
        -compressChunk() void
        -encryptChunk() void
        -encryptionCallback(encryptJobResult, exception) void

        %% State Transitions - Sending Phase
        +prepareForSending() void
        -selectPartition() void
        -generateBlobId() void
        -createOperationTracker() void

        %% Request Generation
        +poll(requestsToSend, requestsToDrop) void
        -fetchRequests(requestsToSend) void
        -createPutRequest(replicaId) PutRequest
        -createChunkPutRequestInfo(putRequest, replicaId) RequestInfo
        -cleanupExpiredInFlightRequests(requestsToDrop) void

        %% Response Processing
        +handleResponse(responseInfo) void
        -processServerError(serverErrorCode) void
        -processSuccessResponse(putResponse) void
        -checkAndMaybeComplete() void

        %% Utilities
        -verifyCRC(putResponse) boolean
        -getSlippedPutBlobId() BlobId
        +getChunkBlobId() BlobId
        +getChunkException() RouterException
        +hasSendSucceeded() boolean
    }

    %% ========================================
    %% METADATAPUTCHUNK - Extends PutChunk for metadata
    %% ========================================
    class MetadataPutChunk {
        -indexToChunkIdsAndChunkSizes: TreeMap~Integer,Pair~StoreKey,Long~~
        -intermediateChunkSize: int
        -firstChunkIdAndProperties: Pair~StoreKey,BlobProperties~
        -reservedMetadataChunkId: BlobId

        +MetadataPutChunk(chunksToStitch)
        +setIntermediateChunkSize(size) void
        +addChunkId(chunkBlobId, chunkSize, chunkIndex) void
        +maybeNotifyForChunkCreation() void
        -buildMetadataContent() void
        +createPutRequest(replicaId) PutRequest
        -serializeMetadataContentV2() ByteBuffer
        -serializeMetadataContentV3() ByteBuffer
    }

    %% ========================================
    %% CHUNKSTATE ENUM
    %% ========================================
    class ChunkState {
        <<enumeration>>
        Free
        Building
        AwaitingBlobTypeResolution
        Encrypting
        Ready
        Complete
    }

    %% ========================================
    %% KEY DEPENDENCIES - Configuration & Services
    %% ========================================
    class RouterConfig {
        +routerMaxPutChunkSizeBytes: int
        +routerPutRequestParallelism: int
        +routerPutSuccessTarget: int
        +routerMetadataContentVersion: short
        +routerReservedMetadataBlobIdEnabled: boolean
        +routerCrcCheckOnPutEnabled: boolean
        +routerCrcVerificationAccountContainerAllowlist: Set~String~
        +routerRequestNetworkTimeoutMs: int
        +routerMaxSlippedPutAttempts: int
    }

    class NonBlockingRouterMetrics {
        +getMetricRegistry() MetricRegistry
        +operationQueuingRate: Meter
        +encryptTimeMs: Histogram
        +compressionTimeMs: Histogram
        +putChunkOperationLatencyMs: Histogram
        +slippedPutAttemptCount: Counter
        +crcMismatchCount: Counter
    }

    class ClusterMap {
        <<interface>>
        +getWritablePartitionIds(partitionClass) List~PartitionId~
        +getRandomWritablePartition(partitionClass, excludedPartitions) PartitionId
        +getDataNodeId(hostname, port) DataNodeId
        +getReplicaIds(dataNodeId) List~ReplicaId~
    }

    class NotificationSystem {
        <<interface>>
        +onBlobCreated(blobId, blobProperties, account, container, notificationBlobType) void
        +onBlobReplicaCreated(blobId, dataCenterId, account, container, blobType) void
    }

    class AccountService {
        <<interface>>
        +getAccountById(id) Account
        +getContainerById(accountId, containerId) Container
    }

    %% ========================================
    %% ENCRYPTION & COMPRESSION SERVICES
    %% ========================================
    class KeyManagementService {
        <<interface>>
        +getKey(blobProperties, cryptoContextHeader) Object
        +generateKey(blobProperties) Object
    }

    class CryptoService {
        <<interface>>
        +encrypt(input, key) ByteBuffer
        +decrypt(input, key) ByteBuffer
    }

    class CryptoJobHandler {
        +submitJob(cryptoJob) void
        +getResultQueue() LinkedBlockingQueue~Object~
    }

    class EncryptJob {
        +EncryptJob(blobId, key, buf, userMetadata, callback)
        -blobId: BlobId
        -key: Object
        -toEncrypt: ByteBuffer
        -encryptedBlobContent: ByteBuffer
        -encryptedUserMetadata: ByteBuffer
        -encryptedPerBlobKey: ByteBuffer
    }

    class CompressionService {
        +isBlobCompressible(blobProperties) boolean
        +compress(algorithm, data) ByteBuf
        +decompress(algorithm, data, decompressedSize) ByteBuf
    }

    %% ========================================
    %% CLUSTER & PARTITION CLASSES
    %% ========================================
    class PartitionId {
        <<interface>>
        +getId() long
        +getPartitionClass() String
        +getPartitionState() PartitionState
        +getReplicaIds() List~ReplicaId~
        +toPathString() String
    }

    class ReplicaId {
        <<interface>>
        +getDataNodeId() DataNodeId
        +getPartitionId() PartitionId
        +getDiskId() DiskId
        +getReplicaPath() String
    }

    class OperationTracker {
        <<interface>>
        +getReplicaIterator() Iterator~ReplicaId~
        +onResponse(replicaId, trackedRequestFinalState) void
        +hasSucceeded() boolean
        +hasFailedOnNotFound() boolean
        +isDone() boolean
        +getSuccessReplicas() List~ReplicaId~
    }

    %% ========================================
    %% PROTOCOL CLASSES
    %% ========================================
    class RequestInfo {
        +RequestInfo(hostname, port, request, replicaId, quotaChargeCallback)
        -hostname: String
        -port: Port
        -request: RequestOrResponse
        -replicaId: ReplicaId
        -requestCreateTime: long
        -quotaChargeCallback: QuotaChargeCallback
    }

    class ResponseInfo {
        +getError() NetworkClientErrorCode
        +getResponse() RequestOrResponse
        +getRequestInfo() RequestInfo
        +isQuotaRejected() boolean
    }

    class PutRequest {
        +PutRequest(correlationId, clientId, blobId, blobProperties, userMetadata, blobSize, blobType, blobEncryptionKey, blob, crc)
        -correlationId: int
        -blobId: BlobId
        -blobProperties: BlobProperties
        -userMetadata: ByteBuffer
        -blobSize: long
        -blobType: BlobType
        -blobEncryptionKey: ByteBuffer
        -blob: ByteBuf
        -crc: Long
    }

    class PutResponse {
        +getCorrelationId() int
        +getError() ServerErrorCode
        +getBlobId() BlobId
        +getCrc() Long
        -correlationId: int
        -serverErrorCode: ServerErrorCode
        -blobId: BlobId
        -crc: Long
    }

    %% ========================================
    %% DATA STRUCTURES
    %% ========================================
    class BlobId {
        +BlobId(version, type, datacenterId, accountId, containerId, partitionId, encrypted, dataType)
        +getPartition() PartitionId
        +getAccountId() short
        +getContainerId() short
        +isEncrypted() boolean
        +getDataType() BlobDataType
        +getID() String
    }

    class BlobProperties {
        +getBlobSize() long
        +getServiceId() String
        +getOwnerId() String
        +getContentType() String
        +isEncrypted() boolean
        +getTimeToLiveInSeconds() long
        +getCreationTimeInMs() long
        +getAccountId() short
        +getContainerId() short
        +setBlobSize(size) void
    }

    class ChunkInfo {
        +getChunkBlobId() String
        +getChunkSizeInBytes() long
        +getExpirationTimeInMs() long
    }

    class PutBlobOptions {
        +isChunkUpload() boolean
        +skipCompositeChunk() boolean
        +getMaxUploadSize() long
        +getRestRequest() RestRequest
        +DEFAULT$ PutBlobOptions
    }

    %% ========================================
    %% CALLBACKS & FUTURES
    %% ========================================
    class Callback~T~ {
        <<interface>>
        +onCompletion(result, exception) void
    }

    class FutureResult~T~ {
        +done(result, exception) void
        +get() T
        +get(timeout, unit) T
        +isDone() boolean
    }

    class RouterCallback {
        +onPollReady() void
    }

    class QuotaChargeCallback {
        +charge(quotaResource, quotaConfig) void
        +quotaExceedAllowed(quotaResource) boolean
        +checkAndCharge(quotaResource, quotaConfig) boolean
    }

    %% ========================================
    %% RELATIONSHIPS - Entry Point Flow
    %% ========================================
    NonBlockingRouter --> OperationController : uses
    OperationController --> PutManager : delegates to
    PutManager --> PutOperation : creates & manages
    PutManager --> PutOperation : calls poll(), handleResponse(), fillChunks()

    %% ========================================
    %% RELATIONSHIPS - PutOperation Dependencies
    %% ========================================
    PutOperation --> RouterConfig : configured by
    PutOperation --> NonBlockingRouterMetrics : reports to
    PutOperation --> ClusterMap : queries partitions from
    PutOperation --> NotificationSystem : notifies
    PutOperation --> AccountService : validates accounts with
    PutOperation --> KeyManagementService : gets encryption keys from
    PutOperation --> CryptoService : encrypts with
    PutOperation --> CryptoJobHandler : submits crypto jobs to
    PutOperation --> CompressionService : compresses with
    PutOperation --> QuotaChargeCallback : charges quota via

    %% ========================================
    %% RELATIONSHIPS - Channel Usage
    %% ========================================
    PutOperation *-- ReadableStreamChannel : reads from (upload mode)
    PutOperation *-- ByteBufferAsyncWritableChannel : buffers data in
    PutOperation --> ByteBuf : reads buffers
    ReadableStreamChannel --> ByteBufferAsyncWritableChannel : writes to
    ByteBufferAsyncWritableChannel --> ByteBuf : manages

    %% ========================================
    %% RELATIONSHIPS - Internal Classes
    %% ========================================
    PutOperation *-- "0..*" PutChunk : manages
    PutOperation *-- "1" MetadataPutChunk : creates
    MetadataPutChunk --|> PutChunk : extends
    PutChunk --> ChunkState : has state
    PutChunk --> ByteBuf : holds data in
    PutChunk --> BlobId : generates
    PutChunk --> OperationTracker : tracks requests with

    %% ========================================
    %% RELATIONSHIPS - Metadata Tracking
    %% ========================================
    MetadataPutChunk --> ChunkInfo : processes (stitch mode)
    PutOperation --> ChunkInfo : receives list of (stitch mode)
    MetadataPutChunk --> BlobId : collects chunk IDs

    %% ========================================
    %% RELATIONSHIPS - Request/Response Flow
    %% ========================================
    PutChunk --> RequestInfo : creates
    PutChunk --> PutRequest : creates
    RequestInfo --> PutRequest : contains
    PutChunk --> ResponseInfo : receives
    ResponseInfo --> PutResponse : contains
    PutChunk --> ReplicaId : sends to

    %% ========================================
    %% RELATIONSHIPS - Cluster Topology
    %% ========================================
    PutChunk --> PartitionId : selects
    ClusterMap --> PartitionId : provides
    PartitionId --> ReplicaId : contains
    OperationTracker --> ReplicaId : tracks

    %% ========================================
    %% RELATIONSHIPS - Encryption
    %% ========================================
    PutChunk --> EncryptJob : creates
    EncryptJob --> CryptoJobHandler : submitted to
    EncryptJob --> KeyManagementService : uses key from

    %% ========================================
    %% RELATIONSHIPS - Data Objects
    %% ========================================
    PutOperation --> BlobProperties : uses
    PutOperation --> PutBlobOptions : configured by
    PutOperation --> BlobId : produces final
    PutChunk --> BlobProperties : creates chunk-specific
    PutRequest --> BlobProperties : includes

    %% ========================================
    %% RELATIONSHIPS - Completion
    %% ========================================
    PutOperation --> FutureResult : completes
    PutOperation --> Callback : invokes
    PutOperation --> RouterCallback : notifies

    %% ========================================
    %% THREADING MODEL NOTES
    %% ========================================
    note for PutOperation "Threading Model:\n• ChunkFiller Thread: Executes fillChunks() - fills chunks with data from channel\n• Crypto Thread Pool: Executes encryption jobs via CryptoJobHandler\n• Main Thread: Executes poll() to generate requests and handleResponse() to process responses\n• All threads coordinate via volatile flags and ConcurrentLinkedQueue"

    note for PutChunk "Chunk State Machine:\nFree → Building → [AwaitingBlobTypeResolution] → [Encrypting] → Ready → Complete\n\nFree: Available for reuse\nBuilding: Being filled with data\nAwaitingBlobTypeResolution: First chunk waiting to determine if blob is simple or composite\nEncrypting: Encryption in progress (if enabled)\nReady: Ready to send to servers\nComplete: Successfully uploaded to sufficient replicas"

    note for MetadataPutChunk "Metadata Chunk Purpose:\n• Created for composite blobs (>1 data chunk)\n• Contains serialized list of data chunk BlobIds and sizes\n• Uses metadata content version (V2 or V3) from RouterConfig\n• Can use reserved metadata blob ID for optimization\n• Final blob ID is the metadata chunk's blob ID"

    note for ReadableStreamChannel "Data Flow (Upload Mode):\n1. channel.readInto(chunkFillerChannel, callback)\n2. ChunkFiller thread calls chunkFillerChannel.getNextByteBuf()\n3. ByteBuf filled into PutChunk via fillFrom()\n4. When chunk full, compress (optional) → encrypt (optional) → send\n5. Repeat until channel exhausted"

    note for PutOperation "Operation Modes:\n\nDirect Upload:\n• channel != null, chunksToStitch == null\n• Reads data from ReadableStreamChannel\n• Splits into chunks if > maxPutChunkSize\n• Creates metadata chunk if composite\n\nStitch Upload:\n• channel == null, chunksToStitch != null\n• Validates existing chunk BlobIds\n• Only creates metadata chunk\n• No data transfer, just metadata assembly"
```

## Key Relationships Summary

### Entry Points (Who Calls PutOperation)
1. **NonBlockingRouter** receives user requests for `putBlob()` or `stitchBlob()`
2. **OperationController** coordinates with PutManager
3. **PutManager** creates PutOperation via factory methods (`forUpload()` or `forStitching()`)
4. **PutManager** manages lifecycle:
   - ChunkFillerThread calls `fillChunks()` to read data
   - Main thread calls `poll()` to generate requests
   - Main thread calls `handleResponse()` to process responses

### Channel Usage (How Data Flows)
1. **For Direct Uploads:**
   - `ReadableStreamChannel` (input) → `ByteBufferAsyncWritableChannel` (buffer) → `ByteBuf` (chunks) → `PutChunk` (processing)
   - Data flows asynchronously through callbacks

2. **For Stitch Operations:**
   - No channels used
   - Only processes existing `ChunkInfo` objects into metadata

### Internal Classes (How PutOperation Works Internally)
1. **PutChunk:**
   - Represents a single data chunk to upload
   - Manages state transitions (Free → Building → Encrypting → Ready → Complete)
   - Handles compression, encryption, request generation, and response processing
   - Reusable: after completion, returns to Free state

2. **MetadataPutChunk:**
   - Extends PutChunk
   - Special chunk containing list of data chunk IDs
   - Only sent for composite blobs (multiple chunks)
   - Final blob ID comes from this chunk

3. **ChunkState:**
   - Enum tracking chunk lifecycle
   - Ensures proper state transitions
   - Coordinates between threads

### Key Dependencies
- **RouterConfig:** All configuration parameters
- **ClusterMap:** Partition selection and topology
- **KeyManagementService + CryptoService + CryptoJobHandler:** Encryption pipeline
- **CompressionService:** Optional compression
- **OperationTracker:** Per-chunk replica tracking and success determination
- **NotificationSystem:** Blob creation notifications
- **QuotaChargeCallback:** Resource quota management

### Threading Model
- **ChunkFiller Thread (PutManager):** Calls `fillChunks()` to read from channel into chunks
- **Crypto Thread Pool:** Executes encryption jobs asynchronously
- **Main Thread (PutManager):** Calls `poll()` and `handleResponse()`
- Coordination via `volatile` flags and `ConcurrentLinkedQueue`

### Request/Response Flow
1. `PutChunk.poll()` → creates `RequestInfo` with `PutRequest`
2. Network layer sends to `ReplicaId`
3. `ResponseInfo` with `PutResponse` returns
4. `PutChunk.handleResponse()` processes result
5. `OperationTracker` determines success/failure
6. `checkAndMaybeComplete()` completes chunk or retries (slipped put)

## Operational Flows

### Direct Upload Flow
```
startOperation()
  → startReadingFromChannel()
  → channel.readInto(chunkFillerChannel)

fillChunks() [ChunkFiller Thread]
  → chunkFillerChannel.getNextByteBuf()
  → chunkToFill.fillFrom(buf)
  → onFillComplete()
  → compressChunk() [optional]
  → encryptChunk() [optional]
  → prepareForSending()

poll() [Main Thread]
  → chunk.poll()
  → chunk.fetchRequests()
  → creates RequestInfo

handleResponse() [Main Thread]
  → chunk.handleResponse()
  → verifyCRC()
  → checkAndMaybeComplete()
  → onChunkOperationComplete()

[When all chunks complete]
  → metadataPutChunk.buildMetadataContent()
  → metadataPutChunk.poll()
  → setOperationCompleted()
  → maybeNotifyForBlobCreation()
```

### Stitch Upload Flow
```
startOperation()
  → processChunksToStitch()
  → validate chunks
  → metadataPutChunk.addChunkId() for each
  → mark chunkFillingCompletedSuccessfully

poll() [Main Thread]
  → metadataPutChunk.poll()
  → creates RequestInfo

handleResponse() [Main Thread]
  → metadataPutChunk.handleResponse()
  → setOperationCompleted()
  → maybeNotifyForBlobCreation()
```

