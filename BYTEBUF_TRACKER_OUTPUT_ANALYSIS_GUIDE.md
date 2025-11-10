# ByteBuf Tracker Output Analysis Guide

This guide explains how to interpret ByteBuf Flow Tracker output to confirm or deny memory leaks from the leak detection tests created in this branch.

**Note:** For detailed test-specific expectations, see **BYTEBUF_LEAK_TEST_EXPECTATIONS.md** which documents expected tracker output patterns for all 67 leak tests.

## Overview

The ByteBuf Flow Tracker instruments Netty ByteBuf operations and tracks the flow of buffers through the application using a Trie data structure. It reports buffers that were allocated but never released, indicating potential memory leaks.

## Output Format

The tracker outputs two formats when a test completes:

### 1. Human-Readable Tree Format

```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (refCnt=1, capacity=1024):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(1024)
  ├─ First Touch: DecryptJob.<init>()
  │  └─ Constructor parameter wrapping detected
  ├─ Flow Path:
  │  └─ DecryptJob.run()
  │     └─ GCMCryptoService.decrypt()
  │        └─ ByteBuf.retain()
  └─ Problem: Never released!
```

### 2. LLM-Optimized Structured Format

```
BYTEBUF_LEAK_DETECTED
id: bytebuf-00001
refCount: 1
capacity: 1024
allocatedBy: PooledByteBufAllocator.heapBuffer
firstTouch: com.github.ambry.router.DecryptJob.<init>
flowPath:
  - com.github.ambry.router.DecryptJob.run()
  - com.github.ambry.router.GCMCryptoService.decrypt()
  - io.netty.buffer.ByteBuf.retain()
retainCount: 1
releaseCount: 0
status: LEAKED
```

## What Indicates a Leak

### Clear Leak Indicators

1. **Non-Zero Unreleased Count**
   ```
   Unreleased ByteBufs: 1
   ```
   Any count > 0 indicates a leak

2. **Reference Count > 0**
   ```
   refCnt=1
   ```
   or
   ```
   refCount: 1
   ```
   Buffer still has active references

3. **Imbalanced Retain/Release**
   ```
   retainCount: 2
   releaseCount: 1
   ```
   More retains than releases = leak

4. **Flow Path Ending Without Release**
   ```
   Flow Path:
     └─ DecryptJob.closeJob()
        └─ [END - NO RELEASE]
   ```
   Path doesn't show a `.release()` call

### Example: CRITICAL Bug #1 - DecryptJob.closeJob()

**Expected Tracker Output When Bug Exists:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 1

ByteBuf #1 (refCnt=1, capacity=512):
  ├─ Allocated: PooledByteBufAllocator.DEFAULT.heapBuffer(512)
  ├─ First Touch: DecryptJob.<init>(encryptedContent)
  │  └─ Constructor parameter stored as: encryptedBlobContent
  ├─ Flow Path:
  │  └─ DecryptJob.closeJob()
  │     └─ [CLOSED WITHOUT RELEASE]
  └─ Problem: closeJob() called but encryptedBlobContent never released!

Test: DecryptJobLeakTest.testCloseJobBeforeRunLeaksBuffer
Status: LEAK CONFIRMED ✓
```

**Expected Tracker Output After Bug Fixed:**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 0

Test: DecryptJobLeakTest.testCloseJobBeforeRunLeaksBuffer
Status: CLEAN (no leaks detected)
```

## What Indicates Proper Cleanup (No Leak)

### Clean Test Indicators

1. **Zero Unreleased Count**
   ```
   Unreleased ByteBufs: 0
   ```

2. **Balanced Retain/Release**
   ```
   retainCount: 2
   releaseCount: 2
   ```

3. **Flow Path Ending with Release**
   ```
   Flow Path:
     └─ EncryptJob.run()
        └─ GCMCryptoService.encrypt()
           └─ ByteBuf.release()  ← GOOD!
   ```

4. **Empty Report or "No leaks detected"**
   ```
   === ByteBuf Flow Tracker Report ===
   No unreleased ByteBufs detected.
   ```

