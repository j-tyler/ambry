# Production ByteBuf Leak Tests

This document describes the production-quality leak tests created to detect and verify fixes for ByteBuf memory leaks in Ambry.

## Overview

Two new test classes have been created that will **FAIL** when bugs exist and **PASS** when bugs are fixed. These are real production tests, not demonstrations.

---

## Test Classes

### 1. DecryptJobCloseLeakTest (4 tests)

**Bug:** `DecryptJob.closeJob()` does NOT release `encryptedBlobContent`

**Location:** `ambry-router/src/main/java/com/github/ambry/router/DecryptJob.java:112-114`

**Impact:** Every aborted decryption job leaks a ByteBuf

#### Current Buggy Code:
```java
@Override
public void closeJob(GeneralSecurityException gse) {
  callback.onCompletion(null, gse);
  // ❌ Missing: encryptedBlobContent.release()
}
```

#### Fixed Code:
```java
@Override
public void closeJob(GeneralSecurityException gse) {
  if (encryptedBlobContent != null) {
    encryptedBlobContent.release();
  }
  callback.onCompletion(null, gse);
}
```

#### Tests:

1. **testCloseJobReleasesEncryptedContent**
   - Calls `closeJob()` before `run()` executes
   - Verifies `encryptedBlobContent` is released
   - **FAILS** until bug is fixed (NettyByteBufLeakHelper detects leak)

2. **testCloseJobAfterConstructorReleasesBuffer**
   - Calls `closeJob()` immediately after construction
   - Tests cleanup at different lifecycle stages
   - **FAILS** until bug is fixed

3. **testMultipleCloseJobCallsAreSafe**
   - Calls `closeJob()` multiple times
   - Verifies idempotency and no double-release
   - **FAILS** initially, then tests idempotency after fix

4. **testNormalExecutionNoLeak** (Baseline)
   - Tests successful `run()` execution
   - Verifies normal path doesn't leak
   - **PASSES** (validates that `run()` is correct)

---

### 2. GCMCryptoServiceDecryptLeakTest (5 tests)

**Bug:** `GCMCryptoService.decrypt()` releases WRONG buffer on exception

**Location:** `ambry-router/src/main/java/com/github/ambry/router/GCMCryptoService.java:196-200`

