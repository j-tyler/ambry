# ByteBuf Leak Analysis: Corrupt Data Deserialization

## Summary

**CRITICAL PRODUCTION BUG IDENTIFIED**: MessageFormatRecord.Blob_Format_V1.deserializeBlobRecord() leaks ByteBuf when CRC validation fails.

---

## 1. Leak Source Identification

From tracer output:
```
CRITICAL_LEAK|root=UnpooledByteBufAllocator.directBuffer|leak_rate=100.0%
```

**Test**: `testBlobData_CorruptData_NoCleanup_LEAK` (ByteBufLeakFlowExplorationTest.java:164-185)

This test intentionally creates corrupt blob data to expose the production bug.

---

## 2. Production Code Leak Verification

**File**: `ambry-messageformat/src/main/java/com/github/ambry/messageformat/MessageFormatRecord.java`
**Method**: `Blob_Format_V1.deserializeBlobRecord()` (lines 1681-1696)

### This IS a production code leak, NOT a test code issue.

The test exercises a real bug where ByteBuf is allocated but never released when deserialization encounters corrupt data.

---

## 3. Line-by-Line Production Code Leak Explanation

### The Exact Leak Path (Blob_Format_V1.deserializeBlobRecord):

**Line 1682-1683**: DataInputStream created from CrcInputStream
```java
DataInputStream dataStream = new DataInputStream(crcStream);
long dataSize = dataStream.readLong();
```

**Line 1687**: **ByteBuf ALLOCATED** (ref count = 1)
```java
ByteBuf byteBuf = Utils.readNettyByteBufFromCrcInputStream(crcStream, (int) dataSize);
```
- Direct ByteBuf allocated to hold blob content
- **Caller has NO reference to this ByteBuf yet**
- ByteBuf is NOT owned by any wrapper object
- If exception occurs here, ByteBuf WILL leak

**Lines 1688-1689**: CRC values read for validation
```java
long crc = crcStream.getValue();        // Calculated CRC from stream
long streamCrc = dataStream.readLong();  // Stored CRC from blob record
```

**Lines 1690-1694**: **THE BUG - Exception thrown without ByteBuf cleanup**
```java
if (crc != streamCrc) {
  logger.error("corrupt data while parsing blob content expectedcrc {} actualcrc {}", crc, streamCrc);
  throw new MessageFormatException("corrupt data while parsing blob content",
      MessageFormatErrorCodes.DataCorrupt);
}
```

**When CRC mismatch occurs (corrupt data)**:
1. ❌ ByteBuf from line 1687 is allocated (ref = 1)
2. ❌ MessageFormatException thrown immediately
3. ❌ Line 1695 NEVER reached (BlobData never created)
4. ❌ NO try-finally block to release ByteBuf
5. ❌ Caller catches exception but has NO ByteBuf reference
6. 💥 **ByteBuf LEAKED - native memory never reclaimed**

**Line 1695**: Only reached when CRC is VALID (not reached in corrupt case)
```java
return new BlobData(BlobType.DataBlob, dataSize, byteBuf);
```
- BlobData constructor takes ownership of ByteBuf
- Caller can release via `blobData.release()`
- **This line is SKIPPED when data is corrupt**

---

## 4. Production Scenarios That Trigger This Leak

### Real-world conditions that cause CRC mismatches:

1. **Disk Corruption**: Bit flips in stored blob data due to hardware failures
2. **Network Corruption**: Packet corruption during replication between data centers
3. **Partial Writes**: Incomplete writes to disk causing CRC inconsistency
4. **Malicious Data**: Attacker sends crafted corrupt blobs to trigger leak
5. **Software Bugs**: Code that writes incorrect CRC values during serialization
6. **Power Failures**: Interrupted writes leaving corrupted data on disk

### Impact Severity:

- **Frequency**: Every corrupt blob encountered = 1 leaked ByteBuf
- **Memory Type**: Native memory (direct buffers), not GC-managed
- **Accumulation**: Leaks accumulate over time, can exhaust memory
- **Large Blobs**: 1MB+ corrupt blobs = severe memory leak per occurrence
- **No Recovery**: Leaked native memory never reclaimed until JVM restart

**Severity Classification**: **CRITICAL / HIGH**

---

## 5. Test Suite: MessageFormatCorruptDataLeakTest.java

Created 6 comprehensive tests using NettyByteBufLeakHelper to expose the bug.

### Test 1: `testCorruptBlobV1_CrcMismatch_LeaksByteBuffer`
**Purpose**: Basic corrupt blob deserialization leak detection
**Data**: 1KB blob with wrong CRC (+12345 offset)
**Expected**: MessageFormatException thrown, ByteBuf leaked
**Status**: ❌ Currently FAILS (leak detected)
**Fix Status**: ✅ Will PASS once production code fixed

### Test 2: `testMultipleCorruptBlobs_AccumulatesLeaks`
**Purpose**: Verify leak accumulation with multiple corrupt blobs
**Data**: 3 corrupt blobs (512B, 612B, 712B) with XOR'd CRCs
**Expected**: 3 leaked ByteBufs
**Status**: ❌ Currently FAILS (3 leaks detected)
**Fix Status**: ✅ Will PASS once production code fixed

### Test 3: `testLargeCorruptBlob_SignificantLeak`
**Purpose**: Demonstrate severe memory leak with large blobs
**Data**: 1MB corrupt blob (realistic production size)
**Expected**: 1MB ByteBuf leaked - HIGH SEVERITY
**Status**: ❌ Currently FAILS (1MB leak detected)
**Fix Status**: ✅ Will PASS once production code fixed

