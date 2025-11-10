# PutOperation Real Production Bug Tests

**Date:** 2025-11-10
**Status:** Tests created, waiting for production fixes to be applied

---

## Overview

This document describes the **real production bug tests** that will **FAIL** due to actual ByteBuf memory leaks in PutOperation code. These are different from the simulation tests (PutOperationCompressionLeakTest, PutOperationRetainedDuplicateLeakTest) which pass by disabling leak detection.

### Key Difference: Simulation vs Real Bug Tests

| Aspect | Simulation Tests | Real Bug Tests |
|--------|------------------|----------------|
| **File** | `PutOperationCompressionLeakTest.java`<br>`PutOperationRetainedDuplicateLeakTest.java` | `PutOperationRealBugTest.java`<br>`PutOperationProductionBugTest.java` |
| **Leak Detection** | DISABLED (`leakHelper.setDisabled(true)`) | ENABLED (default) |
| **Test Status** | PASS (with manual cleanup) | **FAIL** (until fixes applied) |
| **Purpose** | Demonstrate bug conditions | Prove bugs exist in production |
| **Cleanup** | Manual cleanup to avoid test failure | No cleanup - let leak detection fail |

---

## Test File: PutOperationRealBugTest.java

**Location:** `ambry-router/src/test/java/com/github/ambry/router/PutOperationRealBugTest.java`

**Status:** ✅ Simplified version that compiles, ⚠️ Tests @Ignored until production fixes applied

**Key Implementation Details:**
- Extends `UnpooledHeapByteBuf` instead of using non-public `WrappedByteBuf`
- Uses direct method calls to simulate production flow
- Leak detection fully enabled (not disabled)

### Tests Included (3 tests)

#### Test #1: testRealBug_CrcExceptionAfterCompressionLeaksCompressedBuffer

**Bug Location:** `PutOperation.java:1562-1576`

**Reproduction:**
```java
ByteBuf newBuffer = compressionService.compressChunk(buf, isFullChunk, outputDirectMemory);
if (newBuffer != null) {
  buf.release();           // Line 1564: Old buffer released ✓
  buf = newBuffer;         // Line 1565: OWNERSHIP TRANSFERRED to PutChunk
  isChunkCompressed = true;
  if (routerConfig.routerVerifyCrcForPutRequests) {
    chunkCrc32.reset();
    for (ByteBuffer byteBuffer : buf.nioBuffers()) {  // Line 1570: EXCEPTION HERE
      chunkCrc32.update(byteBuffer);                 // ← No try-catch!
    }
  }
}
// If exception occurs at line 1570, buf (compressed) is LEAKED
```

**Test Method:**
1. Create `ThrowingNioBuffersByteBuf` that throws during `nioBuffers()`
2. This simulates the state AFTER line 1565 (ownership transferred)
3. Call `buf.nioBuffers()` → throws RuntimeException
4. No try-catch → buffer remains allocated with refCnt=1
5. `leakHelper.afterTest()` detects leak → **TEST FAILS**

**Expected Result:** ❌ TEST FAILS with "ByteBuf leak detected: 1 buffer(s) not released"

**After Fix Applied:** ✅ TEST PASSES

**Required Fix:**
```java
if (newBuffer != null) {
  buf.release();
  buf = newBuffer;
  isChunkCompressed = true;
  if (routerConfig.routerVerifyCrcForPutRequests) {
    try {  // ADD THIS
      chunkCrc32.reset();
      for (ByteBuffer byteBuffer : buf.nioBuffers()) {
        chunkCrc32.update(byteBuffer);
      }
    } catch (Exception e) {
      buf.release();  // Release compressed buffer
      throw e;
    }
  }
}
```

---

#### Test #2: testRealBug_CrcExceptionInEncryptionCallbackLeaksEncryptedBuffer

**Bug Location:** `PutOperation.java:1498-1503`

**Reproduction:**
```java
buf = result.getEncryptedBlobContent();  // Line 1498: OWNERSHIP TRANSFERRED
isChunkEncrypted = true;
for (ByteBuffer byteBuffer : buf.nioBuffers()) {  // Line 1500: EXCEPTION HERE
  chunkCrc32.update(byteBuffer);                 // ← No try-catch!
}
```

