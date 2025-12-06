# Ambry Named Blob Architecture Guide

**Purpose**: Implementation reference for AI agents working on S3 migration
**Version**: 1.0
**Date**: 2025-12-06

---

## Executive Summary

This document maps the Ambry named blob system architecture for AI agents implementing S3 migration. The key insight is that Ambry has **two distinct data paths**:

1. **Router Path**: Handles blob content storage/retrieval via Ambry storage servers
2. **NamedBlobDb Path**: Handles metadata (name→ID mappings) via MySQL database

For S3 migration, both paths must be addressed, but they have different integration points and strategies.

---

## 1. System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              HTTP Layer                                          │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                    FrontendRestRequestService                            │    │
│  │                         handleGet() / handlePut() / handleDelete()       │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
│                                      │                                           │
│                    ┌─────────────────┴─────────────────┐                        │
│                    ▼                                   ▼                        │
│  ┌──────────────────────────────┐    ┌──────────────────────────────────────┐  │
│  │     Metadata Handlers        │    │        Content Handlers              │  │
│  │  ┌────────────────────────┐  │    │  ┌────────────────────────────────┐  │  │
│  │  │ NamedBlobListHandler   │  │    │  │      GetBlobHandler            │  │  │
│  │  │ (list operations)      │  │    │  │  (get blob content)            │  │  │
│  │  └────────────────────────┘  │    │  └────────────────────────────────┘  │  │
│  │                              │    │  ┌────────────────────────────────┐  │  │
│  │                              │    │  │    NamedBlobPutHandler         │  │  │
│  │                              │    │  │  (put blob content + metadata) │  │  │
│  │                              │    │  └────────────────────────────────┘  │  │
│  │                              │    │  ┌────────────────────────────────┐  │  │
│  │                              │    │  │   NamedBlobDeleteHandler       │  │  │
│  │                              │    │  │  (delete content + metadata)   │  │  │
│  │                              │    │  └────────────────────────────────┘  │  │
│  └──────────────┬───────────────┘    └──────────────────┬───────────────────┘  │
│                 │                                       │                       │
└─────────────────┼───────────────────────────────────────┼───────────────────────┘
                  │                                       │
                  │                                       │
    ┌─────────────▼─────────────┐         ┌──────────────▼──────────────┐
    │       NamedBlobDb         │         │          Router             │
    │       (Interface)         │         │        (Interface)          │
    │                           │         │                             │
    │  • list()                 │         │  • getBlob()                │
    │  • get()   [name→ID]      │         │  • putBlob()                │
    │  • put()   [ID mapping]   │         │  • deleteBlob()             │
    │  • delete() [soft delete] │         │  • updateBlobTtl()          │
    └─────────────┬─────────────┘         └──────────────┬──────────────┘
                  │                                       │
    ┌─────────────▼─────────────┐         ┌──────────────▼──────────────┐
    │     MySqlNamedBlobDb      │         │     NonBlockingRouter       │
    │                           │         │                             │
    │  ┌─────────────────────┐  │         │  ┌───────────────────────┐  │
    │  │  named_blobs_v2     │  │         │  │  OperationController  │  │
    │  │  table in MySQL     │  │         │  │  GetManager           │  │
    │  └─────────────────────┘  │         │  │  PutManager           │  │
    │                           │         │  │  DeleteManager        │  │
    └─────────────┬─────────────┘         └──────────────┬──────────────┘
                  │                                       │
                  ▼                                       ▼
    ┌─────────────────────────┐           ┌─────────────────────────────┐
    │      MySQL Database     │           │   Ambry Storage Servers     │
    │                         │           │                             │
    │  Stores:                │           │  Stores:                    │
    │  • blob_name            │           │  • Actual blob bytes        │
    │  • blob_id (reference)──┼──────────►│  • Blob metadata            │
    │  • version              │           │  • Replicated across DCs    │
    │  • blob_state           │           │                             │
    │  • deleted_ts           │           │                             │
    │  • blob_size            │           │                             │
    │  • modified_ts          │           │                             │
    └─────────────────────────┘           └─────────────────────────────┘
           METADATA                              CONTENT
