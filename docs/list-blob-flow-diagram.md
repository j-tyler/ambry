# List Blob Request Flow

This document details the class/method path from the HTTP router through service logic for a list blob request in Ambry.

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
    participant NamedBlobDb
    participant MySqlNamedBlobDb
    participant Database
    participant Page
    participant NamedBlobListEntry

    %% HTTP Request Entry
    Client->>FrontendRestRequestService: GET /named/{account}/{container}?prefix=...&page=...

    %% Routing Decision
    FrontendRestRequestService->>FrontendRestRequestService: handleGet(restRequest, restResponseChannel)
    Note over FrontendRestRequestService: Line 304-350

    FrontendRestRequestService->>NamedBlobPath: parse(requestPath, args)
    Note over NamedBlobPath: Extracts accountName, containerName,<br/>blobNamePrefix, pageToken
    NamedBlobPath-->>FrontendRestRequestService: namedBlobPath (with blobName == null)

    Note over FrontendRestRequestService: Route condition (line 335-338):<br/>matchesOperation("named") &&<br/>blobName == null → list request

    %% Handler Invocation
    FrontendRestRequestService->>NamedBlobListHandler: handle(restRequest, restResponseChannel, callback)
    Note over NamedBlobListHandler: Line 80-83

    NamedBlobListHandler->>CallbackChain: new CallbackChain(...)
    CallbackChain->>CallbackChain: start()
    Note over CallbackChain: Line 110-125:<br/>- Inject metrics<br/>- Inject account/container<br/>- Check namedBlobDb != null

    %% Security Processing
    CallbackChain->>SecurityService: processRequest(restRequest, callback)
    SecurityService-->>CallbackChain: securityProcessRequestCallback()
    Note over CallbackChain: Line 132-136

    CallbackChain->>SecurityService: postProcessRequest(restRequest, callback)
    SecurityService-->>CallbackChain: securityPostProcessRequestCallback()
    Note over CallbackChain: Line 142-156

    %% Parse Parameters & Start List
    CallbackChain->>NamedBlobPath: parse(requestPath, args)
    NamedBlobPath-->>CallbackChain: accountName, containerName, prefix, pageToken

    CallbackChain->>CallbackChain: listRecursively(accountName, containerName, prefix, pageToken, maxKey, groupDirs)
    Note over CallbackChain: Line 190-198: Top-level recursive aggregation

    %% Recursive List Internal
    rect rgb(240, 248, 255)
        Note over CallbackChain,MySqlNamedBlobDb: Recursive Pagination Loop

        CallbackChain->>CallbackChain: listRecursivelyInternal(...)
        Note over CallbackChain: Line 218-254

        CallbackChain->>NamedBlobDb: list(accountName, containerName, prefix, pageToken, maxKey)
        Note over NamedBlobDb: Interface method - Line 70

        NamedBlobDb->>MySqlNamedBlobDb: list(...)
        Note over MySqlNamedBlobDb: Line 312-322

        MySqlNamedBlobDb->>MySqlNamedBlobDb: executeTransactionAsync(...)

        MySqlNamedBlobDb->>MySqlNamedBlobDb: run_list_v2(...)
        Note over MySqlNamedBlobDb: Line 619-662:<br/>Constructs SQL query

        MySqlNamedBlobDb->>Database: PreparedStatement.executeQuery()
        Note over Database: SQL filters by:<br/>- account_id, container_id<br/>- blob_state = READY<br/>- prefix LIKE pattern<br/>- not deleted/expired

        Database-->>MySqlNamedBlobDb: ResultSet

        loop For each row in ResultSet
            MySqlNamedBlobDb->>MySqlNamedBlobDb: Build NamedBlobRecord
            Note over MySqlNamedBlobDb: Line 641-655:<br/>blobName, blobId, version,<br/>deletionTime, blobSize, modifiedTime
        end

        MySqlNamedBlobDb->>Page: new Page(entries, nextContinuationToken)
        Note over Page: Line 44-47

        Page-->>MySqlNamedBlobDb: Page<NamedBlobRecord>
        MySqlNamedBlobDb-->>NamedBlobDb: Page<NamedBlobRecord>
        NamedBlobDb-->>CallbackChain: currentPage

        CallbackChain->>CallbackChain: mergePageResults(aggregatedPage, currentPage, ...)
        Note over CallbackChain: Line 276-310:<br/>- Accumulates entries<br/>- Handles directory grouping<br/>- Sets nextPageToken

        alt entries.size() < maxKey && tokenToUse != null
            CallbackChain->>CallbackChain: listRecursivelyInternal(...) [recurse]
        else entries.size() >= maxKey OR tokenToUse == null
            CallbackChain-->>CallbackChain: Return aggregated Page
        end
    end

    %% Response Serialization
    CallbackChain->>CallbackChain: listBlobsCallback()
    Note over CallbackChain: Line 162-171

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

## Class/Method Reference Table

