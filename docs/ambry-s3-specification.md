# Ambry ↔ S3 Operation Specification

This specification documents the observable behavior of Ambry Router operations from the client's perspective, enabling equivalent implementation in S3.

---

## How to Use This Specification

**For Proxy Implementers:** Each operation section documents Ambry behavior. Implement S3 equivalents that produce identical observable behavior from the client's perspective.

**For Client Migration Agents:** Use the Ambry code examples as patterns to find in client code. Replace with S3 equivalents that preserve the same semantics.

**Verification:** Use the Conformance Test Suite (Section 17) to validate behavioral equivalence.

---

## Table of Contents

### Router Operations (blob ID-based)
1. [Put Blob](#1-put-blob)
2. [Get Blob](#2-get-blob)
3. [Delete Blob](#3-delete-blob)
4. [Undelete Blob](#4-undelete-blob)
5. [Update TTL](#5-update-ttl)
6. [Stitch Blob](#6-stitch-blob-composite-blobs)

### Named Blob Operations (name-based)
7. [Named Blob Get](#7-named-blob-get)
8. [Named Blob Put](#8-named-blob-put)
9. [Named Blob Delete](#9-named-blob-delete)
10. [Named Blob List](#10-named-blob-list)

### Dataset Operations (versioned collections)
11. [Dataset CRUD](#11-dataset-crud)
12. [Dataset Version CRUD](#12-dataset-version-crud)

### Pre-Configured Settings (affect API behavior)
13. [Container Configuration](#13-container-configuration)
14. [Account Configuration](#14-account-configuration)

### Behaviors & Reference
15. [Cross-Cutting Behaviors](#15-cross-cutting-behaviors)
16. [Reference](#16-reference)
17. [Conformance Test Suite](#17-conformance-test-suite)
18. [Open Questions](#18-open-questions)

---

## 1. Put Blob

### 1.1 Ambry Behavior

#### Basic Put
```java
BlobProperties props = new BlobProperties(blobSize, serviceId, accountId, containerId, isEncrypted);
byte[] userMetadata = ...;
ReadableStreamChannel data = ...;

Future<String> future = router.putBlob(props, userMetadata, data, PutBlobOptions.DEFAULT);
String blobId = future.get();
```

**Observable behavior:**
- Returns an opaque blob ID string
- Blob is immediately retrievable via `getBlob(blobId)`
- All properties and userMetadata are preserved exactly as provided

#### Put with TTL
```java
BlobProperties props = new BlobProperties(blobSize, serviceId, ownerId, contentType,
    isPrivate, timeToLiveInSeconds, accountId, containerId, isEncrypted, ...);
```

**Observable behavior:**
- If `timeToLiveInSeconds` > 0, blob expires after that many seconds from creation
- After expiration: `getBlob(blobId)` returns `RouterErrorCode.BlobExpired`
- `Utils.Infinite_Time` (-1) means blob never expires

#### Error Cases

| Condition | Ambry Error | Source |
|-----------|-------------|--------|
| Data exceeds max size | `BlobTooLarge` | RouterErrorCode.java:70 |
| Channel read error | `BadInputChannel` | RouterErrorCode.java:75 |
| Cluster capacity full | `InsufficientCapacity` | RouterErrorCode.java:80 |
| Router is closed | `RouterClosed` | RouterErrorCode.java:57 |
| Invalid put arguments | `InvalidPutArgument` | RouterErrorCode.java:45 |

### 1.2 S3 Equivalent

TODO: Document S3 implementation

#### Basic Put
```python
# TODO: S3 equivalent
```

#### Put with TTL
```python
# TODO: S3 equivalent - lifecycle rules or object expiration
```

#### Error Mapping

| Ambry Error | S3 Equivalent |
|-------------|---------------|
| `BlobTooLarge` | TODO |
| `BadInputChannel` | TODO |
| `InsufficientCapacity` | TODO |
| `InvalidPutArgument` | TODO |

### 1.3 Verification
- [ ] Put returns valid blob ID
- [ ] Immediate get returns exact data
- [ ] All BlobProperties preserved
- [ ] All userMetadata preserved
- [ ] TTL expiration behavior matches

---

## 2. Get Blob

### 2.1 Ambry Behavior

#### Basic Get
```java
GetBlobOptions options = new GetBlobOptionsBuilder()
    .operationType(GetBlobOptions.OperationType.All)
    .build();

GetBlobResult result = router.getBlob(blobId, options).get();
BlobInfo info = result.getBlobInfo();
ReadableStreamChannel data = result.getBlobDataChannel();
```

**Observable behavior:**
- Returns `GetBlobResult` containing `BlobInfo` and/or data channel
- `BlobInfo` contains `BlobProperties` and `userMetadata` exactly as stored

#### Operation Types

| OperationType | Returns |
|---------------|---------|
| `All` | `BlobInfo` + data channel |
| `BlobInfo` | `BlobInfo` only (no data transfer) |
| `Data` | Data channel only |
| `BlobChunkIds` | List of chunk IDs (composite blobs) |

#### Range Requests
```java
GetBlobOptions options = new GetBlobOptionsBuilder()
    .operationType(GetBlobOptions.OperationType.Data)
    .range(ByteRange.fromOffsetRange(startOffset, endOffset))
    .build();
```

**Observable behavior:**
- Returns only bytes in specified range
- Invalid range returns `RangeNotSatisfiable`

#### Error Cases

| Condition | Ambry Error | Source |
|-----------|-------------|--------|
| Blob ID never existed | `BlobDoesNotExist` | RouterErrorCode.java:92 |
| Blob was deleted | `BlobDeleted` | RouterErrorCode.java:87 |
| Blob TTL expired | `BlobExpired` | RouterErrorCode.java:97 |
| Invalid range | `RangeNotSatisfiable` | RouterErrorCode.java:102 |
| Malformed blob ID | `InvalidBlobId` | RouterErrorCode.java:32 |

### 2.2 S3 Equivalent

TODO: Document S3 implementation

#### Basic Get
```python
# TODO: S3 equivalent
```

#### Operation Types Mapping

| Ambry OperationType | S3 Equivalent |
|---------------------|---------------|
| `All` | TODO |
| `BlobInfo` | TODO (HEAD?) |
| `Data` | TODO |
| `BlobChunkIds` | TODO |

#### Error Mapping

| Ambry Error | S3 Equivalent |
|-------------|---------------|
| `BlobDoesNotExist` | TODO (404?) |
| `BlobDeleted` | TODO |
| `BlobExpired` | TODO |
| `RangeNotSatisfiable` | TODO (416?) |
| `InvalidBlobId` | TODO |

### 2.3 Verification
- [ ] Get returns exact data from put
- [ ] BlobInfo matches original properties
- [ ] Range requests return correct bytes
- [ ] All OperationTypes work correctly
- [ ] Error codes match for all conditions

---

## 3. Delete Blob

### 3.1 Ambry Behavior

```java
router.deleteBlob(blobId, serviceId).get();
```

**Observable behavior:**
- Returns `Future<Void>` (no return value)
- Subsequent `getBlob(blobId)` returns `BlobDeleted`
- **This is a soft delete** — blob is recoverable via `undeleteBlob()`

#### Error Cases

| Condition | Ambry Error |
|-----------|-------------|
| Authorization failure | `BlobAuthorizationFailure` |
| Blob does not exist | TODO: OQ-001 |
| Blob already deleted | TODO: OQ-002 |

### 3.2 S3 Equivalent

TODO: Document S3 implementation

```python
# TODO: S3 equivalent - must support soft delete semantics
```

#### Error Mapping

| Ambry Error | S3 Equivalent |
|-------------|---------------|
| `BlobDeleted` | TODO |
| `BlobAuthorizationFailure` | TODO |

### 3.3 Verification
- [ ] Delete succeeds for existing blob
- [ ] Get after delete returns `BlobDeleted`
- [ ] Undelete after delete restores blob
- [ ] Delete idempotency (OQ-001, OQ-002)

---

## 4. Undelete Blob

### 4.1 Ambry Behavior

```java
router.deleteBlob(blobId, serviceId).get();    // soft delete
router.undeleteBlob(blobId, serviceId).get();  // restore
router.getBlob(blobId, options).get();         // succeeds
```

**Observable behavior:**
- Restores a soft-deleted blob
- Get after undelete returns original data exactly
- All properties and metadata preserved

#### Error Cases

| Condition | Ambry Error | Source |
|-----------|-------------|--------|
| Blob was never deleted | `BlobNotDeleted` | RouterErrorCode.java:132 |
| Blob already undeleted | `BlobUndeleted` | RouterErrorCode.java:122 |
| Blob TTL expired | `BlobExpired` | BlobStoreTest.java:1796 |
| Blob does not exist | TODO: OQ-003 |

### 4.2 S3 Equivalent

TODO: Document S3 implementation

```python
# TODO: S3 equivalent - requires versioning or similar mechanism
```

#### Error Mapping

| Ambry Error | S3 Equivalent |
|-------------|---------------|
| `BlobNotDeleted` | TODO |
| `BlobUndeleted` | TODO |
| `BlobExpired` | TODO |

### 4.3 Verification
- [ ] Undelete restores deleted blob
- [ ] Data and metadata exactly preserved
- [ ] Cannot undelete non-deleted blob
- [ ] Cannot undelete expired blob
- [ ] Undelete idempotency

---

## 5. Update TTL

### 5.1 Ambry Behavior

```java
router.updateBlobTtl(blobId, serviceId, expiresAtMs).get();
```

**Observable behavior:**
- Returns `Future<Void>` (no return value)
- Blob's expiration time updated to `expiresAtMs`
- `Utils.Infinite_Time` (-1) makes blob permanent

#### Error Cases

| Condition | Ambry Error |
|-----------|-------------|
| Blob does not exist | `BlobDoesNotExist` |
| Blob was deleted | `BlobDeleted` |
| Update not allowed | `BlobUpdateNotAllowed` |
| Blob already expired | TODO: OQ-004 |
| Shortening TTL | TODO: OQ-005 |

### 5.2 S3 Equivalent

TODO: Document S3 implementation

```python
# TODO: S3 equivalent
```

#### Error Mapping

| Ambry Error | S3 Equivalent |
|-------------|---------------|
| `BlobDoesNotExist` | TODO |
| `BlobDeleted` | TODO |
| `BlobUpdateNotAllowed` | TODO |

### 5.3 Verification
- [ ] TTL extension works
- [ ] Making blob permanent works
- [ ] Cannot update deleted blob
- [ ] TTL shortening behavior (OQ-005)

---

## 6. Stitch Blob (Composite Blobs)

### 6.1 Ambry Behavior

```java
// Upload chunks with chunkUpload flag
String chunk1 = router.putBlob(props, meta, data1,
    PutBlobOptions.builder().chunkUpload(true).build()).get();
String chunk2 = router.putBlob(props, meta, data2,
    PutBlobOptions.builder().chunkUpload(true).build()).get();

// Stitch into composite blob
List<ChunkInfo> chunks = List.of(
    new ChunkInfo(chunk1, chunkOffset1, chunkSize1, expiresAtMs),
    new ChunkInfo(chunk2, chunkOffset2, chunkSize2, expiresAtMs)
);
String compositeBlobId = router.stitchBlob(props, meta, chunks, options).get();
```

**Observable behavior:**
- Returns new blob ID for composite blob
- `getBlob(compositeBlobId)` returns concatenated data in order
- `getBlob(compositeBlobId, BlobChunkIds)` returns chunk ID list

#### Error Cases

| Condition | Ambry Error | Source |
|-----------|-------------|--------|
| Mismatched metadata chunk ID | `InvalidOrMismatchedStitchBlobReservedMetadataChunkId` | RouterErrorCode.java:38 |
| Chunk does not exist | TODO: OQ-009 |
| Chunk already deleted | TODO: OQ-010 |

### 6.2 S3 Equivalent

TODO: Document S3 implementation (likely S3 Multipart Upload)

```python
# TODO: S3 equivalent
```

### 6.3 Verification
- [ ] Composite blob returns correct concatenated data
- [ ] Chunk IDs retrievable
- [ ] Chunk accessibility after stitch (OQ-006)
- [ ] Chunk reuse behavior (OQ-007)

---

## 7. Named Blob Get

**Requires:** Container `namedBlobMode` ≠ `DISABLED`

Source: `ambry-api/src/main/java/com/github/ambry/named/NamedBlobDb.java`

### 7.1 Ambry Behavior

```java
NamedBlobRecord record = namedBlobDb.get(accountName, containerName, blobName).get();
String blobId = record.getBlobId();
long expirationTimeMs = record.getExpirationTimeMs();
long version = record.getVersion();
```

**Observable behavior:**
- Returns `NamedBlobRecord` containing blob ID, expiration, version, size
- Blob ID can then be used with Router `getBlob()` to retrieve data
- Returns error if named blob does not exist

#### Error Cases

| Condition | Error | Source |
|-----------|-------|--------|
| Named blob does not exist | TODO | |
| Container namedBlobMode = DISABLED | TODO | |

### 7.2 S3 Equivalent

TODO: Document S3 implementation (S3 uses names natively via key)

### 7.3 Verification
- [ ] Get by name returns correct blob ID
- [ ] Version tracking works correctly
- [ ] Expiration time returned correctly

---

## 8. Named Blob Put

**Requires:** Container `namedBlobMode` ≠ `DISABLED`

Source: `ambry-api/src/main/java/com/github/ambry/named/NamedBlobDb.java`

### 8.1 Ambry Behavior

```java
NamedBlobRecord record = NamedBlobRecord.forPut(
    accountName, containerName, blobName, blobId, expirationTimeMs, blobSize);
PutResult result = namedBlobDb.put(record).get();
```

**Observable behavior:**
- Creates mapping from human-readable name to blob ID
- If name already exists and `namedBlobMode` = `OPTIONAL`: updates mapping (upsert)
- If name already exists and `namedBlobMode` = `NO_UPDATE`: fails
- Returns `PutResult` with success/failure info

#### Error Cases

| Condition | Error | Source |
|-----------|-------|--------|
| Name exists, mode = NO_UPDATE | TODO | |
| Container namedBlobMode = DISABLED | TODO | |

### 8.2 S3 Equivalent

TODO: Document S3 implementation (S3 PutObject uses key directly)

### 8.3 Verification
- [ ] Put creates name→blobId mapping
- [ ] Upsert behavior with OPTIONAL mode
- [ ] Rejection with NO_UPDATE mode

---

## 9. Named Blob Delete

**Requires:** Container `namedBlobMode` ≠ `DISABLED`

Source: `ambry-api/src/main/java/com/github/ambry/named/NamedBlobDb.java`

### 9.1 Ambry Behavior

```java
DeleteResult result = namedBlobDb.delete(accountName, containerName, blobName).get();
```

**Observable behavior:**
- Removes the name→blobId mapping
- Does NOT delete the underlying blob (that requires separate `router.deleteBlob()`)
- Returns `DeleteResult`

#### Error Cases

| Condition | Error | Source |
|-----------|-------|--------|
| Named blob does not exist | TODO | |

### 9.2 S3 Equivalent

TODO: Document S3 implementation

### 9.3 Verification
- [ ] Delete removes name mapping
- [ ] Underlying blob is NOT deleted
- [ ] Subsequent get by name fails

---

## 10. Named Blob List

**Requires:** Container `namedBlobMode` ≠ `DISABLED`

Source: `ambry-api/src/main/java/com/github/ambry/named/NamedBlobDb.java`

### 10.1 Ambry Behavior

```java
Page<NamedBlobRecord> page = namedBlobDb.list(
    accountName, containerName, blobNamePrefix, pageToken, maxKeys).get();

List<NamedBlobRecord> records = page.getEntries();
String nextPageToken = page.getNextPageToken(); // null if no more pages
```

**Observable behavior:**
- Returns paginated list of named blobs matching prefix
- Each record contains: blobName, blobId, expirationTimeMs, version, blobSize, modifiedTimeMs
- Supports directory-like listing (blobs can be marked as directories)
- `pageToken` = null starts from beginning
- `nextPageToken` = null means no more pages

#### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `accountName` | String | Account name |
| `containerName` | String | Container name |
| `blobNamePrefix` | String | Prefix to filter (like S3 prefix) |
| `pageToken` | String | Pagination token (null for first page) |
| `maxKeys` | Integer | Max results per page |

### 10.2 S3 Equivalent

TODO: Document S3 implementation (S3 ListObjectsV2)

### 10.3 Verification
- [ ] List returns matching blobs
- [ ] Pagination works correctly
- [ ] Prefix filtering works
- [ ] Directory markers handled

---

## 11. Dataset CRUD

Datasets are versioned collections of blobs within a container.

Source: `ambry-api/src/main/java/com/github/ambry/account/AccountService.java`

### 11.1 Ambry Behavior

#### Add Dataset
```java
accountService.addDataset(dataset);
```

#### Get Dataset
```java
Dataset dataset = accountService.getDataset(accountName, containerName, datasetName);
```

#### Update Dataset
```java
accountService.updateDataset(dataset);
```

#### Delete Dataset
```java
accountService.deleteDataset(accountName, containerName, datasetName);
```

#### List Datasets
```java
Page<String> datasets = accountService.listAllValidDatasets(accountName, containerName, pageToken);
```

### 11.2 S3 Equivalent

TODO: Document S3 implementation (may not have direct equivalent)

### 11.3 Verification
- [ ] Dataset CRUD operations work
- [ ] Listing with pagination works

---

## 12. Dataset Version CRUD

Source: `ambry-api/src/main/java/com/github/ambry/account/AccountService.java`

### 12.1 Ambry Behavior

#### Add Dataset Version
```java
DatasetVersionRecord record = accountService.addDatasetVersion(
    accountName, containerName, datasetName, version,
    timeToLiveInSeconds, creationTimeInMs, datasetVersionTtlEnabled, state);
```

#### Get Dataset Version
```java
DatasetVersionRecord record = accountService.getDatasetVersion(
    accountName, containerName, datasetName, version);
```

#### Delete Dataset Version
```java
accountService.deleteDatasetVersion(accountName, containerName, datasetName, version);
```

#### Rename Dataset Version
```java
accountService.renameDatasetVersion(
    accountName, containerName, datasetName, sourceVersion, targetVersion);
```

#### List Dataset Versions
```java
Page<String> versions = accountService.listAllValidDatasetVersions(
    accountName, containerName, datasetName, pageToken);
```

#### Update Dataset Version TTL
```java
accountService.updateDatasetVersionTtl(accountName, containerName, datasetName, version);
```

### 12.2 S3 Equivalent

TODO: Document S3 implementation (S3 versioning is different model)

### 12.3 Verification
- [ ] Version CRUD operations work
- [ ] TTL update works
- [ ] Rename works
- [ ] Listing with pagination works

---

## 13. Container Configuration

**Pre-configured settings on the container that affect ALL API calls to blobs in that container.**

These are NOT parameters clients pass in API calls — they are set up ahead of time by account administrators.

Source: `ambry-api/src/main/java/com/github/ambry/account/Container.java`

### 13.1 Container Properties

| Property | Type | Default | Description | Operations Affected |
|----------|------|---------|-------------|---------------------|
| `encrypted` | boolean | false | Blob encryption | Put, Get |
| `cacheable` | boolean | true | Cache-Control headers | Get |
| `ttlRequired` | boolean | true | TTL must be set on put | Put |
| `paranoidDurabilityEnabled` | boolean | false | Enhanced durability | Put |
| `mediaScanDisabled` | boolean | false | Skip content scanning | Put |
| `replicationPolicy` | String | null | Replication behavior | Put |
| `namedBlobMode` | enum | DISABLED | Named blob API mode | Put, Get |
| `status` | enum | ACTIVE | Container status | All |
| `securePathRequired` | boolean | false | Path validation | Get |
| `backupEnabled` | boolean | false | Backup behavior | Put |
| `accessControlAllowOrigin` | String | "" | CORS header | Get |
| `cacheTtlInSecond` | Long | null | Cache TTL override | Get |
| `contentTypeWhitelistForFilenamesOnDownload` | Set | empty | Filename header control | Get |
| `userMetadataKeysToNotPrefixInResponse` | Set | empty | Metadata header control | Get |
| `deleteTriggerTime` | long | 0 | Scheduled deletion | Delete |

### 13.2 Property Details

#### 13.2.1 encrypted

TODO: Document encryption behavior
- How encryption affects put operations
- How encryption affects get operations
- Key management
- S3 equivalent (SSE-S3? SSE-KMS? Client-side?)

#### 13.2.2 cacheable

TODO: Document caching behavior
- What Cache-Control headers are set
- How this affects CDN behavior
- S3 equivalent

#### 13.2.3 ttlRequired

TODO: Document TTL requirement
- What happens on put without TTL when ttlRequired=true
- Error returned
- S3 equivalent (lifecycle rules?)

#### 13.2.4 paranoidDurabilityEnabled

TODO: Document paranoid durability
- What additional durability guarantees are provided
- How this affects put latency
- S3 equivalent (if any)

#### 13.2.5 status

| Status | Effect on Operations |
|--------|---------------------|
| `ACTIVE` | All operations allowed |
| `INACTIVE` | TODO: Document behavior |
| `DELETE_IN_PROGRESS` | TODO: Document behavior |

#### 13.2.6 namedBlobMode

| Mode | Behavior |
|------|----------|
| `DISABLED` | Named blob APIs disabled |
| `OPTIONAL` | Both named and ID-based APIs work; updates allowed |
| `NO_UPDATE` | Both APIs work; updates disallowed |

TODO: Document S3 equivalent

### 13.3 S3 Mapping

| Container Property | S3 Equivalent |
|--------------------|---------------|
| `encrypted` | TODO |
| `cacheable` | TODO |
| `ttlRequired` | TODO |
| `paranoidDurabilityEnabled` | TODO |
| `status` | TODO |
| `accessControlAllowOrigin` | TODO |
| `cacheTtlInSecond` | TODO |

---

## 14. Account Configuration

**Pre-configured settings on the account that affect ALL containers and blobs within that account.**

These are NOT parameters clients pass in API calls — they are set up ahead of time by account administrators.

Source: `ambry-api/src/main/java/com/github/ambry/account/Account.java`

### 14.1 Account Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `status` | enum | ACTIVE | Account status |
| `aclInheritedByContainer` | boolean | false | ACL inheritance |
| `quotaResourceType` | enum | ACCOUNT | Quota enforcement level |
| `rampControl` | object | null | Migration ramp settings |
| `migrationConfig` | object | null | Storage backend migration |

### 14.2 Property Details

#### 14.2.1 status

| Status | Effect |
|--------|--------|
| `ACTIVE` | All operations allowed |
| `INACTIVE` | TODO: Document behavior |

#### 14.2.2 aclInheritedByContainer

TODO: Document ACL inheritance
- How account ACLs apply to containers
- How container overrides work
- S3 equivalent (bucket policies?)

#### 14.2.3 quotaResourceType

| Type | Quota Enforcement |
|------|-------------------|
| `ACCOUNT` | Quota at account level |
| `CONTAINER` | Quota per container |

TODO: Document S3 equivalent

### 14.3 S3 Mapping

| Account Property | S3 Equivalent |
|------------------|---------------|
| `status` | TODO |
| `aclInheritedByContainer` | TODO |
| `quotaResourceType` | TODO |

---

## 15. Cross-Cutting Behaviors

### 15.1 TTL Semantics

TODO: Document TTL behavior that spans operations

- How TTL is calculated (creation time + seconds)
- Infinite TTL representation (`Utils.Infinite_Time` = -1)
- Interaction with delete/undelete
- S3 equivalent mechanism

### 15.2 Soft Delete Model

TODO: Document soft delete behavior

- Delete marks blob as deleted but retains data
- Undelete restores within retention window
- Retention period (OQ-011)
- S3 equivalent (versioning? delete markers?)

### 15.3 Composite Blob Model

TODO: Document chunking and stitching semantics

- Chunk upload process
- Stitch operation semantics
- Chunk lifecycle after stitch
- S3 equivalent (Multipart Upload?)

### 15.4 Authorization Model

TODO: Document account/container authorization

- Account ID and Container ID in blob properties
- Authorization failure conditions
- S3 equivalent (bucket policies? IAM?)

### 15.5 Error Handling Patterns

TODO: Document common error patterns

- Transient vs permanent errors
- Retry semantics
- Rate limiting (`TooManyRequests`)

---

## 16. Reference

### 16.1 RouterErrorCode → S3/HTTP Mapping

| RouterErrorCode | Description | S3/HTTP Equivalent |
|-----------------|-------------|-------------------|
| `AmbryUnavailable` | DataNodes unreachable | TODO |
| `InvalidBlobId` | Malformed blob ID | TODO |
| `InvalidPutArgument` | Illegal put argument | TODO |
| `OperationTimedOut` | Request timeout | TODO |
| `RouterClosed` | Router was closed | TODO |
| `UnexpectedInternalError` | Internal error | TODO |
| `BlobTooLarge` | Exceeds size limit | TODO |
| `BadInputChannel` | Input read error | TODO |
| `InsufficientCapacity` | Storage full | TODO |
| `BlobDeleted` | Soft-deleted | TODO |
| `BlobDoesNotExist` | Never existed | TODO |
| `BlobExpired` | TTL expired | TODO |
| `RangeNotSatisfiable` | Invalid byte range | TODO |
| `ChannelClosed` | Output channel closed | TODO |
| `BlobUpdateNotAllowed` | Update rejected | TODO |
| `BlobAuthorizationFailure` | Auth mismatch | TODO |
| `BlobUndeleted` | Already undeleted | TODO |
| `LifeVersionConflict` | Concurrent modification | TODO |
| `BlobNotDeleted` | Not deleted | TODO |
| `TooManyRequests` | Rate limited | TODO |
| `BlobCorrupted` | CRC check failed | TODO |

### 16.2 BlobProperties → S3 Metadata Mapping

| BlobProperty | Type | S3 Equivalent |
|--------------|------|---------------|
| `blobSize` | long | TODO |
| `serviceId` | String | TODO |
| `ownerId` | String | TODO |
| `contentType` | String | TODO (Content-Type?) |
| `contentEncoding` | String | TODO (Content-Encoding?) |
| `filename` | String | TODO |
| `isPrivate` | boolean | TODO (deprecated) |
| `timeToLiveInSeconds` | long | TODO |
| `creationTimeInMs` | long | TODO |
| `accountId` | short | TODO |
| `containerId` | short | TODO |
| `isEncrypted` | boolean | TODO |
| `externalAssetTag` | String | TODO (non-persistent) |
| `reservedMetadataBlobId` | String | TODO (non-persistent) |

### 16.3 Constants

| Constant | Value | Meaning |
|----------|-------|---------|
| `Utils.Infinite_Time` | -1 | Blob never expires |
| Max blob size | TODO: OQ-012 | |
| Max chunk count | TODO: OQ-008 | |
| Soft delete retention | TODO: OQ-011 | |

---

## 17. Conformance Test Suite

These tests validate behavioral equivalence. Each must pass for both Ambry and S3 implementation.

### 17.1 Lifecycle Tests

```
Test: put-then-get
  Given: I put a blob with properties P and data D
  When:  I get the blob with OperationType.All
  Then:  I receive BlobInfo with properties P and data D exactly

Test: put-delete-get
  Given: I put a blob
  When:  I delete the blob, then get it
  Then:  I receive error BlobDeleted

Test: put-delete-undelete-get
  Given: I put a blob with data D
  When:  I delete, then undelete, then get
  Then:  I receive data D exactly

Test: put-with-ttl-wait-get
  Given: I put a blob with TTL of T seconds
  When:  I wait longer than T seconds, then get
  Then:  I receive error BlobExpired
```

### 17.2 Error Condition Tests

```
Test: get-nonexistent
  Given: A blob ID that was never created
  When:  I get the blob
  Then:  I receive error BlobDoesNotExist

Test: undelete-never-deleted
  Given: I put a blob (not deleted)
  When:  I undelete the blob
  Then:  I receive error BlobNotDeleted

Test: undelete-already-undeleted
  Given: I put, delete, then undelete a blob
  When:  I undelete again
  Then:  I receive error BlobUndeleted

Test: undelete-expired
  Given: I put a blob with short TTL, delete it, wait for expiry
  When:  I undelete the blob
  Then:  I receive error BlobExpired
```

### 17.3 Range Request Tests

```
Test: get-range-valid
  Given: I put a blob with 1000 bytes
  When:  I get bytes 100-199
  Then:  I receive exactly 100 bytes matching original[100:200]

Test: get-range-invalid
  Given: I put a blob with 100 bytes
  When:  I get bytes 200-299
  Then:  I receive error RangeNotSatisfiable
```

### 17.4 Composite Blob Tests

```
Test: stitch-then-get
  Given: I put chunk1 (100 bytes) and chunk2 (100 bytes)
  When:  I stitch them, then get the composite blob
  Then:  I receive 200 bytes = chunk1 + chunk2

Test: stitch-get-chunk-ids
  Given: I stitch chunk1 and chunk2 into composite
  When:  I get composite with OperationType.BlobChunkIds
  Then:  I receive [chunk1_id, chunk2_id]
```

---

## 18. Open Questions

| ID | Question | Impact | Status |
|----|----------|--------|--------|
| OQ-001 | What happens when deleting a non-existent blob? | Delete idempotency | TODO |
| OQ-002 | What happens when deleting an already-deleted blob? | Delete idempotency | TODO |
| OQ-003 | What error when undeleting a non-existent blob? | Undelete error handling | TODO |
| OQ-004 | What happens when updating TTL on an expired blob? | TTL edge case | TODO |
| OQ-005 | Can TTL be shortened, or only extended? | TTL semantics | TODO |
| OQ-006 | Are chunks accessible after being stitched? | Composite blob behavior | TODO |
| OQ-007 | Can chunks be reused in multiple stitch operations? | Composite blob behavior | TODO |
| OQ-008 | What is the maximum number of chunks? | Stitch limits | TODO |
| OQ-009 | What error when stitching with non-existent chunk? | Stitch error handling | TODO |
| OQ-010 | What error when stitching with deleted chunk? | Stitch error handling | TODO |
| OQ-011 | What is the soft-delete retention period? | Undelete window | TODO |
| OQ-012 | What is the maximum blob size? | Size limits | TODO |

---

*Specification Version: 0.4 (Draft)*
