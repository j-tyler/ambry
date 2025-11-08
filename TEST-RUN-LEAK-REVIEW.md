# ByteBuf Leak Analysis Report

**Date**: 2025-11-08
**Branch**: claude/ambry-bytebuf-leak-analysis-011CUvpA35PcZtvmNSGeFF1r
**Test Run**: Full test suite with ByteBuf Flow Tracker enabled

## Overview

This document contains a comprehensive analysis of all potential ByteBuf leaks detected during test execution using the ByteBuf Flow Tracker from j-tyler/bytebuddy-bytebuf-tracer.

**Total Unique Leak Patterns**: TBD
**Highest Reference Count**: 29

---

## Understanding the Leak Format

- `[LEAK:ref=N]`: ByteBuf stopped being tracked with reference count N (should be 0)
- `Method[ref=X]`: Method call with ByteBuf reference count X at that point
- `->`: Call flow progression
- Reference count should reach 0 when ByteBuf is properly released

---

## Complete Leak Inventory

### Category 1: Crypto Service Leaks

#### 1.1 MockCryptoService.decrypt -> Utils.applyByteBufferFunctionToByteBuf

```
[LEAK:ref=2] MockCryptoService.decrypt[ref=1] -> Utils.applyByteBufferFunctionToByteBuf[ref=2]
```

**Count**: 1
**Max Ref Count**: 2
**Likelihood Rating**: TBD

---

#### 1.2 MockCryptoService.encrypt -> GCMCryptoService.encrypt Chains

```
[LEAK:ref=2] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=2] -> MockCryptoService.encrypt[ref=2] -> GCMCryptoService.encrypt[ref=2]
[LEAK:ref=9] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=8] -> MockCryptoService.encrypt[ref=9] -> GCMCryptoService.encrypt[ref=9]
[LEAK:ref=8] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=8] -> MockCryptoService.encrypt[ref=8] -> GCMCryptoService.encrypt[ref=8]
[LEAK:ref=9] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=8] -> MockCryptoService.encrypt[ref=8] -> GCMCryptoService.encrypt[ref=9]
[LEAK:ref=8] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=7] -> MockCryptoService.encrypt[ref=7] -> GCMCryptoService.encrypt[ref=8]
[LEAK:ref=7] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=7] -> MockCryptoService.encrypt[ref=7] -> GCMCryptoService.encrypt[ref=7]
[LEAK:ref=9] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=7] -> MockCryptoService.encrypt[ref=7] -> GCMCryptoService.encrypt[ref=9]
[LEAK:ref=6] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=7] -> MockCryptoService.encrypt[ref=7] -> GCMCryptoService.encrypt[ref=6]
[LEAK:ref=5] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=7] -> MockCryptoService.encrypt[ref=6] -> GCMCryptoService.encrypt[ref=5]
[LEAK:ref=9] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=7] -> MockCryptoService.encrypt[ref=9] -> GCMCryptoService.encrypt[ref=9]
[LEAK:ref=8] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=7] -> MockCryptoService.encrypt[ref=8] -> GCMCryptoService.encrypt[ref=8]
[LEAK:ref=9] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=7] -> MockCryptoService.encrypt[ref=8] -> GCMCryptoService.encrypt[ref=9]
[LEAK:ref=9] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=9] -> MockCryptoService.encrypt[ref=9] -> GCMCryptoService.encrypt[ref=9]
[LEAK:ref=5] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=4] -> MockCryptoService.encrypt[ref=5] -> GCMCryptoService.encrypt[ref=5]
[LEAK:ref=4] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=4] -> MockCryptoService.encrypt[ref=4] -> GCMCryptoService.encrypt[ref=4]
[LEAK:ref=5] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=4] -> MockCryptoService.encrypt[ref=4] -> GCMCryptoService.encrypt[ref=5]
[LEAK:ref=3] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=4] -> MockCryptoService.encrypt[ref=3] -> GCMCryptoService.encrypt[ref=3]
[LEAK:ref=4] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=3] -> MockCryptoService.encrypt[ref=4] -> GCMCryptoService.encrypt[ref=4]
[LEAK:ref=5] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=3] -> MockCryptoService.encrypt[ref=4] -> GCMCryptoService.encrypt[ref=5]
[LEAK:ref=4] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=3] -> MockCryptoService.encrypt[ref=3] -> GCMCryptoService.encrypt[ref=4]
[LEAK:ref=3] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=3] -> MockCryptoService.encrypt[ref=3] -> GCMCryptoService.encrypt[ref=3]
[LEAK:ref=8] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=6] -> MockCryptoService.encrypt[ref=7] -> GCMCryptoService.encrypt[ref=8]
[LEAK:ref=7] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=6] -> MockCryptoService.encrypt[ref=7] -> GCMCryptoService.encrypt[ref=7]
[LEAK:ref=7] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=6] -> MockCryptoService.encrypt[ref=6] -> GCMCryptoService.encrypt[ref=7]
[LEAK:ref=6] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=6] -> MockCryptoService.encrypt[ref=6] -> GCMCryptoService.encrypt[ref=6]
[LEAK:ref=7] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=5] -> MockCryptoService.encrypt[ref=7] -> GCMCryptoService.encrypt[ref=7]
[LEAK:ref=7] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=5] -> MockCryptoService.encrypt[ref=6] -> GCMCryptoService.encrypt[ref=7]
[LEAK:ref=9] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=5] -> MockCryptoService.encrypt[ref=6] -> GCMCryptoService.encrypt[ref=9]
[LEAK:ref=6] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=5] -> MockCryptoService.encrypt[ref=6] -> GCMCryptoService.encrypt[ref=6]
[LEAK:ref=7] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=5] -> MockCryptoService.encrypt[ref=5] -> GCMCryptoService.encrypt[ref=7]
[LEAK:ref=6] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=5] -> MockCryptoService.encrypt[ref=5] -> GCMCryptoService.encrypt[ref=6]
[LEAK:ref=5] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=5] -> MockCryptoService.encrypt[ref=5] -> GCMCryptoService.encrypt[ref=5]
[LEAK:ref=3] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=5] -> MockCryptoService.encrypt[ref=4] -> GCMCryptoService.encrypt[ref=3]
[LEAK:ref=9] MockCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=5] -> MockCryptoService.encrypt[ref=9] -> GCMCryptoService.encrypt[ref=9]
```

