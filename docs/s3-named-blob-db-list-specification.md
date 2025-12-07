# S3NamedBlobDb List Operation Specification

**Version**: 1.0
**Status**: Draft
**Date**: 2025-12-06

## 1. Overview

This specification defines the implementation of `S3NamedBlobDb.list()` - an S3-backed implementation of the `NamedBlobDb` interface that returns responses compatible with the existing `MySqlNamedBlobDb` implementation.

### 1.1 Goals

1. Provide a drop-in replacement for `MySqlNamedBlobDb.list()` during S3 migration
2. Enable a `CompositeNamedBlobDb` to switch between MySQL and S3 backends
3. Support long-running migration with verification capabilities

### 1.2 Non-Goals

1. Byte-identical timestamps (accepted deviation - see Section 5.1)
2. Modifying other `NamedBlobDb` methods (out of scope for this document)

---

## 2. Ambry List API Analysis

### 2.1 API Entry Points

Ambry supports blob listing through two HTTP API patterns:

#### 2.1.1 Ambry Native API

```
GET /named/{accountName}/{containerName}?prefix={prefix}&page={pageToken}&maxKeys={maxKeys}&delimiter={delimiter}
```

**Handler Chain**:
```
FrontendRestRequestService.handleGet()          [line 335-338]
    └─► NamedBlobListHandler.handle()           [line 80]
            └─► CallbackChain.listRecursively() [line 190]
                    └─► NamedBlobDb.list()      [interface]
```

#### 2.1.2 S3-Compatible API

```
GET /s3/{accountName}/{containerName}?list-type=2&prefix={prefix}&continuation-token={token}&max-keys={maxKeys}&delimiter={delimiter}
```

**Handler Chain**:
```
FrontendRestRequestService.handleGet()          [line 332-334]
    └─► S3GetHandler
            └─► S3ListHandler.doHandle()        [line 92]
                    └─► NamedBlobListHandler    [delegates]
                            └─► NamedBlobDb.list()
```

### 2.2 Request Parameters

| Parameter | Ambry Name | S3 Name | Description | Default |
|-----------|------------|---------|-------------|---------|
| Account | Path: `{accountName}` | Path: `{accountName}` | Account identifier | Required |
| Container | Path: `{containerName}` | Path: `{containerName}` | Container identifier | Required |
| Prefix | `prefix` | `prefix` | Filter blobs starting with this prefix | `null` (list all) |
| Page Token | `page` | `continuation-token` (v2) / `marker` (v1) | Resume listing from this position | `null` (first page) |
| Max Keys | `maxKeys` | `max-keys` | Maximum entries per page | Config: `listMaxResults` (default 1000) |
| Delimiter | `delimiter` | `delimiter` | Group by directory (only `/` supported) | `null` (no grouping) |

### 2.3 Listing Modes

The `NamedBlobDb.list()` method supports three distinct listing modes based on parameters:

#### Mode 1: List All Blobs (No Prefix)

**Condition**: `prefix == null`

**MySQL Query** (`LIST_ALL_QUERY`):
```sql
SELECT t1.blob_name, t1.blob_id, t1.version, t1.deleted_ts, t1.blob_size, t1.modified_ts
FROM named_blobs_v2 t1
INNER JOIN (
    SELECT account_id, container_id, blob_name, max(version) as version
    FROM named_blobs_v2
    WHERE (account_id, container_id) = (?, ?)
      AND blob_state = 1  -- READY
      AND (deleted_ts IS NULL OR deleted_ts > UTC_TIMESTAMP())
    GROUP BY account_id, container_id, blob_name
) t2 ON (t1.account_id, t1.container_id, t1.blob_name, t1.version) =
        (t2.account_id, t2.container_id, t2.blob_name, t2.version)
WHERE CASE WHEN ? IS NOT NULL THEN t1.blob_name >= ? ELSE 1 END
ORDER BY t1.blob_name ASC
LIMIT ?
```

**S3 Implementation**:
```
ListObjectsV2Request.builder()
    .bucket(bucket)
    .prefix("{accountId}/{containerId}/")
    .startAfter(pageToken != null ? "{accountId}/{containerId}/" + pageToken : null)
    .maxKeys(maxKeys + 1)
    .build()
```

#### Mode 2: List with Prefix

**Condition**: `prefix != null`

**MySQL Query** (`LIST_WITH_PREFIX_SQL`):
```sql
-- Uses CTE or subquery depending on config.listNamedBlobsSQLOption
SELECT blob_name, blob_id, version, deleted_ts, blob_size, modified_ts
FROM named_blobs_v2
WHERE account_id = ? AND container_id = ?
  AND blob_state = 1  -- READY
  AND blob_name LIKE ?  -- prefix%
  AND blob_name >= ?    -- pageToken or prefix
  AND (deleted_ts IS NULL OR deleted_ts > UTC_TIMESTAMP())
  AND version = (SELECT MAX(version) ... )
ORDER BY blob_name ASC
LIMIT ?
```

**S3 Implementation**:
```
ListObjectsV2Request.builder()
    .bucket(bucket)
    .prefix("{accountId}/{containerId}/" + blobNamePrefix)
    .startAfter(pageToken != null ? "{accountId}/{containerId}/" + pageToken : null)
    .maxKeys(maxKeys + 1)
    .build()
```

#### Mode 3: List with Delimiter (Directory Grouping)

**Condition**: `delimiter == "/" && frontendConfig.enableDelimiter`

**Note**: Directory grouping is handled at the `NamedBlobListHandler` layer, not in `NamedBlobDb.list()`. The handler calls `extractDirectory()` to convert blob names into virtual directory entries.

**MySQL**: No change to query - filtering done in Java code.

**S3 Implementation**: Two options:

- **Option A (Recommended)**: Use S3 native delimiter support
  ```
  ListObjectsV2Request.builder()
      .prefix("{accountId}/{containerId}/" + blobNamePrefix)
      .delimiter("/")
      .build()
  ```
  Then map `CommonPrefixes` to directory `NamedBlobRecord` entries.

