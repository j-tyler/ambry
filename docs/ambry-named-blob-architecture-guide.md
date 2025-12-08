# Ambry Named Blob Architecture Guide

**Purpose**: Architecture reference for implementing S3 migration
**Version**: 1.2

---

## Executive Summary

Ambry named blob system has **two distinct data paths**:

1. **Router Path**: Handles blob content storage/retrieval via Ambry storage servers
2. **NamedBlobDb Path**: Handles metadata (name→ID mappings) via database

For S3 migration, both paths must be addressed with different integration points.

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
│  │  └────────────────────────┘  │    │  └────────────────────────────────┘  │  │
│  │                              │    │  ┌────────────────────────────────┐  │  │
│  │                              │    │  │    NamedBlobPutHandler         │  │  │
│  │                              │    │  └────────────────────────────────┘  │  │
│  │                              │    │  ┌────────────────────────────────┐  │  │
│  │                              │    │  │      DeleteBlobHandler         │  │  │
│  │                              │    │  └────────────────────────────────┘  │  │
│  └──────────────┬───────────────┘    └──────────────────┬───────────────────┘  │
│                 │                                       │                       │
└─────────────────┼───────────────────────────────────────┼───────────────────────┘
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
    └─────────────┬─────────────┘         └──────────────┬──────────────┘
                  │                                       │
                  ▼                                       ▼
    ┌─────────────────────────┐           ┌─────────────────────────────┐
    │      MySQL Database     │           │   Ambry Storage Servers     │
    │                         │           │                             │
    │  Stores:                │           │  Stores:                    │
    │  • blob_name            │           │  • Actual blob bytes        │
    │  • blob_id (reference)──┼──────────►│  • Replicated across DCs    │
    │  • version, blob_state  │           │                             │
    │  • deleted_ts, blob_size│           │                             │
    └─────────────────────────┘           └─────────────────────────────┘
           METADATA                              CONTENT
```

---

## 2. Operation Flow Maps

### 2.1 LIST Operation

**Path**: Metadata Only (NamedBlobDb)

```
GET /named/{account}/{container}?prefix=...&page=...&maxKeys=...

┌───────────────────────────────────────────────────┐
│ FrontendRestRequestService.handleGet()            │
│                                                   │
│   Routing: blobName == null → NamedBlobListHandler│
└───────────────────────────────────────────────────┘
                         │
                         ▼
┌───────────────────────────────────────────────────┐
│ NamedBlobListHandler.handle()                     │
│                                                   │
│   1. Security checks                              │
│   2. Parse prefix, pageToken, maxKeys             │
│   3. Call namedBlobDb.list()                      │
│   4. Serialize Page<NamedBlobRecord> to JSON      │
└───────────────────────────────────────────────────┘
                         │
                         ▼
┌───────────────────────────────────────────────────┐
│ NamedBlobDb.list()                                │
│                                                   │
│   Returns: Page<NamedBlobRecord>                  │
│   Implementations: MySqlNamedBlobDb               │
│   (S3NamedBlobDb is proposed, not yet implemented)│
└───────────────────────────────────────────────────┘

                  ┌─────────────────┐
                  │  Router: NO     │
                  │  NamedBlobDb:   │
                  │    YES (list)   │
                  └─────────────────┘
```

**S3 Migration**: Replace `NamedBlobDb.list()` implementation

---

### 2.2 GET Operation

**Path**: Metadata Lookup + Content Retrieval

```
GET /named/{account}/{container}/{blobName}

┌───────────────────────────────────────────────────┐
│ FrontendRestRequestService.handleGet()            │
│                                                   │
│   Routing: blobName != null → GetBlobHandler      │
└───────────────────────────────────────────────────┘
                         │
                         ▼
┌───────────────────────────────────────────────────┐
│ GetBlobHandler.handle()                           │
│                                                   │
│   1. Security checks                              │
│   2. router.getBlob(restRequest, blobIdStr, ...)  │
│      → Router internally calls idConverter        │
│      → idConverter calls NamedBlobDb.get()        │
│   3. Returns blob content via callback            │
└───────────────────────────────────────────────────┘
                         │
          ┌──────────────┴──────────────┐
          ▼                             ▼