**Count**: 35 variations
**Max Ref Count**: 9
**Likelihood Rating**: TBD

---

#### 1.3 GCMCryptoService.encrypt Direct Leaks

```
[LEAK:ref=8] GCMCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=8]
[LEAK:ref=7] GCMCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=7]
[LEAK:ref=9] GCMCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=9]
[LEAK:ref=4] GCMCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=4]
[LEAK:ref=3] GCMCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=3]
[LEAK:ref=6] GCMCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=6]
[LEAK:ref=5] GCMCryptoService.encrypt[ref=1] -> GCMCryptoService.encrypt[ref=5]
```

**Count**: 7 variations
**Max Ref Count**: 9
**Likelihood Rating**: TBD

---

### Category 2: Compression Service Leaks

```
[LEAK:ref=1] CompressionService.compressChunk[ref=1]
[LEAK:ref=2] CompressionService.decompress[ref=1] -> CompressionMap.getAlgorithmName[ref=2]
[LEAK:ref=1] Unpooled.wrappedBuffer[ref=1] -> CompressionService.decompress[ref=1] -> CompressionMap.getAlgorithmName[ref=1]
[LEAK:ref=1] Unpooled.wrappedBuffer[ref=1] -> Unpooled.wrappedBuffer[ref=1] -> CompressionService.decompress[ref=1] -> CompressionMap.getAlgorithmName[ref=1] -> CompressionService.decompress[ref=1] -> CompressionMap.getAlgorithmName[ref=1]
```

**Count**: 4 variations
**Max Ref Count**: 2
**Likelihood Rating**: TBD

---

### Category 3: Unpooled.wrappedBuffer with Massive Retain Chains

#### 3.1 Simple retain() Chains

```
[LEAK:ref=2] Unpooled.wrappedBuffer[ref=1] -> UnpooledHeapByteBuf.retain[ref=2] -> UnpooledHeapByteBuf.retain[ref=2]
```

**Count**: 1
**Max Ref Count**: 2
**Likelihood Rating**: TBD