- **Option B**: Match MySQL behavior exactly - fetch all, filter in Java.
  This ensures byte-identical behavior but is less efficient.

**Decision**: Use **Option B** for migration phase to ensure identical behavior. Option A can be enabled post-migration via configuration.[^1]

[^1]: Option A (native S3 delimiter) is more efficient but may have subtle behavioral differences in edge cases like empty directory markers or blob names containing consecutive delimiters.

---

## 3. S3 Key Structure

### 3.1 Recommendation

```
{accountId}/{containerId}/{blobName}
```

**Example**:
```
101/501/documents/report.pdf
101/501/images/photo.jpg
```

### 3.2 Decision: Use IDs, Not Names

**Decision**: Use numeric `accountId` and `containerId` (shorts) instead of string names.

**Rationale**:
1. **MySQL Consistency**: The MySQL schema uses `account_id` (short) and `container_id` (short) as the primary key components. Using IDs maintains consistency.

2. **Name Changes**: Account/container names can theoretically change without affecting the underlying ID. Using IDs avoids key migration if names change.

3. **Key Length**: IDs are shorter than names (max 5 digits vs. potentially long strings), leaving more space for blob names within S3's 1024-byte key limit.

4. **Lookup Direction**: The `list()` method receives `accountName` and `containerName` as parameters. Use `AccountService` to resolve these to IDs before constructing S3 keys:
   ```java
   Account account = accountService.getAccountByName(accountName);
   Container container = account.getContainerByName(containerName);
   short accountId = account.getId();
   short containerId = container.getId();
   String s3Prefix = accountId + "/" + containerId + "/";
   ```

### 3.3 Rationale for Key Structure

1. **Prefix Filtering**: S3 `ListObjectsV2` prefix parameter maps directly to `{accountId}/{containerId}/{blobNamePrefix}`

2. **Pagination**: S3 `StartAfter` parameter accepts a full key, allowing use of `{accountId}/{containerId}/{pageToken}` where `pageToken` is the blob name

3. **Account/Container Isolation**: Natural partitioning by account and container

4. **No Encoding Required**: Blob names are used as-is (S3 keys support UTF-8)

### 3.4 Alternatives Considered

#### Alternative A: Include Metadata in Key

```
{accountId}/{containerId}/{blobName}__v{version}__{blobId}
```

**Rejected**:
- Max key length is 1024 bytes - metadata reduces available space for blobName
- Parsing complexity
- blobName cannot contain the separator sequence
- Version changes would require key rename (not atomic in S3)[^2]

[^2]: S3 does not support atomic key rename. Changing metadata encoded in the key would require copy + delete, risking data loss.

#### Alternative B: Flat Structure with Encoded Path

```
{accountId}_{containerId}_{base64(blobName)}
```

**Rejected**:
- Loses S3 prefix filtering capability
- Increases key length
- Makes debugging difficult[^3]

[^3]: Base64 encoding makes keys unreadable and prevents using S3 console for troubleshooting.

---

## 4. S3 Object Metadata Storage

### 4.1 Required Metadata Fields

Each S3 object must store the following user metadata:

| Metadata Key | Type | Source | Description |
|--------------|------|--------|-------------|
| `x-amz-meta-blob-id` | String | `NamedBlobRecord.blobId` | Ambry blob ID (Base64URL encoded) |
| `x-amz-meta-version` | String (long) | `NamedBlobRecord.version` | Named blob version number |
| `x-amz-meta-blob-state` | String (int) | `NamedBlobState.ordinal()` | 0=IN_PROGRESS, 1=READY |
| `x-amz-meta-deleted-ts` | String (long) | Soft delete timestamp | Epoch millis when blob was deleted, empty if active |
| `x-amz-meta-expiration-ms` | String (long) | TTL expiration | Epoch millis when blob expires, or "-1" for permanent |
| `x-amz-meta-modified-ms` | String (long) | `NamedBlobRecord.modifiedTimeMs` | Epoch millis (see Section 5.1) |

**Important - MySQL `deleted_ts` Column Dual-Use**:

In MySQL, the `deleted_ts` column serves **two distinct purposes**:
1. **TTL Expiration**: When a blob is created with a TTL, `deleted_ts` stores the future timestamp when the blob expires
2. **Soft Delete**: When a blob is explicitly deleted, `deleted_ts` is set to the deletion time

The MySQL query filters both cases with: `deleted_ts IS NULL OR deleted_ts > UTC_TIMESTAMP()`
- `NULL` means permanent blob that hasn't been deleted
- Future timestamp means either TTL expiration or scheduled deletion

**S3 Implementation Decision**: Store these as **separate metadata fields**:
- `x-amz-meta-expiration-ms`: The original TTL expiration (from blob creation)
- `x-amz-meta-deleted-ts`: The soft delete timestamp (only set when DELETE is called)

This separation allows S3NamedBlobDb to correctly filter blobs by checking both conditions independently.

### 4.2 Metadata Example

```
x-amz-meta-blob-id: AAEAAQAxLi4u
x-amz-meta-version: 1702012800000001
x-amz-meta-blob-state: 1
x-amz-meta-deleted-ts:
x-amz-meta-expiration-ms: -1
x-amz-meta-modified-ms: 1702012800123
```

### 4.3 S3 Size Field

The `blobSize` field can be obtained directly from S3 `ListObjectsV2` response (`S3Object.size()`). No metadata storage required.

---

## 5. Data Mapping Considerations

### 5.1 Timestamp Precision

**MySQL**: Stores `modified_ts` as `TIMESTAMP` with millisecond precision.

**S3**: `LastModified` has second precision only.

**Decision**: Store `modifiedTimeMs` in object metadata (`x-amz-meta-modified-ms`). Accept that S3-native timestamps will differ.

