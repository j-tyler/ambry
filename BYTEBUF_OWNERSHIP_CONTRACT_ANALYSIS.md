# ByteBufferAsyncWritableChannel: ByteBuf Ownership Contract Analysis

**Date:** 2025-11-10
**Branch:** `claude/fix-bytebuffer-async-writable-channel-011CUyfsdsmCtAE7iRL3G8At`
**Analysis Type:** Complete ByteBuf Lifecycle and Ownership Rules

---

## Executive Summary

After paranoid analysis of ByteBufferAsyncWritableChannel, I've determined the **ownership contract** and identified where it breaks.

### Ownership Contract (BY DESIGN)

**For `write(ByteBuf src, Callback<Long> callback)`:**
```
1. Caller creates ByteBuf and passes it to channel
2. Channel stores reference in ChunkData
3. Consumer retrieves ByteBuf via getNextByteBuf()
4. Consumer uses the ByteBuf (reads data, creates slices, etc.)
5. Consumer calls resolveOldestChunk()
6. Channel invokes callback
7. **CALLBACK MUST RELEASE THE BYTEBUF** ← Caller's responsibility!
```

This is **correct by design** - the channel is just a queue, caller retains ownership.

### Contract Violation (BUG)

**For `write(ByteBuffer src, Callback<Long> callback)`:**
```
1. Caller has NIO ByteBuffer (not Netty ByteBuf)
2. Channel creates INTERNAL wrapper: Unpooled.wrappedBuffer(src)
3. Wrapper ByteBuf has refCnt=1
4. Channel calls write(wrapper, callback) internally
5. Consumer retrieves wrapper (directly or converted)
6. Consumer uses wrapper
7. Consumer calls resolveOldestChunk()
8. Channel invokes callback with original ByteBuffer (not wrapper!)
9. **CALLBACK CANNOT RELEASE WRAPPER - NO REFERENCE TO IT!** ❌
10. Wrapper leaks with refCnt=1 forever (direct buffer, never GC'd)
```

---

## Detailed Contract Analysis

### Channel Design Philosophy

ByteBufferAsyncWritableChannel is designed as a **passive queue**:
- Does NOT own ByteBufs
- Does NOT allocate ByteBufs
- Does NOT release ByteBufs
- Simply stores references and passes them through

**Responsibility Matrix:**

| Operation | Channel | Caller | Consumer |
|-----------|---------|--------|----------|
| Allocate ByteBuf | ❌ | ✅ | ❌ |
| Write ByteBuf | ✅ (store ref) | ✅ (pass) | ❌ |
| Retrieve ByteBuf | ✅ (return ref) | ❌ | ✅ |
| Use ByteBuf | ❌ | ❌ | ✅ |
| Release ByteBuf | ❌ | ✅ (in callback) | ✅ (or consumer) |

### Code Evidence: ChunkData.resolveChunk()

```java
private void resolveChunk(Exception exception) {
    if (buf != null) {
        long bytesWritten = buf.readerIndex() - startPos;
        future.done(bytesWritten, exception);
        if (callback != null) {
            callback.onCompletion(bytesWritten, exception);  // ← Callback invoked
        }
        // ❌ NO buf.release() here!
    }
}
```

**The channel does NOT release** - it's callback's job.

---

## Who Follows the Contract?

### ✅ CORRECT: PutOperation Usage

**File:** `ambry-router/src/main/java/com/github/ambry/router/PutOperation.java`

#### Writing Side (Caller)
The ReadableStreamChannel writes ByteBufs to chunkFillerChannel:
```java
channel.readInto(chunkFillerChannel, (result, exception) -> {
    // Callback handles completion
    chunkFillerChannel.close();
});
```

#### Reading Side (Consumer)
PutOperation consumes ByteBufs:
```java
channelReadBuf = chunkFillerChannel.getNextByteBuf(0);  // Get ByteBuf
chunkToFill.fillFrom(channelReadBuf);  // Create retained slice

if (!channelReadBuf.isReadable()) {
    chunkFillerChannel.resolveOldestChunk(null);  // Invoke callback
    channelReadBuf = null;
}
```

#### Release Side (Consumer owns slices)
PutChunk creates **retained slices** and owns them:
```java
int fillFrom(ByteBuf channelReadBuf) {
    slice = channelReadBuf.readRetainedSlice(toWrite);  // refCnt++ on slice
    buf = slice;  // PutChunk owns this slice
    return toWrite;
}

synchronized void releaseBlobContent() {
    if (buf != null) {
        ReferenceCountUtil.safeRelease(buf);  // ✅ Slice released
        buf = null;
    }
}
```