## Analyzing Output for Each Test Category

### HIGH-Risk Tests (Should Show Leaks When Bugs Exist)

#### Test: DecryptJobLeakTest.testCloseJobBeforeRunLeaksBuffer

**What to Look For:**
- ByteBuf allocated in DecryptJob constructor
- First touch: `DecryptJob.<init>(encryptedContent)`
- Flow path shows: `DecryptJob.closeJob()`
- **LEAK EXPECTED**: No `.release()` in flow path
- Bug location: `DecryptJob.java:112-114`

**Tracker Output Pattern (Bug Present):**
```
ByteBuf #1:
  First Touch: DecryptJob.<init>
  Stored as: encryptedBlobContent
  Flow: closeJob() → [NO RELEASE]
  Status: LEAKED
```

**How to Confirm:**
- Unreleased count = 1
- Flow path ends at `closeJob()` without release
- Constructor tracking shows buffer stored as `encryptedBlobContent`

---

#### Test: AmbrySendToHttp2AdaptorLeakTest.testExceptionDuringFrameWritingLeaksRetainedSlices

**What to Look For:**
- Multiple ByteBuf slices created via `content.retainedSlice()`
- Each slice has `refCnt=1` initially
- Exception thrown during write (after some slices created)
- **LEAK EXPECTED**: Retained slices not tracked for cleanup
- Bug location: `AmbrySendToHttp2Adaptor.java:89-104`

**Tracker Output Pattern (Bug Present):**
```
ByteBuf #1 (slice):
  First Touch: ByteBuf.retainedSlice()
  Flow: AmbrySendToHttp2Adaptor.write() → ctx.write(slice) → Exception
  Status: LEAKED

ByteBuf #2 (slice):
  First Touch: ByteBuf.retainedSlice()
  Flow: AmbrySendToHttp2Adaptor.write() → ctx.write(slice) → Exception
  Status: LEAKED
```

**How to Confirm:**
- Multiple unreleased ByteBufs (1-2 slices)
- All are slices (created via `retainedSlice()`)
- Flow paths end with exception, no release
- Test assertion `assertTrue("Should have leaked slices", leakedSlices > 0)` passes

---

#### Test: NettyRequestLeakTest.testWriteContentExceptionAfterRetainLeaksBuffer

**What to Look For:**
- ByteBuf retained in `NettyRequest.writeContent()`
- Exception thrown by channel.write()
- **LEAK EXPECTED**: No release in exception path
- Bug location: `NettyRequest.java:208-215`

**Tracker Output Pattern (Bug Present):**
```
ByteBuf #1:
  First Touch: NettyRequest.addContent()
  Flow:
    └─ NettyRequest.writeContent()
       └─ ByteBuf.retain()  [refCnt: 1→2]
       └─ AsyncWritableChannel.write() → Exception
       └─ [NO RELEASE IN CATCH]
  refCount: 2
  Status: LEAKED
```

**How to Confirm:**
- Unreleased count = 1
- `refCnt=2` (original + retain)
- Flow shows `retain()` but no matching `release()`
- Test assertion `assertEquals("Content should be retained and LEAKED", 2, content.refCnt())` passes

---

#### Test: GCMCryptoServiceLeakTest.testDecryptExceptionLeaksDecryptedContent

