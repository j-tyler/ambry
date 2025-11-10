# ByteBuf Leak Test Expectations - Per-Test Analysis

This document describes exactly what to look for in the ByteBuf Flow Tracker output for each of the 67 leak tests created on this branch.

**Updated 2025-11-09:** Added caller-level ownership transfer tests for PutOperation/GetBlobOperation compression/decompression and retained duplicate paths.

## How to Use This Document

For each test, this guide provides:
1. **Test Purpose** - What the test does
2. **Expected Behavior** - Whether leak should be detected
3. **Tracker Output Pattern** - Exact output to look for
4. **Confirmation Criteria** - How to verify expectations
5. **Bug Location** - Where the production bug is (if applicable)

## Test Execution Command

```bash
# Run all leak tests with tracker
./gradlew test -PwithByteBufTracking --tests '*LeakTest'

# Run specific test
./gradlew test -PwithByteBufTracking --tests 'DecryptJobLeakTest.testCloseJobBeforeRunLeaksBuffer'
```

---

# HIGH-Risk Tests (39 tests)

**Updated 2025-11-09:** Added 12 new caller-level ownership transfer tests (7 bug-exposing + 5 baseline)

## EncryptJobLeakTest.java (4 tests)

### Test 1: testEncryptionFailureReleasesBuffer

**Purpose:** Verify GCMCryptoService.encrypt() releases input buffer when encryption fails

**Expected Behavior:** ✅ NO LEAK - Buffer properly released in error path

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 0
```

**Confirmation:**
- Unreleased count = 0
- Test passes without NettyByteBufLeakHelper assertion failure

**What Would Indicate Bug:**
```
Unreleased ByteBufs: 1

ByteBuf #1:
  First Touch: GCMCryptoService.encrypt()
  Allocated: heapBuffer(toEncrypt.capacity())
  Flow: Cipher.doFinal() → Exception
  Status: LEAKED
```

---

### Test 2: testEncryptionSuccessTransfersOwnership

**Purpose:** Verify successful encryption transfers ownership to EncryptJobResult

**Expected Behavior:** ✅ NO LEAK - Encrypted buffer owned by result object

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Unreleased count = 0
- Test releases result's buffer: `encryptJobResult.getEncryptedBlobContent().release()`
- No leak after release

**What Would Indicate Bug:**
- If leak detected after explicit release, indicates ownership not transferred correctly

---

### Test 3: testCloseJobDuringActiveEncryption

**Purpose:** Test race condition: closeJob() called while encryption in progress

**Expected Behavior:** ✅ NO LEAK - Either encryption completes normally or buffers cleaned up

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- No double-release (would throw IllegalReferenceCountException)
- No leaked buffers
- Test assertion `assertFalse("Should not have double-release", doubleRelease.get())` passes

**What Would Indicate Bug:**
```
java.lang.IllegalReferenceCountException: refCnt: 0, decrement: 1
```
or
```
Unreleased ByteBufs: 1
(race condition causing leak or double-release)
```

---

### Test 4: testCloseJobAfterEncryptionSuccess

**Purpose:** Verify closeJob() after successful encryption releases result buffer

**Expected Behavior:** ✅ NO LEAK - Result buffer released by closeJob()

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Encryption completes successfully
- closeJob() releases encryptedContent
- No buffers remain

**What Would Indicate Bug:**
```
Unreleased ByteBufs: 1

ByteBuf #1:
  First Touch: GCMCryptoService.encrypt() → return
  Stored in: EncryptJobResult.encryptedBlobContent
  Flow: EncryptJob.closeJob() → [NO RELEASE]
```

---

## DecryptJobLeakTest.java (6 tests)

### Test 1: testDecryptionFailureReleasesEncryptedContent

**Purpose:** Verify decryption failure releases encrypted content

**Expected Behavior:** ✅ NO LEAK - encryptedContent released in catch block

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Decryption throws exception
- encryptedContent released before exception propagated
- No leak detected

---

### Test 2: testDecryptionSuccessTransfersOwnership

**Purpose:** Verify successful decryption transfers ownership to result

**Expected Behavior:** ✅ NO LEAK - Decrypted buffer owned by result

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Decryption succeeds
- Result buffer explicitly released: `result.getDecryptedBlobContent().release()`
- No leak after release

---

### Test 3: testCloseJobBeforeRunLeaksBuffer ⚠️ CRITICAL BUG

**Purpose:** Demonstrate closeJob() doesn't release encryptedBlobContent

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (refCnt=1, capacity=512):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(512)
  ├─ First Touch: DecryptJob.<init>(encryptedContent)
  │  └─ Constructor parameter stored as: encryptedBlobContent
  ├─ Flow Path:
  │  └─ DecryptJob.closeJob()
  │     └─ job.exception = GeneralSecurityException
  │     └─ [NO RELEASE OF encryptedBlobContent]
  └─ Status: LEAKED
```

**Confirmation:**
- ✅ Unreleased count = 1 (confirms bug exists)
- ✅ ByteBuf tracked to DecryptJob constructor
- ✅ Flow shows closeJob() called but no release
- ✅ Test assertion passes: Buffer intentionally leaked

**Bug Location:** `ambry-router/src/main/java/com/github/ambry/router/DecryptJob.java:112-114`

**Fix Required:**
```java
void closeJob(Exception exception) {
  this.exception = exception;
  if (encryptedBlobContent != null) {
    encryptedBlobContent.release();  // ADD THIS
    encryptedBlobContent = null;
  }
}
```

**After Fix - Expected Output:**
```
Unreleased ByteBufs: 0
```

---

### Test 4: testCloseJobDuringActiveDecryption

**Purpose:** Test race condition during decryption

**Expected Behavior:** ✅ NO LEAK - Buffers properly cleaned up

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- No double-release exception
- No leaked buffers
- Race handled correctly

---

### Test 5: testCloseJobAfterDecryptionSuccess

**Purpose:** Verify closeJob() after success releases result

**Expected Behavior:** ✅ NO LEAK - Result buffer released

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Decryption completes
- closeJob() releases decryptedContent
- No leak

---

### Test 6: testCloseJobWithEncryptionKeyNotSet

**Purpose:** Verify closeJob() when encryption key missing

**Expected Behavior:** ✅ NO LEAK - encryptedContent released even without key

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- closeJob() called without ever setting encryption key
- encryptedBlobContent still released (if this test were using fixed closeJob())
- Currently: LEAKS (same bug as test 3)

**Current Tracker Output (Bug Present):**
```
Unreleased ByteBufs: 1
(Same bug as testCloseJobBeforeRunLeaksBuffer)
```

---

## AmbrySendToHttp2AdaptorLeakTest.java (5 tests)

### Test 1: testExceptionDuringFrameWritingLeaksRetainedSlices ⚠️ CRITICAL BUG

**Purpose:** Demonstrate exception during multi-frame write leaks slices due to shared refCnt

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (refCnt=2, capacity=24576):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(24*1024)
  ├─ Flow Path:
  │  └─ AmbrySendToHttp2Adaptor.write()
  │     └─ readSlice() → slice1.retain() [shared refCnt: 1→2]
  │     └─ ctx.write(slice1) succeeds
  │     └─ readSlice() → slice2.retain() [shared refCnt: 2→3]
  │     └─ ctx.write(slice2) → Exception thrown
  │     └─ finally: content.release() [shared refCnt: 3→2]
  │     └─ [LEAKED - refCnt still > 0]
  └─ Status: LEAKED (refCnt=2 due to shared counter)