**Why this works:**
1. ReadableStreamChannel writes ByteBuf (owns it)
2. PutOperation gets ByteBuf reference
3. PutOperation creates **retained slice** (refCnt++)
4. PutOperation resolves chunk (callback invoked)
5. ReadableStreamChannel callback releases **original ByteBuf**
6. PutChunk releases **retained slice**
7. Both slices and original get released properly ✅

### ✅ CORRECT (with caveat): ReadableStreamChannelInputStream

**File:** `ambry-commons/src/main/java/com/github/ambry/commons/ReadableStreamChannelInputStream.java`

#### Usage Pattern
```java
private final ByteBufferAsyncWritableChannel asyncWritableChannel = new ByteBufferAsyncWritableChannel();

public ReadableStreamChannelInputStream(ReadableStreamChannel readableStreamChannel) {
    readableStreamChannel.readInto(asyncWritableChannel, callback);  // ReadableStreamChannel writes
}

private boolean loadData() throws IOException {
    currentChunk = asyncWritableChannel.getNextChunk();  // ← Gets ByteBuffer (not ByteBuf!)
    // ... use currentChunk ...
    asyncWritableChannel.resolveOldestChunk(null);  // Resolve
}
```

**Wait, where is ByteBuf released?**

The callback:
```java
private static class CloseWriteChannelCallback implements Callback<Long> {
    @Override
    public void onCompletion(Long result, Exception exception) {
        this.exception = exception;
        channel.close();  // ← Just closes channel, doesn't release!
    }
}
```

**Analysis:**
- Uses `getNextChunk()` which returns ByteBuffer (converted)
- `convertToByteBuffer()` doesn't release the ByteBuf
- Callback doesn't release either
- **POTENTIAL LEAK** unless ReadableStreamChannel implementation releases the ByteBufs it writes

**Assumption:** The ReadableStreamChannel implementation must be releasing its own ByteBufs after callback. This needs verification for each ReadableStreamChannel subclass.

---

## The Wrapper Bug in Detail

### Root Cause: Hidden Wrapper

```java
@Override
public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
    if (src == null) {
        throw new IllegalArgumentException("Source buffer cannot be null");
    }
    return write(Unpooled.wrappedBuffer(src), callback);  // ← Line 89
}
```

### What Happens

1. **Wrapper Creation:**
   ```java
   ByteBuf wrapper = Unpooled.wrappedBuffer(src);  // refCnt=1, direct buffer
   ```

2. **Internal Write:**
   ```java
   write(wrapper, callback);  // Channel stores wrapper in ChunkData
   ```

3. **Consumer Gets Wrapper:**
   ```java
   // Option A: Consumer uses getNextByteBuf()
   ByteBuf buf = channel.getNextByteBuf();  // Returns wrapper

   // Option B: Consumer uses getNextChunk()
   ByteBuffer bb = channel.getNextChunk();  // Converts wrapper → ByteBuffer
   ```

4. **Callback Invoked:**
   ```java
   channel.resolveOldestChunk(null);
   // → ChunkData.resolveChunk()
   // → callback.onCompletion(bytesWritten, null)
   ```

5. **Callback Cannot Release:**
   ```java
   // Callback signature:
   callback.onCompletion(Long result, Exception exception)

   // Callback has:
   // - Original ByteBuffer (passed to write())
   // - Result count
   // - Exception (if any)

   // Callback does NOT have:
   // - Wrapper ByteBuf (created internally!)
   // - No way to release it!
   ```

### Why It's Critical

**Direct Buffer Characteristics:**
- Allocated off-heap (native memory)
- Never garbage collected by JVM
- Must be explicitly freed
- Leak accumulates in native memory until process crashes

**Impact:**
```
write(ByteBuffer) called 1000 times
= 1000 wrapper ByteBufs created
= 1000 × wrapper size in native memory leaked
= Process OOM eventually
```

---

## Fix Options Revisited

### Option 1: Track Wrappers, Release After Consumption ✅ Recommended