**Implication**: During verification, timestamp comparison should use metadata value, not S3 `LastModified`.

### 5.2 Version Semantics

**MySQL**: `version` is a monotonically increasing value (typically timestamp-based).

**S3**: Has native versioning, but semantics differ.

**Decision**: Store Ambry version in metadata. Do not rely on S3 versioning for this purpose.

### 5.2.1 Overwrite Handling (Same Blob Name PUT)

When a blob is PUT with the same name as an existing blob:

**MySQL Behavior**:
- New row is inserted with a higher `version` value
- Old version remains in the database but is excluded from queries by the `MAX(version)` subquery
- List returns only the latest version

**S3 Implementation Decision**:
- **Option A (Recommended)**: Overwrite the existing S3 object with new content and metadata
  - Simpler implementation
  - No orphaned objects
  - Matches S3 native behavior
  - Version number in metadata always reflects the "current" version

- **Option B**: Use S3 versioning to maintain history
  - Requires S3 bucket versioning enabled
  - More complex list filtering (must filter by latest version)
  - Higher storage costs

**Decision**: Use **Option A** (overwrite). The `list()` operation returns only the current version, so maintaining version history in S3 is unnecessary for API compatibility. Version history in MySQL is a side-effect of the insert-only schema, not a required feature.

**Implication**: During migration, if an implementer needs to verify version matching:
```java
// In CompositeNamedBlobDb verification
NamedBlobRecord mysqlRecord = mysqlDb.get(account, container, blobName).join();
NamedBlobRecord s3Record = s3Db.get(account, container, blobName).join();

// Version may differ if writes happened only to one backend
// This is expected during migration
if (mysqlRecord.getVersion() != s3Record.getVersion()) {
    log.warn("Version mismatch for {}: mysql={}, s3={}",
        blobName, mysqlRecord.getVersion(), s3Record.getVersion());
}
```

### 5.3 Null Expiration

**MySQL**: `deleted_ts = NULL` means blob never expires.

**S3 Metadata**: Use empty string or "-1" to represent no expiration.

```java
// Writing
if (expirationTimeMs == Utils.Infinite_Time) {
    metadata.put("x-amz-meta-expiration-ms", "-1");
} else {
    metadata.put("x-amz-meta-expiration-ms", String.valueOf(expirationTimeMs));
}

// Reading
String expStr = metadata.get("x-amz-meta-expiration-ms");
long expirationTimeMs = (expStr == null || expStr.isEmpty() || "-1".equals(expStr))
    ? Utils.Infinite_Time
    : Long.parseLong(expStr);
```

---

## 6. Filtering Strategy

### 6.1 Constraint: No Per-Object Metadata Calls

**Problem**: S3 `ListObjectsV2` does not return user metadata. Retrieving metadata requires a `HeadObject` call per object.

**Rejected Approach**: Calling HeadObject for each listed object to filter by `blob_state`, `deleted_ts`, and `expiration_ms`. For a page of 1000 objects, this would require 1000+ API calls, resulting in unacceptable latency.

**Decision**: Use `ListObjectsV2` only. Accept that S3 list cannot filter by metadata.

### 6.2 Behavioral Differences from MySQL

Because S3 list cannot filter by metadata, the following differences exist:

| Scenario | MySQL Behavior | S3 Behavior |
|----------|---------------|-------------|
| IN_PROGRESS blobs | Excluded from list | **Included in list** |
| Soft-deleted blobs | Excluded from list | **Included in list** |
| Expired blobs (TTL passed) | Excluded from list | **Included in list** |

**Client Impact**: Clients may see objects in list results that they cannot access. Subsequent `GET` or `DELETE` calls for these objects will fail with appropriate errors.

### 6.3 Implementation

```java
public CompletableFuture<Page<NamedBlobRecord>> list(
        String accountName, String containerName,
        String blobNamePrefix, String pageToken, Integer maxKeys) {

    return CompletableFuture.supplyAsync(() -> {
        String s3Prefix = buildS3Prefix(accountName, containerName, blobNamePrefix);

        ListObjectsV2Response response = s3Client.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(s3Prefix)
                .startAfter(pageToken != null ? s3Prefix + pageToken : null)
                .maxKeys(maxKeys + 1)
                .build());

        List<NamedBlobRecord> results = response.contents().stream()
            .map(s3Obj -> buildRecordFromS3(s3Obj, accountName, containerName))
            .collect(Collectors.toList());

        String nextToken = null;
        if (results.size() > maxKeys) {
            nextToken = results.remove(maxKeys).getBlobName();
        }

        return new Page<>(results, nextToken);
    });
}

private NamedBlobRecord buildRecordFromS3(S3Object s3Obj,
        String accountName, String containerName) {
    return new NamedBlobRecord(
        accountName,
        containerName,
        extractBlobName(s3Obj.key()),
        "",   // blobId - not available from ListObjectsV2, not in client response
        -1L,  // expirationTimeMs - not available, use -1 (permanent)
        0L,   // version - not available from ListObjectsV2, not in client response
        s3Obj.size(),
        s3Obj.lastModified().toEpochMilli(),
        false
    );
}
```

### 6.4 Fields Not Available from ListObjectsV2

| Field | ListObjectsV2 | NamedBlobRecord Value | Client Impact |
|-------|---------------|----------------------|---------------|
| `blobId` | Not available | Empty string `""` | Not in JSON response |
| `version` | Not available | `0L` | Not in JSON response |
| `expirationTimeMs` | Not available | `-1L` (permanent) | Shown as non-expiring |

**Note**: `blobId` and `version` are internal fields not exposed in the HTTP response JSON. The placeholder values do not affect clients.

---

## 7. Pagination Token Handling

### 7.1 MySQL Behavior

MySQL uses the **blob name** as the pagination token:

```java
// MySqlNamedBlobDb.java:643-646
if (resultIndex++ == maxKeysValue) {
    nextContinuationToken = blobName;  // The (maxKeys+1)th blob's name
    break;
}
```

