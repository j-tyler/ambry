# Specification Project Guide

Use this document when building the specification.

---

## Purpose

The specification enables implementing agents to build:
1. An Ambry-compatible proxy API backed by S3
2. Automated client code migration from Ambry to S3

**The specification must be exhaustive.** Incomplete documentation causes implementing agents to hallucinate behavior, leading to bugs. When in doubt, document it. The specification is expected to grow to thousands of lines.

---

## What the Specification Must Cover

### Operations
- Put, Get, Delete, Undelete, UpdateTtl, Stitch
- Observable behavior from the client's perspective
- All error conditions and their triggers
- S3 equivalents for each behavior

### Configuration (CRITICAL)

**NOT internal server configuration.** Document only:
1. Parameters clients pass in API calls
2. Container/Account settings that affect API behavior

**1. API Call Parameters**
For each operation, document all input parameters:
- `BlobProperties` fields (put)
- `GetBlobOptions` fields (get)
- `PutBlobOptions` fields (put, stitch)
- Service ID parameter (delete, undelete, updateTtl)

**2. Container Configuration** (from `Container.java`)
Pre-configured settings that affect ALL API calls to that container:

| Property | Affects |
|----------|---------|
| `encrypted` | Put encrypts data; Get decrypts |
| `cacheable` | Get response Cache-Control headers |
| `ttlRequired` | Put must include TTL or fails |
| `paranoidDurabilityEnabled` | Put durability guarantees |
| `mediaScanDisabled` | Put content scanning |
| `namedBlobMode` | Whether named blob APIs work |
| `status` | Whether operations are allowed |
| `securePathRequired` | Get path validation |
| `accessControlAllowOrigin` | Get CORS headers |
| `cacheTtlInSecond` | Get cache TTL |

**3. Account Configuration** (from `Account.java`)
Pre-configured settings that affect ALL containers in the account:

| Property | Affects |
|----------|---------|
| `status` | Whether operations are allowed |
| `aclInheritedByContainer` | Authorization behavior |
| `quotaResourceType` | Rate limiting scope |

### Cross-Cutting Behaviors
- TTL semantics and expiration
- Soft delete model and retention
- Composite blob (chunking/stitching) model
- Authorization (account/container)
- Error handling patterns

---

## Authoring Rules

1. **Client-centric only**: Document what the client observes, not Ambry internals
2. **Never fabricate**: Use TODO for unknowns — incomplete is better than wrong
3. **Document actual behavior**: Include bugs if clients depend on them
4. **Code examples**: Show Ambry and S3 code side-by-side
5. **Source references**: Cite file:line for verified claims
6. **Configuration exhaustiveness**: Every config option that affects behavior must be documented

---

## Specification Structure

Each operation section must include:
```
## N. Operation Name

### N.1 Ambry Behavior
- Code example
- Observable behavior
- How configuration affects this operation
- Error cases with sources

### N.2 S3 Equivalent
- Code example (or TODO)
- Error mapping
- Configuration mapping

### N.3 Verification
- Checklist of behaviors to test
```

---

## Open Questions

| ID | Question |
|----|----------|
| OQ-001 | What is Ambry's soft delete retention period? |
| OQ-002 | Can chunks be reused in multiple stitch operations? |
| OQ-003 | How does Ambry handle duplicate blob IDs on put? |
| OQ-004 | What is the maximum blob size? Maximum chunk count? |
| OQ-005 | Does Ambry support conditional operations? |
| OQ-006 | What undocumented behaviors do clients depend on? |
| OQ-007 | What happens when TTL is updated on an expired blob? |

---

## Design Decisions

*Record decisions made during specification development here.*

| Decision | Rationale |
|----------|-----------|
| | |

---

## Source Files

### Router API (blob ID-based operations)
- `ambry-api/src/main/java/com/github/ambry/router/Router.java`
- `ambry-api/src/main/java/com/github/ambry/router/RouterErrorCode.java`
- `ambry-api/src/main/java/com/github/ambry/router/GetBlobOptions.java`
- `ambry-api/src/main/java/com/github/ambry/router/PutBlobOptions.java`
- `ambry-api/src/main/java/com/github/ambry/messageformat/BlobProperties.java`

### Named Blob API (name-based operations)
- `ambry-api/src/main/java/com/github/ambry/named/NamedBlobDb.java`
- `ambry-api/src/main/java/com/github/ambry/named/NamedBlobRecord.java`
- `ambry-api/src/main/java/com/github/ambry/frontend/NamedBlobPath.java`

### Dataset API (versioned collections)
- `ambry-api/src/main/java/com/github/ambry/account/AccountService.java` (dataset methods)
- `ambry-api/src/main/java/com/github/ambry/account/Dataset.java`
- `ambry-api/src/main/java/com/github/ambry/account/DatasetVersionRecord.java`

### Container/Account (pre-configured settings)
- `ambry-api/src/main/java/com/github/ambry/account/Container.java`
- `ambry-api/src/main/java/com/github/ambry/account/Account.java`

### Tests (behavioral verification)
- `ambry-store/src/test/java/com/github/ambry/store/BlobStoreTest.java`
- `ambry-router/src/test/java/com/github/ambry/router/NonBlockingRouterTest.java`
