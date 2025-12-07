# List Blob Request Flow

This document details the class/method path from the HTTP router through service logic for a list blob request in Ambry.

## Architecture Overview

All list requests flow through a **single path** to a `CompositeNamedBlobDb`. The composite internally decides whether to read from MySQL, S3, or both based on the migration phase. This design ensures:
- Consistent API behavior regardless of backend
- Centralized migration logic
- Async verification without external coordination

```
┌─────────────────────────────────────────────────────────────────────┐
│                        API Layer                                     │
│  FrontendRestRequestService → NamedBlobListHandler → CallbackChain  │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     NamedBlobDb Interface                            │
│                              │                                       │
│                              ▼                                       │
│                   CompositeNamedBlobDb                               │
│         ┌────────────────────┼────────────────────┐                 │
│         │                    │                    │                 │
│         ▼                    ▼                    ▼                 │
│   MySqlNamedBlobDb    S3NamedBlobDb    VerificationService          │
│         │                    │                    │                 │
│         ▼                    ▼                    │                 │
│      MySQL DB            S3 Bucket         (async comparison)       │
└─────────────────────────────────────────────────────────────────────┘
```

## Mermaid Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant FrontendRestRequestService
    participant NamedBlobPath
    participant NamedBlobListHandler
    participant CallbackChain
    participant SecurityService
    participant CompositeNamedBlobDb
    participant MySqlNamedBlobDb
    participant S3NamedBlobDb
    participant Database
    participant S3
    participant Page
    participant NamedBlobListEntry

    %% HTTP Request Entry
    Client->>FrontendRestRequestService: GET /named/{account}/{container}?prefix=...&page=...

    %% Routing Decision
    FrontendRestRequestService->>FrontendRestRequestService: handleGet(restRequest, restResponseChannel)

    FrontendRestRequestService->>NamedBlobPath: parse(requestPath, args)
    Note over NamedBlobPath: Extracts accountName, containerName,<br/>blobNamePrefix, pageToken
    NamedBlobPath-->>FrontendRestRequestService: namedBlobPath (with blobName == null)

    Note over FrontendRestRequestService: Route condition:<br/>matchesOperation("named") &&<br/>blobName == null → list request

    %% Handler Invocation
    FrontendRestRequestService->>NamedBlobListHandler: handle(restRequest, restResponseChannel, callback)

    NamedBlobListHandler->>CallbackChain: new CallbackChain(...)
    CallbackChain->>CallbackChain: start()
    Note over CallbackChain: - Inject metrics<br/>- Inject account/container<br/>- Check namedBlobDb != null

    %% Security Processing
    CallbackChain->>SecurityService: processRequest(restRequest, callback)
    SecurityService-->>CallbackChain: securityProcessRequestCallback()

    CallbackChain->>SecurityService: postProcessRequest(restRequest, callback)
    SecurityService-->>CallbackChain: securityPostProcessRequestCallback()

    %% Parse Parameters & Start List
    CallbackChain->>NamedBlobPath: parse(requestPath, args)
    NamedBlobPath-->>CallbackChain: accountName, containerName, prefix, pageToken

    CallbackChain->>CallbackChain: listRecursively(...)
    Note over CallbackChain: Top-level recursive aggregation

    %% Single Path to Composite DB
    rect rgb(240, 248, 255)
        Note over CallbackChain,S3: Single Path Through CompositeNamedBlobDb

        CallbackChain->>CompositeNamedBlobDb: list(accountName, containerName, prefix, pageToken, maxKey)
        Note over CompositeNamedBlobDb: Implements NamedBlobDb interface

        %% Migration Phase Decision
        alt Migration Phase: MySQL Only (pre-migration)
            CompositeNamedBlobDb->>MySqlNamedBlobDb: list(...)
            MySqlNamedBlobDb->>Database: SQL Query
            Database-->>MySqlNamedBlobDb: ResultSet
            MySqlNamedBlobDb-->>CompositeNamedBlobDb: Page<NamedBlobRecord>

        else Migration Phase: Dual Read with Verification
            par Read from both backends
                CompositeNamedBlobDb->>MySqlNamedBlobDb: list(...)
                MySqlNamedBlobDb->>Database: SQL Query
                Database-->>MySqlNamedBlobDb: ResultSet
                MySqlNamedBlobDb-->>CompositeNamedBlobDb: mysqlPage
            and
                CompositeNamedBlobDb->>S3NamedBlobDb: list(...)
                S3NamedBlobDb->>S3: ListObjectsV2
                S3-->>S3NamedBlobDb: ListObjectsV2Response
                S3NamedBlobDb-->>CompositeNamedBlobDb: s3Page
            end

            Note over CompositeNamedBlobDb: Return MySQL result to caller<br/>(source of truth during migration)

            CompositeNamedBlobDb->>CompositeNamedBlobDb: scheduleAsyncVerification(mysqlPage, s3Page)
            Note over CompositeNamedBlobDb: Async: Compare blobName, blobSize<br/>Log discrepancies, emit metrics

        else Migration Phase: S3 Only (post-migration)
            CompositeNamedBlobDb->>S3NamedBlobDb: list(...)
            S3NamedBlobDb->>S3: ListObjectsV2
            S3-->>S3NamedBlobDb: ListObjectsV2Response
            S3NamedBlobDb-->>CompositeNamedBlobDb: Page<NamedBlobRecord>
        end

        CompositeNamedBlobDb-->>CallbackChain: Page<NamedBlobRecord>
    end

    %% Recursive aggregation continues if needed
    CallbackChain->>CallbackChain: mergePageResults(aggregatedPage, currentPage, ...)
    Note over CallbackChain: - Accumulates entries<br/>- Handles directory grouping<br/>- Sets nextPageToken

    alt entries.size() < maxKey && tokenToUse != null
        CallbackChain->>CallbackChain: listRecursivelyInternal(...) [recurse]
    else entries.size() >= maxKey OR tokenToUse == null
        CallbackChain-->>CallbackChain: Return aggregated Page
    end

    %% Response Serialization
    CallbackChain->>CallbackChain: listBlobsCallback()

    rect rgb(255, 250, 205)
        Note over CallbackChain,Page: ★ INTROSPECTION POINT ★<br/>Page<NamedBlobRecord> is complete here<br/>and can be inspected before serialization
    end

    CallbackChain->>Page: toJson(record -> NamedBlobListEntry.toJson())

    loop For each NamedBlobRecord
        Page->>NamedBlobListEntry: new NamedBlobListEntry(record)
        NamedBlobListEntry->>NamedBlobListEntry: toJson()
        Note over NamedBlobListEntry: Returns JSONObject with:<br/>blobName, expirationTimeMs,<br/>blobSize, modifiedTimeMs, isDirectory
    end

    Page-->>CallbackChain: JSONObject

    CallbackChain->>CallbackChain: serializeJsonToChannel(json)
    CallbackChain->>FrontendRestRequestService: Set headers (Date, Content-Type, Content-Length)
    CallbackChain->>Client: ReadableStreamChannel with JSON response

    Note over Client: Response JSON:<br/>{"entries": [...], "nextPageToken": "..."}