### 7.2 S3 Implementation

S3's `ContinuationToken` is an opaque string that cannot be used interchangeably with blob names.

**Solution**: Use `StartAfter` parameter instead:

```java
ListObjectsV2Request.builder()
    .bucket(bucket)
    .prefix(s3Prefix)
    .startAfter(pageToken != null ? s3Prefix + pageToken : null)
    .maxKeys(maxKeys + 1)
    .build()
```

### 7.3 Token Generation

Match MySQL's `maxKeys + 1` pattern:

```java
// Fetch maxKeys + 1 objects
List<NamedBlobRecord> results = fetchAndFilter(s3Prefix, pageToken, maxKeys + 1);

String nextToken = null;
if (results.size() > maxKeys) {
    // The (maxKeys+1)th blob's name becomes the token
    NamedBlobRecord extra = results.remove(maxKeys);
    nextToken = extra.getBlobName();
}

return new Page<>(results, nextToken);
```

### 7.4 Edge Cases

| Scenario | MySQL Behavior | S3 Implementation |
|----------|---------------|-------------------|
| Empty result | `nextPageToken = null` | Same - `nextToken = null` |
| Exactly `maxKeys` results | `nextPageToken = null` (no more data) | Must verify with additional request or use `IsTruncated` |
| Token points to deleted blob | Skips to next valid blob | Same - filtering handles this |

---

## 8. Response Format

### 8.1 NamedBlobRecord Fields

Each list entry returns a `NamedBlobRecord` with:

| Field | Type | Source (MySQL) | Source (S3) |
|-------|------|----------------|-------------|
| `accountName` | String | Input parameter | Input parameter |
| `containerName` | String | Input parameter | Input parameter |
| `blobName` | String | `blob_name` column | Key suffix after `{acctId}/{contId}/` |
| `blobId` | String | `blob_id` (Base64) | `x-amz-meta-blob-id` |
| `expirationTimeMs` | long | `deleted_ts` column (see note below) | `x-amz-meta-expiration-ms` |
| `version` | long | `version` column | `x-amz-meta-version` |
| `blobSize` | long | `blob_size` column | `S3Object.size()` |
| `modifiedTimeMs` | long | `modified_ts` column | `x-amz-meta-modified-ms` |
| `isDirectory` | boolean | `false` (hardcoded) | `false` (hardcoded)[^5]

**Note on expirationTimeMs**: In MySQL, this is read from `deleted_ts` which stores both TTL expiration and soft delete times (see Section 4.1). In S3, we use the separate `x-amz-meta-expiration-ms` field for clarity.

[^5]: Directory entries (`isDirectory=true`) are created by `NamedBlobListHandler.extractDirectory()`, not by `NamedBlobDb.list()`. The database layer always returns `isDirectory=false`.

### 8.2 Page Structure

```java
public class Page<T> {
    private final List<T> entries;      // List of NamedBlobRecord
    private final String nextPageToken; // null if no more pages
}
```

### 8.3 JSON Serialization (via NamedBlobListEntry)

```json
{
  "entries": [
    {
      "blobName": "documents/report.pdf",
      "blobSize": 1048576,
      "modifiedTimeMs": 1702012800123,
      "isDirectory": false
    },
    {
      "blobName": "images/photo.jpg",
      "expirationTimeMs": 1704067200000,
      "blobSize": 524288,
      "modifiedTimeMs": 1702012800456,
      "isDirectory": false
    }
  ],
  "nextPageToken": "images/photo2.jpg"
}
```

**Note**: `expirationTimeMs` is omitted when value is `-1` (infinite).

---

## 9. Ordering Guarantees

### 9.1 MySQL Ordering

```sql
ORDER BY blob_name ASC
```

Lexicographic ordering by blob name using MySQL's default collation.

### 9.2 S3 Ordering

S3 returns keys in **UTF-8 binary sort order**, which matches lexicographic ordering for ASCII characters.

### 9.3 Compatibility

For standard blob names (ASCII alphanumeric, common punctuation), ordering is identical.

**Potential Divergence**: Unicode characters may sort differently between MySQL collation and S3's UTF-8 binary sort.

**Mitigation**: Document that blob names should use ASCII for guaranteed ordering consistency during migration.

---

## 10. Implementation Checklist

### 10.1 S3NamedBlobDb Class

```java
public class S3NamedBlobDb implements NamedBlobDb {

    private final S3Client s3Client;
    private final String bucket;
    private final AccountService accountService;
    private final ExecutorService executor;

    @Override
    public CompletableFuture<Page<NamedBlobRecord>> list(
            String accountName, String containerName,
            String blobNamePrefix, String pageToken, Integer maxKeys) {
        // Implementation per this specification
    }

    // Helper methods
    private String buildS3Prefix(String accountName, String containerName, String prefix);
    private String extractBlobName(String s3Key, String s3Prefix);
    private NamedBlobRecord buildRecordFromS3(String accountName, String containerName,
            S3Object s3Obj, Map<String, String> metadata);
    private boolean passesFilters(Map<String, String> metadata);
}
```

### 10.2 CompositeNamedBlobDb Class

```java
public class CompositeNamedBlobDb implements NamedBlobDb {

    private final MySqlNamedBlobDb mysqlDb;
    private final S3NamedBlobDb s3Db;
    private final MigrationConfig config;

    @Override
    public CompletableFuture<Page<NamedBlobRecord>> list(...) {
        switch (config.getListSource()) {
            case MYSQL:
                return mysqlDb.list(...);
            case S3:
                return s3Db.list(...);
            case MYSQL_WITH_S3_VERIFY:
                return mysqlDb.list(...).thenApply(mysqlPage -> {
                    verifyAsync(mysqlPage, s3Db.list(...));
                    return mysqlPage;
                });
            case S3_WITH_MYSQL_FALLBACK:
                return s3Db.list(...).exceptionally(e -> {
                    log.warn("S3 list failed, falling back to MySQL", e);
                    return mysqlDb.list(...).join();
                });
        }
    }
}
```