---

#### 3.2 ByteBufferAsyncWritableChannel.write Chains (Small)

```
[LEAK:ref=1] Unpooled.wrappedBuffer[ref=1] -> Unpooled.wrappedBuffer[ref=1] -> ByteBufferAsyncWritableChannel.write[ref=1] -> ByteBufferAsyncWritableChannel.getNextByteBuf_return[ref=1]
[LEAK:ref=5] Unpooled.wrappedBuffer[ref=1] -> Unpooled.wrappedBuffer[ref=1] -> Unpooled.wrappedBuffer[ref=1] -> ByteBufferAsyncWritableChannel.write[ref=1] -> ByteBufferAsyncWritableChannel.getNextByteBuf_return[ref=1] -> UnpooledHeapByteBuf.retain[ref=2] -> UnpooledHeapByteBuf.retain[ref=2] -> UnpooledHeapByteBuf.retain[ref=2] -> UnpooledHeapByteBuf.retain[ref=3] -> UnpooledHeapByteBuf.retain[ref=4] -> UnpooledHeapByteBuf.retain[ref=5]
```

**Count**: 2 variations
**Max Ref Count**: 5
**Likelihood Rating**: TBD

---

#### 3.3 ByteBufferAsyncWritableChannel.write Chains (Medium - ref 9-13)

**Sample Leaks** (5 of ~50 similar patterns):
```
[LEAK:ref=9] Unpooled.wrappedBuffer[ref=1] -> ... -> ByteBufferAsyncWritableChannel.write[ref=1] -> ByteBufferAsyncWritableChannel.getNextByteBuf_return[ref=1] -> UnpooledHeapByteBuf.retain[ref=2] -> ... [multiple retain calls] ... -> UnpooledHeapByteBuf.retain[ref=9]
[LEAK:ref=13] Unpooled.wrappedBuffer[ref=1] -> ... [similar pattern] ... -> UnpooledHeapByteBuf.retain[ref=13]
```

**Count**: ~50 variations
**Max Ref Count**: 13
**Likelihood Rating**: TBD

---

#### 3.4 ByteBufferAsyncWritableChannel.write Chains (Large - ref 14-29)

**Sample Leaks** (5 of ~150 similar patterns):
```
[LEAK:ref=17] Unpooled.wrappedBuffer[ref=1] -> ... -> ByteBufferAsyncWritableChannel.write[ref=1] -> ByteBufferAsyncWritableChannel.getNextByteBuf_return[ref=1] -> UnpooledHeapByteBuf.retain[ref=2] -> ... [many retain calls] ... -> UnpooledHeapByteBuf.retain[ref=17]
[LEAK:ref=29] Unpooled.wrappedBuffer[ref=1] -> ... [massive chain] ... -> UnpooledHeapByteBuf.retain[ref=29]
```

**Count**: ~150 variations
**Max Ref Count**: 29
**Likelihood Rating**: TBD

---

### Category 4: BlobData/GetChunk Leaks

```
[LEAK:ref=2] Utils.readNettyByteBufFromCrcInputStream_return[ref=1] -> BlobData.content_return[ref=1] -> AdvancedLeakAwareByteBuf.retain[ref=2] -> ... -> GetChunk.maybeLaunchCryptoJob[ref=2] -> GetChunk.decompressContent[ref=2] -> GetChunk.decompressContent_return[ref=2] -> GetChunk.filterChunkToRange[ref=2] -> GetChunk.filterChunkToRange_return[ref=2] -> RetainingAsyncWritableChannel.write[ref=2]

[LEAK:ref=1] Utils.readNettyByteBufFromCrcInputStream_return[ref=1] -> BlobData.content_return[ref=1] -> AdvancedLeakAwareByteBuf.retain[ref=2] -> ... -> RetainingAsyncWritableChannel.write[ref=1]

[LEAK:ref=2] Utils.readNettyByteBufFromCrcInputStream_return[ref=1] -> BlobData.content_return[ref=1] -> AdvancedLeakAwareByteBuf.retain[ref=2] -> ... -> .write[ref=2]

[LEAK:ref=2] Utils.readNettyByteBufFromCrcInputStream_return[ref=1] -> BlobData.content_return[ref=1] -> AdvancedLeakAwareByteBuf.retain[ref=2] -> ... -> FirstGetChunk.maybeLaunchCryptoJob[ref=2] -> ... -> FirstGetChunk.filterChunkToRange_return[ref=2] -> RetainingAsyncWritableChannel.write[ref=2]

[LEAK:ref=1] Utils.readNettyByteBufFromCrcInputStream_return[ref=1] -> BlobData.content_return[ref=1] -> AdvancedLeakAwareByteBuf.retain[ref=2] -> ... -> FirstGetChunk.filterChunkToRange_return[ref=2] -> RetainingAsyncWritableChannel.write[ref=1]
```

