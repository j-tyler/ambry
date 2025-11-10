# Fix ByteBuf Memory Leaks in DecryptJob and GCMCryptoService

## Bugs Fixed

### 1. DecryptJob.closeJob() Memory Leak

**Location:** `DecryptJob.java:112-114`

**Bug:** `closeJob()` did not release `encryptedBlobContent` when called.

**How it occurs:**
- DecryptJob constructor takes ownership of `encryptedBlobContent` ByteBuf
- Normal execution: `run()` releases the buffer in its finally block ✓
- Abort scenario: `closeJob()` is called before `run()` executes
- **Problem:** `closeJob()` only invoked callback, never released the owned buffer
- **Result:** Every aborted decryption job leaked a ByteBuf

**Ownership responsibility:**
- DecryptJob **owns** `encryptedBlobContent` after construction
- DecryptJob **must** release it in all exit paths (`run()` OR `closeJob()`)

### 2. GCMCryptoService.decrypt() Wrong Buffer Released

**Location:** `GCMCryptoService.java:196-200`

**Bug:** Exception handler released caller's buffer instead of service's buffer.

**How it occurs:**
- Service allocates **new** `decryptedContent` buffer (line 180)
- Decryption fails during `doFinal()` (line 192)
- Exception handler releases `toDecrypt` (INPUT buffer owned by caller)
- Exception handler never releases `decryptedContent` (OUTPUT buffer owned by service)
- **Result:** Double bug - leaked service's buffer AND corrupted caller's buffer

**Ownership responsibility:**
- Input `toDecrypt`: **Caller retains ownership** - service must NOT touch it
- Output `decryptedContent`: **Service owns** until returned - service must release on error
- Service pattern: Release your own allocations, never touch caller's buffers

---

## Tests

All tests added to existing test classes with NettyByteBufLeakHelper leak detection.

### DecryptJob Tests (CryptoJobHandlerTest)

**testDecryptJobCloseBeforeRunReleasesBuffer()**
- Calls `closeJob()` before `run()` executes (abort scenario)
- **Proves bug:** Without fix, NettyByteBufLeakHelper detects leaked `encryptedBlobContent`
- **Validates fix:** With fix, no leak detected

**testDecryptJobCloseIdempotent()**
- Calls `closeJob()` multiple times
- **Proves fix works:** No `IllegalReferenceCountException` from double-release
- **Validates AtomicBoolean guard:** Cleanup logic only executes once

**testDecryptJobNormalExecutionNoLeak()**
- Baseline test for normal `run()` execution
- Confirms happy path already correct

### GCMCryptoService Tests (GCMCryptoServiceTest)

**testDecryptWithCorruptedCiphertextOwnership()**
- Corrupts ciphertext to trigger exception during `doFinal()`
- **Proves bug:** Without fix, input buffer `refCnt=0` (incorrectly released) AND leak detected
- **Validates fix:** With fix, input buffer `refCnt=1` (untouched) AND no leak

**testDecryptWithWrongKeyOwnership()**
- Uses wrong key to trigger authentication failure
- Tests different exception path with same ownership requirements
- **Validates fix:** Input buffer untouched, no leak

**testSuccessfulDecryptOwnership()**
- Baseline test for successful decryption
- Confirms service doesn't release input in success path

---

## Fixes

### Fix 1: DecryptJob.closeJob() - Release Owned Buffer

```java
@Override
public void closeJob(GeneralSecurityException gse) {
  if (closed.compareAndSet(false, true)) {  // Idempotent guard
    if (encryptedBlobContent != null) {
      encryptedBlobContent.release();      // Release owned buffer
    }
  }
  callback.onCompletion(null, gse);
}
```

**Changes:**
- Added `AtomicBoolean closed` field
- Release `encryptedBlobContent` on first call only
- `compareAndSet(false, true)` ensures idempotency (safe multiple calls)

**Why AtomicBoolean:**
- Thread-safe guard against concurrent closeJob() calls
- Explicit intent: tracks "cleanup done" state separately from ByteBuf refCnt
- Prevents double-release while making method idempotent

### Fix 2: GCMCryptoService.decrypt() - Release Correct Buffer

```java
} catch (Exception e) {
  if (decryptedContent != null) {      // Release OUR buffer
    decryptedContent.release();
  }
  // Do NOT touch toDecrypt - caller owns it
  throw new GeneralSecurityException(...);
}
```

**Changes:**
- Changed from releasing `toDecrypt` (caller's) to `decryptedContent` (ours)
- Matches `encrypt()` method pattern (lines 144-148)

**Why this is correct:**
- Service allocates `decryptedContent`, service must clean it up on error
- Caller owns `toDecrypt` before and after the call
- Follows ownership transfer rules: caller → service → caller (on success)

---

## Impact

**Before Fix:**
- Every aborted DecryptJob leaked ~1KB+ ByteBuf
- Every failed decrypt corrupted caller's buffer + leaked internal buffer
- Production memory leak under error conditions

**After Fix:**
- All ByteBuf ownership contracts respected
- No leaks detected in any scenario
- Idempotent cleanup (safe concurrent access)
- All tests pass with full leak detection enabled

**Test Coverage:**
- 6 new tests in existing test classes
- Tests fail without fixes (proven with original broken code)
- Tests pass with fixes (validates correctness)
