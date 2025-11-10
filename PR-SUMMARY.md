# Fix ByteBuf Memory Leak in PutOperation.encryptChunk()

## Summary

Fixes ByteBuf memory leak in `PutOperation.encryptChunk()` when KMS throws exception after `retainedDuplicate()` is evaluated.

## The Bug

**Location:** `PutOperation.java:1582-1609` (encryptChunk method)

**Root Cause:** Java evaluates constructor arguments left-to-right. In the original code:
```java
new EncryptJob(..., buf.retainedDuplicate(), ..., kms.getRandomKey(), ...)
```

If `kms.getRandomKey()` throws `GeneralSecurityException` after `buf.retainedDuplicate()` has already been evaluated and incremented the refCount, the retained duplicate is orphaned and leaks because:
1. The constructor never completes, so ownership never transfers to EncryptJob
2. The exception handler has no reference to clean up the orphaned ByteBuf

## The Fix

Pre-evaluate arguments in local variables with proper exception handling:

```java
ByteBuf retainedCopy = null;
try {
  retainedCopy = isMetadataChunk() ? null : buf.retainedDuplicate();
  SecretKeySpec randomKey = (SecretKeySpec) kms.getRandomKey();
  cryptoJobHandler.submitJob(new EncryptJob(..., retainedCopy, ..., randomKey, ...));
  retainedCopy = null; // Ownership transferred to EncryptJob
} catch (GeneralSecurityException e) {
  if (retainedCopy != null) {
    retainedCopy.release(); // Clean up orphaned buffer
  }
  // ... handle exception
}
```

**Note:** Explicit cast required because `kms` is declared as raw type `KeyManagementService`.

## Test

Added `testMinimal_RetainedDuplicateArgumentEvaluationLeak()` in `PutOperationTest.java` that:
- Creates a ByteBuf with refCount=1
- Simulates the argument evaluation pattern where retainedDuplicate() executes before exception
- Verifies no leak occurs with proper cleanup

**Validation:**
- Without fix: Test fails with `HeapMemoryLeak: [allocation|deallocation] before test[0|0], after test[1|0]`
- With fix: Test passes with no leaks detected