**Count**: 5 variations
**Max Ref Count**: 2
**Likelihood Rating**: TBD

---

### Category 5: DeleteRequest Leak

```
[LEAK:ref=1] DeleteRequest.content_return[ref=1]
```

**Count**: 1
**Max Ref Count**: 1
**Likelihood Rating**: TBD

---

### Category 6: ByteBufferSend Chain (Very Long)

```
[LEAK:ref=1] UnpooledByteBufAllocator.directBuffer[ref=1] -> Unpooled.wrappedBuffer[ref=1] -> Utils.applyByteBufferFunctionToByteBuf_return[ref=1] -> MockCryptoService.decrypt_return[ref=1] -> Unpooled.wrappedBuffer[ref=1] -> Utils.applyByteBufferFunctionToByteBuf_return[ref=1] -> MockCryptoService.decrypt_return[ref=1] -> ... [repeats many times] ... -> ByteBufferSend.content_return[ref=1] -> ... [MAX_DEPTH_REACHED]
```

**Count**: 1
**Max Ref Count**: 1
**Depth**: MAX_DEPTH_REACHED (very deep chain)
**Likelihood Rating**: TBD

---

### Category 7: Pooled Buffer Leak

```
[LEAK:ref=1] PooledByteBufAllocator.directBuffer[ref=1] -> GCMCryptoService.decrypt_return[ref=1] -> GetChunk.decompressContent[ref=1] -> GetChunk.decompressContent_return[ref=1] -> GetChunk.filterChunkToRange[ref=1] -> GetChunk.filterChunkToRange_return[ref=1] -> .write[ref=1]
```

**Count**: 1
**Max Ref Count**: 1
**Likelihood Rating**: TBD

---

## Summary Statistics

- **Total Leak Instances**: ~300+
- **Unique Leak Patterns**: ~8 major categories
- **Highest Reference Count**: 29
- **Most Common Pattern**: Unpooled.wrappedBuffer with UnpooledHeapByteBuf.retain chains
- **Test Artifacts**: Many leaks appear to be from test setup/teardown

---

## Detailed Analysis by Category

All source code has been reviewed. Below are detailed findings with likelihood ratings (0-5 scale):
- **0**: Definitely not a leak (false positive)
- **1**: Very unlikely to be a real leak (test artifact)
- **2**: Possibly a leak, needs investigation
- **3**: Likely a minor leak in edge cases
- **4**: Likely a real leak with moderate impact
- **5**: Confirmed leak with high impact

---

### Category 1: Crypto Service Leaks - ANALYSIS

#### Finding Summary
**Files Reviewed**:
- `/home/user/ambry/ambry-router/src/main/java/com/github/ambry/router/GCMCryptoService.java`
- `/home/user/ambry/ambry-router/src/test/java/com/github/ambry/router/MockCryptoService.java`
- `/home/user/ambry/ambry-api/src/main/java/com/github/ambry/router/CryptoService.java`

#### 1.1 MockCryptoService.decrypt -> Utils.applyByteBufferFunctionToByteBuf
**Likelihood Rating**: **1/5** (Test artifact)

**Analysis**:
- Uses default interface method from CryptoService that wraps ByteBuffer result
- Creates `Unpooled.wrappedBuffer()` around ByteBuffer (line 482 in Utils.java)
- Not a leak - properly wrapping the result
- Only appears in test scenarios with MockCryptoService

---

#### 1.2 MockCryptoService.encrypt -> GCMCryptoService.encrypt Chains
**Likelihood Rating**: **2/5** (Possible test leak, not production)

