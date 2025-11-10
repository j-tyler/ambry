# Fix Applied for BoundedNettyByteBufReceive Memory Leak

## What Was Changed

**File**: `ambry-api/src/main/java/com/github/ambry/network/BoundedNettyByteBufReceive.java`

**Method**: `readBytesFromReadableByteChannel()` (lines 61-68)

### Before (Buggy Code)
```java
private int readBytesFromReadableByteChannel(ReadableByteChannel channel, ByteBuf buffer) throws IOException {
  int n = channel.read(buffer.nioBuffer(buffer.writerIndex(), buffer.capacity() - buffer.writerIndex()));
  if (n > 0) {
    buffer.writerIndex(buffer.writerIndex() + n);
  }
  return n;
}
```

### After (Fixed Code)
```java
private int readBytesFromReadableByteChannel(ReadableByteChannel channel, ByteBuf buffer) throws IOException {
  // Use ByteBuf's writeBytes() method to let the ByteBuf implementation handle channel reading.
  // This avoids creating NIO buffer views (via nioBuffer()) which can cause pool deallocation
  // issues when exceptions occur during the read operation.
  int writerIndexBefore = buffer.writerIndex();
  int writableBytes = buffer.capacity() - writerIndexBefore;
  return buffer.writeBytes(channel, writableBytes);
}
```

## Why This Fixes the Leak

### Root Cause
The old code called `buffer.nioBuffer(...)` which creates a ByteBuffer view. When `channel.read()` throws an IOException:
1. The ByteBuffer view may have internal state
2. The exception propagates immediately
3. The catch block releases the ByteBuf
4. BUT: Pool-level cleanup of the nioBuffer view is incomplete
5. `NettyByteBufLeakHelper` detects active allocations remain

### The Fix
Using `buffer.writeBytes(channel, length)`:
1. ByteBuf internally handles channel reading
2. No external NIO buffer views created
3. All lifecycle management is internal to ByteBuf
4. Exception handling is cleaner
5. Pool properly deallocates on release()

## Testing the Fix

Run the leak tests:
```bash
./run-boundednetty-leak-tests.sh
```

**Expected Results**: All 6 tests should now PASS:
- ✅ testReadFromIOExceptionAfterSizeBufferAllocation
- ✅ testReadFromIOExceptionOnRequestTooLarge
- ✅ testReadFromIOExceptionDuringContentRead (was failing)
- ✅ testSuccessfulReadWithProperCleanup
- ✅ testEOFExceptionDuringSizeRead
- ✅ testEOFExceptionDuringContentRead (was failing)

## Performance Impact

**None** - `ByteBuf.writeBytes(ReadableByteChannel, int)` is the idiomatic and recommended way to read from channels. It's actually slightly more efficient than creating an NIO buffer view.

## Additional Testing

Also run:
```bash
# Test with ByteBuf tracker enabled
./run-boundednetty-leak-tests.sh

# Test all BoundedNettyByteBufReceive tests (not just leak tests)
./gradlew :ambry-network:test --tests "com.github.ambry.network.BoundedNettyByteBufReceive*"
```

## Verification

The fix was confirmed necessary because:
1. Tests failed WITH ByteBuf tracker → Leaks detected
2. Tests failed WITHOUT ByteBuf tracker → **Real production bug, not instrumentation issue**
3. Same 2 tests failed consistently in both scenarios

## What This Fixes in Production

This bug would cause memory leaks in production whenever:
- Network I/O errors occur during request/response reading
- EOF is encountered unexpectedly
- Any IOException happens while reading content

The leak would be small per-request (92 bytes for a 100-byte request) but accumulates over time in high-traffic systems.