```

## CompositeNamedBlobDb Architecture

The `CompositeNamedBlobDb` is the **central component** that implements the `NamedBlobDb` interface and encapsulates all migration logic.

### Responsibilities

| Responsibility | Description |
|---------------|-------------|
| Backend Selection | Decides which backend(s) to query based on migration phase configuration |
| Result Routing | Returns results from the appropriate backend (MySQL during migration, S3 after) |
| Async Verification | Schedules background comparison of MySQL vs S3 results |
| Metric Emission | Tracks latency, discrepancies, and verification results |

### Migration Phases

```
┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│  Phase 1: MySQL  │───▶│ Phase 2: Dual    │───▶│  Phase 3: S3     │
│     Only         │    │ Read + Verify    │    │     Only         │
└──────────────────┘    └──────────────────┘    └──────────────────┘
       │                        │                        │
       ▼                        ▼                        ▼
  Read: MySQL             Read: Both               Read: S3
  Return: MySQL           Return: MySQL            Return: S3
  Verify: None            Verify: Async            Verify: None
```

### Verification Strategy

During Phase 2 (Dual Read), the composite performs async verification:

1. **Time-based sampling**: Verify at most N requests per time period
2. **Field comparison**: Compare only `blobName` and `blobSize`
3. **Non-blocking**: Verification runs in background thread
4. **Metrics**: Emit counters for matches/mismatches

## Class/Method Reference Table

| Step | Class | Method | Description |
|------|-------|--------|-------------|
| 1 | `FrontendRestRequestService` | `handleGet()` | HTTP entry point |
| 2 | `NamedBlobPath` | `parse()` | Extract request parameters |
| 3 | `NamedBlobListHandler` | `handle()` | Handler invocation |
| 4 | `CallbackChain` | `start()` | Initialize async chain |
| 5 | `CallbackChain` | `securityProcessRequestCallback()` | Security pre-processing |
| 6 | `CallbackChain` | `securityPostProcessRequestCallback()` | Security post-processing |
| 7 | `CallbackChain` | `listRecursively()` | Start recursive aggregation |
| 8 | `CompositeNamedBlobDb` | `list()` | **Single entry point** - routes to backend(s) |
| 9 | `MySqlNamedBlobDb` | `list()` | MySQL implementation |
| 10 | `S3NamedBlobDb` | `list()` | S3 implementation |
| 11 | `CompositeNamedBlobDb` | `scheduleAsyncVerification()` | Background comparison |
| 12 | `CallbackChain` | `mergePageResults()` | Aggregate pages |
| 13 | `CallbackChain` | `listBlobsCallback()` | Serialize response |
| 14 | `Page<T>` | `toJson()` | Convert to JSON |
| 15 | `NamedBlobListEntry` | `toJson()` | Entry serialization |

## Detailed Flow Description

### 1. HTTP Entry Point (`FrontendRestRequestService.handleGet()`)

The routing logic checks:
- Request matches `/named/...` operation
- `blobName` is `null` (indicating a list request, not a single blob fetch)

```java
} else if (requestPath.matchesOperation(Operations.NAMED_BLOB)
    && NamedBlobPath.parse(requestPath, restRequest.getArgs()).getBlobName() == null) {
  namedBlobListHandler.handle(restRequest, restResponseChannel, callback);
}
```

### 2. Handler Initialization (`NamedBlobListHandler.handle()`)

```java
public void handle(RestRequest restRequest, RestResponseChannel restResponseChannel,
    Callback<ReadableStreamChannel> callback) {
  new CallbackChain(restRequest, restResponseChannel, callback).start();
}
```

### 3. Callback Chain Execution

The `CallbackChain` inner class orchestrates the async processing:

1. **`start()`**: Initializes metrics, injects account/container, validates namedBlobDb
2. **`securityProcessRequestCallback()`**: Pre-processes security
3. **`securityPostProcessRequestCallback()`**: Post-processes security, then initiates listing

### 4. CompositeNamedBlobDb.list()

The composite is the **single implementation** of `NamedBlobDb` injected into the handler. It encapsulates all backend routing:

```java
public class CompositeNamedBlobDb implements NamedBlobDb {
    private final MySqlNamedBlobDb mysqlDb;
    private final S3NamedBlobDb s3Db;
    private final MigrationPhase phase;
    private final VerificationService verificationService;