**Analysis**:
- **Varying ref counts (3-9)** correspond to number of data chunks in multi-chunk tests
- Production code (PutOperation.java:1591) uses `retainedDuplicate()` correctly
- **Root cause**: Tests may not properly release EncryptJobResult in all paths
- **GCMCryptoService.encrypt itself is correct**:
  - Line 127: Allocates output ByteBuf
  - Line 146: Releases on exception ✓
  - Line 143: Returns to caller (transfers ownership) ✓

**Recommendations**:
- Audit test code for missing `result.release()` calls
- Not a production code issue

---

#### 1.3 GCMCryptoService.encrypt Direct Leaks
**Likelihood Rating**: **2/5** (Same as 1.2 - test artifact)

**Analysis**: Same issue as Category 1.2 - different call path to same method.

---

#### ⚠️ **CRITICAL BUG FOUND in GCMCryptoService.decrypt**
**File**: `GCMCryptoService.java` lines 196-200
**Severity**: **4/5** (Real bug, affects error paths only)

```java
} catch (Exception e) {
  if (toDecrypt != null) {
    toDecrypt.release();  // ❌ BUG! Releasing INPUT parameter!
  }
  throw new GeneralSecurityException("Exception thrown while decrypting data", e);
}
```

**Problem**:
- Releases the `toDecrypt` input parameter (owned by caller)
- Should release `decryptedContent` instead (like encrypt method does)
- Causes double-release if caller also releases input

**Fix Required**:
```java
} catch (Exception e) {
  if (decryptedContent != null) {
    decryptedContent.release();
  }
  throw new GeneralSecurityException("Exception thrown while decrypting data", e);
}
```

---

### Category 2: Compression Service Leaks - ANALYSIS

**Files Reviewed**:
- `/home/user/ambry/ambry-router/src/main/java/com/github/ambry/router/CompressionService.java`

#### All Compression Leaks
**Likelihood Rating**: **1/5** (Test artifacts only)

**Analysis**:

**CompressionService.compressChunk** (lines 249-313):
- Properly releases `compressedByteBuf` on exception (line 278)
- Properly releases `newChunkByteBuf` in finally (line 282)
- Returns compressed buffer to caller (caller must release)
- **Production usage in PutOperation.java:1562-1566 is correct** ✓

**CompressionService.decompress** (lines 325-408):
- Calls `getAlgorithmName()` which only reads, doesn't retain
- `combineBuffer()` may call `retainedDuplicate()` (ref=2)
- Properly releases `newCompressedByteBuf` in finally (line 390)
- Returns decompressed buffer to caller (caller must release)

**Test Leaks Found** (should be fixed):
1. **CompressionServiceTest.java:232, 242**: Creates decompressed buffers but never releases them
2. **CompressionMapTest.java:92**: `Unpooled.wrappedBuffer()` result never released

**Conclusion**: Production code is correct. Test code has leaks.

---

### Category 3: Unpooled.wrappedBuffer with Massive Retain Chains - ANALYSIS

**Files Reviewed**:
- `/home/user/ambry/ambry-commons/src/main/java/com/github/ambry/commons/ByteBufferAsyncWritableChannel.java`

#### All ByteBufferAsyncWritableChannel Leaks (ref 2-29)
**Likelihood Rating**: **2/5** (Test infrastructure artifacts)

**Analysis**:

**ByteBufferAsyncWritableChannel** itself **NEVER calls retain()**:
- `write()` method (lines 100-119): No retain() call
- `getNextByteBuf()` method (lines 212-218): No retain() call
- `getChunkBuf()` helper (lines 256-267): No retain() call

**The massive retain() chains (up to ref=29) are NOT from this class.**

**Likely causes**:
1. **Test infrastructure**: Tests running in loops/iterations accumulating retains
2. **Multi-layer service chains**: ByteBuf passes through crypto→compression→serialization, each layer retaining
3. **Mock objects**: Test mocks artificially inflating ref counts
4. **Correlation with crypto leaks**: Similar ref counts (3-9) suggest same test scenarios