```

---

## 2. Operation Flow Maps

### 2.1 LIST Operation

**Path**: Metadata Only (NamedBlobDb)

```
GET /named/{account}/{container}?prefix=...&page=...&maxKeys=...

┌─────────────────────────────────────────────────────────────────────────┐
│ FrontendRestRequestService.handleGet()                     [line 304]   │
│                                                                         │
│   Routing Decision (line 335-338):                                      │
│   if (matchesOperation("named") && blobName == null)                    │
│       → NamedBlobListHandler                                            │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ NamedBlobListHandler.handle()                              [line 80]    │
│                                                                         │
│   1. CallbackChain.start()                                 [line 110]   │
│   2. securityService.processRequest()                                   │
│   3. securityService.postProcessRequest()                               │
│   4. Parse NamedBlobPath (prefix, pageToken, maxKeys)      [line 144]   │
│   5. listRecursively()                                     [line 190]   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ NamedBlobDb.list()                                         [line 70]    │
│                                                                         │
│   Interface method - implementation provided by:                        │
│   • MySqlNamedBlobDb (current)                                          │
│   • S3NamedBlobDb (migration target)                                    │
│   • CompositeNamedBlobDb (migration phase)                              │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ MySqlNamedBlobDb.list()                                    [line 312]   │
│                                                                         │
│   1. executeTransactionAsync()                                          │
│   2. run_list_v2()                                         [line 619]   │
│      - Construct SQL query (LIST_ALL or LIST_WITH_PREFIX)               │
│      - Execute PreparedStatement                                        │
│      - Build List<NamedBlobRecord> from ResultSet                       │
│      - Determine nextContinuationToken                                  │
│   3. Return Page<NamedBlobRecord>                                       │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Response Serialization                                                  │
│                                                                         │
│   NamedBlobListHandler.listBlobsCallback()                 [line 162]   │
│   1. page.toJson(record -> NamedBlobListEntry.toJson())                 │
│   2. serializeJsonToChannel()                                           │
│   3. Set headers (Date, Content-Type, Content-Length)                   │
│   4. Return ReadableStreamChannel                                       │
└─────────────────────────────────────────────────────────────────────────┘

                           ┌─────────────────┐
                           │  Router: NO     │
                           │  NamedBlobDb:   │
                           │    YES (list)   │
                           └─────────────────┘
```

**S3 Migration Point**: Replace `MySqlNamedBlobDb.list()` with `S3NamedBlobDb.list()`

---

### 2.2 GET Operation (Named Blob)

**Path**: Metadata Lookup + Content Retrieval

```
GET /named/{account}/{container}/{blobName}

┌─────────────────────────────────────────────────────────────────────────┐
│ FrontendRestRequestService.handleGet()                     [line 304]   │
│                                                                         │
│   Routing Decision (line 344):                                          │
│   else → getBlobHandler.handle()                                        │
│   (blobName != null, so not a list request)                             │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ GetBlobHandler.handle()                                    [line 92]    │
│                                                                         │
│   1. CallbackChain.start()                                              │
│   2. securityService.processRequest()                                   │
│   3. idConverter.convert()  ◄─────────────────────────────────────────┐ │
│      (Resolves named blob path to Ambry blob ID)                      │ │
│                                                                       │ │
│      IdConverter internally calls:                                    │ │
│      NamedBlobDb.get(accountName, containerName, blobName)  ──────────┘ │
│                                                                         │
│   4. securityService.postProcessRequest()                               │
│   5. router.getBlob(blobId, options, callback)                          │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                    ▼                               ▼
┌───────────────────────────────┐   ┌───────────────────────────────────┐
│ NamedBlobDb.get()             │   │ Router.getBlob()                  │
│ (via IdConverter)             │   │                                   │
│                               │   │                                   │
│ Returns: NamedBlobRecord      │   │ Returns: GetBlobResult            │
│ • blobId (Ambry ID)           │   │ • BlobInfo (properties, metadata) │
│ • version                     │   │ • BlobDataChannel (content bytes) │
│ • expirationTimeMs            │   │                                   │
└───────────────────────────────┘   └───────────────────────────────────┘
        │                                           │
        ▼                                           ▼