### Test 4: `testCorruptBlob_TryCatchWithoutFinally_Leaks`
**Purpose**: Mirror exact production bug pattern (try-catch without finally)
**Data**: 256B blob with zero CRC
**Expected**: ByteBuf leaked in catch block
**Status**: ❌ Currently FAILS (leak detected)
**Fix Status**: ✅ Will PASS once production code fixed

### Test 5: `testValidBlob_NoLeak` (Control Test)
**Purpose**: Verify valid blobs do NOT leak
**Data**: 512B blob with CORRECT CRC
**Expected**: No leak, proper cleanup
**Status**: ✅ Currently PASSES (no leak)
**Fix Status**: ✅ Will still PASS (no change needed)

### Test 6: `testCorruptThenValid_OnlyCorruptLeaks`
**Purpose**: Mixed scenario - verify only corrupt blobs leak
**Data**: 1 corrupt + 1 valid blob (128B each)
**Expected**: 1 leak (from corrupt), valid blob released
**Status**: ❌ Currently FAILS (1 leak detected)
**Fix Status**: ✅ Will PASS once production code fixed

---

## 6. Required Production Code Fix

### Current Buggy Code (lines 1681-1696):

```java
public static BlobData deserializeBlobRecord(CrcInputStream crcStream)
    throws IOException, MessageFormatException {
  DataInputStream dataStream = new DataInputStream(crcStream);
  long dataSize = dataStream.readLong();
  if (dataSize > Integer.MAX_VALUE) {
    throw new IOException("We only support data of max size == MAX_INT");
  }
  ByteBuf byteBuf = Utils.readNettyByteBufFromCrcInputStream(crcStream, (int) dataSize);
  long crc = crcStream.getValue();
  long streamCrc = dataStream.readLong();
  if (crc != streamCrc) {
    logger.error("corrupt data while parsing blob content expectedcrc {} actualcrc {}", crc, streamCrc);
    throw new MessageFormatException("corrupt data while parsing blob content",
        MessageFormatErrorCodes.DataCorrupt);
  }
  return new BlobData(BlobType.DataBlob, dataSize, byteBuf);
}
```

### Fixed Code (with try-finally):

```java
public static BlobData deserializeBlobRecord(CrcInputStream crcStream)
    throws IOException, MessageFormatException {
  DataInputStream dataStream = new DataInputStream(crcStream);
  long dataSize = dataStream.readLong();
  if (dataSize > Integer.MAX_VALUE) {
    throw new IOException("We only support data of max size == MAX_INT");
  }
  ByteBuf byteBuf = Utils.readNettyByteBufFromCrcInputStream(crcStream, (int) dataSize);
  try {
    long crc = crcStream.getValue();
    long streamCrc = dataStream.readLong();
    if (crc != streamCrc) {
      logger.error("corrupt data while parsing blob content expectedcrc {} actualcrc {}", crc, streamCrc);
      throw new MessageFormatException("corrupt data while parsing blob content",
          MessageFormatErrorCodes.DataCorrupt);
    }
    return new BlobData(BlobType.DataBlob, dataSize, byteBuf);
  } catch (Exception e) {
    byteBuf.release();  // CRITICAL: Release ByteBuf on any error
    throw e;
  }
}
```

### What Changed:
1. ✅ Wrapped CRC validation and BlobData creation in try block
2. ✅ Added catch block that releases ByteBuf before re-throwing
3. ✅ Ensures ByteBuf is always released on error path
4. ✅ No leak when MessageFormatException or IOException thrown

---

## 7. Verification Plan

### Before Fix:
```bash
./gradlew :ambry-messageformat:test --tests MessageFormatCorruptDataLeakTest
```
**Expected**: 5 out of 6 tests FAIL due to leak detection
**Control**: Test 5 (testValidBlob_NoLeak) PASSES

### After Fix:
```bash
./gradlew :ambry-messageformat:test --tests MessageFormatCorruptDataLeakTest
```
**Expected**: All 6 tests PASS (no leaks detected)

---

## 8. Additional Considerations

### Same Bug in Other Blob Versions?

Check these methods for similar issues:
- `Blob_Format_V2.deserializeBlobRecord()` (line ~1760)
- `Blob_Format_V3.deserializeBlobRecord()` (line ~1840)

Both should be audited for the same pattern.

### Other Deserialization Methods

Audit all methods that allocate ByteBuf and may throw exceptions:
- `MessageFormatRecord.deserializeAndGetBlobWithVersion()`
- Any method calling `Utils.readNettyByteBufFromCrcInputStream()`

---

## 9. Test Files Summary

### New Test File:
- **File**: `ambry-messageformat/src/test/java/com/github/ambry/messageformat/MessageFormatCorruptDataLeakTest.java`
- **Tests**: 6 comprehensive tests
- **Purpose**: Expose production bug, verify fix

### Updated Test File:
- **File**: `ambry-server/src/test/java/com/github/ambry/server/ByteBufLeakFlowExplorationTest.java`
- **Change**: Fixed CRC calculation in 6 tests
- **Purpose**: Explore ByteBuf flows with tracer agent

---

## 10. Commits

**Commit d1dd4af**: Add tests exposing ByteBuf leak in corrupt data deserialization
**Commit 43f30b2**: Fix CRC calculation in ByteBuf leak exploration tests
**Branch**: claude/review-bytebuf-tracer-01XqX5RXVsegrua4awg7TPQm