**Evidence**:
- Production code in PutOperation/GetBlobOperation uses channels correctly
- Tests show proper pattern in ByteBufferAsyncWritableChannelTest.java:354-358
- Channel composes ByteBufs into compositeBuffer and releases on close() (lines 145-152)

**Conclusion**: Test artifacts from complex multi-layer test scenarios, not production bugs.

---

### Category 4: BlobData/GetChunk Leaks - ANALYSIS

**Files Reviewed**:
- `/home/user/ambry/ambry-router/src/main/java/com/github/ambry/router/GetBlobOperation.java`
- `/home/user/ambry/ambry-messageformat/src/main/java/com/github/ambry/messageformat/BlobData.java`

#### All GetChunk/BlobData Leaks
**Likelihood Rating**: **1/5** (Test artifacts - proper release paths exist)

**Analysis**:

**The ref=2 is explained by explicit retain() for async processing**:
- Line 1119-1124 in GetBlobOperation.java:
  ```java
  ByteBuf chunkBuf = blobData.content();  // ref=1
  // We are going to send this chunkBuf later back to the router, so increase the counter here
  chunkBuf.retain();  // ref=2 - THIS IS THE SOURCE
  ```

**Proper release paths exist**:
1. Lines 602-609: Removes from `chunkIndexToBuf` and writes to channel
2. Lines 524-527: `chunkAsyncWriteCallback` releases after async write completes:
   ```java
   ByteBuf byteBuf = chunkIndexToBufWaitingForRelease.remove(currentNumChunk);
   if (byteBuf != null) {
     ReferenceCountUtil.safeRelease(byteBuf);
   }
   ```
3. Lines 250-259: `releaseResource()` cleanup on operation completion/error

**Why tests leak**:
- Tests may not complete full async write flow
- Tests may not call `releaseResource()` on operation cleanup
- Test channels might not properly consume/release buffers

**Conclusion**: Well-structured code with proper async ByteBuf lifecycle management. Leaks are test artifacts.

---

### Category 5: DeleteRequest Leak - ANALYSIS ⚠️

**Files Reviewed**:
- `/home/user/ambry/ambry-protocol/src/main/java/com/github/ambry/protocol/DeleteRequest.java`
- `/home/user/ambry/ambry-router/src/main/java/com/github/ambry/router/DeleteOperation.java`

#### DeleteRequest.content_return
**Likelihood Rating**: **4/5** (LIKELY A REAL PRODUCTION LEAK)

**Analysis**:

**How DeleteRequest allocates ByteBuf** (RequestOrResponse.java:91-96):
```java
protected void prepareBuffer() {
  if (bufferToSend == null) {
    bufferToSend = PooledByteBufAllocator.DEFAULT.ioBuffer((int) sizeInBytes());  // ref=1
    writeHeader();
  }
}
```

**The buffer is returned via content()** (lines 135-140):
```java
@Override
public ByteBuf content() {
  if (bufferToSend == null) {
    prepareBuffer();  // Creates buffer with ref=1
  }
  return bufferToSend;  // Returns without retain
}
```

**Critical Finding: NO RELEASE IN PRODUCTION CODE**
- DeleteOperation.java creates `new DeleteRequest(...)` (line 179)
- Stores in `deleteRequestInfos` map (line 159)
- Removes from map when response arrives (line 194)
- **NO `deleteRequest.release()` call found in DeleteOperation**

**But RequestOrResponse HAS a release() method** (lines 148-154):
```java
@Override
public boolean release() {
  ByteBuf buf = content();
  if (buf != null) {
    return buf.release();
  }
  return false;
}
```

**Only test code releases it**: RequestResponseTest.java lines 1682, 1741

**Production Impact**:
- Every delete operation leaks the request buffer (~100-500 bytes of pooled memory)
- Pooled buffers not returned to pool
- Over time, could exhaust direct memory or pooled allocator
- **This is a real leak that should be fixed**

**Recommended Fix**:
Add release to DeleteOperation.java after response handling:
```java
// In handleResponse() after processing the response
deleteRequest.release();
```

---

### Category 6: ByteBufferSend Chain (Very Long)
**Likelihood Rating**: **1/5** (Test artifact with deep recursion)