**Impact:**
- Leaks `decryptedContent` buffer (service's NEW buffer)
- Incorrectly releases `toDecrypt` buffer (caller's buffer)

#### Current Buggy Code:
```java
catch (Exception e) {
  if (toDecrypt != null) {
    toDecrypt.release();  // ❌ WRONG! Releases caller's buffer
  }
  // ❌ LEAKS decryptedContent (never released)
  throw new GeneralSecurityException(...);
}
```

#### Fixed Code:
```java
catch (Exception e) {
  if (decryptedContent != null) {
    decryptedContent.release();  // ✓ Release our NEW buffer
  }
  // Do NOT touch toDecrypt - caller owns it!
  throw new GeneralSecurityException(...);
}
```

#### Tests:

1. **testDecryptWithInvalidCiphertextDoesNotLeakOrReleaseInput**
   - Corrupts encrypted content to trigger exception
   - Verifies input buffer NOT released (ownership contract)
   - Verifies no leak of `decryptedContent`
   - **FAILS** with BOTH leak detection AND `IllegalReferenceCountException`

2. **testDecryptWithWrongKeyDoesNotLeakOrReleaseInput**
   - Uses wrong decryption key
   - Triggers authentication failure in `doFinal()`
   - Verifies same ownership contract
   - **FAILS** until bug is fixed

3. **testDecryptWithEmptyBufferDoesNotLeakOrReleaseInput**
   - Edge case: empty input buffer
   - Tests exception path with minimal data
   - **FAILS** until bug is fixed

4. **testDecryptExceptionAfterAllocationReleasesNewBuffer**
   - Targets the window after `decryptedContent` allocated but before success
   - Uses malformed content that fails during `doFinal()`
   - **FAILS** until bug is fixed

5. **testSuccessfulDecryptDoesNotLeak** (Baseline)
   - Tests successful decryption
   - Verifies happy path doesn't leak
   - **PASSES** (validates normal execution is correct)

---

## Running the Tests

### Basic Usage

Run tests with NettyByteBufLeakHelper leak detection:

```bash
./run-production-leak-tests.sh
```

### With ByteBuf Flow Tracker

Run tests with detailed ByteBuf flow analysis:

```bash
./run-production-leak-tests.sh --with-tracker
```

### Individual Test Classes

Run specific test class:

```bash
./gradlew :ambry-router:test --tests 'DecryptJobCloseLeakTest'
./gradlew :ambry-router:test --tests 'GCMCryptoServiceDecryptLeakTest'
```

---

## Expected Behavior

### BEFORE Fix (Current State)

**DecryptJobCloseLeakTest:**
```
✗ FAILED: testCloseJobReleasesEncryptedContent
   ByteBuf leak detected by NettyByteBufLeakHelper
   Expected: <0> but was: <1> (leak count)

✗ FAILED: testCloseJobAfterConstructorReleasesBuffer
   ByteBuf leak detected

✗ FAILED: testMultipleCloseJobCallsAreSafe
   ByteBuf leak detected

✓ PASSED: testNormalExecutionNoLeak
```

**GCMCryptoServiceDecryptLeakTest:**
```
✗ FAILED: testDecryptWithInvalidCiphertextDoesNotLeakOrReleaseInput
   ByteBuf leak detected
   IllegalReferenceCountException: refCnt: 0, decrement: 1
   (Caller's buffer was incorrectly released)

✗ FAILED: testDecryptWithWrongKeyDoesNotLeakOrReleaseInput
   ByteBuf leak detected
   IllegalReferenceCountException

✗ FAILED: testDecryptWithEmptyBufferDoesNotLeakOrReleaseInput
   (May fail depending on exception path)

✗ FAILED: testDecryptExceptionAfterAllocationReleasesNewBuffer
   ByteBuf leak detected

✓ PASSED: testSuccessfulDecryptDoesNotLeak
```

### AFTER Fix

All tests **PASS**:
```
✓ PASSED: All 9 tests pass
   No ByteBuf leaks detected
   No IllegalReferenceCountException
   All ownership contracts respected
```

---

## Ownership Contracts Tested

### GCMCryptoService.encrypt(ByteBuf toEncrypt, T key)

✅ **CORRECT** - Service pattern:
- Input `toEncrypt`: Caller retains ownership (NOT released by service)
- Output: NEW buffer returned, caller must release
- On exception: Service releases its NEW buffer, does NOT touch input

### GCMCryptoService.decrypt(ByteBuf toDecrypt, T key)

❌ **BUGGY** - Violates service pattern:
- Input `toDecrypt`: Should NOT be released by service (but currently is in catch block)
- Output: NEW buffer returned (currently leaked on exception)
- On exception: Should release NEW buffer, NOT touch input

### DecryptJob Constructor

✅ **CORRECT** - Consuming constructor pattern:
- Takes ownership of `encryptedBlobContent`
- Caller must NOT touch buffer after passing it
- DecryptJob responsible for releasing

### DecryptJob.run()

✅ **CORRECT** - Proper lifecycle:
- Always releases `encryptedBlobContent` in finally block
- Returns NEW `decryptedBlobContent` to caller
- Caller must release the result

### DecryptJob.closeJob()

❌ **BUGGY** - Violates ownership:
- Should release `encryptedBlobContent` (it owns it)
- Currently does NOT release it

---

## Why These Tests Are Different

### Compared to DecryptJobLeakTest

**DecryptJobLeakTest** (existing):
- Disables leak detection with `leakHelper.setDisabled(true)`
- Manually verifies `refCnt == 1` to prove leak exists
- Manually cleans up leaked buffers
- Demonstrates the bug but doesn't fail builds

**DecryptJobCloseLeakTest** (new):
- Enables leak detection (normal test behavior)
- Tests FAIL when bug exists
- Tests PASS when bug is fixed
- Production-quality regression tests

### Test Philosophy

These tests follow the principle:
> **Tests should fail when production code is broken and pass when production code is correct.**

The existing `*LeakTest` classes were created to demonstrate bugs exist. These new tests enforce that bugs are fixed.

---

## Integration with CI/CD

These tests can be integrated into CI/CD:

```bash
# In CI pipeline
./gradlew :ambry-router:test --tests 'DecryptJobCloseLeakTest' --tests 'GCMCryptoServiceDecryptLeakTest'

# Tests will FAIL if bugs exist
# Tests will PASS after bugs are fixed
# No special configuration needed
```

---

## Files Created

1. **ambry-router/src/test/java/com/github/ambry/router/DecryptJobCloseLeakTest.java**
   - 4 production tests for DecryptJob.closeJob() bug
   - 245 lines

2. **ambry-router/src/test/java/com/github/ambry/router/GCMCryptoServiceDecryptLeakTest.java**
   - 5 production tests for GCMCryptoService.decrypt() bug
   - 315 lines

3. **run-production-leak-tests.sh**
   - Script to run both test classes
   - Provides detailed fix instructions
   - Optional ByteBuf tracker integration
   - 250 lines

---

## Next Steps

### For Developers

1. Run the tests to confirm they FAIL:
   ```bash
   ./run-production-leak-tests.sh
   ```

2. Apply the fixes described in test headers

3. Run tests again to confirm they PASS:
   ```bash
   ./run-production-leak-tests.sh
   ```

4. Commit the fixes

### For Reviewers

1. Review test code to verify ownership contracts
2. Verify tests correctly identify the bugs
3. Confirm test assertions match expected behavior
4. After fixes applied, verify all tests pass

---

## Documentation References

- **BYTEBUF_MEMORY_LEAK_ANALYSIS.md** - Original bug analysis
- **BYTEBUF_LEAK_TEST_EXPECTATIONS.md** - Expected tracker output patterns
- **HIGH_RISK_LEAK_TEST_SUMMARY.md** - All 67 leak tests created
- **BYTEBUF_TRACKER_INTEGRATION.md** - ByteBuf flow tracker documentation

---

**Created:** 2025-11-10
**Branch:** `claude/fix-decrypt-job-close-leak-011CUyZPP1LMXJTwZDPfjX3x`
**Status:** Ready for fix implementation and verification