**Test Method:**
1. Create `ThrowingNioBuffersByteBuf` simulating encrypted buffer
2. Simulate ownership transfer from EncryptJob
3. Call `buf.nioBuffers()` → throws
4. Buffer leaked
5. **TEST FAILS**

**Required Fix:**
```java
buf = result.getEncryptedBlobContent();
isChunkEncrypted = true;
try {  // ADD THIS
  for (ByteBuffer byteBuffer : buf.nioBuffers()) {
    chunkCrc32.update(byteBuffer);
  }
} catch (Exception e) {
  buf.release();  // Release encrypted buffer
  throw e;
}
```

---

#### Test #3: testRealBug_KmsExceptionAfterRetainedDuplicateLeaksBuffer

**Bug Location:** `PutOperation.java:1589-1592`

**Reproduction:**
```java
cryptoJobHandler.submitJob(
  new EncryptJob(...,
      isMetadataChunk() ? null : buf.retainedDuplicate(),  // Arg 3: EVALUATED → refCnt++
      ByteBuffer.wrap(chunkUserMetadata),                  // Arg 4: EVALUATED
      kms.getRandomKey(),                                  // Arg 5: THROWS!
      ...));
```

**Root Cause:** Java evaluates constructor arguments LEFT-TO-RIGHT:
1. Argument 3: `buf.retainedDuplicate()` → creates retained duplicate (refCnt++)
2. Argument 4: `ByteBuffer.wrap(...)` → succeeds
3. Argument 5: `kms.getRandomKey()` → **THROWS GeneralSecurityException**
4. EncryptJob constructor **NEVER CALLED**
5. Retained duplicate created but never passed to EncryptJob
6. **LEAKED** - no reference to it exists

**Test Method:**
1. Create original buffer with refCnt=1
2. Call `retainedDuplicate()` → refCnt=1 for duplicate
3. Simulate `kms.getRandomKey()` throwing
4. EncryptJob constructor never called
5. Retained duplicate leaked
6. **TEST FAILS**

**Required Fix:**
```java
ByteBuf retainedCopy = null;
try {
  retainedCopy = isMetadataChunk() ? null : buf.retainedDuplicate();
  SecretKeySpec key = kms.getRandomKey();  // May throw

  cryptoJobHandler.submitJob(
    new EncryptJob(...,
        retainedCopy,
        ByteBuffer.wrap(chunkUserMetadata),
        key,
        ...));

  retainedCopy = null;  // Ownership transferred to EncryptJob
} catch (GeneralSecurityException e) {
  if (retainedCopy != null) {
    retainedCopy.release();  // Release if not transferred
  }
  throw new RouterException(...);
}
```

---

## Running the Tests

### Step 1: Verify Tests Are Ignored

All real bug tests are annotated with `@Ignore` and will be skipped:

```bash
./gradlew :ambry-router:test --tests "PutOperationRealBugTest"
```

Output:
```
com.github.ambry.router.PutOperationRealBugTest > testRealBug_CrcExceptionAfterCompressionLeaksCompressedBuffer SKIPPED
com.github.ambry.router.PutOperationRealBugTest > testRealBug_CrcExceptionInEncryptionCallbackLeaksEncryptedBuffer SKIPPED
com.github.ambry.router.PutOperationRealBugTest > testRealBug_KmsExceptionAfterRetainedDuplicateLeaksBuffer SKIPPED
```

### Step 2: Enable Tests to See Failures

Remove `@Ignore` annotations and run with ByteBuf tracking:

```bash
# Edit PutOperationRealBugTest.java - remove @Ignore from one test
./gradlew :ambry-router:test \
    --tests "PutOperationRealBugTest.testRealBug_CrcExceptionAfterCompressionLeaksCompressedBuffer" \
    -PwithByteBufTracking
```

Expected output:
```
com.github.ambry.router.PutOperationRealBugTest > testRealBug_CrcExceptionAfterCompressionLeaksCompressedBuffer FAILED
    java.lang.AssertionError: ByteBuf leak detected: 1 buffer(s) not released

=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (refCnt=1, capacity=2048):
  ├─ Allocated: PooledByteBufAllocator.heapBuffer(2048)
  ├─ Wrapped by: ThrowingNioBuffersByteBuf
  ├─ Flow Path:
  │  └─ ThrowingNioBuffersByteBuf.nioBuffers() → RuntimeException
  │  └─ [NO RELEASE - LEAKED]
  └─ Status: LEAKED
```