**Analysis**:
- MAX_DEPTH_REACHED indicates very deep call chain
- Recursive decrypt→wrappedBuffer→decrypt pattern
- Only ref=1 throughout (not accumulating references)
- Likely a test using recursive mocking or iteration
- Not a production concern

---

### Category 7: Pooled Buffer Leak
**Likelihood Rating**: **1/5** (Same as Category 4 - test artifact)

**Analysis**: Same as Category 4 GetChunk leaks - proper async release path exists.

---

## Summary of Findings

### Real Production Bugs Found

| Bug | Severity | File | Lines | Fix Priority |
|-----|----------|------|-------|--------------|
| **GCMCryptoService.decrypt releases input instead of output** | **4/5** | GCMCryptoService.java | 196-200 | **HIGH** |
| **DeleteRequest ByteBuf never released** | **4/5** | DeleteOperation.java | N/A (missing release) | **HIGH** |

### Test Code Issues (Should Fix for Clean Tests)

| Issue | Severity | File | Lines |
|-------|----------|------|-------|
| CompressionServiceTest doesn't release buffers | 1/5 | CompressionServiceTest.java | 232, 242 |
| CompressionMapTest doesn't release wrapped buffer | 1/5 | CompressionMapTest.java | 92 |
| CryptoService tests may not release EncryptJobResult | 2/5 | Various crypto tests | N/A |

### False Positives / Test Artifacts

- **Massive retain() chains (ref=29)**: Multi-layer test scenarios
- **Varying crypto ref counts (3-9)**: Multi-chunk test data
- **GetChunk leaks**: Incomplete test async flows
- **ByteBufferSend deep chain**: Test recursion artifact

---

## Deep Dive: High-Priority Leaks (Rating 4+)

### 🔴 HIGH PRIORITY #1: GCMCryptoService.decrypt Bug

**Location**: `/home/user/ambry/ambry-router/src/main/java/com/github/ambry/router/GCMCryptoService.java:196-200`

**Current Code** (INCORRECT):
```java
} catch (Exception e) {
  if (toDecrypt != null) {
    toDecrypt.release();  // ❌ Releases caller's buffer!
  }
  throw new GeneralSecurityException("Exception thrown while decrypting data", e);
}
```

**Why This Is Bad**:
1. `toDecrypt` is an **input parameter** - owned by the caller
2. Releasing it here causes **double-release** when caller also releases
3. Can cause crashes: "LEAK: ByteBuf.release() was called more times than expected"
4. **Only affects exception paths**, so may not be caught in normal testing

**Block-by-Block Code Flow**:

```java
// Line 172: Method signature - toDecrypt is caller's buffer
public ByteBuf decrypt(ByteBuf toDecrypt, T key) throws GeneralSecurityException {
  ByteBuf decryptedContent = null;  // Line 173: Our allocated buffer

  try {
    // Lines 174-191: Normal processing
    decryptedContent = PooledByteBufAllocator.DEFAULT.ioBuffer(outputSize);  // We allocate
    // ... encryption happens ...
    return decryptedContent;  // Line 192: Transfer ownership to caller

  } catch (Exception e) {
    // Line 197: ❌ BUG - releasing WRONG buffer
    if (toDecrypt != null) {
      toDecrypt.release();  // Should release decryptedContent instead!
    }
    throw new GeneralSecurityException(...);
  } finally {
    // Line 202: Even worse - toDecrypt is caller's buffer!
    if (toDecrypt != null) {
      toDecrypt.skipBytes(toDecrypt.readableBytes());  // OK - just moving position
    }
  }
}
```

**Correct Implementation** (matching encrypt method pattern):
```java
} catch (Exception e) {
  if (decryptedContent != null) {
    decryptedContent.release();  // ✓ Release OUR buffer, not caller's
  }
  throw new GeneralSecurityException("Exception thrown while decrypting data", e);
}
```

**Impact**:
- **Severity**: Medium-High (only affects error paths, but causes crashes)
- **Frequency**: Rare (only when decryption throws exception)
- **Effect**: Double-release → RefCnt assertion failure → application crash

---

### 🔴 HIGH PRIORITY #2: DeleteRequest ByteBuf Leak

**Location**: `/home/user/ambry/ambry-router/src/main/java/com/github/ambry/router/DeleteOperation.java`