┌───────────────────────────────┐   ┌───────────────────────────────────┐
│ MySqlNamedBlobDb.run_get_v2() │   │ NonBlockingRouter                 │
│                               │   │   └─► OperationController         │
│ SQL: SELECT blob_id, version  │   │         └─► GetManager            │
│      FROM named_blobs_v2      │   │               └─► GetBlobOperation│
│      WHERE account_id = ?     │   │                     └─► Network   │
│        AND container_id = ?   │   │                           │       │
│        AND blob_name = ?      │   │                           ▼       │
│        AND blob_state = READY │   │                   Storage Servers │
└───────────────────────────────┘   └───────────────────────────────────┘

                        ┌───────────────────────────┐
                        │  Router: YES (getBlob)    │
                        │  NamedBlobDb: YES (get)   │
                        │                           │
                        │  Sequence:                │
                        │  1. NamedBlobDb.get()     │
                        │  2. Router.getBlob()      │
                        └───────────────────────────┘
```

**S3 Migration Points**:
1. `NamedBlobDb.get()` - Replace MySQL lookup with S3 HeadObject
2. `Router.getBlob()` - Replace Ambry storage with S3 GetObject

---

### 2.3 PUT Operation (Named Blob)

**Path**: Content Storage + Metadata Creation

```
PUT /named/{account}/{container}/{blobName}
Content: <blob bytes>

┌─────────────────────────────────────────────────────────────────────────┐
│ FrontendRestRequestService.handlePut()                     [line 374]   │
│                                                                         │
│   Routing Decision (line 389-398):                                      │
│   if (matchesOperation("named"))                                        │
│       → namedBlobPutHandler.handle()                                    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ NamedBlobPutHandler.handle()                                            │
│                                                                         │
│   Two-Phase Commit Pattern:                                             │
│                                                                         │
│   Phase 1: Store blob content                                           │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │ router.putBlob(blobProperties, userMetadata, channel, options)  │   │
│   │                                                                 │   │
│   │ Returns: blobId (Ambry-generated unique ID)                     │   │
│   └─────────────────────────────────────────────────────────────────┘   │
│                               │                                         │
│                               ▼                                         │
│   Phase 2: Store name→ID mapping                                        │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │ namedBlobDb.put(NamedBlobRecord, NamedBlobState.IN_PROGRESS)    │   │
│   │                                                                 │   │
│   │ Record contains:                                                │   │
│   │ • accountName, containerName, blobName                          │   │
│   │ • blobId (from router.putBlob result)                           │   │
│   │ • expirationTimeMs, blobSize                                    │   │
│   └─────────────────────────────────────────────────────────────────┘   │
│                               │                                         │
│                               ▼                                         │
│   Phase 3: Finalize (mark as READY)                                     │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │ namedBlobDb.updateBlobTtlAndStateToReady(record)                │   │
│   └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                    ▼                               ▼
┌───────────────────────────────┐   ┌───────────────────────────────────┐
│ Router.putBlob()              │   │ NamedBlobDb.put()                 │
│                               │   │                                   │
│ Stores blob content:          │   │ Stores metadata:                  │
│ • Blob bytes                  │   │ • blob_name → blob_id mapping     │
│ • BlobProperties              │   │ • version                         │
│ • UserMetadata                │   │ • blob_state                      │
│                               │   │ • deleted_ts, blob_size           │
│ Returns: blobId               │   │                                   │
└───────────────────────────────┘   └───────────────────────────────────┘
        │                                           │
        ▼                                           ▼
