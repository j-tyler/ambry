# Production Memory Leak Tests

**Created:** 2025-11-10
**Purpose:** Production-level tests that FAIL when bugs exist and PASS when fixed

---

## Overview

This document describes 6 new production test classes containing 28 tests that verify memory leak fixes. These tests are designed to:

1. **FAIL when bugs exist** - Tests will detect leaks via ByteBuf tracker
2. **PASS after fixes applied** - Tests will succeed once production code is corrected
3. **Run in CI/CD** - Can be integrated into continuous testing
4. **Prevent regressions** - Will catch if bugs are reintroduced

**Key Difference from Bug-Exposing Tests:**
- Bug-exposing tests (e.g., `GCMCryptoServiceLeakTest.testDecryptExceptionLeaksDecryptedContent`) disable leak detection and manually demonstrate bugs
- Production tests **enable leak detection** and will **fail assertions** when leaks occur

---

## Test Classes Created

### 1. DecryptJobProductionLeakTest.java

**Location:** `ambry-router/src/test/java/com/github/ambry/router/DecryptJobProductionLeakTest.java`

**Bug:** DecryptJob.closeJob() does not release encryptedBlobContent
**Location:** `ambry-router/src/main/java/com/github/ambry/router/DecryptJob.java:112-114`

**Tests (3):**

#### testDecryptJobAbortedBeforeExecutionReleasesBuffer
- **Scenario:** Job aborted before run() executes (e.g., operation timeout)
- **Expected:** encryptedBlobContent must be released in closeJob()
- **With Bug:** Test FAILS - buffer leaked
- **After Fix:** Test PASSES

#### testDecryptJobAbortedDuringExecutionReleasesBuffer
- **Scenario:** Job starts executing but is aborted mid-execution
- **Expected:** Buffer properly cleaned up
- **With Bug:** Test FAILS - buffer leaked
- **After Fix:** Test PASSES

#### testMultipleDecryptJobAbortsDoNotLeak
- **Scenario:** Multiple operations cancelled (simulates production load)
- **Expected:** No accumulated leaks from 10 aborted operations
- **With Bug:** Test FAILS - 10 buffers leaked
- **After Fix:** Test PASSES

**Required Fix:**
```java
@Override
public void closeJob(GeneralSecurityException gse) {
  // ADD THIS:
  if (encryptedBlobContent != null) {
    encryptedBlobContent.release();
  }
  callback.onCompletion(null, gse);
}
```

---

### 2. GCMCryptoServiceProductionLeakTest.java

**Location:** `ambry-router/src/test/java/com/github/ambry/router/GCMCryptoServiceProductionLeakTest.java`

**Bug:** decrypt() catch block releases wrong buffer (toDecrypt instead of decryptedContent)
**Location:** `ambry-router/src/main/java/com/github/ambry/router/GCMCryptoService.java:196-200`

**Tests (5):**

#### testDecryptCorruptedDataDoesNotLeak
- **Scenario:** Invalid/corrupted encrypted data causes decryption to fail
- **Expected:** decryptedContent buffer (allocated on line 180) released in catch
- **With Bug:** Test FAILS - decryptedContent leaked, toDecrypt double-released
- **After Fix:** Test PASSES

#### testDecryptWithWrongKeyDoesNotLeak
- **Scenario:** Using wrong decryption key (common in key rotation scenarios)
- **Expected:** Decryption fails, buffer properly released
- **With Bug:** Test FAILS - buffer leaked
- **After Fix:** Test PASSES

#### testMultipleDecryptionFailuresDoNotLeak
- **Scenario:** 20 consecutive invalid decryption requests
- **Expected:** No accumulated leaks
- **With Bug:** Test FAILS - 20 buffers leaked
- **After Fix:** Test PASSES

#### testDecryptTruncatedDataDoesNotLeak
- **Scenario:** Network transmission error or incomplete data
- **Expected:** Decryption fails, buffer released
- **With Bug:** Test FAILS - buffer leaked
- **After Fix:** Test PASSES