┌──────────────────────┐   ┌────────────────────────┐
│ NamedBlobDb.get()    │   │ Router.getBlob()       │
│ (via IdConverter)    │   │                        │
│                      │   │ Returns:               │
│ Returns:             │   │ • BlobInfo             │
│ • NamedBlobRecord    │   │ • BlobDataChannel      │
│   with blobId        │   │                        │
└──────────────────────┘   └────────────────────────┘

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
1. `NamedBlobDb.get()` → S3 HeadObject
2. `Router.getBlob()` → S3 GetObject

---

### 2.3 PUT Operation

**Path**: Content Storage + Metadata Creation

```
PUT /named/{account}/{container}/{blobName}
Content: <blob bytes>

┌───────────────────────────────────────────────────┐
│ FrontendRestRequestService.handlePut()            │
│                                                   │
│   Routing: named path → NamedBlobPutHandler       │
└───────────────────────────────────────────────────┘
                         │
                         ▼
┌───────────────────────────────────────────────────┐
│ NamedBlobPutHandler.handle()                      │
│                                                   │
│   1. router.putBlob() → returns blobId            │
│   2. idConverter.convert() → stores metadata      │
│      (internally calls NamedBlobDb.put())         │
└───────────────────────────────────────────────────┘
                         │
          ┌──────────────┴──────────────┐
          ▼                             ▼
┌──────────────────────┐   ┌────────────────────────┐
│ Router.putBlob()     │   │ NamedBlobDb.put()      │
│                      │   │ (via IdConverter)      │
│ Stores:              │   │                        │
│ • Blob bytes         │   │ Stores:                │
│ • BlobProperties     │   │ • name→blobId mapping  │
│                      │   │ • version, state       │
│ Returns: blobId      │   │                        │
└──────────────────────┘   └────────────────────────┘

              ┌───────────────────────────┐
              │  Router: YES (putBlob)    │
              │  NamedBlobDb: YES (put)   │
              │                           │
              │  Sequence:                │
              │  1. Router.putBlob()      │
              │  2. NamedBlobDb.put()     │
              └───────────────────────────┘
```

**S3 Migration**: In S3, content and metadata are stored together in one PutObject call.

---

### 2.4 DELETE Operation

**Path**: Soft Delete Metadata + Hard Delete Content

```
DELETE /named/{account}/{container}/{blobName}

┌───────────────────────────────────────────────────┐
│ FrontendRestRequestService.handleDelete()         │
│                                                   │
│   Routing: named blob path → DeleteBlobHandler    │
└───────────────────────────────────────────────────┘
                         │
                         ▼
┌───────────────────────────────────────────────────┐
│ DeleteBlobHandler.handle()                        │
│                                                   │
│   1. Security checks                              │
│   2. router.deleteBlob(restRequest, null, ...)   │
│      → Router detects named blob path             │
│      → Router calls idConverter.convert()         │
│      → idConverter calls namedBlobDb.delete()     │
│         (soft delete + returns blob IDs)          │
│      → Router deletes actual blob content         │
└───────────────────────────────────────────────────┘
                         │
          ┌──────────────┴──────────────┐
          ▼                             ▼
┌──────────────────────┐   ┌────────────────────────┐
│ NamedBlobDb.delete() │   │ Router.deleteBlob()    │
│ (via IdConverter)    │   │                        │
│                      │   │                        │
│ Soft delete:         │   │ Hard delete:           │
│ • Sets deleted_ts    │   │ • Removes from storage │
│ • Returns blob IDs   │   │ • Uses IDs from delete │
│ • Excluded from list │   │                        │
└──────────────────────┘   └────────────────────────┘

              ┌────────────────────────────────┐
              │  Router: YES (deleteBlob)      │
              │  NamedBlobDb: YES (delete)     │
              │                                │
              │  Sequence:                     │
              │  1. NamedBlobDb.delete()       │
              │     (via idConverter)          │
              │  2. Router deletes content     │
              └────────────────────────────────┘
```

**S3 Migration**: S3 lacks native soft delete. Use metadata update (copy-replace pattern) to set `deleted-ts`. See specification for details.

---

## 3. Interface Contracts

### 3.1 NamedBlobDb Interface