```

**Key Insight:** readSlice() creates slices with SHARED refCnt, not independent counters!

**Confirmation:**
- ✅ Original buffer refCnt > 0 after finally block
- ✅ Test manually cleans up: `while (content.refCnt() > 0) content.release()`
- ✅ Leak helper disabled for this test (expects leak)

**Bug Location:** `ambry-network/src/main/java/com/github/ambry/network/http2/AmbrySendToHttp2Adaptor.java:82-98`

**Fix Required:** Track and release all retained slices in catch block:
```java
List<ByteBuf> retainedSlices = new ArrayList<>();
try {
  while (send.content().isReadable(maxFrameSize)) {
    ByteBuf slice = send.content().readSlice(maxFrameSize);
    slice.retain();
    retainedSlices.add(slice);
    ctx.write(dataFrame);
  }
  // last slice...
} catch (Exception e) {
  // Release all retained slices
  for (ByteBuf slice : retainedSlices) {
    slice.release();
  }
  throw e;
} finally {
  send.content().release();
}
```

---

### Test 2: testFinallyReleasesOriginalButNotRetainedSlices ⚠️ CRITICAL BUG

**Purpose:** Demonstrate finally block releases original but SHARED refCnt means buffer still leaked

**Expected Behavior:** ❌ LEAK EXPECTED - Exposes shared refCnt leak

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (refCnt=1, capacity=20480):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(20*1024)
  ├─ Flow Path:
  │  └─ AmbrySendToHttp2Adaptor.write()
  │     └─ readSlice() → slice1.retain() [shared refCnt: 1→2]
  │     └─ ctx.write(slice1) → Exception on FIRST frame
  │     └─ finally: content.release() [shared refCnt: 2→1]
  │     └─ [LEAKED - refCnt=1, not 0!]
  └─ Status: LEAKED (shared refCnt prevents cleanup)
```

**Confirmation:**
- ✅ refCnt = 1 after finally (not 0)
- ✅ Slice also shows refCnt = 1 (same counter!)
- ✅ Test manually releases: `content.release()`

---

### Test 3: testChannelClosedDuringWrite

**Purpose:** Verify early-return path when channel closed releases buffer

**Expected Behavior:** ✅ NO LEAK - msg released before any slices created

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Channel.isOpen() returns false
- ReferenceCountUtil.release(msg) called immediately
- No slices created
- Promise failed with ChannelException

---

### Test 4: testSuccessfulMultiFrameWriteNoLeak

**Purpose:** Verify multi-frame send with shared refCnt properly cleaned up

**Expected Behavior:** ✅ NO LEAK - All slices released by channel

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- 4 data frames created (3 full + 1 partial)
- After write: refCnt = 4 (shared refCnt: 1 original + 4 retains - 1 finally release)
- Test simulates channel releasing all frames
- Final refCnt = 0 after all slices released

**Key Insight:**
```java
// After adaptor.write():
assertEquals(4, content.refCnt());  // Shared refCnt from 4 retain() calls

// Simulate channel releasing frames:
for (Http2DataFrame frame : dataFrames) {
  frame.content().release();  // Each release() decrements shared counter
}

// Now all cleaned up:
assertEquals(0, content.refCnt());
```

---

### Test 5: testProperFixWouldReleaseAllRetainedSlices

**Purpose:** Demonstrate the correct fix pattern for slice tracking

**Expected Behavior:** ✅ NO LEAK - Shows how to properly track and release slices

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Slices tracked in list
- Exception triggers catch block
- All slices released in catch
- Original released in finally
- No leak

---

## PutRequestLeakTest.java (6 tests)

### Test 1: testConstructorOwnership

**Purpose:** Document and verify PutRequest ownership transfer semantics

**Expected Behavior:** ✅ NO LEAK - Ownership transferred, PutRequest releases on cleanup

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- ByteBuf passed to PutRequest constructor (line 114)
- Constructor does NOT call retain() - direct assignment (line 126 in PutRequest.java)
- refCnt stays 1 after construction
- PutRequest.release() decrements refCnt to 0
- Tracker shows constructor wrapping:
  ```
  First Touch: PutRequest.<init>(materializedBlob)
  Stored as: blob field
  ```

**Key Contract:** Caller must transfer ownership completely - never touch the ByteBuf after passing to PutRequest

---

### Test 2: testConstructorOwnershipViolation

**Purpose:** Demonstrate what happens when caller violates ownership contract

**Expected Behavior:** ✅ NO LEAK - But exposes dangerous ownership pattern

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Caller creates PutRequest (blob refCnt = 1)
- Caller **incorrectly** releases blob (refCnt → 0) - OWNERSHIP VIOLATION
- writeTo() fails with IllegalReferenceCountException (expected)
- **CRITICAL:** Test must still call request.release() to clean up internal buffers (crcByteBuf, bufferToSend)
- ReferenceCountUtil.safeRelease() handles already-released blob gracefully

**What This Test Exposes:**
1. Ownership violation causes use-after-free (good - fails fast)
2. Caller must STILL call release() to prevent internal buffer leaks
3. Even when blob is gone, internal DirectMemory allocations must be cleaned up

**Bug Without Fix:**
```
DirectMemoryLeak: [allocation|deallocation] before test[9|9], after test[10|9]
(crcByteBuf or bufferToSend not released)
```

---

### Test 3: testPrepareBufferFailureMidWay

**Purpose:** Verify prepareBuffer() normal operation doesn't leak

**Expected Behavior:** ✅ NO LEAK - Happy path, buffers managed correctly

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- PutRequest created with blob
- writeTo() succeeds
- Internal buffers (crcByteBuf) allocated and released
- request.release() cleans up all buffers

**Note:** Test is simplified - full test would need mock ByteBuf that fails during nioBuffers() to test exception path

---

### Test 4: testWriteToExceptionDuringPartialWrite

**Purpose:** Verify buffers remain releasable after write exception

**Expected Behavior:** ✅ NO LEAK - Exception doesn't prevent cleanup

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Channel throws IOException after partial write
- Exception propagated to caller
- request.release() still works correctly
- All buffers cleaned up despite exception

**Tracker Flow:**
```
ByteBuf #1 (blob):
  Allocated: heapBuffer(1024)
  Flow:
    → PutRequest.<init>(blob)
    → writeTo(channel) → IOException after 200 bytes
    → request.release() → blob.release()
  Status: CLEAN
```

---

### Test 5: testDoubleRelease

**Purpose:** Verify double-release is safe due to null checks

**Expected Behavior:** ✅ NO LEAK - Second release is no-op

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- First release succeeds (blob refCnt → 0)
- Second release is safe due to null checks in release() method
- No IllegalReferenceCountException thrown

**Code Pattern:**
```java
public boolean release() {
  if (blob != null) {
    ReferenceCountUtil.safeRelease(blob);
    blob = null;  // Prevents double-release
  }
  // Same for crcByteBuf and bufferToSend
  return false;
}
```

---

### Test 6: testSuccessfulPutRequestNoLeak

**Purpose:** Baseline test - verify normal lifecycle doesn't leak

**Expected Behavior:** ✅ NO LEAK - Complete success path

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- PutRequest created
- writeTo() succeeds completely
- request.release() called
- All buffers (blob, crcByteBuf, bufferToSend) cleaned up

**Tracker Flow:**
```
ByteBuf #1 (blob):
  Allocated: heapBuffer(1024)
  Flow:
    → PutRequest.<init>(blob)
    → writeTo(channel) → success
    → request.release() → blob.release()
  Status: CLEAN
```

---

## NettyRequestLeakTest.java (4 tests)

**⚠️ CRITICAL OWNERSHIP PATTERN:** These tests require careful HttpContent lifecycle management!

`DefaultHttpContent` is a `ReferenceCounted` wrapper around ByteBuf. When tests create HttpContent:

```java
ByteBuf content = allocator.heapBuffer(1024);  // content refCnt = 1
HttpContent httpContent = new DefaultHttpContent(content);  // HttpContent wraps ByteBuf
request.addContent(httpContent);  // request calls httpContent.retain() → content refCnt = 2
httpContent.release();  // Test releases its reference → content refCnt = 1
request.close();  // Request releases its reference → content refCnt = 0 ✓
```

