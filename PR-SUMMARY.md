# Fix ByteBuffer Memory Leak in ByteBufferAsyncWritableChannel

## The Bug

`ByteBufferAsyncWritableChannel.write(ByteBuffer)` creates a memory leak by wrapping the input `ByteBuffer` in a Netty `ByteBuf` wrapper without ever releasing it:

```java
ByteBuf wrapper = Unpooled.wrappedBuffer(src);
return write(wrapper, callback);
```

**The problem:** `Unpooled.wrappedBuffer()` creates a `ByteBuf` with `refCnt=1`. According to Netty's reference counting contract, whoever creates or retains a `ByteBuf` is responsible for releasing it. Since `ByteBufferAsyncWritableChannel` creates the wrapper internally, it must release it. The original code never called `wrapper.release()`, causing every call to `write(ByteBuffer)` to leak native memory.

**Why it's critical:** These wrappers reference direct `ByteBuffer`s which allocate native memory that is never garbage collected. Each leak permanently consumes memory until the process terminates.

## The Test

`ByteBufferAsyncWritableChannelTest.testWriteByteBufferReleasesWrapper` proves the bug by:

1. Calling `write(ByteBuffer)` which creates an internal wrapper
2. Using reflection to extract the wrapper `ByteBuf` from the channel's internal queue
3. Verifying the wrapper has `refCnt=1` before resolution
4. Calling `resolveOldestChunk()` to complete the normal flow
5. **Asserting the wrapper has `refCnt=0`** (released) after resolution

Without the fix, the assertion fails because the wrapper still has `refCnt=1`, proving it was never released.

## The Fix

The fix adds a boolean flag to `ChunkData` to mark which ByteBufs are internal wrappers that need releasing:

**1. Add flag to ChunkData:**
```java
private static class ChunkData {
    public final boolean isInternalWrapper;

    private ChunkData(ByteBuf buf, Callback<Long> callback, boolean isInternalWrapper) {
        this.buf = buf;
        this.isInternalWrapper = isInternalWrapper;
        // ...
    }
}
```

**2. Mark wrappers when created:**
```java
public Future<Long> write(ByteBuffer src, Callback<Long> callback) {
    ByteBuf wrapper = Unpooled.wrappedBuffer(src);
    ChunkData chunkData = new ChunkData(wrapper, callback, true);  // Flag = true
    chunks.add(chunkData);
    return chunkData.future;
}

public Future<Long> write(ByteBuf src, Callback<Long> callback) {
    ChunkData chunkData = new ChunkData(src, callback, false);  // Flag = false
    chunks.add(chunkData);
    return chunkData.future;
}
```

**3. Release wrappers when chunks are resolved:**
```java
public void resolveOldestChunk(Exception exception) {
    ChunkData chunkData = chunksAwaitingResolution.poll();
    if (chunkData != null) {
        chunkData.resolveChunk(exception);
        // Release wrapper if it was created internally
        if (chunkData.isInternalWrapper && chunkData.buf != null) {
            chunkData.buf.release();
        }
    }
}
```

The same release logic is also added to `resolveAllRemainingChunks()` to handle the channel close case.

**Why this approach:** We initially tried tracking wrappers in a `Set<ByteBuf>` by object identity, but Netty's leak detection wraps ByteBufs in `AdvancedLeakAwareByteBuf`, breaking identity matching. The flag-based approach is immune to wrapping because the flag lives in `ChunkData`, not the ByteBuf object itself.
