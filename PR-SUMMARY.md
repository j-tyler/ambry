# Fix retainedDuplicate() Memory Leaks in PutOperation

## Background

Netty's `retainedDuplicate()` increments the ByteBuf reference count, transferring ownership of 1 reference to the caller. The caller must call `release()` to avoid memory leaks. When passing a retained duplicate to a constructor/method, ownership transfers to the receiver—but if an exception prevents the receiver from taking ownership, **the caller must release it**.

## The Bugs

This PR fixes two memory leaks where `retainedDuplicate()` ByteBufs are created but never released when exceptions occur before ownership transfer completes.

### Bug #1: KMS Exception After retainedDuplicate() in encryptChunk()

**Location:** `PutOperation.java:1582-1609`

**The Problem:**
```java
cryptoJobHandler.submitJob(
    new EncryptJob(...,
        buf.retainedDuplicate(),  // Evaluated (refCnt++)
        ByteBuffer.wrap(chunkUserMetadata),
        kms.getRandomKey(),  // Throws exception
        ...));
```

Java evaluates constructor arguments left-to-right. When `kms.getRandomKey()` throws after `retainedDuplicate()` is evaluated, the EncryptJob constructor never completes and never receives the retained duplicate. The catch block handles the exception but doesn't release the orphaned ByteBuf.

**Impact:** Every KMS failure leaks a ByteBuf.

### Bug #2: RequestInfo Exception After PutRequest Creation

**Location:** `PutOperation.java:1827-1863`

**The Problem:**
```java
PutRequest putRequest = createPutRequest();  // Contains retainedDuplicate()
RequestInfo requestInfo = new RequestInfo(hostname, port, putRequest, ...);  // Throws
correlationIdToChunkPutRequestInfo.put(correlationId, requestInfo);  // Never reached
```

When `RequestInfo` construction throws, the `PutRequest` (which owns a retained duplicate) is never stored in the tracking map. With no reference to it, `putRequest.release()` is never called.

**Impact:** Every RequestInfo construction failure leaks a ByteBuf.

---

## The Tests

Regression tests in `PutOperationTest.java` verify both fixes using the ByteBuf Flow Tracker:

1. **testEncryptChunkKmsExceptionReleasesRetainedDuplicate()** - Simulates KMS exception after `retainedDuplicate()`, verifies the catch block releases it
2. **testFetchRequestsExceptionReleasesPutRequest()** - Simulates RequestInfo exception after PutRequest creation, verifies `putRequest.release()` is called

Both tests report "Leak Paths: 0" with the fixes in place.

---

## The Fixes

Both fixes follow the pattern: **track ownership → attempt transfer → nullify if successful → cleanup if failed**.

### Fix #1: encryptChunk() - Track and Release retainedDuplicate()

Extract `retainedDuplicate()` into a variable before the constructor call, then release it in the catch block if ownership wasn't transferred:

```java
private void encryptChunk() {
  ByteBuf retainedCopy = null;
  try {
    retainedCopy = isMetadataChunk() ? null : buf.retainedDuplicate();
    cryptoJobHandler.submitJob(
        new EncryptJob(..., retainedCopy, ..., kms.getRandomKey(), ...));
    retainedCopy = null;  // Ownership transferred
  } catch (GeneralSecurityException e) {
    if (retainedCopy != null) {
      retainedCopy.release();  // Release if not transferred
    }
    // ... exception handling
  }
}
```

### Fix #2: fetchRequests() - Release Abandoned PutRequest

Wrap PutRequest creation in try-catch, then release it if not successfully stored:

```java
private void fetchRequests(...) {
  while (replicaIterator.hasNext()) {
    PutRequest putRequest = null;
    try {
      putRequest = createPutRequest();  // Contains retainedDuplicate()
      RequestInfo requestInfo = new RequestInfo(hostname, port, putRequest, ...);
      correlationIdToChunkPutRequestInfo.put(correlationId, requestInfo);
      // ... store in maps
      putRequest = null;  // Successfully stored
    } catch (Exception e) {
      if (putRequest != null) {
        putRequest.release();  // Release abandoned PutRequest
      }
      throw e;
    }
    // ... metrics and logging
  }
}
```

---

## Summary

Both fixes ensure the responsible party always releases ByteBufs, even when exceptions occur. The caller retains responsibility until ownership transfer completes successfully.