**Without the test releasing HttpContent**, the ByteBuf leaks even after request closes!

This is similar to PutRequestLeakTest where `request.release()` was needed even when blob was already
released - internal buffers need cleanup. Here, HttpContent is the wrapper that needs cleanup.

### Test 1: testAddContentExceptionAfterRetainLeaksBuffer

**Purpose:** Attempt to trigger exception after retain in addContent()

**Expected Behavior:** ✅ NO LEAK - Exception occurs BEFORE retain()

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- ✅ Exception path (lines 425-428) throws BEFORE line 433's retain()
- ✅ httpContent2 refCnt remains 1 (never retained)
- ✅ Test comment (line 127-128) correctly identifies this path is SAFE
- ✅ Despite test name implying leak, code analysis proves NO LEAK

**Code Analysis:**
```java
// NettyRequest.addContent() lines 425-428:
if (!isOpen()) {
  throw new RestServiceException(...);  // BEFORE line 433
}
// Line 433: requestContents.add(httpContent.retain());  // Never reached
```

**Note:** This test discovered that the exception path is actually SAFE.
The exception is thrown BEFORE retain() is called, so no leak can occur.

---

### Test 2: testWriteContentExceptionAfterRetainLeaksBuffer ⚠️ CRITICAL BUG

**Purpose:** Demonstrate writeContent() exception after retain() leaks buffer

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (refCnt=2, capacity=1024):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(1024)
  ├─ Flow Path:
  │  └─ NettyRequest.addContent(httpContent)
  │     └─ content stored in contentChunks list
  │  └─ NettyRequest.readInto(channel, callback)
  │     └─ NettyRequest.writeContent()
  │        └─ content.retain()  [refCnt: 1→2]
  │        └─ channel.write() → Exception thrown
  │        └─ [NO RELEASE IN EXCEPTION PATH]
  ├─ refCount: 2 (original + retain without matching release)
  └─ Status: LEAKED
```

**Confirmation:**
- ✅ Unreleased count = 1
- ✅ refCnt = 2 (proves retain() was called)
- ✅ Flow shows retain() followed by exception
- ✅ Test assertion passes: `assertEquals("Content should be retained and LEAKED", 2, content.refCnt())`

**Bug Location:** `ambry-rest/src/main/java/com/github/ambry/rest/NettyRequest.java:208-215`

**Fix Required:**
```java
private void writeContent() throws RestServiceException {
  try {
    HttpContent httpContent = contentChunks.poll();
    ByteBuf content = httpContent.content();
    content.retain();
    writeChannel.write(content, writeCallback);  // May throw
  } catch (Exception e) {
    content.release();  // ADD THIS - release the retained buffer
    throw e;
  }
}
```

**After Fix - Expected Output:**
```
Unreleased ByteBufs: 0
```

---

### Test 3: testWriteContentSuccessReleasesBuffer

**Purpose:** Verify successful writeContent() properly manages buffer

**Expected Behavior:** ✅ NO LEAK - Buffer retained then released by channel

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- content.retain() called
- channel.write() succeeds
- writeCallback invokes and releases buffer
- No leak

---

### Test 4: testCloseReleasesAllContent

**Purpose:** Verify channel close releases unwritten content

**Expected Behavior:** ✅ NO LEAK - Remaining buffers released on close

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Content added but not yet written
- Channel closed
- close() releases all remaining content
- No leak

---

## NettyMultipartRequestLeakTest.java (5 tests)

**⚠️ CRITICAL OWNERSHIP PATTERN:** Same HttpContent lifecycle management required!

Like NettyRequestLeakTest, these tests create `DefaultHttpContent` wrappers that must be released:

```java
ByteBuf content = allocator.heapBuffer(1024);
HttpContent httpContent = new DefaultHttpContent(content);
request.addContent(httpContent);  // request retains → content refCnt = 2
httpContent.release();  // Test releases its reference → content refCnt = 1
```

**Tests MUST release HttpContent after adding to request** or ByteBuf will leak!

### Test 1: testMultipartContentExceptionAfterRetainLeaksBuffer ⚠️ CRITICAL BUG

**Purpose:** Demonstrate same bug as NettyRequest but for multipart

**Expected Behavior:** ❌ LEAK EXPECTED - Same CRITICAL bug in multipart code path

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (refCnt=2):
  Flow:
    → NettyMultipartRequest.writeMultipartContent()
    → content.retain() [refCnt: 1→2]
    → channel.write() → Exception
    → [NO RELEASE]
  Status: LEAKED
```

**Confirmation:**
- ✅ Unreleased count = 1
- ✅ refCnt = 2
- ✅ Same bug pattern as NettyRequest

**Bug Location:** Similar to NettyRequest.java, in multipart handling code

---

### Test 2: testMultipartContentSuccessReleasesBuffer

**Purpose:** Verify successful multipart write manages buffers correctly

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 3: testMultipartSizeLimitExceededReleasesBuffers

**Purpose:** Verify size limit exception releases accumulated buffers

**Expected Behavior:** ✅ NO LEAK - Cleanup on size limit

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Multiple parts added
- Size limit exceeded
- Exception thrown
- All accumulated buffers released

---

### Test 4: testMultipartEmptyPartHandled

**Purpose:** Verify empty multipart part doesn't cause issues

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 5: testMultipartBoundaryDetectionWithLeak

**Purpose:** Verify boundary parsing doesn't leak buffers

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

## PutOperationCompressionLeakTest.java (3 tests)

**Purpose:** Tests CALLER-level ownership transfer after compressionService.compressChunk() returns compressed ByteBuf.

**CRITICAL OWNERSHIP PATTERN:**
```java
// PutOperation.java:1562-1576
ByteBuf newBuffer = compressionService.compressChunk(buf, isFullChunk, outputDirectMemory);
if (newBuffer != null) {
  buf.release();  // Old buffer released
  buf = newBuffer;  // OWNERSHIP TRANSFERRED to PutOperation
  // Exception here → buf (compressed) leaks!
}
```

After line 1565, PutOperation OWNS the compressed ByteBuf and must release it.

### Test 1: testCrcCalculationExceptionAfterCompressionLeaksCompressedBuffer ⚠️ CRITICAL BUG