---

## 11. S3 Bucket Configuration

### 11.1 Required Bucket Settings

| Setting | Value | Rationale |
|---------|-------|-----------|
| **Versioning** | Disabled | Not needed (we track version in metadata); reduces storage costs |
| **Object Lock** | Disabled | Ambry handles deletion/TTL logic; object lock would interfere |
| **Default Encryption** | SSE-S3 or SSE-KMS | Encrypt data at rest |
| **Block Public Access** | All blocked | Security best practice |
| **Access Logging** | Enabled (recommended) | Audit trail for troubleshooting |

### 11.2 Bucket Naming Convention

**Recommendation**: `ambry-namedblob-{environment}-{region}`

**Example**: `ambry-namedblob-prod-us-west-2`

### 11.3 Lifecycle Rules

**Decision**: Do NOT use S3 lifecycle rules for TTL expiration.

**Rationale**:
- Ambry TTL is tracked in metadata (`x-amz-meta-expiration-ms`)
- S3 lifecycle operates on object creation date, not custom metadata
- List filtering already excludes expired blobs
- A separate cleanup job should be implemented to hard-delete expired objects

**Recommended Cleanup Job**:
```java
// Run periodically (e.g., daily) to hard-delete expired/soft-deleted objects
public void cleanupExpiredObjects() {
    ListObjectsV2Response response = s3Client.listObjectsV2(...);
    for (S3Object obj : response.contents()) {
        HeadObjectResponse head = s3Client.headObject(...);
        Map<String, String> metadata = head.metadata();

        long now = System.currentTimeMillis();
        long expiration = parseLong(metadata.get("expiration-ms"), -1);
        long deletedTs = parseLong(metadata.get("deleted-ts"), Long.MAX_VALUE);

        // Hard delete if expired more than 7 days ago OR soft-deleted more than 30 days ago
        if ((expiration > 0 && expiration < now - DAYS_7) ||
            (deletedTs < now - DAYS_30)) {
            s3Client.deleteObject(...);
        }
    }
}
```

### 11.4 IAM Permissions Required

The S3NamedBlobDb implementation requires the following S3 permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket",
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:HeadObject"
      ],
      "Resource": [
        "arn:aws:s3:::ambry-namedblob-*",
        "arn:aws:s3:::ambry-namedblob-*/*"
      ]
    }
  ]
}
```

---

## 12. Migration Phases

| Phase | List Source | Verification | Notes |
|-------|-------------|--------------|-------|
| 1. Baseline | MySQL | None | Current state |
| 2. Dual-Write | MySQL | Async S3 compare | Log discrepancies |
| 3. S3 Primary | S3 | MySQL fallback | Monitor error rates |
| 4. S3 Only | S3 | None | Deprecate MySQL |

---

## 13. Error Handling

### 13.1 S3 API Errors

| Error | Handling |
|-------|----------|
| `NoSuchBucket` | Fail request, log error (configuration issue) |
| `AccessDenied` (403) | Fail request, log error (permissions issue) |
| `ServiceUnavailable` (503) | Retry with exponential backoff |
| `SlowDown` (503) | Retry with exponential backoff |

### 13.2 Account/Container Resolution Errors

| Scenario | Handling |
|----------|----------|
| Account not found | Return empty page (no objects exist for unknown account) |
| Container not found | Return empty page (no objects exist for unknown container) |

---

## 14. Behavioral Differences: S3 vs MySQL

This section documents all known behavioral differences between `S3NamedBlobDb.list()` and `MySqlNamedBlobDb.list()`. Clients migrating to S3 backend should be aware of these differences.

### 14.1 Filtering Differences

| Behavior | MySQL | S3 | Client Impact |
|----------|-------|-----|---------------|
| IN_PROGRESS blobs | Excluded | Included | Client sees blob in list, but GET fails |
| Soft-deleted blobs | Excluded | Included | Client sees blob in list, but GET returns 404 or deleted status |
| Expired blobs | Excluded | Included | Client sees blob in list, but GET may fail |

**Recommendation**: Clients should handle GET failures gracefully after list results. This is already good practice for eventually-consistent systems.

### 14.2 Response Field Differences

| Field | MySQL | S3 | Client-Visible |
|-------|-------|-----|----------------|
| `blobName` | From DB | From S3 key | Yes - identical |
| `blobSize` | From DB | From S3 Size | Yes - identical |
| `modifiedTimeMs` | From DB (ms precision) | From S3 LastModified (s precision) | Yes - **may differ by up to 999ms** |
| `expirationTimeMs` | From DB | Always `-1` | Yes - **always shows as permanent** |
| `blobId` | From DB | Placeholder `""` | No - not in JSON |
| `version` | From DB | Placeholder `0` | No - not in JSON |

### 14.3 Timestamp Precision

**MySQL**: Stores `modified_ts` with millisecond precision.

**S3**: `LastModified` has second precision only.

**Impact**: `modifiedTimeMs` values may differ by up to 999 milliseconds between MySQL and S3 for the same blob.

### 14.4 Expiration Time Display

**MySQL**: Returns actual `expirationTimeMs` for blobs with TTL.

**S3**: Always returns `-1` (permanent) because expiration is stored in metadata, not accessible via ListObjectsV2.

**Impact**: Clients cannot determine blob TTL from S3 list results. The `expirationTimeMs` field will be omitted from JSON response (as it is for permanent blobs).

### 14.5 Ordering

**MySQL**: Lexicographic order using `utf8mb4_bin` collation (binary/case-sensitive).

**S3**: UTF-8 binary sort order.

**Impact**: Ordering is **identical** for both MySQL and S3. Both use binary/case-sensitive ordering. Example:
- MySQL: `A`, `B`, `a`, `b` (binary collation)
- S3: `A`, `B`, `a`, `b` (binary sort)

No ordering differences expected.

### 14.6 Consistency During Migration

During dual-write migration phase:
- A blob may exist in MySQL but not yet in S3 (or vice versa)
- List results may differ between backends
- Use `CompositeNamedBlobDb` verification mode to detect discrepancies

### 14.7 Summary for Client Developers

If your application is migrating from MySQL to S3 backend:

1. **Expect more results**: S3 list may return blobs that MySQL would filter out (IN_PROGRESS, soft-deleted, expired)
2. **Handle GET failures**: A blob appearing in list doesn't guarantee GET success
3. **Don't rely on expirationTimeMs**: S3 list always returns `-1` (field omitted from response)
4. **Expect timestamp variance**: `modifiedTimeMs` may differ by up to 1 second
5. **Ordering is preserved**: Both MySQL and S3 use binary/case-sensitive ordering

---

## 15. Open Questions

### 15.1 Implementation Questions

#### 1. Large Container Performance

**Question**: For containers with millions of objects, S3 list may be slow.

**Decision**: S3 implementation should match or exceed MySQL performance. Measure and monitor latency, but do not change client expectations. If S3 is slower, that's an implementation issue to fix, not a specification change.

**Metrics to collect**:
- `s3_list_latency_p50_ms`
- `s3_list_latency_p99_ms`
- `s3_list_objects_returned`

---

#### 2. Cleanup Job Implementation

**Question**: Who is responsible for implementing the cleanup job that hard-deletes expired and soft-deleted objects from S3?

**Status**: Out of scope for this specification. Requires separate design document.

---

#### 3. Migration Verification

**Question**: What metrics should be collected during dual-write phase?

**Decision**: Time-based sampling with full comparison on async thread.

**Sampling Strategy**:
- Compare at most once per `VERIFICATION_INTERVAL_MS` (configurable, e.g., 60000ms)
- Use token bucket or time-based gate to prevent system strain
- Verification runs on separate thread, does not block response

**Comparison Fields** (fields guaranteed to match):

| Field | Compare | Rationale |
|-------|---------|-----------|
| `blobName` | ✓ Yes | Derived from S3 key, must match |
| `blobSize` | ✓ Yes | S3 Size matches MySQL blob_size |
| `blobId` | ✗ No | Not available from ListObjectsV2 |
| `version` | ✗ No | Not available from ListObjectsV2 |
| `expirationTimeMs` | ✗ No | Not available from ListObjectsV2 |
| `modifiedTimeMs` | ✗ No | Precision differs (ms vs s) |

**Expected Differences** (not failures):
- S3 may include extra entries (IN_PROGRESS, soft-deleted, expired blobs)
- These are documented behavioral differences, not comparison failures

**Implementation**:
```java
public class ListVerificationService {
    private final AtomicLong lastVerificationTime = new AtomicLong(0);
    private final long verificationIntervalMs;
    private final ExecutorService verificationExecutor;

