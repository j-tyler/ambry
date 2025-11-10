# Fix PutOperation ByteBuf Memory Leaks

## Summary

Fixes 3 critical ByteBuf memory leaks in PutOperation where exceptions occur after ownership transfer but before cleanup can execute.

**Key Concept:** In Netty, **the owner of a ByteBuf must call `release()`**. Ownership transfers when a method returns a ByteBuf or a constructor receives one. If an exception occurs after ownership transfers but before the new owner can store/release the buffer, it leaks.

---

## Bug #1: CRC Exception After Compression

**Location:** `PutOperation.java:1562-1576`

**Issue:** After `compressionService.compressChunk()` returns a new buffer and ownership transfers (`buf = newBuffer`), if `buf.nioBuffers()` throws during CRC calculation, the compressed buffer leaks because there's no try-catch to release it.

**Fix:** Wrapped CRC calculation in try-catch block that releases `buf` before re-throwing exception.

---

## Bug #2: CRC Exception in Encryption Callback

**Location:** `PutOperation.java:1498-1503`

**Issue:** After encryption completes and ownership transfers (`buf = result.getEncryptedBlobContent()`), if `buf.nioBuffers()` throws during CRC calculation, the encrypted buffer leaks.

**Fix:** Wrapped CRC calculation in try-catch block that releases `buf` before re-throwing exception.

---

## Bug #3: KMS Exception After retainedDuplicate()

**Location:** `PutOperation.java:1589-1592`

**Issue:** Java evaluates constructor arguments left-to-right. In `new EncryptJob(..., buf.retainedDuplicate(), ..., kms.getRandomKey(), ...)`, if `getRandomKey()` throws after `retainedDuplicate()` is evaluated, the retained duplicate leaks because the constructor never completes and ownership never transfers.

**Fix:** Pre-evaluate `retainedDuplicate()` and `getRandomKey()` into local variables. Wrapped EncryptJob construction in try-catch that releases retained duplicate if exception occurs. Set `retainedCopy = null` after successful construction to indicate ownership transferred.

---

## Tests

Added 3 tests to `PutOperationTest.java` (lines 1343-1570):
- `testProductionBug_CrcExceptionAfterCompressionLeaksCompressedBuffer`
- `testProductionBug_CrcExceptionInEncryptionCallbackLeaksEncryptedBuffer`
- `testProductionBug_KmsExceptionAfterRetainedDuplicateLeaksBuffer`

Each test simulates the exact ownership transfer state and triggers the exception condition. Tests now **PASS** with the fixes applied.

---

## Validation

Run all 3 production bug tests:
```bash
./gradlew :ambry-router:test --tests "PutOperationTest.testProductionBug*"
```

**Result:** ✅ All 3 tests **PASS** with zero leaks detected

---

## Impact

- **Severity:** Critical - Direct memory leaks on error paths
- **Affected:** Compression/encryption with CRC verification, all encryption operations
- **Risk:** Low - Adds defensive error handling without changing happy path