**What to Look For:**
- ByteBuf allocated for decrypted content
- Exception during decryption
- **LEAK EXPECTED**: Catch block releases wrong buffer (caller's instead of decrypted)
- Bug location: `GCMCryptoService.java:196-200`

**Tracker Output Pattern (Bug Present):**
```
ByteBuf #1 (decryptedContent):
  Allocated: ByteBufAllocator.heapBuffer(ciphertext.length)
  Flow:
    └─ GCMCryptoService.decrypt()
       └─ Cipher.doFinal() → Exception
       └─ catch: toDecrypt.release()  [WRONG BUFFER!]
  refCount: 1
  Status: LEAKED

ByteBuf #2 (toDecrypt - caller's buffer):
  Flow:
    └─ GCMCryptoService.decrypt()
       └─ catch: toDecrypt.release()  [DOUBLE RELEASE!]
  refCount: 0
  Status: OVER-RELEASED (potential crash)
```

**How to Confirm:**
- Two issues:
  1. `decryptedContent` leaked (refCnt=1)
  2. `toDecrypt` over-released (refCnt might be negative or cause assertion)
- Flow shows wrong buffer released in catch block

---

### MEDIUM-Risk Tests (Should NOT Show Leaks)

These tests verify that error paths properly clean up. If tracker shows leaks, there's a bug.

#### Test: BoundedNettyByteBufReceiveLeakTest.testReadFromIOExceptionAfterSizeBufferAllocation

**What to Look For:**
- sizeBuffer allocated
- IOException thrown during read
- **NO LEAK EXPECTED**: Catch block releases sizeBuffer (line 81)

**Tracker Output Pattern (Correct Behavior):**
```
=== ByteBuf Flow Tracker Report ===
Unreleased ByteBufs: 0
```

**How to Confirm:**
- Zero leaks detected
- If sizeBuffer appears in any flow, it should end with `.release()`

---

## Special Cases to Watch For

### 1. Constructor Tracking

Tests involving `EncryptJob`, `DecryptJob`, `NettyRequest` wrap ByteBufs in constructors:

```
ByteBuf #1:
  First Touch: DecryptJob.<init>(encryptedContent)
  Stored as: encryptedBlobContent
  ↑ Tracker detected constructor wrapping
```

**What This Means:**
- Buffer ownership transferred to the object
- Object is responsible for releasing when closed
- Look for release in `closeJob()` or destructor

### 2. Retained Slices

Tests like `AmbrySendToHttp2AdaptorLeakTest` create retained slices:

```
ByteBuf #1 (original):
  refCount: 1

ByteBuf #2 (slice):
  First Touch: ByteBuf.retainedSlice(0, 8192)
  Parent: ByteBuf #1
  refCount: 1  ← Independent reference count
```

**What This Means:**
- Slices have their own refCnt
- Parent buffer AND all slices must be released independently
- Tracker will show each slice as a separate leak if not released

### 3. Async Operations

Tests with async callbacks may show buffers in flight:

```
ByteBuf #1:
  Flow:
    └─ AsyncWritableChannel.write() → [PENDING CALLBACK]
```

**What This Means:**
- If buffer is in an async callback, it may not be leaked
- Check if callback is invoked and releases buffer
- Test should use `CountDownLatch.await()` to ensure callback completes

### 4. Double-Release Detection

If a buffer's refCnt goes negative, Netty throws `IllegalReferenceCountException`:

```
java.lang.IllegalReferenceCountException: refCnt: 0, decrement: 1
```

**What This Means:**
- Buffer was released more times than retained
- Often paired with another buffer being leaked
- See GCMCryptoService bug: releases wrong buffer

## Decision Tree for Analyzing Tracker Output

```
Is "Unreleased ByteBufs" > 0?
│
├─ YES → LEAK DETECTED
│  │
│  ├─ Check Test Name
│  │  │
│  │  ├─ Test expects leak (e.g., *testCloseJobBeforeRunLeaksBuffer)?
│  │  │  └─ ✓ CORRECT: Bug confirmed, test SHOULD fail
│  │  │
│  │  └─ Test expects no leak (most MEDIUM-risk tests)?
│  │     └─ ✗ PROBLEM: Unexpected leak, production bug found!
│  │
│  ├─ Examine Flow Path
│  │  └─ Where does it end?
│  │     ├─ Exception thrown → Check catch block has release
│  │     ├─ closeJob() called → Check buffer released in close
│  │     └─ Normal return → Check release before return
│  │
│  └─ Check Reference Count
│     ├─ refCnt > 1 → Extra retain() without matching release()
│     └─ refCnt = 1 → Never released at all
│
└─ NO → NO LEAK
   │
   └─ Test expects leak (e.g., demonstrates CRITICAL bug)?
      ├─ ✗ PROBLEM: Expected leak not detected!
      │  └─ Check if:
      │     • Bug already fixed in production
      │     • Test doesn't trigger the bug condition
      │     • Tracker not instrumenting the right code
      │
      └─ ✓ CORRECT: Clean test, no bugs
```

## Command to Generate Tracker Output

Run tests with ByteBuf tracking enabled:

```bash
# Run all leak tests
./gradlew test -PwithByteBufTracking --tests '*LeakTest'

# Run specific test
./gradlew test -PwithByteBufTracking --tests 'DecryptJobLeakTest.testCloseJobBeforeRunLeaksBuffer'

# Run specific module's leak tests
./gradlew :ambry-router:test -PwithByteBufTracking --tests '*LeakTest'
```

## Expected Results Summary

### Tests That SHOULD Show Leaks (Demonstrating Bugs)

| Test | Expected Leaks | Bug Location |
|------|---------------|--------------|
| DecryptJobLeakTest.testCloseJobBeforeRunLeaksBuffer | 1 buffer | DecryptJob.java:112-114 |
| AmbrySendToHttp2AdaptorLeakTest.testExceptionDuringFrameWritingLeaksRetainedSlices | 1-2 slices | AmbrySendToHttp2Adaptor.java:89-104 |
| NettyRequestLeakTest.testWriteContentExceptionAfterRetainLeaksBuffer | 1 buffer (refCnt=2) | NettyRequest.java:208-215 |
| GCMCryptoServiceLeakTest.testDecryptExceptionLeaksDecryptedContent | 1 buffer | GCMCryptoService.java:196-200 |

### Tests That Should Show NO Leaks (Verifying Correct Cleanup)

All other 59 tests should report:
```
Unreleased ByteBufs: 0
```

If any of these show leaks, it indicates:
1. A previously unknown bug in production code, OR
2. An error in the test implementation

## Troubleshooting

### Tracker Shows No Output

**Possible Causes:**
- `-PwithByteBufTracking` flag not provided
- Tracker agent JAR not built (run `./gradlew buildByteBufAgent`)
- Submodule not initialized (run `git submodule update --init`)

**Solution:**
```bash
git submodule update --init modules/bytebuddy-bytebuf-tracer
./gradlew buildByteBufAgent
./gradlew test -PwithByteBufTracking --tests '*LeakTest'
```

### Tracker Shows Unexpected Leaks

**Possible Causes:**
1. Production bug discovered (GOOD!)
2. Test not cleaning up properly (FIX TEST)
3. Async callback not awaited (FIX TEST)

**Diagnosis:**
- Check flow path: where does buffer end up?
- Check test code: is there a missing `buffer.release()`?
- Check for `CountDownLatch.await()` in async tests

### Test Fails But Tracker Shows No Leak

**Possible Causes:**
- Test assertion wrong
- NettyByteBufLeakHelper has false positive
- Test created buffer outside PooledByteBufAllocator

**Diagnosis:**
- Check test uses `PooledByteBufAllocator.DEFAULT`
- Verify `leakHelper.beforeTest()` called before buffer allocation
- Review test logic for correctness

## Conclusion

The ByteBuf Flow Tracker provides definitive evidence of memory leaks by tracking actual buffer flows through the application. When analyzing output:

1. **Check unreleased count** - Any > 0 indicates a leak
2. **Examine flow paths** - Identify where release should have occurred
3. **Match with test expectations** - Tests demonstrating bugs SHOULD show leaks
4. **Investigate surprises** - Unexpected leaks may reveal unknown bugs

This comprehensive test suite with tracker analysis provides:
- **Proof of CRITICAL bugs** (4 tests intentionally demonstrate leaks)
- **Verification of fixes** (tracker will show 0 leaks after bugs fixed)
- **Regression prevention** (tests will catch if bugs reintroduced)
- **Coverage of edge cases** (59 tests verify proper cleanup in error paths)