```java
// File: ambry-api/src/main/java/com/github/ambry/named/NamedBlobDb.java
// Note: Only primary methods shown. Additional methods include:
//   - pullStaleBlobs(Container, String) for cleanup operations
//   - cleanupStaleData(List<StaleNamedBlob>) for garbage collection

public interface NamedBlobDb extends Closeable {

    CompletableFuture<Page<NamedBlobRecord>> list(
        String accountName, String containerName,
        String blobNamePrefix, String pageToken, Integer maxKey);

    CompletableFuture<NamedBlobRecord> get(
        String accountName, String containerName, String blobName,
        GetOption option, boolean localGet);

    CompletableFuture<PutResult> put(
        NamedBlobRecord record, NamedBlobState state, Boolean isUpsert);

    CompletableFuture<DeleteResult> delete(
        String accountName, String containerName, String blobName);

    CompletableFuture<PutResult> updateBlobTtlAndStateToReady(
        NamedBlobRecord record);
}
```

### 3.2 Router Interface

```java
// File: ambry-api/src/main/java/com/github/ambry/router/Router.java
// Note: Only primary methods shown. Additional methods include:
//   - stitchBlob() for combining chunks into a single blob
//   - undeleteBlob() for restoring deleted blobs
//   - Various default convenience methods returning CompletableFuture

public interface Router extends Closeable {

    Future<GetBlobResult> getBlob(String blobId, GetBlobOptions options,
        Callback<GetBlobResult> callback, QuotaChargeCallback quotaChargeCallback);

    Future<String> putBlob(RestRequest restRequest, BlobProperties blobProperties,
        byte[] userMetadata, ReadableStreamChannel channel, PutBlobOptions options,
        Callback<String> callback, QuotaChargeCallback quotaChargeCallback);

    Future<Void> deleteBlob(RestRequest restRequest, String blobId, String serviceId,
        Callback<Void> callback, QuotaChargeCallback quotaChargeCallback);

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
    String accountName;
    String containerName;
    String blobName;
    String blobId;           // Links to Router storage
    long expirationTimeMs;   // -1 for permanent
    long version;
    long blobSize;
    long modifiedTimeMs;
    boolean isDirectory;
}
```

### 4.2 NamedBlobState

```java
// File: ambry-api/src/main/java/com/github/ambry/protocol/NamedBlobState.java

public enum NamedBlobState {
    IN_PROGRESS,  // ordinal 0
    READY         // ordinal 1
}
```

### 4.3 Page

```java
public class Page<T> {
    List<T> entries;
    String nextPageToken;    // null if last page
}
```

---

## 5. S3 Migration Strategy

> **Note**: The S3 migration components described in this section (`S3NamedBlobDb`, `S3Router`,
> `CompositeNamedBlobDb`, `CompositeRouter`) are **proposed implementations** and do not yet exist
> in the codebase. This section describes the planned architecture for future migration work.

### 5.1 Component Mapping

| Ambry Component | S3 Equivalent | Migration Approach |
|-----------------|---------------|-------------------|
| `Router.putBlob()` | `S3Client.putObject()` | New `S3Router` |
| `Router.getBlob()` | `S3Client.getObject()` | New `S3Router` |
| `Router.deleteBlob()` | `S3Client.deleteObject()` | New `S3Router` |
| `NamedBlobDb.list()` | `listObjectsV2()` + `headObject()` | New `S3NamedBlobDb` |
| `NamedBlobDb.get()` | `S3Client.headObject()` | New `S3NamedBlobDb` |
| `NamedBlobDb.put()` | Metadata in `putObject()` | Merged with Router |
| `NamedBlobDb.delete()` | Update metadata | New `S3NamedBlobDb` |

### 5.2 Migration Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Current Architecture                             │
├─────────────────────────────────────────────────────────────────────────┤
│   Handlers ──────► NamedBlobDb ──────► MySqlNamedBlobDb ──────► MySQL   │
│      │                                                                  │
│      └───────────► Router ───────────► NonBlockingRouter ──► Ambry     │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                       Migration Architecture                             │
├─────────────────────────────────────────────────────────────────────────┤
│   Handlers ──► CompositeNamedBlobDb ──┬──► MySqlNamedBlobDb ──► MySQL   │
│      │                                └──► S3NamedBlobDb ─────► S3      │
│      │                                                                  │
│      └─────► CompositeRouter ─────────┬──► NonBlockingRouter ──► Ambry  │
│                                       └──► S3Router ───────────► S3     │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                         Target Architecture                              │
├─────────────────────────────────────────────────────────────────────────┤
│   Handlers ──────► S3NamedBlobDb ─────────────────────────────► S3      │
│      │                                                           │      │
│      └───────────► S3Router ─────────────────────────────────────┘      │
│   (In S3, metadata and content are co-located)                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.3 Implementation Order