    public void maybeVerifyAsync(Page<NamedBlobRecord> mysqlPage,
                                  Supplier<Page<NamedBlobRecord>> s3PageSupplier,
                                  String accountName, String containerName) {
        long now = System.currentTimeMillis();
        long last = lastVerificationTime.get();

        if (now - last < verificationIntervalMs) {
            return;  // Too soon since last verification
        }

        if (!lastVerificationTime.compareAndSet(last, now)) {
            return;  // Another thread started verification
        }

        verificationExecutor.submit(() -> {
            try {
                Page<NamedBlobRecord> s3Page = s3PageSupplier.get();
                comparePages(mysqlPage, s3Page, accountName, containerName);
            } catch (Exception e) {
                metrics.verificationErrorCount.inc();
                log.warn("Verification failed for {}/{}", accountName, containerName, e);
            }
        });
    }

    private void comparePages(Page<NamedBlobRecord> mysql, Page<NamedBlobRecord> s3,
                              String accountName, String containerName) {
        Set<String> mysqlNames = mysql.getEntries().stream()
            .map(NamedBlobRecord::getBlobName)
            .collect(Collectors.toSet());

        Set<String> s3Names = s3.getEntries().stream()
            .map(NamedBlobRecord::getBlobName)
            .collect(Collectors.toSet());

        // Missing in S3 = potential sync issue
        Set<String> missingInS3 = new HashSet<>(mysqlNames);
        missingInS3.removeAll(s3Names);

        // Extra in S3 = expected (IN_PROGRESS, deleted, expired)
        Set<String> extraInS3 = new HashSet<>(s3Names);
        extraInS3.removeAll(mysqlNames);

        metrics.verificationCount.inc();
        metrics.missingInS3Count.inc(missingInS3.size());
        metrics.extraInS3Count.inc(extraInS3.size());

        if (!missingInS3.isEmpty()) {
            log.warn("Verification: {} blobs missing in S3 for {}/{}",
                missingInS3.size(), accountName, containerName);
        }

        // Compare sizes for matching blobs
        Map<String, Long> mysqlSizes = mysql.getEntries().stream()
            .collect(Collectors.toMap(NamedBlobRecord::getBlobName, NamedBlobRecord::getBlobSize));

        for (NamedBlobRecord s3Record : s3.getEntries()) {
            Long mysqlSize = mysqlSizes.get(s3Record.getBlobName());
            if (mysqlSize != null && !mysqlSize.equals(s3Record.getBlobSize())) {
                metrics.sizeMismatchCount.inc();
                log.warn("Size mismatch for {}: mysql={}, s3={}",
                    s3Record.getBlobName(), mysqlSize, s3Record.getBlobSize());
            }
        }
    }
}
```

**Metrics**:
- `verification_count` - Total verifications performed
- `missing_in_s3_count` - Blobs in MySQL but not S3 (potential issue)
- `extra_in_s3_count` - Blobs in S3 but not MySQL (expected)
- `size_mismatch_count` - Blobs with different sizes (error)
- `verification_error_count` - Verification failures

---

#### 4. Client Communication

**Question**: How will existing Ambry clients be notified of behavioral differences?

**Status**: Requires product decision. Options:
- **Option A**: Release notes only
- **Option B**: Migration guide document
- **Option C**: Both A and B

### 15.2 Edge Case Behavior Questions

The following questions must be answered to ensure S3 implementation matches MySQL behavior where possible:

#### Q1: Empty String vs Null Prefix

**Question**: Is `prefix=""` equivalent to `prefix=null`? Should both return all blobs?

**Answer from codebase** (`MySqlNamedBlobDb.java:622`):
```java
String queryStatement = blobNamePrefix == null ? LIST_ALL_QUERY : LIST_WITH_PREFIX_SQL;
```

- `null` → Uses `LIST_ALL_QUERY` (no LIKE clause)
- `""` → Uses `LIST_WITH_PREFIX_SQL` with pattern `"%"` (matches all)

**S3 Implementation Options**:
- **Option A**: Treat `""` same as `null` - use empty prefix in ListObjectsV2
- **Option B**: Treat `""` literally - prefix with empty string (same result in S3)

**Decision**: **Option A** - both `null` and `""` result in listing all blobs. Functionally equivalent.

---

#### Q2: Case Sensitivity

**Question**: Is blob name matching case-sensitive? If MySQL uses case-insensitive collation and S3 is case-sensitive, is this a breaking change?

**Answer from codebase** (`NamedBlobsSchema.ddl:17`):
```sql
COLLATE=utf8mb4_bin
```

MySQL uses **binary collation** which is **case-sensitive**. S3 is also case-sensitive.

**Decision**: No breaking change. Both MySQL and S3 are case-sensitive. `Foo.txt` and `foo.txt` are different blobs.

---

#### Q3: Special Characters in Blob Names

**Question**: What characters are valid in blob names? Can they contain `/`, `\0`, or other special characters?

**Answer from codebase** (`NamedBlobsSchema.ddl:5`):
```sql
blob_name varchar(350) NOT NULL
```

MySQL `varchar` with `utf8mb4` charset accepts any UTF-8 characters except null byte (`\0`).

**S3 Restrictions**: S3 keys can contain any UTF-8 characters. Some characters require URL encoding in the API but are stored as-is.

**S3 Implementation Options**:
- **Option A**: Accept same characters as MySQL - any UTF-8 except `\0`
- **Option B**: Add validation to reject S3-problematic characters (none significant)

**Decision**: **Option A** - Accept any valid UTF-8 characters. The `/` character is valid and commonly used for virtual directories.

---

#### Q4: Maximum Blob Name Length

**Question**: What is the maximum blob name length?

**Answer from codebase** (`NamedBlobsSchema.ddl:5`):
```sql
blob_name varchar(350) NOT NULL
```

- **MySQL**: 350 characters (not bytes)
- **S3**: 1024 bytes total key length, minus `{accountId}/{containerId}/` prefix (~12 bytes max)

**S3 Implementation Options**:
- **Option A**: Enforce 350-character limit (MySQL compatibility)
- **Option B**: Allow up to ~1012 bytes (S3 limit minus prefix)

**Decision**: **Option A** - Enforce 350-character limit to match MySQL. Blobs created in MySQL must be retrievable from S3.

---

#### Q5: Account/Container Not Found

**Question**: If account or container doesn't exist, what should list return?

**Answer from codebase** (`AccountAndContainerInjector.java`):
- Invalid account → `RestServiceErrorCode.InvalidAccount` (HTTP 400)
- Invalid container → `RestServiceErrorCode.InvalidContainer` (HTTP 400)

The validation happens in `accountAndContainerInjector.injectAccountContainerForNamedBlob()` before reaching `NamedBlobDb.list()`.

**S3 Implementation Options**:
- **Option A**: Return error (match current behavior)
- **Option B**: Return empty page (S3 would return empty for non-existent prefix)

**Decision**: **Option A** - The handler validates account/container before calling `list()`. S3NamedBlobDb can assume valid account/container. If validation is bypassed, return empty page.

---

#### Q6: Invalid Page Token

**Question**: If a client passes a malformed or non-existent page token, what happens?

**Answer from codebase** (`MySqlNamedBlobDb.java:678-679`):
```java
statement.setString(3, pageToken);
statement.setString(4, pageToken);
// Used in: WHERE blob_name >= ?
```

Page token is used directly in `WHERE blob_name >= ?`. A non-existent token simply starts listing from that lexicographic position.

**S3 Implementation Options**:
- **Option A**: Use token as `StartAfter` parameter (same behavior)
- **Option B**: Validate token exists before using

**Decision**: **Option A** - Use token directly in `StartAfter`. Invalid/non-existent tokens start listing from that position. This matches MySQL behavior.

---

#### Q7: maxKeys Edge Cases

**Question**: What happens when `maxKeys=0`, `maxKeys=null`, `maxKeys<0`, or `maxKeys>10000`?

**Answer from codebase**:
- `FrontendConfig.java:385`: `getIntInRange(LIST_MAX_RESULTS, DEFAULT_MAX_KEY_VALUE, 1, Integer.MAX_VALUE)`
- `RestUtils.java:74`: `DEFAULT_MAX_KEY_VALUE = 1000`
- `MySqlNamedBlobDb.java:623`: `maxKeys == null ? config.listMaxResults : maxKeys`

Client-provided `maxKeys` is parsed as integer but not bounds-checked at handler level.

**S3 Implementation Options**:
- **Option A**: Accept any positive integer, cap at S3's max (1000 per request), paginate internally
- **Option B**: Validate and reject invalid values

**Decision**: **Option A** - Accept any integer. Use default (1000) for `null`. For values ≤ 0, use default. For large values, internally paginate S3 requests (S3 max is 1000 per ListObjectsV2).

---

#### Q8: Concurrent Modification During Pagination

**Question**: If blobs are created/deleted between paginated list calls, what consistency guarantees exist?

**Answer from codebase**: No explicit consistency guarantees documented.

**MySQL behavior**: Each page is a separate query. Blobs created after page 1 may appear in page 2 if their name sorts after the token. Deleted blobs won't appear.

**S3 behavior**: S3 provides strong read-after-write consistency. Same behavior as MySQL.

**S3 Implementation Options**:
- **Option A**: Document "no consistency guarantees during pagination"
- **Option B**: Implement snapshot isolation (complex, not in MySQL)

**Decision**: **Option A** - No special consistency guarantees. Clients should expect that concurrent modifications may result in a blob appearing in multiple pages or not appearing at all. This matches MySQL behavior.

---

#### Q9: Utils.Infinite_Time Value

**Question**: What is the actual value of `Utils.Infinite_Time`?

**Answer from codebase** (`Utils.java`):
```java
public static final long Infinite_Time = -1;
```

**Decision**: Use `-1` to represent "no expiration" / "permanent" in S3NamedBlobDb.

---

#### Q10: Future Soft Delete Timestamp

**Question**: Can a blob be soft-deleted with a future `deleted_ts` (scheduled deletion)? If so, should it appear in list until that time?

**Answer from codebase** (`LIST_ALL_QUERY`):
```sql
AND (deleted_ts IS NULL OR deleted_ts > UTC_TIMESTAMP())
```

A blob with `deleted_ts` in the future **will appear** in list results. It becomes invisible only after that timestamp passes.

**S3 Implementation**: This is a filtering difference. S3 list cannot filter by metadata, so:
- Blobs with future `deleted_ts` will appear (correct, matches MySQL)
- Blobs with past `deleted_ts` will also appear (different from MySQL)

**Decision**: Documented in Section 14.1 as a behavioral difference.

---

#### Q11: Response Field Omission Rules

**Question**: Which fields can be omitted from JSON response?

**Answer from codebase** (`NamedBlobListEntry.java:106-114`):
```java
public JSONObject toJson() {
    JSONObject jsonObject = new JSONObject().put(BLOB_NAME_KEY, blobName);
    if (expirationTimeMs != Utils.Infinite_Time) {
      jsonObject.put(EXPIRATION_TIME_MS_KEY, expirationTimeMs);
    }
    jsonObject.put(BLOB_SIZE_KEY, blobSize);
    jsonObject.put(MODIFIED_TIME_MS_KEY, modifiedTimeMs);
    jsonObject.put(IS_DIRECTORY_KEY, isDirectory);
    return jsonObject;
}
```

| Field | Omission Rule |
|-------|---------------|
| `blobName` | Always included |
| `expirationTimeMs` | Omitted if `-1` (permanent) |
| `blobSize` | Always included |
| `modifiedTimeMs` | Always included |
| `isDirectory` | Always included |

**Decision**: Only `expirationTimeMs` is conditionally omitted. Since S3 list always returns `-1` for this field, it will always be omitted from S3 list responses.

---

## Appendix A: Reference Files

| Component | File Path |
|-----------|-----------|
| NamedBlobDb Interface | `ambry-api/src/main/java/com/github/ambry/named/NamedBlobDb.java` |
| MySqlNamedBlobDb | `ambry-named-mysql/src/main/java/com/github/ambry/named/MySqlNamedBlobDb.java` |
| NamedBlobRecord | `ambry-api/src/main/java/com/github/ambry/named/NamedBlobRecord.java` |
| NamedBlobListHandler | `ambry-frontend/src/main/java/com/github/ambry/frontend/NamedBlobListHandler.java` |
| NamedBlobListEntry | `ambry-api/src/main/java/com/github/ambry/frontend/NamedBlobListEntry.java` |
| Page | `ambry-api/src/main/java/com/github/ambry/frontend/Page.java` |
| S3ListHandler | `ambry-frontend/src/main/java/com/github/ambry/frontend/s3/S3ListHandler.java` |
| NamedBlobState | `ambry-api/src/main/java/com/github/ambry/protocol/NamedBlobState.java` |

## Appendix B: SQL Query Reference

### LIST_ALL_QUERY (No Prefix)
```sql
SELECT t1.blob_name, t1.blob_id, t1.version, t1.deleted_ts, t1.blob_size, t1.modified_ts
FROM named_blobs_v2 t1
INNER JOIN (
    SELECT account_id, container_id, blob_name, max(version) as version
    FROM named_blobs_v2
    WHERE (account_id, container_id) = (?, ?)
      AND blob_state = 1
      AND (deleted_ts IS NULL OR deleted_ts > UTC_TIMESTAMP())
    GROUP BY account_id, container_id, blob_name
) t2 ON (t1.account_id, t1.container_id, t1.blob_name, t1.version) =
        (t2.account_id, t2.container_id, t2.blob_name, t2.version)
WHERE CASE WHEN ? IS NOT NULL THEN t1.blob_name >= ? ELSE 1 END
ORDER BY t1.blob_name ASC
LIMIT ?
```

### LIST_WITH_PREFIX_SQL (With Prefix, Option 3)
```sql
SELECT candidate.blob_name, candidate.blob_id, candidate.version,
       candidate.deleted_ts, candidate.blob_size, candidate.modified_ts
FROM named_blobs_v2 candidate
WHERE candidate.account_id = ?
  AND candidate.container_id = ?
  AND candidate.blob_state = 1
  AND candidate.blob_name LIKE ?
  AND candidate.blob_name >= ?
  AND (candidate.deleted_ts IS NULL OR candidate.deleted_ts > UTC_TIMESTAMP())
  AND candidate.version = (
      SELECT MAX(latest.version)
      FROM named_blobs_v2 latest
      WHERE latest.account_id = ?
        AND latest.container_id = ?
        AND latest.blob_name = candidate.blob_name
        AND latest.blob_state = 1
  )
LIMIT ?
```
