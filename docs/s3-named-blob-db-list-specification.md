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

### 3.2 Rationale

1. **Prefix Filtering**: S3 `ListObjectsV2` prefix parameter maps directly to `{accountId}/{containerId}/{blobNamePrefix}`

2. **Pagination**: S3 `StartAfter` parameter accepts a full key, allowing use of `{accountId}/{containerId}/{pageToken}` where `pageToken` is the blob name

3. **Account/Container Isolation**: Natural partitioning by account and container

4. **No Encoding Required**: Blob names are used as-is (S3 keys support UTF-8)

### 3.3 Alternatives Considered

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
| `x-amz-meta-deleted-ts` | String (long) | Deletion timestamp | Epoch millis, or empty if not deleted |
| `x-amz-meta-expiration-ms` | String (long) | `NamedBlobRecord.expirationTimeMs` | Epoch millis, or "-1" for permanent |
| `x-amz-meta-modified-ms` | String (long) | `NamedBlobRecord.modifiedTimeMs` | Epoch millis (see Section 5.1) |

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

### 6.1 State and Deletion Filtering

**MySQL Filters**:
```sql
blob_state = 1  -- READY only
AND (deleted_ts IS NULL OR deleted_ts > UTC_TIMESTAMP())
```

**S3 Limitation**: `ListObjectsV2` cannot filter by metadata.

**Decision**: Client-side filtering (Option B from requirements).

### 6.2 Implementation

```java
public CompletableFuture<Page<NamedBlobRecord>> list(
        String accountName, String containerName,
        String blobNamePrefix, String pageToken, Integer maxKeys) {

    return CompletableFuture.supplyAsync(() -> {
        String s3Prefix = buildS3Prefix(accountName, containerName, blobNamePrefix);
        List<NamedBlobRecord> results = new ArrayList<>();
        String currentToken = pageToken;

        // May need multiple S3 requests if many objects are filtered out
        while (results.size() < maxKeys) {
            ListObjectsV2Response response = fetchS3Page(s3Prefix, currentToken, maxKeys * 2);

            for (S3Object s3Obj : response.contents()) {
                // Must call HeadObject to get metadata for filtering
                HeadObjectResponse head = s3Client.headObject(
                    HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(s3Obj.key())
                        .build());

                Map<String, String> metadata = head.metadata();

                // Filter: blob_state = READY
                int blobState = Integer.parseInt(
                    metadata.getOrDefault("blob-state", "0"));
                if (blobState != NamedBlobState.READY.ordinal()) {
                    continue;
                }

                // Filter: not deleted or deletion in future
                String deletedTsStr = metadata.get("deleted-ts");
                if (deletedTsStr != null && !deletedTsStr.isEmpty()) {
                    long deletedTs = Long.parseLong(deletedTsStr);
                    if (deletedTs <= System.currentTimeMillis()) {
                        continue;
                    }
                }

                // Build record from metadata
                results.add(buildRecordFromS3(accountName, containerName, s3Obj, metadata));

                if (results.size() >= maxKeys + 1) {
                    break;
                }
            }

            if (!response.isTruncated()) {
                break;  // No more objects
            }
            currentToken = extractBlobName(response.contents().get(
                response.contents().size() - 1).key());
        }

        // Handle pagination token (maxKeys + 1 pattern)
        String nextToken = null;
        if (results.size() > maxKeys) {
            nextToken = results.remove(maxKeys).getBlobName();
        }

        return new Page<>(results, nextToken);
    });
}
```

### 6.3 Performance Implications

**Problem**: Client-side filtering requires `HeadObject` call for each S3 object to retrieve metadata.

**Mitigation Strategies**:

1. **Batch HeadObject requests**: Use S3 Batch Operations or parallel async calls
2. **Over-fetch**: Request `maxKeys * filterRatio` objects where `filterRatio` is estimated based on typical filter rates
3. **Caching**: Cache metadata for recently listed objects (with appropriate TTL)

**Alternative Considered**: Store state in object tags and use S3 Select.

**Rejected**: S3 Select does not support filtering by tags during list operations.[^4]

[^4]: S3 Object Tags can be used with S3 Inventory for offline filtering, but not for real-time list operations.

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
| `expirationTimeMs` | long | `deleted_ts` column | `x-amz-meta-expiration-ms` |
| `version` | long | `version` column | `x-amz-meta-version` |
| `blobSize` | long | `blob_size` column | `S3Object.size()` |
| `modifiedTimeMs` | long | `modified_ts` column | `x-amz-meta-modified-ms` |
| `isDirectory` | boolean | `false` (hardcoded) | `false` (hardcoded)[^5] |

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

## 11. Migration Phases

| Phase | List Source | Verification | Notes |
|-------|-------------|--------------|-------|
| 1. Baseline | MySQL | None | Current state |
| 2. Dual-Write | MySQL | Async S3 compare | Log discrepancies |
| 3. S3 Primary | S3 | MySQL fallback | Monitor error rates |
| 4. S3 Only | S3 | None | Deprecate MySQL |

---

## 12. Open Questions

1. **HeadObject Rate Limiting**: What is the expected QPS for list operations? May need to implement rate limiting or caching for HeadObject calls.

2. **Large Container Performance**: For containers with millions of objects where most are filtered out, pagination may require many S3 requests. Consider maintaining a separate "live objects" index.

3. **Consistency Window**: S3 provides strong read-after-write consistency, but there may be edge cases during high-write scenarios. Document acceptable consistency guarantees.

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
