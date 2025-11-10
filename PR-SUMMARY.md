# Fix Critical ByteBuf Memory Leak in NettyRequest.writeContent()

## The Bug

**Location:** `ambry-rest/src/main/java/com/github/ambry/rest/NettyRequest.java:494-495`

**Root Cause:** Memory leak when `AsyncWritableChannel.write()` throws an exception

### How It Occurs

1. `NettyRequest.writeContent()` calls `httpContent.retain()` at line 494, incrementing the reference count from 1 to 2
2. `writeChannel.write()` is called at line 495 and throws an exception
3. When `write()` throws, the `ContentWriteCallback` is never instantiated
4. Since `ContentWriteCallback` is responsible for releasing the retained reference, **the retained ByteBuf is never released**
5. Result: Permanent memory leak (refCnt remains at 1 instead of being decremented to 0)

### Reference Counting Contract

In Netty's reference counting system:
- `retain()` increments the reference count - the caller takes ownership responsibility
- `release()` decrements the reference count - the owner relinquishes responsibility
- When an operation that retains a buffer can fail, the code **must release on failure** to prevent leaks

The buggy code violated this contract by retaining without a matching release on the exception path.

---

## The Tests

**Location:** `ambry-rest/src/test/java/com/github/ambry/rest/NettyRequestTest.java`

Added 3 production tests integrated into the permanent test suite:

### 1. `testWriteContentExceptionLeaksRetainedBuffer`
Proves the core bug by:
- Creating a faulty `AsyncWritableChannel` that throws on `write()`
- Adding HttpContent to NettyRequest (triggers `writeContent()`)
- Verifying the exception is thrown
- Properly releasing the test's reference following ownership contract
- Using `NettyByteBufLeakHelper` to detect the leaked retained reference

**Result:** FAILS before fix (leak detected), PASSES after fix

### 2. `testWriteContentSuccessProperlyReleasesBuffer`
Validates the fix doesn't break the success path by:
- Using a working `AsyncWritableChannel` that completes successfully
- Verifying no leaks occur in normal operation

**Result:** PASSES both before and after fix

### 3. `testMultipleContentChunksWithWriteFailureLeakAll`
Demonstrates severity by:
- Succeeding on first 2 chunks, failing on 3rd chunk
- Showing the leak occurs even when previous writes succeeded

**Result:** FAILS before fix (leak detected), PASSES after fix

---

## The Fix

**File:** `ambry-rest/src/main/java/com/github/ambry/rest/NettyRequest.java:494-502`

Wrapped the `write()` call in a try-catch block to release the retained reference if an exception occurs:

```java
// Retain this httpContent so it won't be garbage collected right away. Release it in the callback.
httpContent.retain();
try {
  writeChannel.write(httpContent.content(), new ContentWriteCallback(httpContent, isLast, callbackWrapper));
} catch (Exception e) {
  // If write() throws, callback won't be created, so we must release the retained content
  httpContent.release();
  throw e;
}
allContentReceived = isLast;
```

### Why This Works

- **Success path:** `write()` succeeds → `ContentWriteCallback` created → callback releases on completion
- **Failure path:** `write()` throws → catch block releases immediately → no leak
- **Result:** Reference count properly managed in all cases

---

## Verification

Run the tests:
```bash
./run-writeContent-leak-test.sh
```

Or directly:
```bash
./gradlew :ambry-rest:test \
  --tests "NettyRequestTest.testWriteContentExceptionLeaksRetainedBuffer" \
  --tests "NettyRequestTest.testWriteContentSuccessProperlyReleasesBuffer" \
  --tests "NettyRequestTest.testMultipleContentChunksWithWriteFailureLeakAll"
```

**Expected:** All tests PASS, NettyByteBufLeakHelper reports 0 leaks