┌───────────────────────────────┐   ┌───────────────────────────────────┐
│ NonBlockingRouter             │   │ MySqlNamedBlobDb                  │
│   └─► PutManager              │   │                                   │
│         └─► PutOperation      │   │ SQL: INSERT INTO named_blobs_v2   │
│               └─► Network     │   │      (account_id, container_id,   │
│                     │         │   │       blob_name, blob_id,         │
│                     ▼         │   │       deleted_ts, version,        │
│             Storage Servers   │   │       blob_state, blob_size)      │
└───────────────────────────────┘   └───────────────────────────────────┘

                        ┌───────────────────────────┐
                        │  Router: YES (putBlob)    │
                        │  NamedBlobDb: YES (put)   │
                        │                           │
                        │  Sequence:                │
                        │  1. Router.putBlob()      │
                        │  2. NamedBlobDb.put()     │
                        │  3. NamedBlobDb.update()  │
                        └───────────────────────────┘
```

**S3 Migration Points**:
1. `Router.putBlob()` - Replace Ambry storage with S3 PutObject
2. `NamedBlobDb.put()` - S3 metadata is stored with the object (no separate call needed)

**S3 Simplification**: In S3, content and metadata are stored together in one PutObject call. The two-phase commit pattern may be simplified.

---

### 2.4 DELETE Operation (Named Blob)

**Path**: Soft Delete Metadata + (Optional) Hard Delete Content

```
DELETE /named/{account}/{container}/{blobName}

┌─────────────────────────────────────────────────────────────────────────┐
│ FrontendRestRequestService.handleDelete()                               │
│                                                                         │
│   Routing Decision:                                                     │
│   if (matchesOperation("named"))                                        │
│       → namedBlobDeleteHandler.handle()                                 │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ NamedBlobDeleteHandler                                                  │
│                                                                         │
│   Soft Delete Pattern:                                                  │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │ namedBlobDb.delete(accountName, containerName, blobName)        │   │
│   │                                                                 │   │
│   │ This performs a SOFT DELETE:                                    │   │
│   │ UPDATE named_blobs_v2 SET deleted_ts = NOW() WHERE ...          │   │
│   │                                                                 │   │
│   │ Returns: DeleteResult with blobId                               │   │
│   └─────────────────────────────────────────────────────────────────┘   │
│                               │                                         │
│                               ▼                                         │
│   (Optional) Hard delete blob content:                                  │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │ router.deleteBlob(blobId)                                       │   │
│   │                                                                 │   │
│   │ Only called in certain conditions                               │   │
│   └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                    ▼                               ▼
┌───────────────────────────────┐   ┌───────────────────────────────────┐
│ NamedBlobDb.delete()          │   │ Router.deleteBlob()               │
│                               │   │ (optional/deferred)               │
│ Soft delete - sets timestamp: │   │                                   │
│ • deleted_ts = NOW()          │   │ Hard delete from storage:         │
│ • Blob still exists in DB     │   │ • Removes blob from servers       │
│ • Excluded from list results  │   │ • Replication across DCs          │
│                               │   │                                   │
└───────────────────────────────┘   └───────────────────────────────────┘

                        ┌────────────────────────────────┐
                        │  Router: OPTIONAL (deleteBlob) │
                        │  NamedBlobDb: YES (delete)     │
                        │                                │
                        │  Sequence:                     │
                        │  1. NamedBlobDb.delete()       │
                        │  2. Router.deleteBlob()        │
                        │     (async/background)         │
                        └────────────────────────────────┘
```

**S3 Migration Points**:
1. `NamedBlobDb.delete()` - Update S3 object metadata OR delete object
2. `Router.deleteBlob()` - S3 DeleteObject (if hard delete)

**S3 Consideration**: S3 doesn't have native soft delete. Options:
- Store `deleted_ts` in metadata (matches MySQL behavior)
- Use S3 versioning with delete markers
- Immediate hard delete (behavior change)

---

## 3. Interface Contracts

### 3.1 NamedBlobDb Interface

```java
// File: ambry-api/src/main/java/com/github/ambry/named/NamedBlobDb.java