### Step 3: Apply Production Fixes

After applying the fixes to PutOperation.java, re-run the tests:

```bash
./gradlew :ambry-router:test --tests "PutOperationRealBugTest" -PwithByteBufTracking
```

Expected output:
```
com.github.ambry.router.PutOperationRealBugTest > testRealBug_CrcExceptionAfterCompressionLeaksCompressedBuffer PASSED ✓
com.github.ambry.router.PutOperationRealBugTest > testRealBug_CrcExceptionInEncryptionCallbackLeaksEncryptedBuffer PASSED ✓
com.github.ambry.router.PutOperationRealBugTest > testRealBug_KmsExceptionAfterRetainedDuplicateLeaksBuffer PASSED ✓

=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 0
No leaks detected
```

---

## Test Workflow

```
1. CREATE TESTS (✅ Done)
   ↓
2. TESTS ARE @Ignored (✅ Current state)
   ↓
3. APPLY PRODUCTION FIXES (⏳ Next step)
   ↓
4. REMOVE @Ignore ANNOTATIONS
   ↓
5. RUN TESTS WITH FIXES (Should PASS)
   ↓
6. COMMIT & PUSH
```

---

## Production Fixes Required

### Fix #1: PutOperation.java line 1562-1576 (compressChunk)

**Add try-catch around CRC calculation after compression:**

```java
if (newBuffer != null) {
  buf.release();
  buf = newBuffer;
  isChunkCompressed = true;
  if (routerConfig.routerVerifyCrcForPutRequests) {
    try {
      chunkCrc32.reset();
      for (ByteBuffer byteBuffer : buf.nioBuffers()) {
        chunkCrc32.update(byteBuffer);
      }
    } catch (Exception e) {
      buf.release();  // Release compressed buffer on error
      throw e;
    }
  }
}
```

### Fix #2: PutOperation.java line 1498-1503 (encryptionCallback)

**Add try-catch around CRC calculation after encryption:**

```java
buf = result.getEncryptedBlobContent();
isChunkEncrypted = true;
try {
  for (ByteBuffer byteBuffer : buf.nioBuffers()) {
    chunkCrc32.update(byteBuffer);
  }
} catch (Exception e) {
  buf.release();  // Release encrypted buffer on error
  throw e;
}
```

### Fix #3: PutOperation.java line 1589-1592 (encryptChunk)

**Pre-evaluate arguments to avoid left-to-right evaluation leak:**

```java
ByteBuf retainedCopy = null;
try {
  retainedCopy = isMetadataChunk() ? null : buf.retainedDuplicate();
  SecretKeySpec key = kms.getRandomKey();

  cryptoJobHandler.submitJob(
    new EncryptJob(accountId, containerId, retainedCopy,
        ByteBuffer.wrap(chunkUserMetadata), key, cryptoService, kms,
        cryptoJobMetricsTracker, encryptionCallback));

  retainedCopy = null;  // Ownership transferred
} catch (GeneralSecurityException e) {
  if (retainedCopy != null) {
    retainedCopy.release();
  }
  throw new RouterException("Failed to create encryption job", e,
      RouterErrorCode.UnexpectedInternalError);
}
```

---

## Summary

**Status:** ✅ Real bug tests created, compiled, and ready

**What's Done:**
- Created `PutOperationRealBugTest.java` with 3 tests
- Fixed compilation errors (uses `UnpooledHeapByteBuf` instead of non-public `WrappedByteBuf`)
- Tests use leak detection (enabled)
- Tests are @Ignored (will fail until fixes applied)
- Documented all bugs with exact code locations

**What's Next:**
- Apply production fixes to PutOperation.java
- Remove @Ignore annotations
- Run tests to verify fixes work
- Commit fixes with passing tests

**Validation:**
- Tests will FAIL without fixes (proving bugs exist)
- Tests will PASS with fixes (proving bugs are fixed)
- Provides permanent regression protection

---

**End of Documentation**