1. **`S3NamedBlobDb.list()`** - Read-only, lowest risk. See `docs/s3-named-blob-db-list-specification.md`
2. **`S3NamedBlobDb.get()`** - Read-only, used by IdConverter
3. **`CompositeNamedBlobDb`** - Wrapper for dual-read verification
4. **`S3Router.getBlob()`** - Read-only content retrieval
5. **`S3Router.putBlob()`** - Write path
6. **`S3NamedBlobDb.put()`** - Often merged with Router put
7. **`S3NamedBlobDb.delete()`** and **`S3Router.deleteBlob()`**

---

## 6. Key Files Reference

### Interfaces

| Interface | File Path |
|-----------|-----------|
| `NamedBlobDb` | `ambry-api/src/main/java/com/github/ambry/named/NamedBlobDb.java` |
| `Router` | `ambry-api/src/main/java/com/github/ambry/router/Router.java` |

### Implementations

| Class | File Path |
|-------|-----------|
| `MySqlNamedBlobDb` | `ambry-named-mysql/src/main/java/com/github/ambry/named/MySqlNamedBlobDb.java` |
| `NonBlockingRouter` | `ambry-router/src/main/java/com/github/ambry/router/NonBlockingRouter.java` |

### Handlers

| Handler | File Path |
|---------|-----------|
| `NamedBlobListHandler` | `ambry-frontend/src/main/java/com/github/ambry/frontend/NamedBlobListHandler.java` |
| `GetBlobHandler` | `ambry-frontend/src/main/java/com/github/ambry/frontend/GetBlobHandler.java` |
| `NamedBlobPutHandler` | `ambry-frontend/src/main/java/com/github/ambry/frontend/NamedBlobPutHandler.java` |
| `DeleteBlobHandler` | `ambry-frontend/src/main/java/com/github/ambry/frontend/DeleteBlobHandler.java` |

### Tests

| Test Class | File Path |
|------------|-----------|
| `MySqlNamedBlobDbIntegrationTest` | `ambry-named-mysql/src/integration-test/.../MySqlNamedBlobDbIntegrationTest.java` |

---

## 7. Common Pitfalls

### 7.1 Async Handling

All `NamedBlobDb` and `Router` methods return `CompletableFuture`. Ensure proper exception propagation and timeout handling.

### 7.2 Account/Container Resolution

Handlers receive `accountName`/`containerName` as strings. MySQL uses `account_id`/`container_id` (shorts). Use `AccountService` for mapping.

### 7.3 Blob ID Format

Ambry blob IDs are Base64URL-encoded binary. Preserve exact encoding when storing in S3 metadata.

### 7.4 Soft Delete vs Hard Delete

MySQL uses soft delete (`deleted_ts`). S3 lacks native equivalent. See specification for implementation approach.

---

## Appendix: Quick Reference

### Operation → Interface Mapping

| HTTP Operation | Handler | NamedBlobDb | Router |
|----------------|---------|-------------|--------|
| `GET ?prefix=` | NamedBlobListHandler | `list()` | - |
| `GET /{blob}` | GetBlobHandler | `get()` | `getBlob()` |
| `PUT /{blob}` | NamedBlobPutHandler | `put()` | `putBlob()` |
| `DELETE /{blob}` | DeleteBlobHandler | `delete()` | `deleteBlob()` |

### Return Types

| Method | Return Type |
|--------|-------------|
| `NamedBlobDb.list()` | `Page<NamedBlobRecord>` |
| `NamedBlobDb.get()` | `NamedBlobRecord` |
| `NamedBlobDb.put()` | `PutResult` |
| `NamedBlobDb.delete()` | `DeleteResult` |
| `Router.getBlob()` | `GetBlobResult` |
| `Router.putBlob()` | `String` (blobId) |
| `Router.deleteBlob()` | `Void` |