    @Override
    public CompletableFuture<Page<NamedBlobRecord>> list(
            String accountName, String containerName,
            String blobNamePrefix, String pageToken, Integer maxKeys) {

        switch (phase) {
            case MYSQL_ONLY:
                return mysqlDb.list(accountName, containerName, blobNamePrefix, pageToken, maxKeys);

            case DUAL_READ_VERIFY:
                CompletableFuture<Page<NamedBlobRecord>> mysqlFuture =
                    mysqlDb.list(accountName, containerName, blobNamePrefix, pageToken, maxKeys);
                CompletableFuture<Page<NamedBlobRecord>> s3Future =
                    s3Db.list(accountName, containerName, blobNamePrefix, pageToken, maxKeys);

                return mysqlFuture.thenCompose(mysqlPage -> {
                    s3Future.thenAccept(s3Page ->
                        verificationService.scheduleAsyncVerification(mysqlPage, s3Page));
                    return CompletableFuture.completedFuture(mysqlPage);
                });

            case S3_ONLY:
                return s3Db.list(accountName, containerName, blobNamePrefix, pageToken, maxKeys);
        }
    }
}
```

### 5. Backend Implementations

#### MySqlNamedBlobDb.list()

Queries MySQL with filters:
- `account_id`, `container_id`
- `blob_state = READY`
- `blob_name LIKE prefix%`
- `(deleted_ts IS NULL OR deleted_ts > NOW())`

#### S3NamedBlobDb.list()

Calls S3 ListObjectsV2:
- Uses prefix: `{accountId}/{containerId}/{blobNamePrefix}`
- Returns all objects (no metadata filtering possible)
- See [S3 Named Blob DB Specification](s3-named-blob-db-list-specification.md) for behavioral differences

### 6. Recursive Aggregation (`listRecursively()`)

```java
public CompletableFuture<Page<NamedBlobRecord>> listRecursively(
    String accountName, String containerName, String blobNamePrefix,
    String pageToken, Integer maxKey, boolean groupDirectories) {

  Page<NamedBlobRecord> initialAggregatedPage = new Page<>(new ArrayList<>(), null);
  return listRecursivelyInternal(...).thenApply(
      finalPage -> new Page<>(finalPage.getEntries(), finalPage.getNextPageToken()));
}
```

This recursively fetches and merges pages until:
- `maxKey` entries are accumulated, OR
- No more pages exist (`nextPageToken == null`)

### 7. Response Serialization (`listBlobsCallback()`)

```java
private Callback<Page<NamedBlobRecord>> listBlobsCallback() {
  return buildCallback(frontendMetrics.listDbLookupMetrics, page -> {
    ReadableStreamChannel channel = serializeJsonToChannel(
        page.toJson(record -> new NamedBlobListEntry(record).toJson())
    );
    finalCallback.onCompletion(channel, null);
  }, uri, LOGGER, finalCallback);
}
```

---

## Introspection Point

The **complete list is available for introspection** at the following point:

### Location: `NamedBlobListHandler.java`, in `listBlobsCallback()`

```java
private Callback<Page<NamedBlobRecord>> listBlobsCallback() {
  return buildCallback(frontendMetrics.listDbLookupMetrics, page -> {
    // ★ INTROSPECTION POINT ★
    // The 'page' parameter contains the complete Page<NamedBlobRecord>
    // with all aggregated entries from the recursive listing.

    // At this point you can:
    // 1. Inspect page.getEntries() - List<NamedBlobRecord>
    // 2. Verify the list contents
    // 3. Check page.getNextPageToken() for pagination state

    ReadableStreamChannel channel = serializeJsonToChannel(
        page.toJson(record -> new NamedBlobListEntry(record).toJson())
    );
    ...
  }, uri, LOGGER, finalCallback);
}
```

### What's Available at Introspection Point

The `Page<NamedBlobRecord>` object contains:

| Field | Type | Description |
|-------|------|-------------|
| `entries` | `List<NamedBlobRecord>` | Complete list of blob records |
| `nextPageToken` | `String` | `null` if complete, or token for next page |

Each `NamedBlobRecord` contains:

| Field | Type | Description |
|-------|------|-------------|
| `accountName` | `String` | Account name |
| `containerName` | `String` | Container name |
| `blobName` | `String` | Full blob name/path |
| `blobId` | `String` | Ambry blob ID (Base64 encoded) |
| `expirationTimeMs` | `long` | Expiration timestamp (-1 for infinite) |
| `version` | `long` | Version number |
| `blobSize` | `long` | Size in bytes |
| `modifiedTimeMs` | `long` | Last modified timestamp |
| `isDirectory` | `boolean` | Whether this is a virtual directory |

### Alternative Introspection Points

1. **Inside `CompositeNamedBlobDb.list()`**: Observe backend selection and verification scheduling
2. **After `listRecursively()` completes**: The `CompletableFuture<Page<NamedBlobRecord>>` resolves with the complete page
3. **Inside `mergePageResults()`**: Observe the accumulation process and intermediate states

---

## Response JSON Structure

```json
{
  "entries": [
    {
      "blobName": "path/to/file.txt",
      "expirationTimeMs": -1,
      "blobSize": 1024,
      "modifiedTimeMs": 1701625600000,
      "isDirectory": false
    },
    {
      "blobName": "path/to/folder/",
      "expirationTimeMs": -1,
      "blobSize": 0,
      "modifiedTimeMs": 0,
      "isDirectory": true
    }
  ],
  "nextPageToken": "path/to/next/blob"
}
```

Where `nextPageToken` is `null` if no more pages exist.

---

## Related Documentation

- [S3 Named Blob DB List Specification](s3-named-blob-db-list-specification.md) - Detailed spec for S3 implementation
- [Ambry Named Blob Architecture Guide](ambry-named-blob-architecture-guide.md) - System architecture overview
