# S3 Migration: Alignment Decisions

**Purpose**: This document identifies key tradeoffs in migrating Ambry's list operation from MySQL to S3. Each section explains the Ambry functionality, why S3 can't easily support it, and presents options for the team to decide.

**Status**: Draft - Requires team alignment

---

## Table of Contents

1. [Internal State Filtering](#1-internal-state-filtering)
2. [Expiration Time Visibility](#2-expiration-time-visibility)
3. [Timestamp Precision](#3-timestamp-precision)
4. [Directory Delimiter Handling](#4-directory-delimiter-handling)
5. [Version History Retention](#5-version-history-retention)
6. [TTL Enforcement and Cleanup](#6-ttl-enforcement-and-cleanup)
7. [Client Communication Strategy](#7-client-communication-strategy)

---

## 1. Internal State Filtering

### Ambry Functionality

**API Calls Affected:**
```
GET /named/{accountName}/{containerName}
GET /named/{accountName}/{containerName}?prefix={prefix}&page={token}&maxKeys={n}
GET /s3/{accountName}/{containerName}?list-type=2&prefix={prefix}
```

These APIs do not expose any filtering parameters for blob state. However, MySQL applies internal filters before returning results:

```sql
WHERE blob_state = 1                                    -- Only READY blobs
  AND (deleted_ts IS NULL OR deleted_ts > NOW())        -- Exclude soft-deleted/expired
```

This means clients only see blobs in a valid, retrievable state. Blobs that are:
- **IN_PROGRESS** (upload not yet complete)
- **Soft-deleted** (DELETE called but not yet purged)
- **Expired** (TTL has passed)

...are automatically excluded from list results.

### Why S3 Can't Easily Support This

The question is whether the S3 client implementation only stores and exposes objects in the READY state that are not soft-deleted or expired.

If S3 contains only valid objects, `ListObjectsV2` would naturally return correct results. However, if S3 contains objects in all states (IN_PROGRESS, soft-deleted, expired), then list results will include objects that MySQL would have filtered out.

### Options

#### Option A: Store Metadata and Filter via HeadObject

**Description**: Store blob state in S3 object metadata. On list, call `HeadObject` for each object to check state, then filter results.

**Pros**:
- Matches MySQL behavior exactly

**Cons**:
- Extremely expensive: 1000+ API calls per page
- Unacceptable latency
- Higher S3 costs

#### Option B: Change S3 Client Implementation

**Description**: Ensure the S3 client only writes objects when they reach READY state, and deletes objects immediately on soft-delete or expiration.

**Considerations**:
- How does the current implementation handle IN_PROGRESS uploads?
- When is an object written to S3 relative to its state transition?
- Can we guarantee deletion happens synchronously with soft-delete?
- How do we handle TTL expiration (background job to delete expired objects)?

**Pros**:
- ListObjectsV2 naturally returns correct results
- No per-object API calls needed

**Cons**:
- May require changes to write/delete paths
- TTL expiration requires background cleanup job
- Need to understand current S3 client behavior

#### Option C: Accept Behavioral Differences

**Description**: S3 list returns all objects regardless of state. Clients may see blobs they cannot access.

**Behavioral Changes**:
| Scenario | MySQL | S3 |
|----------|-------|-----|
| IN_PROGRESS blobs | Hidden | Visible |
| Soft-deleted blobs | Hidden | Visible |
| Expired blobs (TTL passed) | Hidden | Visible |

**Client Impact**:
- List may return more results than before
- `GET` or `DELETE` on these objects will fail with appropriate errors

**Pros**:
- Simple list implementation
- No additional API calls
- Low latency

**Cons**:
- Breaking change in list behavior
- Clients may need updates to handle GET failures after list

### Team Decision Required

**Question**: How should the S3 implementation handle blob state filtering?

- [ ] **Option A**: Store metadata and filter via HeadObject (expensive, not recommended)
- [ ] **Option B**: Change S3 client to only store/expose valid objects
- [ ] **Option C**: Accept behavioral differences, document for clients
- [ ] Other: _____________

---

## 2. Expiration Time Visibility

### Ambry Functionality

**API Calls Affected:**
```
GET /named/{accountName}/{containerName}
GET /named/{accountName}/{containerName}?prefix={prefix}
GET /s3/{accountName}/{containerName}?list-type=2
```

MySQL list returns `expirationTimeMs` for each blob:

```json
{
  "blobName": "temp-file.txt",
  "expirationTimeMs": 1704067200000,
  "blobSize": 1024
}
```

This value comes from the `deleted_ts` column in MySQL, which stores the expiration timestamp for blobs with TTL.

### The Problem

S3's `ListObjectsV2` API returns only:
- Object key
- Size
- LastModified
- ETag
- Storage class

There is no built-in field for expiration time. If we want to return `expirationTimeMs` in list results, we need to:
1. Decide where to store expiration time in S3
2. Decide how (or whether) to retrieve it during list operations

### Options

#### Option A: Do Not Store Expiration Time in S3

**Description**: S3 objects do not contain expiration information. List always returns `expirationTimeMs = -1`.

**Implications**:
- `expirationTimeMs` field will always be `-1` (omitted from JSON response)
- Expiration enforcement would need to happen elsewhere (e.g., MySQL remains source of truth for TTL, or a separate tracking system)

**Pros**:
- Simplest S3 implementation

**Cons**:
- Cannot enforce TTL from S3 alone
- List behavior differs from MySQL

#### Option B: Store Expiration in S3 User Metadata

**Description**: Store expiration as S3 user metadata (e.g., `x-amz-meta-expiration-ms`).

**Implications**:
- User metadata is NOT returned by `ListObjectsV2`
- To return expiration in list, would need `HeadObject` per object (expensive)
- Metadata can be read when fetching individual objects

**Pros**:
- Expiration data exists in S3
- Can be retrieved via `GET` or `HEAD` on individual blobs

**Cons**:
- Cannot efficiently return in list results
- HeadObject per listed object is too expensive

#### Option C: Store Expiration in S3 Object Tags

**Description**: Store expiration as an S3 object tag.

**Implications**:
- Tags are also NOT returned by `ListObjectsV2`
- Tags require separate `GetObjectTagging` call per object
- Tags are limited to 10 per object

**Pros**:
- Tags can be used with S3 Lifecycle rules (limited)
- Can be retrieved per object

**Cons**:
- Same retrieval problem as metadata
- Limited to 10 tags per object

#### Option D: Encode Expiration in Object Key

**Description**: Include expiration timestamp in the S3 key structure.

**Example**: `{accountId}/{containerId}/exp-1704067200000/{blobName}`

**Implications**:
- Expiration visible in list results (parseable from key)
- Changing expiration requires copy+delete (new key)
- Complicates prefix queries

**Pros**:
- Expiration available in list without additional calls

**Cons**:
- TTL updates are expensive (copy+delete)
- More complex key parsing
- May break prefix-based queries

#### Option E: Accept That List Cannot Return Expiration

**Description**: Store expiration in metadata (Option B), but accept that list returns `-1`. Document as behavioral difference.

**Implications**:
- List response differs from MySQL
- Expiration available via `GET`/`HEAD` on individual blobs

**Pros**:
- Expiration data stored in S3
- Simple list implementation
- Individual blob retrieval still has expiration

**Cons**:
- List behavior differs from MySQL

### Team Decision Required

**Question**: Where should expiration time be stored, and should list return it?

Storage decision:
- [ ] **Option A**: Do not store expiration in S3
- [ ] **Option B**: Store in S3 user metadata
- [ ] **Option C**: Store in S3 object tags
- [ ] **Option D**: Encode in object key
- [ ] Other: _____________

List behavior decision:
- [ ] List returns `-1` (expiration not available in list)
- [ ] List returns actual expiration (requires Option D or expensive per-object calls)
- [ ] Other: _____________

---

## 3. Timestamp Precision

### Ambry Functionality

**API Calls Affected:**
```
GET /named/{accountName}/{containerName}
GET /named/{accountName}/{containerName}?prefix={prefix}
GET /s3/{accountName}/{containerName}?list-type=2
```

MySQL stores and returns `modifiedTimeMs` with **millisecond precision**:

```json
{
  "blobName": "file.txt",
  "modifiedTimeMs": 1702012800123
}
```

### Why S3 Can't Easily Support This

S3's `LastModified` field has only **second precision**. The milliseconds are always `.000`.

We store the original millisecond timestamp in metadata (`x-amz-meta-modified-ms`), but this is not available in `ListObjectsV2`.

### Options

#### Option A: Use S3 LastModified (Second Precision)

**Description**: Return S3's `LastModified` converted to milliseconds. Values will always end in `000`.

**API Change**:
- `modifiedTimeMs` values will always end in `000`
- Values may differ by up to 999ms from MySQL for the same blob

**Pros**:
- Simple implementation
- No additional API calls

**Cons**:
- Precision loss from milliseconds to seconds

#### Option B: Fetch True Timestamp from Metadata

**Description**: Call HeadObject for each listed object to retrieve `x-amz-meta-modified-ms`.

**Pros**:
- Millisecond precision preserved

**Cons**:
- 1000+ additional API calls per page
- Unacceptable latency

#### Option C: Accept and Document Precision Difference

**Description**: Same as Option A, but explicitly document that timestamp precision differs between backends.

**Migration verification**: Compare timestamps at second precision only (truncate milliseconds).

**Pros**:
- Clear expectations
- Simple implementation

**Cons**:
- Same as Option A

### Team Decision Required

**Question**: Is millisecond timestamp precision required?

- [ ] **Option A**: Use S3 second precision, accept differences
- [ ] **Option B**: Fetch metadata for exact timestamps (not recommended)
- [ ] **Option C**: Accept and explicitly document precision difference
- [ ] Other: _____________

---

## 4. Directory Delimiter Handling

### Ambry Functionality

**API Calls Affected:**
```
GET /named/{accountName}/{containerName}?delimiter=/
GET /named/{accountName}/{containerName}?prefix={prefix}&delimiter=/
GET /s3/{accountName}/{containerName}?list-type=2&delimiter=/
```

When `delimiter=/` is specified, Ambry groups blobs into virtual directories:

```
# Blobs:
photos/vacation/img1.jpg
photos/vacation/img2.jpg
photos/work/doc.pdf

# List with delimiter=/ and prefix=photos/
→ photos/vacation/  (directory)
→ photos/work/      (directory)
```

MySQL returns all matching blobs, and `NamedBlobListHandler` performs directory grouping in Java.

### Why S3 Can't Easily Support This

S3 **does** support delimiter natively via `ListObjectsV2` with `delimiter` parameter. It returns `CommonPrefixes` for directories.

However, S3's delimiter behavior may differ in edge cases:
- Empty directory markers
- Blob names with consecutive delimiters (`photos//img.jpg`)
- Interaction with filtering (which we can't do anyway)

### Options

#### Option A: Use S3 Native Delimiter (More Efficient)

**Description**: Pass `delimiter` to S3 ListObjectsV2, map `CommonPrefixes` to directory entries.

**Pros**:
- Efficient - S3 does the grouping
- Fewer objects transferred
- Native S3 behavior

**Cons**:
- May have subtle behavioral differences in edge cases
- Harder to verify against MySQL

#### Option B: Fetch All, Filter in Java (MySQL Compatible)

**Description**: Ignore S3 delimiter support. Fetch all blobs, let handler do directory grouping.

**Pros**:
- Identical behavior to MySQL
- Easier migration verification

**Cons**:
- Less efficient - fetches more data
- More client-side processing

#### Option C: Configuration Flag

**Description**: Make delimiter handling configurable:

```yaml
s3.list.useNativeDelimiter: false  # Default: match MySQL
```

Start with Option B for migration, switch to Option A post-migration.

**Pros**:
- Safe migration path
- Can optimize later

**Cons**:
- Configuration complexity

### Team Decision Required

**Question**: Should we use S3's native delimiter support?

- [ ] **Option A**: Use S3 native delimiter (more efficient)
- [ ] **Option B**: Match MySQL behavior exactly (fetch all, filter in Java)
- [ ] **Option C**: Configuration flag to switch between modes
- [ ] Other: _____________

---

## 5. Version History Retention

### Ambry Functionality

**API Calls Affected:**
```
PUT /named/{accountName}/{containerName}/{blobName}  (overwrite existing blob)
GET /named/{accountName}/{containerName}             (list returns only latest version)
```

MySQL keeps all blob versions:

```sql
-- Multiple rows for same blob_name with different versions
blob_name     | version           | blob_state
--------------|-------------------|------------
file.txt      | 1702012800000001  | READY
file.txt      | 1702012800000002  | READY  ← latest, returned by list
```

When a blob is overwritten, the old version remains in the database. List returns only the latest (`MAX(version)`).

### Why S3 Can't Easily Support This

S3 has native versioning, but:
1. It must be enabled at bucket level
2. Listing versions requires different API (`ListObjectVersions`)
3. Filtering to "latest version only" is not built-in
4. Increases storage costs

### Options

#### Option A: Overwrite in S3 (No Version History)

**Description**: PUT to same key overwrites the existing object. Only current version exists.

**Impact**:
- List naturally returns only current version (no filtering needed)
- Version history is lost in S3

**Pros**:
- Simple implementation
- Lower storage costs
- List behavior is correct

**Cons**:
- Cannot recover previous versions from S3
- Different from MySQL's implicit history

#### Option B: Enable S3 Versioning

**Description**: Enable bucket versioning. Each PUT creates a new version.

**List Implementation**: Must filter to latest version only, either by:
- Using `ListObjectVersions` and filtering
- Maintaining "latest" marker in metadata

**Pros**:
- Version history preserved
- Can recover previous versions

**Cons**:
- Higher storage costs
- More complex list implementation
- Need lifecycle rules to clean old versions

#### Option C: Hybrid - Overwrite but Log to Audit

**Description**: Overwrite in S3 (Option A), but log version changes to separate audit system.

**Pros**:
- Simple S3 implementation
- Audit trail if needed

**Cons**:
- Additional infrastructure
- Audit log is separate from S3

### Team Decision Required

**Question**: Do we need to preserve blob version history in S3?

- [ ] **Option A**: Overwrite - no version history in S3
- [ ] **Option B**: Enable S3 versioning
- [ ] **Option C**: Overwrite with separate audit logging
- [ ] Other: _____________

---

## 6. TTL Enforcement and Cleanup

### Ambry Functionality

**API Calls Affected:**
```
PUT /named/{accountName}/{containerName}/{blobName}   (with x-ambry-blob-ttl header)
GET /named/{accountName}/{containerName}              (list excludes expired blobs)
GET /named/{accountName}/{containerName}/{blobName}   (GET fails for expired blobs)
DELETE /named/{accountName}/{containerName}/{blobName} (soft delete sets deleted_ts)
```

MySQL excludes expired blobs automatically via query:

```sql
AND (deleted_ts IS NULL OR deleted_ts > UTC_TIMESTAMP())
```

No separate cleanup job needed—expired blobs simply stop appearing in queries.

### Why S3 Can't Easily Support This

1. **List filtering**: S3 can't filter by expiration metadata (covered in Section 1)
2. **S3 Lifecycle Rules**: Operate on object age from creation date, NOT custom metadata
3. **Storage cost**: Expired blobs remain in S3 until explicitly deleted

### Options

#### Option A: Implement Cleanup Job

**Description**: Separate background job scans S3 and deletes expired/soft-deleted objects.

```java
// Pseudo-code for cleanup job
for each object in bucket:
    metadata = HeadObject(object)
    if isExpired(metadata) or isSoftDeleted(metadata):
        if gracePeriodPassed(metadata):
            DeleteObject(object)
```

**Parameters to decide**:
- How often to run? (hourly, daily)
- Grace period before hard delete? (7 days after expiration?)
- Should deleted objects be recoverable?

**Pros**:
- Reduces storage costs
- Clean S3 bucket

**Cons**:
- Additional infrastructure to maintain
- Cost of scanning (HeadObject per object)
- Delay between expiration and deletion

#### Option B: Use S3 Lifecycle with Object Tags

**Description**: Write expiration as S3 object tag (not metadata), configure lifecycle rule.

```xml
<LifecycleRule>
    <Filter>
        <Tag>
            <Key>expiration-bucket</Key>
            <Value>2024-01</Value>
        </Tag>
    </Filter>
    <Expiration>
        <Days>0</Days>
    </Expiration>
</LifecycleRule>
```

**Pros**:
- Native S3 cleanup
- No custom job needed

**Cons**:
- Requires changing write path to use tags
- Limited granularity (tag-based buckets, not exact timestamps)
- Tags limited to 10 per object

#### Option C: Accept Storage Cost, No Cleanup

**Description**: Let expired objects remain in S3 indefinitely.

**Pros**:
- Simple - no cleanup infrastructure
- Objects recoverable if needed

**Cons**:
- Ever-growing storage costs
- S3 list includes expired objects forever

### Team Decision Required

**Question**: How should expired and soft-deleted blobs be cleaned up from S3?

- [ ] **Option A**: Implement cleanup job (parameters TBD)
- [ ] **Option B**: Use S3 lifecycle with object tags
- [ ] **Option C**: No cleanup - accept storage cost
- [ ] Other: _____________

If Option A, additional decisions:
- Cleanup frequency: _____________ (e.g., daily)
- Grace period after expiration: _____________ (e.g., 7 days)
- Grace period after soft delete: _____________ (e.g., 30 days)

---

## 7. Client Communication Strategy

### Context

**All List API Calls Affected:**
```
GET /named/{accountName}/{containerName}[?prefix=...&page=...&maxKeys=...&delimiter=...]
GET /s3/{accountName}/{containerName}?list-type=2[&prefix=...&continuation-token=...&max-keys=...&delimiter=...]
```

The S3 migration introduces behavioral differences that may affect clients:

1. **More results in list**: IN_PROGRESS, soft-deleted, and expired blobs visible
2. **Missing expiration info**: `expirationTimeMs` always omitted
3. **Timestamp precision**: Milliseconds truncated to seconds
4. **Potential ordering differences**: Unicode edge cases

### Options

#### Option A: Release Notes Only

**Description**: Document changes in release notes. No proactive outreach.

**Pros**:
- Minimal effort
- Standard practice for minor changes

**Cons**:
- Clients may be surprised by changes
- Support burden when issues arise

#### Option B: Migration Guide Document

**Description**: Create detailed migration guide covering:
- What's changing
- How to test compatibility
- Code changes clients may need
- Timeline

**Pros**:
- Clear communication
- Reduces support burden
- Professional approach for breaking changes

**Cons**:
- Effort to create and maintain
- May alarm clients unnecessarily

#### Option C: Feature Flag Rollout

**Description**: Add configuration to switch list source per account/container:

```yaml
list.source.default: mysql
list.source.override:
  account-123: s3
  account-456: s3
```

Migrate accounts gradually with direct communication.

**Pros**:
- Controlled rollout
- Can revert problematic accounts

**Cons**:
- Longer migration timeline
- Operational complexity

#### Option D: Versioned API

**Description**: Introduce API version for S3-backed behavior:

```
GET /v2/named/{account}/{container}
```

Old `/named/` continues using MySQL indefinitely.

**Pros**:
- No breaking changes to existing clients
- Clients opt-in to new behavior

**Cons**:
- Maintain two implementations
- MySQL never deprecated

### Team Decision Required

**Question**: How should we communicate S3 migration changes to clients?

- [ ] **Option A**: Release notes only
- [ ] **Option B**: Migration guide document
- [ ] **Option C**: Feature flag rollout per account
- [ ] **Option D**: Versioned API
- [ ] Combination: _____________

---

## Summary of Decisions Needed

| # | Topic | Recommended | Decision |
|---|-------|-------------|----------|
| 1 | Internal state filtering | Option A: Accept behavioral differences | ☐ |
| 2 | Expiration time storage & visibility | TBD (storage + list behavior) | ☐ |
| 3 | Timestamp precision | Option C: Accept and document | ☐ |
| 4 | Delimiter handling | Option C: Config flag (start with B) | ☐ |
| 5 | Version history | Option A: Overwrite, no history | ☐ |
| 6 | TTL cleanup | Option A: Implement cleanup job | ☐ |
| 7 | Client communication | Option B or C | ☐ |

---

## Appendix: Quick Reference

### Behavioral Differences Summary

| Aspect | MySQL Behavior | S3 Behavior | Breaking Change? |
|--------|---------------|-------------|------------------|
| IN_PROGRESS blobs | Hidden | Visible | Yes |
| Soft-deleted blobs | Hidden | Visible | Yes |
| Expired blobs | Hidden | Visible | Yes |
| `expirationTimeMs` | Actual value | Always -1 (omitted) | Yes |
| `modifiedTimeMs` precision | Milliseconds | Seconds | Minor |
| Version history | Retained in DB | Overwritten | No (not exposed in list) |
| Sort order | Binary (case-sensitive) | Binary (case-sensitive) | No |

### Client Compatibility Checklist

Clients should verify they handle these scenarios:
- [ ] GET after list returns 404 or error (blob was IN_PROGRESS/deleted/expired)
- [ ] `expirationTimeMs` missing from response (blob appears permanent)
- [ ] Timestamps may differ by up to 999ms when comparing with other sources