#### testSuccessfulDecryptionAfterFailures
- **Scenario:** Successful decryption after some failures (verifies fix doesn't break normal operation)
- **Expected:** No leaks from failures or success
- **After Fix:** Test PASSES

**Required Fix:**
```java
public ByteBuf decrypt(ByteBuf toDecrypt, SecretKeySpec key) throws GeneralSecurityException {
  ByteBuf decryptedContent = null;
  try {
    decryptedContent = allocator.directBuffer(ciphertext.length);
    // ... decryption logic ...
    return decryptedContent;
  } catch (Exception e) {
    // FIX: Release the right buffer!
    if (decryptedContent != null) {
      decryptedContent.release();  // ✓ Release what we allocated
    }
    // DO NOT release toDecrypt - caller owns it!
    throw new GeneralSecurityException(...);
  }
}
```

---

### 3. PutOperationCompressionProductionLeakTest.java

**Location:** `ambry-router/src/test/java/com/github/ambry/router/PutOperationCompressionProductionLeakTest.java`

**Bugs:** Exceptions after compressionService.compressChunk() returns leak compressed buffer
**Locations:**
- `ambry-router/.../PutOperation.java:1562-1576` (CRC calculation after compression)
- `ambry-router/.../PutOperation.java:1498-1503` (CRC calculation in encryption callback)

**Tests (5):**

#### testCompressionFollowedByCrcCalculationExceptionDoesNotLeak
- **Scenario:** CRC calculation fails after ownership transfer from compression service
- **Expected:** Compressed buffer released in catch block
- **With Bug:** Test FAILS - compressed buffer leaked
- **After Fix:** Test PASSES

#### testMultipleCompressionsWithExceptionsDoNotLeak
- **Scenario:** 10 chunk compressions with intermittent failures
- **Expected:** No accumulated leaks
- **With Bug:** Test FAILS - multiple buffers leaked
- **After Fix:** Test PASSES

#### testEncryptionCallbackCrcCalculationExceptionDoesNotLeak
- **Scenario:** CRC calculation fails after receiving encrypted buffer from EncryptJob
- **Expected:** Encrypted buffer released
- **With Bug:** Test FAILS - encrypted buffer leaked
- **After Fix:** Test PASSES

#### testSuccessfulCompressionAndCrcCalculationNoLeak
- **Scenario:** Normal compression and CRC calculation (verifies fix doesn't break normal operation)
- **Expected:** No leaks
- **After Fix:** Test PASSES

#### testLargeBufferCompressionWithFailuresDoesNotLeak
- **Scenario:** 4MB chunk compression with failure (realistic size)
- **Expected:** No leak even with large buffers
- **With Bug:** Test FAILS - very visible leak
- **After Fix:** Test PASSES

**Required Fix (two locations):**

**Location 1: PutOperation.java:1562-1576**
```java
private void compressChunk() {
  ByteBuf compressedBuffer = null;
  try {
    compressedBuffer = compressionService.compressChunk(buf);
    buf.release();  // Release old buffer
    buf = compressedBuffer;  // Ownership transfer
    compressedBuffer = null;  // Mark as transferred

    // CRC calculation
    for (ByteBuffer bb : buf.nioBuffers()) {
      crc32.update(bb);
    }
  } catch (Exception e) {
    if (compressedBuffer != null) {
      compressedBuffer.release();  // Release if not transferred
    }
    throw e;
  }
}
```

**Location 2: PutOperation.java:1498-1503**
```java
private void encryptionCallback(EncryptJob.EncryptJobResult result) {
  ByteBuf encryptedBuffer = null;
  try {
    encryptedBuffer = result.getEncryptedBlobContent();
    buf = encryptedBuffer;  // Ownership transfer
    encryptedBuffer = null;  // Mark as transferred

    // CRC calculation
    for (ByteBuffer bb : buf.nioBuffers()) {
      crc32.update(bb);
    }
  } catch (Exception e) {
    if (encryptedBuffer != null) {
      encryptedBuffer.release();
    }
    throw e;
  }
}
```

---

### 4. GetBlobOperationDecompressionProductionLeakTest.java

**Location:** `ambry-router/src/test/java/com/github/ambry/router/GetBlobOperationDecompressionProductionLeakTest.java`

**Bugs:** Exceptions after decompressContent() returns leak decompressed buffer
**Locations:**
- `ambry-router/.../GetBlobOperation.java:882-885` (chunkIndexToBuf.put() exception)
- `ambry-router/.../GetBlobOperation.java:884` (filterChunkToRange() exception)
- `ambry-router/.../GetBlobOperation.java:1588-1597` (resolveRange() exception)

**Tests (6):**

#### testDecompressionFollowedByMapPutExceptionDoesNotLeak
- **Scenario:** Map operation fails after decompression ownership transfer
- **Expected:** Decompressed buffer released
- **With Bug:** Test FAILS - decompressed buffer leaked
- **After Fix:** Test PASSES

#### testDecompressionFollowedByFilterExceptionDoesNotLeak
- **Scenario:** filterChunkToRange() throws IndexOutOfBoundsException after decompression
- **Expected:** Decompressed buffer released
- **With Bug:** Test FAILS - buffer leaked
- **After Fix:** Test PASSES

#### testSimpleBlobResolveRangeExceptionDoesNotLeak
- **Scenario:** resolveRange() throws exception preventing safeRelease() call
- **Expected:** Buffer released in catch block
- **With Bug:** Test FAILS - buffer leaked
- **After Fix:** Test PASSES

#### testMultipleDecompressionsWithExceptionsDoNotLeak
- **Scenario:** 10 chunk decompressions with intermittent failures
- **Expected:** No accumulated leaks
- **With Bug:** Test FAILS - multiple leaks
- **After Fix:** Test PASSES

#### testSuccessfulDecompressionAndProcessingNoLeak
- **Scenario:** Normal decompression and processing
- **Expected:** No leaks
- **After Fix:** Test PASSES

#### testLargeBufferDecompressionWithFailuresDoesNotLeak
- **Scenario:** 4MB chunk decompression with failure
- **Expected:** No leak even with large buffers
- **With Bug:** Test FAILS - very visible leak
- **After Fix:** Test PASSES

**Required Fixes (three locations):**

**Location 1: GetBlobOperation.java:882-885**
```java
private void maybeProcessCallbacks() {
  ByteBuf decompressedContent = null;
  try {
    decompressedContent = decompressContent(decryptedContent);
    ByteBuf filteredContent = filterChunkToRange(decompressedContent, ...);
    chunkIndexToBuf.put(chunkIndex, filteredContent);
    decompressedContent = null;  // Ownership transferred to map
  } catch (Exception e) {
    if (decompressedContent != null) {
      decompressedContent.release();
    }
    throw e;
  }
}
```

**Location 2: GetBlobOperation.java:1588-1597 (simple blob path)**
```java
ByteBuf decompressedContent = null;
try {
  decompressedContent = decompressContent(decryptedContent);
  if (!resolveRange(totalSize)) {
    return;
  }
  operationResult.set(decompressedContent);
  decompressedContent = null;  // Ownership transferred
} catch (Exception e) {
  if (decompressedContent != null) {
    ReferenceCountUtil.safeRelease(decompressedContent);
  }
  throw e;
}
```

---

### 5. NettyRequestProductionLeakTest.java

**Location:** `ambry-rest/src/test/java/com/github/ambry/rest/NettyRequestProductionLeakTest.java`

**Bug:** writeContent() exception after retain() leaks buffer
**Location:** `ambry-rest/src/main/java/com/github/ambry/rest/NettyRequest.java:208-215`

**Tests (4):**

#### testWriteContentChannelWriteFailureDoesNotLeak
- **Scenario:** writeChannel.write() throws exception after content.retain()
- **Expected:** Retained content released in catch block
- **With Bug:** Test FAILS - content leaked with refCnt=2
- **After Fix:** Test PASSES

#### testMultipleWriteContentFailuresDoNotLeak
- **Scenario:** 5 consecutive write failures
- **Expected:** No accumulated leaks
- **With Bug:** Test FAILS - 5 buffers leaked
- **After Fix:** Test PASSES

#### testWriteContentIntermittentFailuresDoNotLeak
- **Scenario:** Some failures, then success (verifies recovery)
- **Expected:** No leaks from either failures or success
- **After Fix:** Test PASSES

#### testSuccessfulWriteContentNoLeak
- **Scenario:** Normal write operation
- **Expected:** No leaks
- **After Fix:** Test PASSES

**Required Fix:**
```java
protected void writeContent(AsyncWritableChannel writeChannel, ReadIntoCallbackWrapper callbackWrapper,
    HttpContent httpContent) {
  // Retain this httpContent so it won't be garbage collected right away.
  httpContent.retain();

  try {
    writeChannel.write(httpContent.content(), new ContentWriteCallback(httpContent, isLast, callbackWrapper));
  } catch (Exception e) {
    // ADD THIS: Release the retained buffer if write fails
    httpContent.release();
    throw e;
  }

  allContentReceived = isLast;
}
```

---

### 6. AmbrySendToHttp2AdaptorProductionLeakTest.java

**Location:** `ambry-network/src/test/java/com/github/ambry/network/http2/AmbrySendToHttp2AdaptorProductionLeakTest.java`

**Bug:** Retained slices not released on exception due to shared refCnt
**Location:** `ambry-network/.../http2/AmbrySendToHttp2Adaptor.java:82-98`

**Tests (5):**

#### testExceptionDuringFrameWritingShouldNotLeakSlices
- **Scenario:** Exception during ctx.write() after some slices retained
- **Expected:** All retained slices released
- **With Bug:** Test FAILS - multiple slices leaked due to shared refCnt
- **After Fix:** Test PASSES

#### testFinallyBlockShouldAccountForSharedRefCnt
- **Scenario:** Exception on first frame, finally block insufficient
- **Expected:** Retained slice released in catch before finally
- **With Bug:** Test FAILS - shared refCnt causes leak
- **After Fix:** Test PASSES

#### testMultipleFrameWriteFailuresDoNotLeak
- **Scenario:** 10 multi-frame writes with failures
- **Expected:** No accumulated leaks
- **With Bug:** Test FAILS - multiple buffers leaked
- **After Fix:** Test PASSES

#### testLargeMultiFrameSendWithFailureDoesNotLeak
- **Scenario:** 100KB content (~13 frames) with failure after 5 frames
- **Expected:** All 5 retained slices released
- **With Bug:** Test FAILS - massive leak (refCnt=5)
- **After Fix:** Test PASSES

#### testSuccessfulMultiFrameWriteNoLeak
- **Scenario:** Normal multi-frame write
- **Expected:** No leaks
- **After Fix:** Test PASSES

**Required Fix:**
```java
public void write(ChannelHandlerContext ctx, Send send, ChannelPromise promise) throws Exception {
  List<ByteBuf> retainedSlices = new ArrayList<>();  // Track all retained slices
  try {
    while (send.content().isReadable(maxFrameSize)) {
      ByteBuf slice = send.content().readSlice(maxFrameSize);
      slice.retain();
      retainedSlices.add(slice);  // Track it
      DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(slice, false);
      ctx.write(dataFrame);
    }

    // Last slice
    ByteBuf lastSlice = send.content().readSlice(send.content().readableBytes());
    lastSlice.retain();
    retainedSlices.add(lastSlice);
    DefaultHttp2DataFrame lastFrame = new DefaultHttp2DataFrame(lastSlice, true);
    ctx.write(lastFrame);

    ctx.flush();
  } catch (Exception e) {
    logger.error("Error while processing frames. Channel: {}", ctx.channel(), e);
    // ADD THIS: Release all retained slices on exception
    for (ByteBuf slice : retainedSlices) {
      slice.release();
    }
    throw e;
  } finally {
    send.content().release();
  }
}
```

---

## Running the Tests

### Run All Production Tests
```bash
./run-production-leak-tests.sh
```

### Run Individual Test Classes
```bash
# Router tests
./gradlew :ambry-router:test -PwithByteBufTracking --tests DecryptJobProductionLeakTest
./gradlew :ambry-router:test -PwithByteBufTracking --tests GCMCryptoServiceProductionLeakTest
./gradlew :ambry-router:test -PwithByteBufTracking --tests PutOperationCompressionProductionLeakTest
./gradlew :ambry-router:test -PwithByteBufTracking --tests GetBlobOperationDecompressionProductionLeakTest

# Rest tests
./gradlew :ambry-rest:test -PwithByteBufTracking --tests NettyRequestProductionLeakTest

# Network tests
./gradlew :ambry-network:test -PwithByteBufTracking --tests AmbrySendToHttp2AdaptorProductionLeakTest
```

---

## Test Results Interpretation

### With Bugs (Current State)

**Expected Output:**
```
❌ DecryptJobProductionLeakTest > testDecryptJobAbortedBeforeExecutionReleasesBuffer FAILED
    ByteBuf leak detected: 1 buffer with refCnt=1

❌ GCMCryptoServiceProductionLeakTest > testDecryptCorruptedDataDoesNotLeak FAILED
    ByteBuf leak detected: 1 direct buffer with refCnt=1

❌ All tests FAIL with leak detection errors
```

**ByteBuf Flow Tracker Output:**
```
=== ByteBuf Flow Final Report ===
Leak Paths: 28
Critical Leaks (🚨): 28 (direct buffers - never GC'd)
```

### After Fixes Applied

**Expected Output:**
```
✅ DecryptJobProductionLeakTest > testDecryptJobAbortedBeforeExecutionReleasesBuffer PASSED
✅ GCMCryptoServiceProductionLeakTest > testDecryptCorruptedDataDoesNotLeak PASSED
✅ All tests PASS
```

**ByteBuf Flow Tracker Output:**
```
=== ByteBuf Flow Final Report ===
Leak Paths: 0
Total Paths: 28 (all clean)
```

---

## Summary

| Module | Test Class | Tests | Bugs Covered | Lines of Code |
|--------|-----------|-------|--------------|---------------|
| ambry-router | DecryptJobProductionLeakTest | 3 | 1 | 149 |
| ambry-router | GCMCryptoServiceProductionLeakTest | 5 | 1 | 208 |
| ambry-router | PutOperationCompressionProductionLeakTest | 5 | 2 | 227 |
| ambry-router | GetBlobOperationDecompressionProductionLeakTest | 6 | 3 | 268 |
| ambry-rest | NettyRequestProductionLeakTest | 4 | 1 | 176 |
| ambry-network | AmbrySendToHttp2AdaptorProductionLeakTest | 5 | 1 | 244 |
| **TOTAL** | **6 classes** | **28 tests** | **9 bugs** | **1,272 LOC** |

---

## Integration with CI/CD

These tests can be integrated into continuous integration:

```yaml
# Example GitHub Actions workflow
name: Memory Leak Tests

on: [push, pull_request]

jobs:
  leak-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
        with:
          submodules: recursive

      - name: Set up JDK 11
        uses: actions/setup-java@v2
        with:
          java-version: '11'

      - name: Run Production Leak Tests
        run: ./run-production-leak-tests.sh
```

---

## Next Steps

1. **Verify Bugs Exist:**
   ```bash
   ./run-production-leak-tests.sh
   ```
   All tests should FAIL, confirming bugs exist.

2. **Apply Fixes:**
   - Fix DecryptJob.java:112-114
   - Fix GCMCryptoService.java:196-200
   - Fix PutOperation.java:1562-1576, 1498-1503
   - Fix GetBlobOperation.java:882-885, 884, 1588-1597
   - Fix NettyRequest.java:208-215
   - Fix AmbrySendToHttp2Adaptor.java:82-98

3. **Re-run Tests:**
   ```bash
   ./run-production-leak-tests.sh
   ```
   All tests should PASS, confirming fixes work.

4. **Commit and Push:**
   ```bash
   git add .
   git commit -m "Fix 9 critical ByteBuf memory leaks"
   git push
   ```

5. **Enable in CI/CD:**
   Add to your continuous integration pipeline.

---

**End of Document**