**Purpose:** Demonstrate exception during CRC calculation after compression leaks compressed buffer

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (refCnt=1, capacity=~2048):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(2048)
  ├─ First Touch: Test simulating CompressionService.compressChunk() return
  ├─ Flow Path:
  │  └─ Simulates PutOperation.PutChunk.compressChunk()
  │     └─ Line 1562: ByteBuf newBuffer = compressionService.compressChunk(...)
  │     └─ Line 1564: buf.release();  // Old buffer released ✓
  │     └─ Line 1565: buf = newBuffer;  // OWNERSHIP TRANSFERRED to PutChunk
  │     └─ Line 1570: for (ByteBuffer byteBuffer : buf.nioBuffers()) {
  │        └─ Exception thrown: RuntimeException("Simulated CRC calculation failure")
  │        └─ [NO RELEASE IN CATCH BLOCK]
  └─ Status: LEAKED
```

**Confirmation:**
- ✅ Unreleased count = 1 (confirms bug exists)
- ✅ Compressed buffer refCnt = 1 (never released)
- ✅ Test manually cleans up: `compressedBuffer.release()`
- ✅ Leak helper disabled for this test (`leakHelper.setDisabled(true)`)

**Bug Location:** `ambry-router/src/main/java/com/github/ambry/router/PutOperation.java:1562-1576`

**Root Cause:** No try-catch around CRC calculation after compression. Exception after ownership transfer (line 1565) prevents normal cleanup.

**Fix Required:**
```java
ByteBuf newBuffer = compressionService.compressChunk(buf, isFullChunk, outputDirectMemory);
if (newBuffer != null) {
  buf.release();
  buf = newBuffer;
  isChunkCompressed = true;
  if (routerConfig.routerVerifyCrcForPutRequests) {
    try {  // ADD try-catch
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

### Test 2: testEncryptionCallbackCrcExceptionLeaksEncryptedBuffer ⚠️ CRITICAL BUG

**Purpose:** Demonstrate exception during CRC calculation in encryption callback leaks encrypted buffer

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (refCnt=1, capacity=~4128):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(4128)
  ├─ First Touch: Test simulating EncryptJob.EncryptJobResult
  ├─ Flow Path:
  │  └─ Simulates PutOperation.PutChunk.encryptionCallback()
  │     └─ Line 1498: buf = result.getEncryptedBlobContent();  // OWNERSHIP TRANSFER
  │     └─ Line 1500: for (ByteBuffer byteBuffer : buf.nioBuffers()) {
  │        └─ Exception thrown during nioBuffers()
  │        └─ [NO RELEASE IN EXCEPTION PATH]
  └─ Status: LEAKED
```

**Confirmation:**
- ✅ Unreleased count = 1
- ✅ Encrypted buffer refCnt = 1
- ✅ Test manually cleans up: `encryptedBuffer.release()`
- ✅ Leak helper disabled (`leakHelper.setDisabled(true)`)

**Bug Location:** `ambry-router/src/main/java/com/github/ambry/router/PutOperation.java:1498-1503`

**Root Cause:** No try-catch around CRC calculation in encryptionCallback(). Exception after ownership transfer (line 1498) leaks encrypted buffer.

**Fix Required:**
```java
private void encryptionCallback(EncryptJob.EncryptJobResult result, Exception exception) {
  // ...
  buf = result.getEncryptedBlobContent();  // Ownership transferred
  try {  // ADD try-catch
    for (ByteBuffer byteBuffer : buf.nioBuffers()) {
      chunkCrc32.update(byteBuffer);
    }
  } catch (Exception e) {
    buf.release();  // Release encrypted buffer
    throw new RuntimeException(e);
  }
  // ...
}
```

---

### Test 3: testSuccessfulCompressionNoLeak

**Purpose:** Baseline test - verify normal compression path doesn't leak

**Expected Behavior:** ✅ NO LEAK - Compressed buffer properly managed

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Compression succeeds
- CRC calculation succeeds (no exception)
- Compressed buffer released normally
- No leak

**Tracker Flow:**
```
ByteBuf #1 (sourceData):
  Allocated: heapBuffer(4096)
  Flow:
    → Released (simulating old buffer release line 1564)
  Status: CLEAN

ByteBuf #2 (compressedBuffer):
  Allocated: heapBuffer(2048)
  Flow:
    → Ownership transferred (line 1565: buf = newBuffer)
    → CRC calculation succeeds (line 1570-1572)
    → Released by caller
  Status: CLEAN
```

---

## GetBlobOperationDecompressionLeakTest.java (5 tests)

**Purpose:** Tests CALLER-level ownership transfer after decompressContent() returns decompressed ByteBuf.

**CRITICAL OWNERSHIP PATTERN:**
```java
// GetBlobOperation.java:882-885
ByteBuf decryptedContent = result.getDecryptedBlobContent();
ByteBuf decompressedContent = decompressContent(decryptedContent);  // Returns NEW buffer, releases input
if (decompressedContent != null) {
  chunkIndexToBuf.put(chunkIndex, filterChunkToRange(decompressedContent));
  // Exception in put() or filterChunkToRange() → decompressedContent leaks!
}
```

**decompressContent() contract:**
```java
// GetBlobOperation.java:917-936
protected ByteBuf decompressContent(ByteBuf sourceBuffer) {
  if (!isChunkCompressed) {
    return sourceBuffer;  // No ownership change
  }
  try {
    return decompressionService.decompress(sourceBuffer.duplicate(), ...);  // Returns NEW buffer
  } finally {
    sourceBuffer.release();  // ALWAYS releases input - takes ownership
  }
}
```

After decompressContent() returns, GetBlobOperation OWNS the decompressed ByteBuf and must release it.

### Test 1: testChunkMapPutExceptionAfterDecompressionLeaksBuffer ⚠️ CRITICAL BUG

**Purpose:** Demonstrate exception during chunkIndexToBuf.put() after decompression leaks buffer

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (decryptedContent, refCnt=0):
  Allocated: heapBuffer(4096)
  Flow:
    → decompressContent(decryptedContent) called
    → Released in finally block (line 935) ✓
  Status: CLEAN

ByteBuf #2 (decompressedContent, refCnt=1):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(4096)
  ├─ First Touch: Test simulating DecompressionService.decompress() return
  ├─ Flow Path:
  │  └─ Simulates GetBlobOperation.decryptionCallback()
  │     └─ Line 882: ByteBuf decompressedContent = decompressContent(...)
  │     └─ OWNERSHIP TRANSFERRED to GetBlobOperation
  │     └─ Line 884: chunkIndexToBuf.put(chunkIndex, ...)
  │        └─ Exception thrown: ConcurrentModificationException
  │        └─ [NO RELEASE IN CATCH BLOCK]
  └─ Status: LEAKED
```

**Confirmation:**
- ✅ Unreleased count = 1 (decompressedContent)
- ✅ decryptedContent properly released by decompressContent()
- ✅ Test manually cleans up: `decompressedContent.release()`
- ✅ Leak helper disabled (`leakHelper.setDisabled(true)`)

**Bug Location:** `ambry-router/src/main/java/com/github/ambry/router/GetBlobOperation.java:882-885`

**Root Cause:** No try-catch around chunkIndexToBuf.put(). Exception after ownership transfer (line 882) leaks decompressed buffer.

**Fix Required:**
```java
ByteBuf decryptedContent = result.getDecryptedBlobContent();
ByteBuf decompressedContent = null;
try {
  decompressedContent = decompressContent(decryptedContent);
  if (decompressedContent != null) {
    chunkIndexToBuf.put(chunkIndex, filterChunkToRange(decompressedContent));
    numChunksRetrieved.incrementAndGet();
  }
} catch (Exception e) {
  if (decompressedContent != null) {
    decompressedContent.release();  // Release decompressed buffer
  }
  throw e;
}
```

---

### Test 2: testFilterChunkToRangeExceptionLeaksDecompressedBuffer ⚠️ CRITICAL BUG

**Purpose:** Demonstrate exception in filterChunkToRange() after decompression leaks buffer

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (decompressedContent, refCnt=1):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(4096)
  ├─ Flow Path:
  │  └─ Line 882: decompressedContent = decompressContent(...)
  │  └─ Line 884: filterChunkToRange(decompressedContent)
  │     └─ Exception thrown: IndexOutOfBoundsException
  │     └─ [NO RELEASE IN CATCH BLOCK]
  └─ Status: LEAKED
```

**Confirmation:**
- ✅ Unreleased count = 1
- ✅ Exception in filterChunkToRange() prevents cleanup
- ✅ Test manually cleans up: `decompressedContent.release()`
- ✅ Leak helper disabled

**Bug Location:** `ambry-router/src/main/java/com/github/ambry/router/GetBlobOperation.java:884`

**Root Cause:** filterChunkToRange() can throw IndexOutOfBoundsException. No try-catch to release decompressed buffer.

**Fix:** Same as Test 1 - wrap entire block in try-catch

---

### Test 3: testResolveRangeExceptionLeaksDecompressedBuffer ⚠️ CRITICAL BUG

**Purpose:** Demonstrate exception in resolveRange() before safeRelease() leaks buffer

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (decompressedContent, refCnt=1):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(4096)
  ├─ Flow Path:
  │  └─ Simulates GetBlobOperation.resolveRangeInternal()
  │     └─ Lines 1588-1597: Process chunks and build result
  │     └─ Line 1589: resolveRange(...) → Exception
  │     └─ [Line 1595 safeRelease() NEVER REACHED]
  └─ Status: LEAKED
```

**Confirmation:**
- ✅ Unreleased count = 1
- ✅ Exception before safeRelease() prevents cleanup
- ✅ Test manually cleans up: `decompressedContent.release()`
- ✅ Leak helper disabled

**Bug Location:** `ambry-router/src/main/java/com/github/ambry/router/GetBlobOperation.java:1588-1597`

**Root Cause:** Exception in resolveRange() prevents execution of line 1595 safeRelease()

**Fix Required:**
```java
for (int i = 0; i < numChunks; i++) {
  ByteBuf buf = chunkIndexToBuf.remove(i);
  try {
    GetOption getOption = getOperationOptions.get(i);
    if (resolveRange(...)) {  // May throw
      // ... use buf ...
    } else {
      ReferenceCountUtil.safeRelease(buf);
    }
  } catch (Exception e) {
    ReferenceCountUtil.safeRelease(buf);  // ADD: Release on exception
    throw e;
  }
}
```

---

### Test 4: testSuccessfulDecompressionNoLeak

**Purpose:** Baseline test - verify normal decompression path doesn't leak

**Expected Behavior:** ✅ NO LEAK - Decompressed buffer properly managed

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- decompressContent() succeeds
- Returns decompressed buffer
- chunkIndexToBuf.put() succeeds
- filterChunkToRange() succeeds
- No exceptions
- All buffers properly released

**Tracker Flow:**
```
ByteBuf #1 (decryptedContent):
  Flow:
    → decompressContent(decryptedContent)
    → Released in finally block (line 935)
  Status: CLEAN

ByteBuf #2 (decompressedContent):
  Flow:
    → Returned from decompressContent()
    → filterChunkToRange() succeeds
    → Stored in chunkIndexToBuf
    → Released by caller
  Status: CLEAN
```

---

### Test 5: testDecompressContentReleasesInputBuffer

**Purpose:** Baseline test - verify decompressContent() contract (always releases input)

**Expected Behavior:** ✅ NO LEAK - Input buffer released even when decompression fails

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Input buffer passed to decompressContent()
- Simulates exception during decompression
- finally block (line 935) releases input buffer
- No leak despite exception

**Tracker Flow:**
```
ByteBuf #1 (sourceBuffer):
  Flow:
    → decompressContent(sourceBuffer)
    → Exception in decompressionService.decompress()
    → finally: sourceBuffer.release() ✓
  Status: CLEAN
```

**Key Contract:** decompressContent() ALWAYS releases the input buffer, even on exception. This is by design - service takes ownership.

---

## PutOperationRetainedDuplicateLeakTest.java (4 tests)

**Purpose:** Tests CALLER-level ownership of retained duplicates passed to EncryptJob and PutRequest.

**CRITICAL OWNERSHIP PATTERN:**
```java
// PutOperation.java:1591 - EncryptJob constructor
new EncryptJob(
    accountId,                          // 1st arg - evaluated
    containerId,                        // 2nd arg - evaluated
    buf.retainedDuplicate(),            // 3rd arg - evaluated → refCnt++
    ByteBuffer.wrap(chunkUserMetadata), // 4th arg - evaluated
    kms.getRandomKey(),                 // 5th arg - evaluated → may throw!
    ...
)
// If 5th arg throws, EncryptJob constructor never called
// But 3rd arg already created retained duplicate → LEAK
```

**Java Argument Evaluation Order:** Arguments evaluated LEFT-TO-RIGHT. If later argument throws, earlier arguments already evaluated/created.

### Test 1: testEncryptJobConstructorExceptionAfterRetainedDuplicateLeaksBuffer ⚠️ CRITICAL BUG

**Purpose:** Demonstrate exception during EncryptJob constructor argument evaluation leaks retained duplicate

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (retainedDuplicate, refCnt=1):
  ├─ Allocated: buf.retainedDuplicate()
  ├─ First Touch: PutOperation.encryptChunk()
  ├─ Flow Path:
  │  └─ PutOperation.encryptChunk() line 1591
  │     └─ new EncryptJob(..., buf.retainedDuplicate(), ..., kms.getRandomKey(), ...)
  │     └─ Argument evaluation order:
  │        └─ 1st-2nd args: evaluated ✓
  │        └─ 3rd arg: buf.retainedDuplicate() → refCnt++ ✓
  │        └─ 4th arg: ByteBuffer.wrap() → evaluated ✓
  │        └─ 5th arg: kms.getRandomKey() → GeneralSecurityException thrown!
  │        └─ EncryptJob constructor NEVER CALLED
  │     └─ catch block: exception handled, BUT retainedDuplicate already created
  │     └─ [NO RELEASE OF RETAINED DUPLICATE]
  └─ Status: LEAKED
```

**Confirmation:**
- ✅ Unreleased count = 1 (confirms bug exists)
- ✅ ByteBuf tracked to retainedDuplicate() call
- ✅ Flow shows argument evaluation order
- ✅ Exception thrown during 5th argument evaluation
- ✅ Test manually cleans up: `retainedCopy.release()`
- ✅ Leak helper disabled for this test (`leakHelper.setDisabled(true)`)

**Bug Location:** `ambry-router/src/main/java/com/github/ambry/router/PutOperation.java:1589-1592`

**Root Cause:** Java evaluates constructor arguments left-to-right. `retainedDuplicate()` (3rd arg) evaluated before `kms.getRandomKey()` (5th arg) throws exception. EncryptJob constructor never completes, but retained duplicate already created.

**Impact:** Every time `kms.getRandomKey()` fails, a retained duplicate leaks

**Fix Required:**
```java
private void encryptChunk() {
  ByteBuf retainedCopy = null;
  try {
    retainedCopy = isMetadataChunk() ? null : buf.retainedDuplicate();
    cryptoJobHandler.submitJob(
        new EncryptJob(...,
            retainedCopy,
            ByteBuffer.wrap(chunkUserMetadata),
            kms.getRandomKey(),
            ...));
    retainedCopy = null;  // Ownership transferred
  } catch (GeneralSecurityException e) {
    if (retainedCopy != null) {
      retainedCopy.release();  // Release if not transferred
    }
    // existing exception handling
  }
}
```

---

### Test 2: testRequestInfoConstructionExceptionAfterPutRequestCreationLeaksBuffer ⚠️ CRITICAL BUG

**Purpose:** Demonstrate exception during RequestInfo construction after PutRequest creation leaks retained duplicate

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (retainedDuplicate in PutRequest, refCnt=1):
  ├─ Allocated: buf.retainedDuplicate() in createPutRequest()
  ├─ First Touch: PutRequest.<init>(materializedBlob)
  ├─ Flow Path:
  │  └─ PutOperation.fetchRequests()
  │     └─ Line 1825: PutRequest putRequest = createPutRequest()
  │        └─ Line 1854: new PutRequest(..., buf.retainedDuplicate(), ...)
  │        └─ retainedDuplicate created, stored in PutRequest.blob ✓
  │     └─ Line 1826: RequestInfo requestInfo = new RequestInfo(...)
  │        └─ Exception thrown during RequestInfo construction!
  │     └─ PutRequest never stored in correlationIdToChunkPutRequestInfo map
  │     └─ [NO CALL TO putRequest.release()]
  └─ Status: LEAKED (inside abandoned PutRequest object)
```

**Confirmation:**
- ✅ Unreleased count = 1
- ✅ Retained duplicate tracked to PutRequest.blob field
- ✅ Flow shows RequestInfo construction exception
- ✅ PutRequest never stored (no reference exists)
- ✅ Test manually cleans up: `putRequest.release()`
- ✅ Leak helper disabled (`leakHelper.setDisabled(true)`)

**Bug Location:** `ambry-router/src/main/java/com/github/ambry/router/PutOperation.java:1825-1830`

**Sequence:**
1. `createPutRequest()` calls `buf.retainedDuplicate()` (refCnt: 1→2)
2. PutRequest constructed with retained duplicate
3. RequestInfo constructor throws (e.g., NPE, IllegalArgumentException)
4. PutRequest not stored in `correlationIdToChunkPutRequestInfo` map
5. No reference to PutRequest exists → `release()` never called
6. **LEAK**: retained duplicate never released

**Impact:** Any exception during RequestInfo construction leaks retained duplicate

**Fix Required:**
```java
private void fetchRequests(RequestRegistrationCallback<PutOperation> requestRegistrationCallback) {
  while (replicaIterator.hasNext()) {
    ReplicaId replicaId = replicaIterator.next();
    PutRequest putRequest = null;
    try {
      putRequest = createPutRequest();
      RequestInfo requestInfo = new RequestInfo(hostname, port, putRequest, ...);
      correlationIdToChunkPutRequestInfo.put(correlationId, requestInfo);
      requestRegistrationCallback.registerRequestToSend(PutOperation.this, requestInfo);
      putRequest = null;  // Transferred to map
    } catch (Exception e) {
      if (putRequest != null) {
        putRequest.release();  // Release if not stored
      }
      throw e;
    }
  }
}
```

---

### Test 3: testSuccessfulEncryptJobConstructionNoLeak

**Purpose:** Baseline test - verify successful EncryptJob construction doesn't leak

**Expected Behavior:** ✅ NO LEAK - EncryptJob receives and releases retained duplicate

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Encryption job created successfully
- All arguments evaluated without exception
- EncryptJob receives retained duplicate
- EncryptJob.run() finally block releases it (line 96)
- No leak

**Tracker Flow:**
```
ByteBuf #1 (original):
  Allocated: heapBuffer(1024)
  Flow:
    → Released by test
  Status: CLEAN

ByteBuf #2 (retainedDuplicate):
  Allocated: buf.retainedDuplicate()
  Flow:
    → Passed to EncryptJob constructor
    → EncryptJob.run() → finally: blobContentToEncrypt.release()
  Status: CLEAN
```

---

### Test 4: testSuccessfulPutRequestCreationAndReleaseNoLeak

**Purpose:** Baseline test - verify successful PutRequest creation and release doesn't leak

**Expected Behavior:** ✅ NO LEAK - PutRequest receives and releases retained duplicate

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- PutRequest created successfully with retained duplicate
- RequestInfo construction succeeds
- Request stored in map
- Later, when request completes: `putRequest.release()` called
- PutRequest.release() releases blob ByteBuf (line 323)
- No leak

**Tracker Flow:**
```
ByteBuf #1 (original):
  Allocated: heapBuffer(1024)
  Flow:
    → Released by test
  Status: CLEAN

ByteBuf #2 (retainedDuplicate):
  Allocated: buf.retainedDuplicate()
  Flow:
    → Passed to PutRequest constructor
    → Stored in PutRequest.blob field
    → PutRequest stored in map
    → putRequest.release() → blob.release()
  Status: CLEAN
```

---

# MEDIUM-Risk Tests (29 tests)

## CompressionServiceLeakTest.java - REMOVED ❌

**Status:** All 7 tests removed (2025-11-09)

**Reason:** Tests were at wrong abstraction level - testing service internals instead of caller (PutOperation/GetBlobOperation) ownership.

**What was removed:**
- testCompressChunkSuccessNoLeak
- testCompressChunkFailureReleasesBuffer
- testCompressChunkNullInputSafe
- testCompressChunkEmptyInput
- testCompressChunkLargeBuffer
- testCompressChunkCompressionRatioCheckFails
- testCompressChunkAllocatorFailure

**What's needed instead:**
Tests at PutOperation/GetBlobOperation level where ByteBuf ownership is transferred after compressChunk()/decompressChunk() return.

---

## GCMCryptoServiceLeakTest.java (8 tests)

### Test 1: testEncryptSuccessNoLeak

**Purpose:** Verify successful encryption doesn't leak

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 2: testEncryptFailureReleasesBuffer

**Purpose:** Verify encryption failure releases allocated buffer

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Encryption throws exception
- Allocated encryptedContent released in catch
- No leak

---

### Test 3: testDecryptSuccessNoLeak

**Purpose:** Verify successful decryption doesn't leak

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 4: testDecryptExceptionLeaksDecryptedContent ⚠️ CRITICAL BUG

**Purpose:** Demonstrate decrypt() catch block releases wrong buffer

**Expected Behavior:** ❌ LEAK EXPECTED - This test demonstrates a CRITICAL bug

**Tracker Output to Look For:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1-2

ByteBuf #1 (decryptedContent, refCnt=1):
  ├─ Allocated: ByteBufAllocator.heapBuffer(ciphertext.length)
  ├─ Flow Path:
  │  └─ GCMCryptoService.decrypt()
  │     └─ decryptedContent = allocator.heapBuffer()
  │     └─ Cipher.doFinal() → Exception
  │     └─ catch: toDecrypt.release()  [WRONG BUFFER!]
  │     └─ [decryptedContent NEVER RELEASED]
  └─ Status: LEAKED

ByteBuf #2 (toDecrypt, refCnt=0 or negative):
  Flow:
    → GCMCryptoService.decrypt(toDecrypt)
    → catch: toDecrypt.release()  [CALLER'S BUFFER - SHOULD NOT RELEASE]
  Status: OVER-RELEASED (IllegalReferenceCountException may occur)
```

**Confirmation:**
- ✅ Unreleased count >= 1 (decryptedContent leaked)
- ✅ May see IllegalReferenceCountException if toDecrypt over-released
- ✅ Test assertion passes confirming leak

**Bug Location:** `ambry-router/src/main/java/com/github/ambry/router/GCMCryptoService.java:196-200`

**Fix Required:**
```java
public ByteBuf decrypt(ByteBuf toDecrypt, SecretKeySpec key) throws GeneralSecurityException {
  ByteBuf decryptedContent = null;
  try {
    // ... decryption code ...
    decryptedContent = allocator.heapBuffer(ciphertext.length);
    // ... doFinal ...
    return decryptedContent;
  } catch (Exception e) {
    if (decryptedContent != null) {
      decryptedContent.release();  // FIX: Release the right buffer!
    }
    // DO NOT release toDecrypt - caller owns it!
    throw new GeneralSecurityException(...);
  }
}
```

---

### Test 5: testEncryptNullInput

**Purpose:** Verify null input handled

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 6: testDecryptNullInput

**Purpose:** Verify null input handled

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 7: testEncryptEmptyBuffer

**Purpose:** Verify empty buffer encryption

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 8: testDecryptInvalidCiphertext

**Purpose:** Verify invalid ciphertext decryption fails cleanly

**Expected Behavior:** ✅ NO LEAK or ❌ LEAK (if bug #4 present)

**Tracker Output to Look For (after fix):**
```
Unreleased ByteBufs: 0
```

**Tracker Output (bug present):**
```
Unreleased ByteBufs: 1
(Same as testDecryptExceptionLeaksDecryptedContent)
```

---

## BoundedNettyByteBufReceiveLeakTest.java (7 tests)

### Test 1: testReadFromIOExceptionAfterSizeBufferAllocation

**Purpose:** Verify IOException after sizeBuffer allocation releases it

**Expected Behavior:** ✅ NO LEAK - Catch block releases sizeBuffer

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- First readFrom() reads 4 bytes (partial size header)
- Second readFrom() throws IOException
- Catch block (line 81): `sizeBuffer.release()`
- No leak

**What Would Indicate Bug:**
```
Unreleased ByteBufs: 1

ByteBuf #1 (sizeBuffer):
  Allocated: ByteBufAllocator.DEFAULT.heapBuffer(8)
  Flow: readFrom() → IOException → [NO RELEASE]
  Status: LEAKED
```

---

### Test 2: testReadFromIOExceptionAfterBufferAllocation

**Purpose:** Verify IOException after buffer allocation releases it

**Expected Behavior:** ✅ NO LEAK - Catch block releases buffer

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Size header read (8 bytes)
- Buffer allocated (100 - 8 = 92 bytes)
- IOException on data read
- Catch block (line 103): `buffer.release()`
- No leak

---

### Test 3: testEOFExceptionDuringSizeHeaderRead

**Purpose:** Verify EOFException during size read doesn't leak

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Channel returns -1 (EOF)
- EOFException thrown before full sizeBuffer read
- sizeBuffer released in catch block
- No leak

---

### Test 4: testEOFExceptionDuringContentRead

**Purpose:** Verify EOFException during content read doesn't leak

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Size header read completely
- Buffer allocated
- EOF during content read
- Buffer released in catch block

---

### Test 5: testSuccessfulReadComplete

**Purpose:** Verify complete successful read doesn't leak

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Size header read
- Content read completely
- isReadComplete() returns true
- Buffer owned by BoundedNettyByteBufReceive, released by caller

---

### Test 6: testMultipleReadFromCalls

**Purpose:** Verify multiple readFrom() calls accumulate without leak

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Multiple partial reads
- All data accumulated
- Final buffer contains all data
- Released by caller

---

### Test 7: testOversizedRequestRejected

**Purpose:** Verify oversized request throws exception and releases buffer

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Size header indicates size > maxRequestSize
- IOException thrown
- sizeBuffer released before exception
- No leak

---

## ByteBufferAsyncWritableChannelLeakTest.java (6 tests)

### Test 1: testWriteSuccess

**Purpose:** Verify successful write doesn't leak

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 2: testWriteAfterClose

**Purpose:** Verify write after close throws exception without leak

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Channel closed
- Write throws ClosedChannelException
- Buffer not retained by channel
- Caller still owns buffer

---

### Test 3: testMultipleWrites

**Purpose:** Verify multiple sequential writes don't leak

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 4: testWriteEmptyBuffer

**Purpose:** Verify empty buffer write

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 5: testCloseWithPendingWrites

**Purpose:** Verify close() with pending writes releases them

**Expected Behavior:** ✅ NO LEAK or potential design decision

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Note:** If buffers remain, verify this is by design (caller responsible for cleanup)

---

### Test 6: testCallbackExceptionHandled

**Purpose:** Verify callback exception doesn't leak buffers

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

## RetainingAsyncWritableChannelLeakTest.java (8 tests)

### Test 1: testWriteAndRetainSuccess

**Purpose:** Verify write retains buffer correctly

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Buffer written and retained
- refCnt incremented
- Channel releases when closed
- No leak

---

### Test 2: testMultipleWritesAccumulate

**Purpose:** Verify multiple writes accumulate into composite buffer

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Multiple buffers written
- Each retained and added to composite
- All released when channel closed

---

### Test 3: testCloseReleasesAllRetainedBuffers

**Purpose:** Verify close() releases all accumulated buffers

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Buffers accumulated
- close() called
- All buffers released
- Composite buffer released

---

### Test 4: testSizeLimitExceededReleasesBuffers

**Purpose:** Verify size limit exception releases accumulated buffers

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Buffers accumulated up to limit
- Next write exceeds limit
- Exception thrown
- All previously accumulated buffers released

---

### Test 5: testWriteAfterCloseThrows

**Purpose:** Verify write after close throws without leak

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 6: testEmptyBufferWrite

**Purpose:** Verify empty buffer handled

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

---

### Test 7: testConcurrentWritesHandled

**Purpose:** Verify concurrent writes don't cause leaks

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Multiple threads writing concurrently
- All buffers properly retained and tracked
- All released on close

---

### Test 8: testGetConsumedBufferReturnsComposite

**Purpose:** Verify getting consumed buffer transfers ownership

**Expected Behavior:** ✅ NO LEAK

**Tracker Output to Look For:**
```
Unreleased ByteBufs: 0
```

**Confirmation:**
- Buffers accumulated
- getConsumedBuffer() returns composite
- Ownership transferred to caller
- Caller releases, no leak

---

# Summary Statistics

## Updated Counts (2025-11-09)

**Total Tests:** 67 (55 from initial creation + 12 new caller-level tests)
- **Bug-Exposing Tests:** 17 (7 new + 10 original)
  - 7 new bugs in PutOperation/GetBlobOperation ownership transfer (2025-11-09)
    - 5 bugs: compression/decompression ownership
    - 2 bugs: retainedDuplicate() ownership
  - 10 original bugs (DecryptJob, AmbrySendToHttp2Adaptor, NettyRequest, GCMCryptoService)
- **Baseline Tests:** 50 (5 new + 45 original)
- **Removed Tests:** 7 (CompressionServiceLeakTest.java - wrong abstraction level)

**Net Change:** 62 original - 7 removed + 12 added = 67 total

## Tests Expecting Leaks (17 bug-exposing tests)

### Confirmed Production Bugs (13):

| Test | Expected Leaks | Bug Fixed? | Added |
|------|----------------|------------|-------|
| DecryptJobLeakTest.testCloseJobBeforeRunLeaksBuffer | 1 | ❌ | Original |
| DecryptJobLeakTest.testCloseJobLeakIsRealWithoutFix | 1 | ❌ | Original |
| AmbrySendToHttp2AdaptorLeakTest.testExceptionDuringFrameWritingLeaksRetainedSlices | 1 (refCnt>0) | ❌ | Original |
| AmbrySendToHttp2AdaptorLeakTest.testFinallyReleasesOriginalButNotRetainedSlices | 1 (refCnt=1) | ❌ | Original |
| NettyRequestLeakTest.testWriteContentExceptionAfterRetainLeaksBuffer | 1 (refCnt=2) | ❌ | Original |
| GCMCryptoServiceLeakTest.testDecryptExceptionLeaksDecryptedContent | 1 | ❌ | Original |
| **PutOperationCompressionLeakTest.testCrcCalculationExceptionAfterCompressionLeaksCompressedBuffer** | 1 | ❌ | **2025-11-09** |
| **PutOperationCompressionLeakTest.testEncryptionCallbackCrcExceptionLeaksEncryptedBuffer** | 1 | ❌ | **2025-11-09** |
| **GetBlobOperationDecompressionLeakTest.testChunkMapPutExceptionAfterDecompressionLeaksBuffer** | 1 | ❌ | **2025-11-09** |
| **GetBlobOperationDecompressionLeakTest.testFilterChunkToRangeExceptionLeaksDecompressedBuffer** | 1 | ❌ | **2025-11-09** |
| **GetBlobOperationDecompressionLeakTest.testResolveRangeExceptionLeaksDecompressedBuffer** | 1 | ❌ | **2025-11-09** |
| **PutOperationRetainedDuplicateLeakTest.testEncryptJobConstructorExceptionAfterRetainedDuplicateLeaksBuffer** | 1 | ❌ | **2025-11-09** |
| **PutOperationRetainedDuplicateLeakTest.testRequestInfoConstructionExceptionAfterPutRequestCreationLeaksBuffer** | 1 | ❌ | **2025-11-09** |

**Key Insight for AmbrySendToHttp2Adaptor bugs:** Both tests expose the same root cause - `readSlice()` creates slices with SHARED refCnt. Multiple `retain()` calls increment the shared counter, but finally block only releases once, leaving refCnt > 0.

**Confirmation:** If tracker shows "Unreleased ByteBufs: 0" for any of these, the bug has been fixed in production code.

### Tests Showing Paths Are Safe (4):

| Test | Expected | Actual | Notes |
|------|----------|--------|-------|
| NettyRequestLeakTest.testAddContentExceptionAfterRetainLeaksBuffer | Leak | NO LEAK | Exception before retain |
| NettyMultipartRequestLeakTest.testAddContentRetainThenException | Leak | NO LEAK | Exception before retain |
| NettyMultipartRequestLeakTest.testProcessMultipartContentRetainThenException | Leak | TBD | Complex nested retain |
| AmbrySendToHttp2AdaptorLeakTest.testProperFixWouldReleaseAllRetainedSlices | NO LEAK | NO LEAK | Demonstrates fix pattern |

## Tests Expecting No Leaks (50 baseline tests)

**New Baseline Tests Added (2025-11-09):**
- PutOperationCompressionLeakTest.testSuccessfulCompressionNoLeak
- GetBlobOperationDecompressionLeakTest.testSuccessfulDecompressionNoLeak
- GetBlobOperationDecompressionLeakTest.testDecompressContentReleasesInputBuffer
- PutOperationRetainedDuplicateLeakTest.testSuccessfulEncryptJobConstructionNoLeak
- PutOperationRetainedDuplicateLeakTest.testSuccessfulPutRequestCreationAndReleaseNoLeak

All 50 baseline tests should show:
```
Unreleased ByteBufs: 0
```

**Alert:** If any of these show leaks, it indicates:
- Previously unknown production bug (investigate!)
- Test implementation error (fix test)
- False positive (verify NettyByteBufLeakHelper)

---

# Quick Verification Checklist

After running tests with `-PwithByteBufTracking`:

## Bug-Exposing Tests (17 tests)

### Must Show Leaks (13 confirmed bugs):
- [ ] DecryptJobLeakTest.testCloseJobBeforeRunLeaksBuffer - 1 leak
- [ ] DecryptJobLeakTest.testCloseJobLeakIsRealWithoutFix - 1 leak
- [ ] AmbrySendToHttp2AdaptorLeakTest.testExceptionDuringFrameWritingLeaksRetainedSlices - 1 leak (refCnt>0)
- [ ] AmbrySendToHttp2AdaptorLeakTest.testFinallyReleasesOriginalButNotRetainedSlices - 1 leak (refCnt=1)
- [ ] NettyRequestLeakTest.testWriteContentExceptionAfterRetainLeaksBuffer - 1 leak (refCnt=2)
- [ ] GCMCryptoServiceLeakTest.testDecryptExceptionLeaksDecryptedContent - 1 leak
- [ ] **PutOperationCompressionLeakTest.testCrcCalculationExceptionAfterCompressionLeaksCompressedBuffer - 1 leak (NEW 2025-11-09)**
- [ ] **PutOperationCompressionLeakTest.testEncryptionCallbackCrcExceptionLeaksEncryptedBuffer - 1 leak (NEW 2025-11-09)**
- [ ] **GetBlobOperationDecompressionLeakTest.testChunkMapPutExceptionAfterDecompressionLeaksBuffer - 1 leak (NEW 2025-11-09)**
- [ ] **GetBlobOperationDecompressionLeakTest.testFilterChunkToRangeExceptionLeaksDecompressedBuffer - 1 leak (NEW 2025-11-09)**
- [ ] **GetBlobOperationDecompressionLeakTest.testResolveRangeExceptionLeaksDecompressedBuffer - 1 leak (NEW 2025-11-09)**
- [ ] **PutOperationRetainedDuplicateLeakTest.testEncryptJobConstructorExceptionAfterRetainedDuplicateLeaksBuffer - 1 leak (NEW 2025-11-09)**
- [ ] **PutOperationRetainedDuplicateLeakTest.testRequestInfoConstructionExceptionAfterPutRequestCreationLeaksBuffer - 1 leak (NEW 2025-11-09)**

### Should Show No Leaks (4 tests that turned out safe):
- [ ] NettyRequestLeakTest.testAddContentExceptionAfterRetainLeaksBuffer - 0 leaks (exception before retain)
- [ ] NettyMultipartRequestLeakTest.testAddContentRetainThenException - 0 leaks (exception before retain)
- [ ] NettyMultipartRequestLeakTest.testProcessMultipartContentRetainThenException - TBD
- [ ] AmbrySendToHttp2AdaptorLeakTest.testProperFixWouldReleaseAllRetainedSlices - 0 leaks (demonstrates fix)

## Baseline Tests (50 tests)
- [ ] All 50 baseline tests show 0 leaks (proper cleanup verification)
- [ ] Includes 5 new baseline tests for PutOperation/GetBlobOperation (2025-11-09)
- [ ] No unexpected leaks in any baseline tests
- [ ] No IllegalReferenceCountException (except where documented)

## General Validation
- [ ] Tracker flow paths match expected patterns
- [ ] All constructor-wrapped ByteBufs tracked correctly
- [ ] All retained slices accounted for (check shared refCnt behavior!)
- [ ] All async callbacks completed before test end

**Special Note:** For AmbrySendToHttp2AdaptorLeakTest, verify tracker shows:
- readSlice() + retain() creates SHARED refCnt (not independent)
- Multiple retain() calls accumulate on the shared counter
- Single release() in finally leaves refCnt > 0 (THE BUG!)

If all checkboxes pass: ✅ Test suite validates ByteBuf memory management correctly!

---

## Changes Made (2025-11-09)

**Tests Removed:**
- CompressionServiceLeakTest.java (7 tests) - Testing at wrong abstraction level

**Tests Added:**
- PutOperationCompressionLeakTest.java (3 tests: 2 bug-exposing + 1 baseline)
  - testCrcCalculationExceptionAfterCompressionLeaksCompressedBuffer (BUG)
  - testEncryptionCallbackCrcExceptionLeaksEncryptedBuffer (BUG)
  - testSuccessfulCompressionNoLeak (baseline)
- GetBlobOperationDecompressionLeakTest.java (5 tests: 3 bug-exposing + 2 baseline)
  - testChunkMapPutExceptionAfterDecompressionLeaksBuffer (BUG)
  - testFilterChunkToRangeExceptionLeaksDecompressedBuffer (BUG)
  - testResolveRangeExceptionLeaksDecompressedBuffer (BUG)
  - testSuccessfulDecompressionNoLeak (baseline)
  - testDecompressContentReleasesInputBuffer (baseline)
- PutOperationRetainedDuplicateLeakTest.java (4 tests: 2 bug-exposing + 2 baseline)
  - testEncryptJobConstructorExceptionAfterRetainedDuplicateLeaksBuffer (BUG)
  - testRequestInfoConstructionExceptionAfterPutRequestCreationLeaksBuffer (BUG)
  - testSuccessfulEncryptJobConstructionNoLeak (baseline)
  - testSuccessfulPutRequestCreationAndReleaseNoLeak (baseline)

**New Bugs Discovered:**
- 7 CRITICAL bugs in PutOperation/GetBlobOperation ownership transfer (2025-11-09)
  - 5 bugs: compression/decompression ownership - exceptions AFTER service returns ByteBuf
  - 2 bugs: retainedDuplicate() ownership - exceptions during constructor argument evaluation
- All occur when exceptions happen AFTER ownership transfer to caller
- No try-catch blocks to release caller-owned buffers on exception

**Documentation Updates:**
- Updated all counts (67 total tests: 17 bug-exposing + 50 baseline)
- Added detailed tracker output expectations for all 12 new tests
- Added ownership transfer pattern documentation
- Updated summary statistics and quick verification checklist
- Clarified test categorization throughout
- Added notes about which bugs are confirmed vs safe

**Key Insights:**
1. Tests must be at the **caller level** where ByteBuf ownership is transferred, not inside service internals
2. Ownership transfer timing is critical - exceptions AFTER return = caller's responsibility to release
3. CompressionService/DecompressionService tests removed - not testing actual bug locations
4. PutOperation/GetBlobOperation tests added - testing actual bug locations (callers)
5. **Java argument evaluation order matters:** When calling constructors with inline operations (e.g., `new Foo(bar.retainedDuplicate())`), earlier arguments evaluated before later arguments can throw exceptions
6. **retainedDuplicate() ownership:** Caller must handle exceptions that occur after `retainedDuplicate()` is evaluated but before constructor completes