public interface NamedBlobDb extends Closeable {

    /**
     * List blobs with a given prefix.
     *
     * @param accountName    Account name
     * @param containerName  Container name
     * @param blobNamePrefix Prefix filter (null = list all)
     * @param pageToken      Pagination token (null = first page)
     * @param maxKey         Max results per page
     * @return Page<NamedBlobRecord> with entries and nextPageToken
     */
    CompletableFuture<Page<NamedBlobRecord>> list(
        String accountName, String containerName,
        String blobNamePrefix, String pageToken, Integer maxKey);

    /**
     * Get a single blob's metadata by name.
     * Used by IdConverter to resolve name → blobId.
     */
    CompletableFuture<NamedBlobRecord> get(
        String accountName, String containerName, String blobName,
        GetOption option, boolean localGet);

    /**
     * Store a name→blobId mapping.
     * Called after Router.putBlob() succeeds.
     */
    CompletableFuture<PutResult> put(
        NamedBlobRecord record, NamedBlobState state, Boolean isUpsert);

    /**
     * Soft-delete a blob (set deleted_ts).
     */
    CompletableFuture<DeleteResult> delete(
        String accountName, String containerName, String blobName);

    /**
     * Finalize a blob (set state=READY, clear TTL).
     */
    CompletableFuture<PutResult> updateBlobTtlAndStateToReady(
        NamedBlobRecord record);
}
```

### 3.2 Router Interface

```java
// File: ambry-api/src/main/java/com/github/ambry/router/Router.java

public interface Router extends Closeable {

    /**
     * Get blob content and/or metadata.
     *
     * @param blobId  Ambry blob ID (e.g., "AAEAAQAx...")
     * @param options What to retrieve (BlobInfo, Data, or All)
     * @return GetBlobResult with BlobInfo and/or data channel
     */
    Future<GetBlobResult> getBlob(String blobId, GetBlobOptions options,
        Callback<GetBlobResult> callback, QuotaChargeCallback quotaChargeCallback);

    /**
     * Store blob content.
     *
     * @param blobProperties Size, TTL, content type, etc.
     * @param userMetadata   Custom metadata bytes
     * @param channel        Blob content stream
     * @return Generated blobId
     */
    Future<String> putBlob(RestRequest restRequest, BlobProperties blobProperties,
        byte[] userMetadata, ReadableStreamChannel channel, PutBlobOptions options,
        Callback<String> callback, QuotaChargeCallback quotaChargeCallback);

    /**
     * Delete blob content from storage servers.
     */
    Future<Void> deleteBlob(RestRequest restRequest, String blobId, String serviceId,
        Callback<Void> callback, QuotaChargeCallback quotaChargeCallback);

    /**
     * Update blob TTL (make permanent or extend).
     */
    Future<Void> updateBlobTtl(RestRequest restRequest, String blobId, String serviceId,
        long expiresAtMs, Callback<Void> callback, QuotaChargeCallback quotaChargeCallback);
}
```

---

## 4. Data Models

### 4.1 NamedBlobRecord

```java
// File: ambry-api/src/main/java/com/github/ambry/named/NamedBlobRecord.java

public class NamedBlobRecord {
    String accountName;      // Account identifier
    String containerName;    // Container identifier
    String blobName;         // Human-readable blob name
    String blobId;           // Ambry blob ID (links to Router storage)
    long expirationTimeMs;   // -1 for permanent
    long version;            // Monotonic version number
    long blobSize;           // Size in bytes
    long modifiedTimeMs;     // Last modified timestamp
    boolean isDirectory;     // Virtual directory marker
}
```

### 4.2 NamedBlobState

```java
// File: ambry-api/src/main/java/com/github/ambry/protocol/NamedBlobState.java