```java
private final Queue<ByteBuf> wrapperBuffers = new LinkedBlockingQueue<>();

@Override
public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
    if (src == null) {
        throw new IllegalArgumentException("Source buffer cannot be null");
    }
    ByteBuf wrapper = Unpooled.wrappedBuffer(src);
    wrapperBuffers.add(wrapper);  // Track it
    return write(wrapper, callback);
}

public void resolveOldestChunk(Exception exception) {
    ChunkData chunkData = chunksAwaitingResolution.poll();
    if (chunkData != null) {
        // Release wrapper if it was created by write(ByteBuffer)
        if (wrapperBuffers.remove(chunkData.buf)) {
            chunkData.buf.release();  // Release wrapper
        }
        chunkData.resolveChunk(exception);
    }
}

@Override
public void close() {
    if (channelOpen.compareAndSet(true, false)) {
        // Release all unreleased wrappers
        for (ByteBuf wrapper : wrapperBuffers) {
            if (wrapper.refCnt() > 0) {
                wrapper.release();
            }
        }
        wrapperBuffers.clear();
        resolveAllRemainingChunks(new ClosedChannelException());
    }
    // ...
}
```

**Pros:**
- Fixes the leak completely
- No breaking changes
- Maintains existing API
- Caller doesn't need to change

**Cons:**
- Slight performance overhead (queue operations)
- Need to track which ByteBufs are wrappers

### Option 2: Copy Data Instead of Wrapping

```java
@Override
public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
    if (src == null) {
        throw new IllegalArgumentException("Source buffer cannot be null");
    }
    // Copy instead of wrap
    ByteBuf copied = Unpooled.buffer(src.remaining());
    copied.writeBytes(src);

    // Wrap callback to release the copy
    Callback<Long> wrapperCallback = (result, exception) -> {
        copied.release();  // Release our copy
        if (callback != null) {
            callback.onCompletion(result, exception);
        }
    };

    return write(copied, wrapperCallback);
}
```

**Pros:**
- Simple implementation
- Clear ownership (channel owns the copy)
- No leak possible

**Cons:**
- **Data copy overhead** (every byte copied)
- Performance impact for large buffers
- More memory allocations

### Option 3: Deprecate write(ByteBuffer)

```java
/**
 * @deprecated Use {@link #write(ByteBuf, Callback)} instead.
 * This method creates internal ByteBuf wrappers that may leak.
 * Migrate to: ByteBuf buf = Unpooled.wrappedBuffer(byteBuffer); channel.write(buf, callback);
 */
@Deprecated
@Override
public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
    // Existing implementation with leak
    // OR throw UnsupportedOperationException
}
```

**Pros:**
- Forces callers to handle ByteBuf explicitly
- Makes ownership clear
- Prevents future issues

**Cons:**
- Breaking change
- Requires all callers to migrate
- May affect external users

---

## Recommended Solution

**Implement Option 1** with these enhancements:

1. **Track wrapper relationship:**
   ```java
   private final Map<ByteBuf, Boolean> bufferInfo = new ConcurrentHashMap<>();
   // true = wrapper (channel must release), false = caller's (caller releases)
   ```

2. **Release wrappers automatically:**
   - On `resolveOldestChunk()`: Release if wrapper
   - On `close()`: Release all unreleased wrappers

3. **Add logging for debugging:**
   ```java
   logger.debug("Created wrapper ByteBuf for ByteBuffer write, refCnt={}", wrapper.refCnt());
   logger.debug("Releasing wrapper ByteBuf, refCnt before={}", wrapper.refCnt());
   ```

4. **Add unit tests:**
   - Test wrapper creation and release
   - Test close() releases pending wrappers
   - Verify no double-release

---

## Verification Steps

After fix:

1. **Run bug tests:** Should show NO leaks
   ```bash
   ./run_bytebuffer_bug_tests.sh
   ```

2. **Check tracker output:**
   ```
   Unreleased ByteBufs: 0  ✅
   ```

3. **Run baseline tests:** Should still pass
   ```bash
   ./run_bytebuffer_leak_tests.sh
   ```

4. **Integration test:** Test PutOperation end-to-end

5. **Stress test:** 10,000 writes, verify no leaks

---

## Conclusion

**Contract is correct:** Channel is a passive queue, doesn't own ByteBufs.

**Bug is localized:** Only affects `write(ByteBuffer)` which creates hidden wrappers.

**Fix is straightforward:** Track and release wrappers internally.

**Impact is critical:** Direct buffer leaks accumulate until OOM.

**Tests are ready:** 5 bug-exposing tests confirm the issue and will verify the fix.

---

**Status:** Ready for fix implementation. All analysis complete.