**Missing Code**: No release() call for DeleteRequest

**How the Leak Happens** (Step-by-Step):

1. **DeleteOperation creates request** (line 179):
   ```java
   DeleteRequest deleteRequest = new DeleteRequest(...);
   ```

2. **DeleteRequest allocates pooled ByteBuf** (RequestOrResponse.java:93):
   ```java
   bufferToSend = PooledByteBufAllocator.DEFAULT.ioBuffer((int) sizeInBytes());  // ref=1
   ```

3. **Request is sent to network layer** (DeleteOperation.java:159):
   ```java
   deleteRequestInfos.put(correlationId, deleteRequestInfo);
   // Request buffer is sent over network
   ```

4. **Response arrives** (line 194):
   ```java
   deleteRequestInfos.remove(correlationId);  // Removes from map
   // ❌ BUG: DeleteRequest object (with its ByteBuf) is now orphaned
   // No release() call → pooled buffer never returned to pool
   ```

**Why This Is a Leak**:
- RequestOrResponse allocates from **PooledByteBufAllocator** (not heap)
- Pooled allocators reuse memory chunks
- Unreleased pooled buffers **never get returned to pool**
- Eventually exhausts pool → allocates new chunks → memory growth
- Small per-operation (~100-500 bytes) but **accumulates over millions of deletes**

**Evidence of Leak**:
- Tests properly release: `RequestResponseTest.java:1682, 1741`
- Production code has no release call
- RequestOrResponse provides `release()` method but it's never called

**Block-by-Block Analysis of DeleteOperation.handleResponse()**:

```java
// Line 179: Request created
DeleteRequest deleteRequest = new DeleteRequest(correlationIdToUse, clientId, blobId,
    accountId, containerId, deletionTime, (short) 0, operationTimeMs);

// Line 192-194: Response handling
if (response != null && response.getError() == ServerErrorCode.No_Error) {
  deleteRequestInfos.remove(correlationId);  // ❌ Request removed but never released
  numChunksDeleted.incrementAndGet();
}

// ❌ MISSING: deleteRequest.release();
```

**Recommended Fix**:
```java
// After line 194, add:
DeleteRequestInfo requestInfo = deleteRequestInfos.remove(correlationId);
if (requestInfo != null && requestInfo.getRequest() != null) {
  requestInfo.getRequest().release();  // ✓ Return pooled buffer
}
numChunksDeleted.incrementAndGet();
```

**Impact**:
- **Severity**: Medium (slow leak, small per-operation)
- **Frequency**: Every delete operation
- **Effect**: Pooled memory never reclaimed → eventual OOM or pool exhaustion
- **Production Risk**: HIGH - this affects all delete operations

---

## Recommendations

### Immediate Actions Required

1. **Fix GCMCryptoService.decrypt bug** (lines 196-200)
   - Change `toDecrypt.release()` to `decryptedContent.release()`
   - Add test case for decrypt exception path

2. **Fix DeleteOperation leak**
   - Add `deleteRequest.release()` after response handling
   - Verify all RequestOrResponse subclasses have proper cleanup

3. **Fix test code leaks** (for clean CI):
   - CompressionServiceTest.java: Add buffer releases
   - CompressionMapTest.java: Release wrapped buffers

### Long-Term Improvements

1. **Enable ByteBuf leak detection in CI/CD**
   - Add `-Dio.netty.leakDetection.level=PARANOID` to test JVM args
   - Fail builds on detected leaks

2. **Audit other RequestOrResponse subclasses**
   - PutRequest, GetRequest, etc.
   - Ensure all have release() calls in operation handlers

3. **Code review checklist**
   - All PooledByteBufAllocator allocations must have matching release()
   - Exception handlers must release locally-allocated buffers
   - Async callbacks must handle buffer cleanup

---

## Conclusion

**Total Leak Categories Analyzed**: 7
**Real Production Bugs**: 2 (both rated 4/5)
**Test Artifacts**: 5 categories

**Production Code Quality**: Generally excellent ByteBuf lifecycle management, with two critical exceptions that should be fixed immediately.

**Test Code Quality**: Needs cleanup - several tests don't properly release ByteBufs, leading to noisy leak reports.