| Step | File | Class | Method | Line |
|------|------|-------|--------|------|
| 1 | `ambry-frontend/.../FrontendRestRequestService.java` | `FrontendRestRequestService` | `handleGet()` | 304 |
| 2 | `ambry-api/.../frontend/NamedBlobPath.java` | `NamedBlobPath` | `parse()` | 54 |
| 3 | `ambry-frontend/.../NamedBlobListHandler.java` | `NamedBlobListHandler` | `handle()` | 80 |
| 4 | `ambry-frontend/.../NamedBlobListHandler.java` | `CallbackChain` | `start()` | 110 |
| 5 | `ambry-frontend/.../NamedBlobListHandler.java` | `CallbackChain` | `securityProcessRequestCallback()` | 132 |
| 6 | `ambry-frontend/.../NamedBlobListHandler.java` | `CallbackChain` | `securityPostProcessRequestCallback()` | 142 |
| 7 | `ambry-frontend/.../NamedBlobListHandler.java` | `CallbackChain` | `listRecursively()` | 190 |
| 8 | `ambry-frontend/.../NamedBlobListHandler.java` | `CallbackChain` | `listRecursivelyInternal()` | 218 |
| 9 | `ambry-api/.../named/NamedBlobDb.java` | `NamedBlobDb` | `list()` | 70 |
| 10 | `ambry-named-mysql/.../MySqlNamedBlobDb.java` | `MySqlNamedBlobDb` | `list()` | 312 |
| 11 | `ambry-named-mysql/.../MySqlNamedBlobDb.java` | `MySqlNamedBlobDb` | `run_list_v2()` | 619 |
| 12 | `ambry-frontend/.../NamedBlobListHandler.java` | `CallbackChain` | `mergePageResults()` | 276 |
| 13 | `ambry-frontend/.../NamedBlobListHandler.java` | `CallbackChain` | `listBlobsCallback()` | 162 |
| 14 | `ambry-api/.../frontend/Page.java` | `Page<T>` | `toJson()` | 68 |
| 15 | `ambry-api/.../frontend/NamedBlobListEntry.java` | `NamedBlobListEntry` | `toJson()` | ~106 |

## Detailed Flow Description

### 1. HTTP Entry Point (`FrontendRestRequestService.handleGet()`)

```java
// Line 335-338 in FrontendRestRequestService.java
} else if (requestPath.matchesOperation(Operations.NAMED_BLOB)
    && NamedBlobPath.parse(requestPath, restRequest.getArgs()).getBlobName() == null) {
  namedBlobListHandler.handle(restRequest, restResponseChannel, callback);
}
```

The routing logic checks:
- Request matches `/named/...` operation
- `blobName` is `null` (indicating a list request, not a single blob fetch)

### 2. Handler Initialization (`NamedBlobListHandler.handle()`)

```java
// Line 80-83
public void handle(RestRequest restRequest, RestResponseChannel restResponseChannel,
    Callback<ReadableStreamChannel> callback) {
  new CallbackChain(restRequest, restResponseChannel, callback).start();
}
```

### 3. Callback Chain Execution

The `CallbackChain` inner class orchestrates the async processing:

1. **`start()`** (line 110): Initializes metrics, injects account/container, validates namedBlobDb
2. **`securityProcessRequestCallback()`** (line 132): Pre-processes security
3. **`securityPostProcessRequestCallback()`** (line 142): Post-processes security, then initiates listing

### 4. Recursive List Aggregation (`listRecursively()`)

```java
// Line 190-198
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

### 5. Database Query (`MySqlNamedBlobDb.run_list_v2()`)

```java
// Line 619-662
private Page<NamedBlobRecord> run_list_v2(...) {
  // Constructs SQL query with filters:
  // - account_id, container_id
  // - blob_state = READY
  // - blob_name LIKE prefix%
  // - (deleted_ts IS NULL OR deleted_ts > NOW())

  ResultSet resultSet = statement.executeQuery();
  List<NamedBlobRecord> entries = new ArrayList<>();
  while (resultSet.next()) {
    // Build NamedBlobRecord from each row
    entries.add(new NamedBlobRecord(...));
  }
  return new Page<>(entries, nextContinuationToken);
}
```

### 6. Response Serialization (`listBlobsCallback()`)

```java
// Line 162-171
private Callback<Page<NamedBlobRecord>> listBlobsCallback() {
  return buildCallback(frontendMetrics.listDbLookupMetrics, page -> {
    ReadableStreamChannel channel = serializeJsonToChannel(
        page.toJson(record -> new NamedBlobListEntry(record).toJson())
    );
    // Set response headers and return
    finalCallback.onCompletion(channel, null);
  }, uri, LOGGER, finalCallback);
}
```

---

## Introspection Point

The **complete list is available for introspection** at the following point:

### Location: `NamedBlobListHandler.java`, line 163

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

1. **After `listRecursively()` completes** (line 150-154): The `CompletableFuture<Page<NamedBlobRecord>>` resolves with the complete page.

2. **Inside `mergePageResults()`** (line 276-310): Observe the accumulation process and intermediate states.

3. **After `run_list_v2()`** (line 656): Each individual database page before aggregation.

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