public enum NamedBlobState {
    IN_PROGRESS,  // ordinal 0 - blob being uploaded
    READY         // ordinal 1 - blob available for reads
}
```

### 4.3 Page

```java
// File: ambry-api/src/main/java/com/github/ambry/frontend/Page.java

public class Page<T> {
    List<T> entries;         // List of records
    String nextPageToken;    // null if last page
}
```

---

## 5. S3 Migration Strategy

### 5.1 Component Mapping

| Ambry Component | S3 Equivalent | Migration Approach |
|-----------------|---------------|-------------------|
| `Router.putBlob()` | `S3Client.putObject()` | New `S3Router` implementation |
| `Router.getBlob()` | `S3Client.getObject()` | New `S3Router` implementation |
| `Router.deleteBlob()` | `S3Client.deleteObject()` | New `S3Router` implementation |
| `NamedBlobDb.list()` | `S3Client.listObjectsV2()` + `headObject()` | New `S3NamedBlobDb` implementation |
| `NamedBlobDb.get()` | `S3Client.headObject()` | New `S3NamedBlobDb` implementation |
| `NamedBlobDb.put()` | Metadata in `putObject()` | Merged with Router put |
| `NamedBlobDb.delete()` | Update metadata or `deleteObject()` | New `S3NamedBlobDb` implementation |

### 5.2 Integration Points

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Current Architecture                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   Handlers ──────► NamedBlobDb ──────► MySqlNamedBlobDb ──────► MySQL   │
│      │                                                                  │
│      └───────────► Router ───────────► NonBlockingRouter ──► Ambry     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                       Migration Architecture                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   Handlers ──► CompositeNamedBlobDb ──┬──► MySqlNamedBlobDb ──► MySQL   │
│      │                                │                                 │
│      │                                └──► S3NamedBlobDb ─────► S3      │
│      │                                                                  │
│      └─────► CompositeRouter ─────────┬──► NonBlockingRouter ──► Ambry  │
│                                       │                                 │
│                                       └──► S3Router ───────────► S3     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                         Target Architecture                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   Handlers ──────► S3NamedBlobDb ─────────────────────────────► S3      │
│      │                                                           │      │
│      └───────────► S3Router ─────────────────────────────────────┘      │
│                                                                         │
│   (Note: In S3, metadata and content are co-located)                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.3 Implementation Order

For an AI agent implementing S3 migration, the recommended order is:

1. **`S3NamedBlobDb.list()`** - Lowest risk, read-only operation
   - See: `docs/s3-named-blob-db-list-specification.md`

2. **`S3NamedBlobDb.get()`** - Read-only, used by IdConverter

3. **`CompositeNamedBlobDb`** - Wrapper for dual-read verification

4. **`S3Router.getBlob()`** - Read-only content retrieval

5. **`S3Router.putBlob()`** - Write path (higher risk)

6. **`S3NamedBlobDb.put()`** - Often merged with Router put for S3

7. **`S3NamedBlobDb.delete()`** and **`S3Router.deleteBlob()`**

---

## 6. Key Files Reference

### 6.1 Interfaces

| Interface | File Path |
|-----------|-----------|
| `NamedBlobDb` | `ambry-api/src/main/java/com/github/ambry/named/NamedBlobDb.java` |
| `Router` | `ambry-api/src/main/java/com/github/ambry/router/Router.java` |

### 6.2 Implementations

| Class | File Path |
|-------|-----------|
| `MySqlNamedBlobDb` | `ambry-named-mysql/src/main/java/com/github/ambry/named/MySqlNamedBlobDb.java` |
| `NonBlockingRouter` | `ambry-router/src/main/java/com/github/ambry/router/NonBlockingRouter.java` |

### 6.3 Handlers

| Handler | File Path | Operations |
|---------|-----------|------------|
| `NamedBlobListHandler` | `ambry-frontend/.../frontend/NamedBlobListHandler.java` | LIST |
| `GetBlobHandler` | `ambry-frontend/.../frontend/GetBlobHandler.java` | GET |
| `NamedBlobPutHandler` | `ambry-frontend/.../frontend/NamedBlobPutHandler.java` | PUT |
| `NamedBlobDeleteHandler` | `ambry-frontend/.../frontend/NamedBlobDeleteHandler.java` | DELETE |

### 6.4 Data Models

| Model | File Path |
|-------|-----------|
| `NamedBlobRecord` | `ambry-api/src/main/java/com/github/ambry/named/NamedBlobRecord.java` |
| `Page` | `ambry-api/src/main/java/com/github/ambry/frontend/Page.java` |
| `NamedBlobListEntry` | `ambry-api/src/main/java/com/github/ambry/frontend/NamedBlobListEntry.java` |
| `NamedBlobState` | `ambry-api/src/main/java/com/github/ambry/protocol/NamedBlobState.java` |

---

## 7. Testing Guidance

### 7.1 Unit Tests

Each S3 implementation should have unit tests mocking the S3 client:

```java
@Test
public void testListWithPrefix() {
    // Mock S3Client.listObjectsV2() and headObject()
    // Verify correct S3 prefix construction
    // Verify filtering logic
    // Verify pagination token handling
}
```

### 7.2 Integration Tests

Reference existing MySQL tests for expected behavior:

| Test Class | File Path |
|------------|-----------|
| `MySqlNamedBlobDbIntegrationTest` | `ambry-named-mysql/src/integration-test/.../MySqlNamedBlobDbIntegrationTest.java` |

### 7.3 Verification Tests

During migration, compare MySQL and S3 results:

```java
@Test
public void testListResultsMatch() {
    Page<NamedBlobRecord> mysqlResult = mysqlDb.list(...).get();
    Page<NamedBlobRecord> s3Result = s3Db.list(...).get();

    // Compare entries (excluding timestamps per spec)
    assertEntriesMatch(mysqlResult.getEntries(), s3Result.getEntries());
    assertEquals(mysqlResult.getNextPageToken(), s3Result.getNextPageToken());
}
```

---

## 8. Common Pitfalls

### 8.1 Async Handling

All `NamedBlobDb` and `Router` methods return `CompletableFuture`. Ensure proper:
- Exception propagation
- Callback chaining
- Timeout handling

### 8.2 Account/Container Resolution

The handlers receive `accountName`/`containerName` as strings, but MySQL uses `account_id`/`container_id` (shorts). The `AccountService` performs this mapping. For S3, decide whether to use names or IDs in the key structure.

### 8.3 Blob ID Format

Ambry blob IDs are Base64URL-encoded binary. When storing in S3 metadata, preserve exact encoding.

### 8.4 Soft Delete vs Hard Delete

MySQL uses soft delete (`deleted_ts`). S3 doesn't have native equivalent. Maintain consistent behavior during migration.

---

## Appendix: Quick Reference

### Operation → Interface Mapping

| HTTP Operation | Handler | NamedBlobDb Method | Router Method |
|----------------|---------|-------------------|---------------|
| `GET ?prefix=` | NamedBlobListHandler | `list()` | - |
| `GET /{blob}` | GetBlobHandler | `get()` (via IdConverter) | `getBlob()` |
| `PUT /{blob}` | NamedBlobPutHandler | `put()`, `updateBlobTtlAndStateToReady()` | `putBlob()` |
| `DELETE /{blob}` | NamedBlobDeleteHandler | `delete()` | `deleteBlob()` (optional) |

### Response Types

| Interface Method | Return Type |
|-----------------|-------------|
| `NamedBlobDb.list()` | `Page<NamedBlobRecord>` |
| `NamedBlobDb.get()` | `NamedBlobRecord` |
| `NamedBlobDb.put()` | `PutResult` |
| `NamedBlobDb.delete()` | `DeleteResult` |
| `Router.getBlob()` | `GetBlobResult` |
| `Router.putBlob()` | `String` (blobId) |
| `Router.deleteBlob()` | `Void` |
