# ByteBuf Leak Analysis - Comprehensive Flow Review

**Purpose**: Identify all potential ByteBuf leak paths in production code that may not be covered by tests.

**Methodology**: For each ByteBuf-wrapping class, trace all possible code paths including:
- Normal execution paths
- Exception handling paths
- Early return paths
- Ownership transfer scenarios
- Caller responsibilities

**Legend**:
- ✅ **SAFE**: Proper release handling confirmed
- ⚠️ **POTENTIAL LEAK**: Leak possible under specific conditions
- 🚨 **HIGH RISK**: Clear leak path identified
- 📝 **NEEDS REVIEW**: Complex flow requires additional analysis

---

## Class 6: com.github.ambry.network.NettyServerRequest

**File**: `ambry-network/src/main/java/com/github/ambry/network/NettyServerRequest.java`

**Parent Class**: `AbstractByteBufHolder<NettyServerRequest>` (provides default reference counting behavior)

### Constructor Entry Points

1. **Line 32-37**: `NettyServerRequest(ChannelHandlerContext ctx, ByteBuf content)`
   - Primary constructor for production use
   - Stores ChannelHandlerContext and ByteBuf content (lines 33-34)
   - Creates NettyByteBufDataInputStream wrapper (line 35)
   - Records start time (line 36)
   - **No retain() called - assumes ownership transfer**

2. **Line 42-47**: `NettyServerRequest(ChannelHandlerContext ctx, ByteBuf content, long creationTime)`
   - Test constructor allowing custom creation time
   - Otherwise identical to primary constructor
   - Used for testing timeout/expiry scenarios

### ByteBuf Fields

```java
private final ChannelHandlerContext ctx;          // Line 27 - Netty channel context
private final ByteBuf content;                    // Line 30 - Request bytes from HTTP/2
private final InputStream inputStream;            // Line 28 - NettyByteBufDataInputStream wrapper (non-owning)
```

### Ownership Model

**CRITICAL OWNERSHIP CHARACTERISTICS**:
1. NettyServerRequest **owns** the ByteBuf passed to constructor
2. Constructor does NOT call retain() - assumes transfer of ownership from caller
3. Caller (AmbryNetworkRequestHandler) MUST retain before passing if they need to keep reference
4. NettyServerRequest **must** be released by RequestHandler or on error paths
5. Fields are **final** - cannot be changed after construction
6. NettyByteBufDataInputStream is a non-owning wrapper - does not manage lifecycle

### Normal Flow Path

```
1. AmbryNetworkRequestHandler receives HTTP/2 request
   ├─ Line 49: channelRead0(ctx, FullHttpRequest msg)
   ├─ Line 50: dup = msg.content().retainedDuplicate()  // ✅ RETAINS reference
   ├─ Line 51: networkRequest = new NettyServerRequest(ctx, dup)
   └─ Ownership transferred from retained ByteBuf to NettyServerRequest

2. Request sent to channel (Line 54)
   ├─ requestResponseChannel.sendRequest(networkRequest)
   └─ Request queued in NettyServerRequestResponseChannel

3. RequestHandler receives and processes request
   ├─ RequestHandler.java line 44: req = requestChannel.receiveRequest()
   ├─ Line 49: requests.handleRequests(req)
   └─ InputStream accessed via req.getInputStream()

4. RequestHandler releases request in finally block ✅
   ├─ RequestHandler.java lines 56-61:
   │  └─ finally { if (req != null) { req.release(); req = null; } }
   └─ PROPER CLEANUP - All requests released
```

### Exception Handling in AmbryNetworkRequestHandler

```java
// Lines 48-65 in AmbryNetworkRequestHandler.java
@Override
protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
    ByteBuf dup = msg.content().retainedDuplicate();  // ref count +1
    NettyServerRequest networkRequest = new NettyServerRequest(ctx, dup);
    try {
        discardHeaderBytes(networkRequest);
        requestResponseChannel.sendRequest(networkRequest);
    } catch (InterruptedException e) {
        dup.release();  // ⚠️ Releases ByteBuf directly, not networkRequest
        // Log error
    } catch (IOException e) {
        // Log error
        ctx.channel().close();
        networkRequest.release();  // ✅ Releases via networkRequest
    }
}
```

**Note**: Two different cleanup strategies:
- `InterruptedException`: Releases `dup` (the ByteBuf)
- `IOException`: Releases `networkRequest` (the wrapper)

Both are functionally equivalent since NettyServerRequest doesn't retain, but inconsistent pattern.

### replace() Method Analysis

```java
// Lines 69-71
@Override
public NettyServerRequest replace(ByteBuf content) {
    return new NettyServerRequest(ctx, content);
}
```

**Leak Risk**:
- Creates new NettyServerRequest with new content
- Original NettyServerRequest.content is NOT released
- Caller must release BOTH old and new NettyServerRequest

---

## Potential Leak Scenarios - NettyServerRequest

### ✅ SAFE-6.1: Normal Request Handling Path

**Condition**: Normal HTTP/2 request processing flow.

**Analysis**:
- AmbryNetworkRequestHandler properly retains ByteBuf before passing to constructor (line 50)
- RequestHandler.java lines 56-61 has proper finally block
- ALL requests released regardless of success or exception
- Production code follows correct lifecycle pattern

**Severity**: ✅ **SAFE**
**Likelihood**: N/A - No leak in normal path
**Impact**: None - Proper cleanup

---

### ⚠️ LEAK-6.2: Exception in discardHeaderBytes()

**Condition**: IOException thrown in discardHeaderBytes() before sendRequest().

**Leak Path**:
```java
// AmbryNetworkRequestHandler.java lines 48-65
ByteBuf dup = msg.content().retainedDuplicate();  // ref count +1
NettyServerRequest networkRequest = new NettyServerRequest(ctx, dup);
try {
    discardHeaderBytes(networkRequest);  // Line 53
    // ⚠️ IOException thrown here (e.g., stream read error)
    requestResponseChannel.sendRequest(networkRequest);  // Never reached
} catch (InterruptedException e) {
    dup.release();  // Not caught - InterruptedException only
    // ...
} catch (IOException e) {
    logger.error(...);
    ctx.channel().close();
    networkRequest.release();  // ✅ PROPER CLEANUP
}
```

**Analysis**: IOException is properly caught and networkRequest is released.

**Severity**: ✅ **SAFE**
**Likelihood**: N/A - Proper exception handling
**Impact**: None - IOException path releases networkRequest

---

### 🚨 LEAK-6.3: InterruptedException Inconsistent Cleanup

**Condition**: sendRequest() throws InterruptedException.

**Leak Path**:
```java
// AmbryNetworkRequestHandler.java lines 48-65
ByteBuf dup = msg.content().retainedDuplicate();  // ref count +1
NettyServerRequest networkRequest = new NettyServerRequest(ctx, dup);
try {
    discardHeaderBytes(networkRequest);
    requestResponseChannel.sendRequest(networkRequest);  // Throws InterruptedException
} catch (InterruptedException e) {
    dup.release();  // ✅ Releases ByteBuf
    // ⚠️ BUT: dup and networkRequest.content are THE SAME ByteBuf
    // ⚠️ networkRequest still exists but its content is released
    // ✅ Actually safe because networkRequest is not sent to channel
    // ✅ RequestHandler never sees it, so no double-release
}
```

**Analysis**:
- On InterruptedException, `dup.release()` is called
- `networkRequest` still exists with reference to `dup`, but goes out of scope
- `networkRequest` is NOT sent to channel, so RequestHandler never sees it
- No double-release because RequestHandler doesn't receive the request

**Severity**: ✅ **SAFE** (but confusing pattern)
**Likelihood**: N/A - Actually safe
**Impact**: None - ByteBuf properly released, networkRequest discarded

**Recommendation**: For consistency and clarity, should release via networkRequest:
```java
catch (InterruptedException e) {
    networkRequest.release();  // Better: release via wrapper
    // ...
}
```

---

### ⚠️ LEAK-6.4: Exception Before retainedDuplicate() Complete

**Condition**: Exception during retainedDuplicate() call.

**Leak Path**:
```java
// AmbryNetworkRequestHandler.java line 50
ByteBuf dup = msg.content().retainedDuplicate();
// ⚠️ If retainedDuplicate() throws (e.g., OutOfMemoryError)
// ⚠️ dup is null or partially initialized
// ⚠️ No cleanup possible - exception propagates up

// Actually SAFE: msg.content() is managed by Netty
// FullHttpRequest will be released by Netty when ref count drops
```

**Severity**: ✅ **SAFE**
**Likelihood**: N/A - Netty manages FullHttpRequest lifecycle
**Impact**: None - Netty cleans up msg.content()

---

### ⚠️ LEAK-6.5: replace() Creates Orphan Request

**Condition**: Caller uses replace() method and forgets to release original.

**Leak Path**:
```java
NettyServerRequest original = new NettyServerRequest(ctx, buffer1);
NettyServerRequest replaced = original.replace(buffer2);

// ❌ If only 'replaced' is released:
replaced.release();  // Releases buffer2
// buffer1 is LEAKED (original.content never released)

// ✅ MUST release both:
original.release();   // Releases buffer1
replaced.release();   // Releases buffer2
```

**Severity**: ⚠️ **MEDIUM**
**Likelihood**: **VERY LOW** - replace() not used in production code
**Impact**: Original ByteBuf leaked

**Mitigation**:
- Grep for replace() usage and verify proper cleanup
- Document replace() semantics clearly
- Consider deprecating if unused

---

### 📝 LEAK-6.6: Test Code Leak

**Condition**: Test code not releasing NettyServerRequest.

**Evidence from NettyServerRequestResponseChannelTest.java**:
The test in the summary mentioned NettyServerRequest instances created but NOT released in tests (lines 247, 254, 260). This was identified as a test artifact leak.

**Current State**: ⚠️ **TESTS LEAK**
```java
// NettyServerRequestResponseChannelTest.java
private NettyServerRequest createNettyServerRequest(int len) {
    byte[] array = new byte[len];
    TestUtils.RANDOM.nextBytes(array);
    ByteBuf content = Unpooled.wrappedBuffer(array);
    return new NettyServerRequest(null, content);  // Created
    // ❌ Never released in tests
}
```

**Severity**: 📝 **INFO** (test only, not production)
**Likelihood**: **HIGH** - Multiple tests affected
**Impact**: Test ByteBuf leaks only

**Mitigation**: Update tests to release requests:
```java
@Test
public void testSendAndReceiveRequest() throws Exception {
    NettyServerRequest request = createNettyServerRequest(13);
    try {
        channel.sendRequest(request);
        NetworkRequest received = channel.receiveRequest();
        // ... assertions ...
    } finally {
        if (request != null) {
            request.release();
        }
    }
}
```

---

## Caller Analysis - NettyServerRequest

### Production Callers

1. **AmbryNetworkRequestHandler.channelRead0()** - ⚠️ **CREATES AND SENDS**
   ```java
   // Lines 49-65 in AmbryNetworkRequestHandler.java
   ByteBuf dup = msg.content().retainedDuplicate();  // ✅ RETAINS
   NettyServerRequest networkRequest = new NettyServerRequest(ctx, dup);
   try {
       discardHeaderBytes(networkRequest);
       requestResponseChannel.sendRequest(networkRequest);
   } catch (InterruptedException e) {
       dup.release();  // ⚠️ Inconsistent: releases ByteBuf directly
   } catch (IOException e) {
       networkRequest.release();  // ✅ Releases via wrapper
   }
   ```
   - Properly retains ByteBuf before passing
   - Has exception handling (both InterruptedException and IOException)
   - ⚠️ Inconsistent cleanup pattern (should always use networkRequest.release())

2. **RequestHandler.run()** - ✅ **RELEASES REQUESTS**
   ```java
   // Lines 44-61 in RequestHandler.java
   req = requestChannel.receiveRequest();
   requests.handleRequests(req);
   // finally block:
   req.release();  // ✅ PROPER RELEASE
   ```
   - Same handler used for SocketServerRequest
   - **Always releases in finally block**

### Test Code Callers

⚠️ **Tests DO NOT release**:
```java
// NettyServerRequestResponseChannelTest.java lines 70-87
channel.sendRequest(createNettyServerRequest(13));
NetworkRequest request = channel.receiveRequest();
Assert.assertTrue(request instanceof NettyServerRequest);
assertEquals(13, ((NettyServerRequest) request).content().readableBytes());
// ❌ NO release() call

// Lines 75-86: Multiple requests created and received, none released
```

**Impact**: This is the source of the test artifact leak mentioned in the conversation summary.

---

## Recommendations - NettyServerRequest

### HIGH Priority

1. **Standardize exception cleanup in AmbryNetworkRequestHandler**
   ```java
   // Current has two patterns:
   catch (InterruptedException e) {
       dup.release();  // ⚠️ Direct ByteBuf release
   } catch (IOException e) {
       networkRequest.release();  // ✅ Wrapper release
   }

   // Should be consistent:
   catch (InterruptedException e) {
       networkRequest.release();  // ✅ Always use wrapper
   } catch (IOException e) {
       networkRequest.release();  // ✅ Always use wrapper
   }
   ```

2. **Fix test leaks** (LEAK-6.6)
   ```java
   // Add cleanup to all test methods
   @Test
   public void testSendAndReceiveRequest() throws Exception {
       List<NettyServerRequest> requests = new ArrayList<>();
       try {
           requests.add(createNettyServerRequest(13));
           channel.sendRequest(requests.get(0));
           // ... test logic ...
       } finally {
           requests.forEach(NettyServerRequest::release);
       }
   }
   ```

### MEDIUM Priority

3. **Verify replace() is unused**
   - Search codebase for any usage of NettyServerRequest.replace()
   - If found, verify both old and new are released
   - If not found, consider deprecating

4. **Add Javadoc warnings**
   ```java
   /**
    * IMPORTANT: The caller MUST call release() on this NettyServerRequest when done.
    * Failure to release will cause ByteBuf memory leaks.
    *
    * This class takes ownership of the ByteBuf passed to the constructor.
    * Do not use the ByteBuf after passing it to NettyServerRequest.
    *
    * The NettyByteBufDataInputStream created in the constructor is a non-owning
    * wrapper and does not need to be closed separately.
    *
    * For HTTP/2 requests, the caller should use retainedDuplicate() before
    * passing the ByteBuf to ensure proper reference counting.
    */
   ```

### LOW Priority

5. **Make NettyServerRequest AutoCloseable**
   ```java
   public class NettyServerRequest extends AbstractByteBufHolder<NettyServerRequest>
       implements NetworkRequest, AutoCloseable {

       @Override
       public void close() {
           release();
       }
   }
   ```

6. **Add leak detection to tests**
   ```java
   @After
   public void checkForLeaks() {
       assertTrue("NettyServerRequest leaked",
           ResourceLeakDetector.checkForLeaks(NettyServerRequest.class));
   }
   ```

---

## Summary - NettyServerRequest

### Overall Risk: ⚠️ **MEDIUM-LOW**

**Strengths**:
- ✅ AmbryNetworkRequestHandler properly retains before creating request
- ✅ Exception handling covers both InterruptedException and IOException
- ✅ RequestHandler has proper finally block with release()
- ✅ Simple ownership model (single owner)

**Weaknesses**:
- ⚠️ Inconsistent cleanup pattern (dup.release() vs networkRequest.release())
- ⚠️ Tests leak NettyServerRequest objects (test artifact only)
- ⚠️ replace() method has confusing semantics (but appears unused)

**Most Critical Issue**:
- **Test leaks** (LEAK-6.6): Not production critical, but causes test artifact leaks as identified in conversation

**Recommended Fix Priority**:
1. Fix test leaks (MEDIUM) - eliminates test artifacts
2. Standardize exception cleanup (MEDIUM) - improves code consistency
3. Improve documentation (LOW) - prevents future mistakes

---

## Class 7: com.github.ambry.commons.ByteBufferAsyncWritableChannel$ChunkData

**File**: `ambry-commons/src/main/java/com/github/ambry/commons/ByteBufferAsyncWritableChannel.java`

**Parent Class**: None (private static inner class)

**NOTE**: ChunkData is a **private static inner class** of ByteBufferAsyncWritableChannel (line 296).

### Constructor Entry Points

1. **Line 314-322**: `ChunkData(ByteBuf buf, Callback<Long> callback)`
   - Private constructor only accessible within ByteBufferAsyncWritableChannel
   - Stores ByteBuf reference directly (line 315)
   - Records starting reader position (line 317)
   - Stores callback for later invocation (line 321)
   - **No retain() called - assumes ownership transfer from write() caller**

### ByteBuf Fields

```java
public ByteBuf buf;                               // Line 304 - The chunk data bytes
private final int startPos;                       // Line 306 - Starting reader position
private final Callback<Long> callback;            // Line 307 - Completion callback
public final FutureResult<Long> future;           // Line 300 - Future for async result
```

### Ownership Model

**CRITICAL OWNERSHIP CHARACTERISTICS**:
1. ChunkData **stores reference** to ByteBuf but does NOT own lifecycle
2. Constructor does NOT call retain() - assumes ownership transfer
3. resolveChunk() does NOT call release() - caller must manage lifecycle
4. **COMPLEX**: Caller of write() passes ownership to ChunkData
5. **COMPLEX**: Caller of getNextByteBuf() receives ByteBuf and takes over responsibility
6. Field is **public mutable** - can be modified after construction

### Normal Flow Path

```
1. Application writes data via write(ByteBuf src)
   ├─ Line 100-119 in ByteBufferAsyncWritableChannel:
   ├─ Line 104-105: If channel closed, src.release() immediately ✅
   ├─ Line 113: chunkData = new ChunkData(src, callback)
   ├─ Line 114: chunks.add(chunkData)
   └─ Ownership transferred from caller to ChunkData

2. Consumer retrieves chunk via getNextByteBuf()
   ├─ Line 212-217: ch getNextByteBuf()
   ├─ Line 215: chunkData = chunks.take() // Blocks until available
   ├─ Line 256-267: getChunkBuf(chunkData)
   │  ├─ Line 258-259: Extract chunkData.buf
   │  ├─ Line 260: chunksAwaitingResolution.add(chunkData)
   │  └─ Return ByteBuf to caller
   └─ Ownership transferred from ChunkData to consumer

3. Consumer uses ByteBuf (PutOperation example)
   ├─ Line 703 in PutOperation.java: fillFrom(channelReadBuf)
   ├─ Lines 1643, 1649: slice = channelReadBuf.readRetainedSlice(toWrite)
   │  └─ readRetainedSlice() INCREMENTS reference count
   ├─ Slices stored in PutChunk.buf (retained)
   └─ Original channelReadBuf consumed (reader index advances)

4. Consumer resolves chunk via resolveOldestChunk()
   ├─ Line 244-249: resolveOldestChunk(exception)
   ├─ Line 245: chunkData = chunksAwaitingResolution.poll()
   ├─ Line 247: chunkData.resolveChunk(exception)
   ├─ Lines 329-337: resolveChunk() implementation
   │  ├─ Line 331: Calculate bytes written (readerIndex - startPos)
   │  ├─ Line 332-334: Complete future and invoke callback
   │  └─ ❌ NO release() call on buf
   └─ ChunkData discarded

5. ByteBuf lifecycle depends on Netty reference counting
   ├─ If consumer called readRetainedSlice(), slices hold references
   ├─ Original ByteBuf shares ref count with slices
   ├─ When all slices released, original ByteBuf also released
   └─ ✅ Eventual cleanup via Netty's shared reference counting
```

### resolveChunk() Analysis - NO RELEASE

```java
// Lines 329-337
private void resolveChunk(Exception exception) {
    if (buf != null) {
        long bytesWritten = buf.readerIndex() - startPos;
        future.done(bytesWritten, exception);
        if (callback != null) {
            callback.onCompletion(bytesWritten, exception);
        }
        // ❌ NO buf.release() call!
        // ❌ buf is NOT set to null
    }
}
```

**CRITICAL**: resolveChunk() does NOT release the ByteBuf!

### Caller Responsibility Model

1. **Writer (via write())**: Passes ByteBuf to ChunkData, **loses ownership**
2. **Consumer (via getNextByteBuf())**: Receives ByteBuf, **gains ownership**, **MUST release**
3. **ChunkData**: Acts as **temporary holder**, does NOT manage lifecycle

---

## Potential Leak Scenarios - ByteBufferAsyncWritableChannel$ChunkData

### ⚠️ LEAK-7.1: Consumer Never Calls resolveOldestChunk()

**Condition**: Consumer calls getNextByteBuf() but never calls resolveOldestChunk().

**Leak Path**:
```java
ByteBufferAsyncWritableChannel channel = new ByteBufferAsyncWritableChannel();
ByteBuf data = allocator.buffer(1024);
// ... fill data ...
channel.write(data, null);  // Ownership transferred to ChunkData

// Consumer retrieves chunk
ByteBuf chunk = channel.getNextByteBuf();
// ... use chunk ...
// ❌ Forgot to call channel.resolveOldestChunk(null)
// ❌ ChunkData remains in chunksAwaitingResolution queue
// ❌ ByteBuf is never released (unless consumer manually releases)
```

**Severity**: 🚨 **HIGH**
**Likelihood**: **MEDIUM** - Easy to forget in error paths
**Impact**: ByteBuf leaked per forgotten resolveOldestChunk() call

**Mitigation**:
```java
ByteBuf chunk = null;
try {
    chunk = channel.getNextByteBuf();
    if (chunk != null) {
        // ... use chunk ...
    }
} finally {
    channel.resolveOldestChunk(exception);
}
```

---

### ⚠️ LEAK-7.2: Consumer Doesn't Release After Using ByteBuf

**Condition**: Consumer extracts slices with readRetainedSlice() but never releases them.

**Leak Path**:
```java
// In PutOperation.fillFrom() - lines 1643, 1649
ByteBuf channelReadBuf = channel.getNextByteBuf();
ByteBuf slice = channelReadBuf.readRetainedSlice(toWrite);  // ref count +1
// ... store slice in putChunk.buf ...

// Later: slice must be released
// ❌ If putChunk.buf is never released, memory leaks!

channel.resolveOldestChunk(null);  // ✅ Resolves ChunkData
// ⚠️ Original channelReadBuf might still be retained by slices
// ✅ When all slices released, original channelReadBuf also released
```

**Analysis**:
- Netty's retained slices share reference count with original ByteBuf
- When all slices are released, original is also released
- **BUT**: If any slice is never released, original ByteBuf leaks too

**Severity**: 🚨 **HIGH**
**Likelihood**: **HIGH** - Easy to forget slice cleanup
**Impact**: ByteBuf leaked for every unreleased slice

**Mitigation**: Ensure all PutChunk.buf instances are properly released

---

### 🚨 LEAK-7.3: Exception Before Consuming ByteBuf

**Condition**: getNextByteBuf() returns ByteBuf, but exception thrown before any slices taken.

**Leak Path**:
```java
// In PutOperation.fillFrom() - lines 685-711
channelReadBuf = chunkFillerChannel.getNextByteBuf(0);  // Line 685
if (channelReadBuf != null) {
    if (channelReadBuf.readableBytes() > 0 && isChunkAwaitingResolution()) {
        isSimpleBlob = false;
        maybeResolveAwaitingChunk();  // Line 692
        // ⚠️ If exception thrown here
    }
    chunkToFill = getChunkToFill();  // Line 695
    if (chunkToFill == null) {
        break;  // Line 699
        // ⚠️ channelReadBuf not consumed, not released!
        // ⚠️ Loop breaks, channelReadBuf goes out of scope on next iteration
    } else {
        bytesFilledSoFar += chunkToFill.fillFrom(channelReadBuf);  // Line 703
        // ...
        if (!channelReadBuf.isReadable()) {
            chunkFillerChannel.resolveOldestChunk(null);  // Line 710
            channelReadBuf = null;  // Line 711
        }
    }
}
```

**Leak Scenarios**:
1. Exception at line 692: channelReadBuf allocated but not consumed or released
2. chunkToFill == null at line 696: Break leaves channelReadBuf dangling
3. Next loop iteration: channelReadBuf reassigned without releasing previous

**Severity**: 🚨 **HIGH**
**Likelihood**: **MEDIUM** - Exception paths and state transitions
**Impact**: One ByteBuf leaked per exception/break

**Current Code Analysis**:
Looking at line 696-699:
```java
if (chunkToFill == null) {
    // channel has data, but no chunks are free to be filled yet.
    maybeStartTrackingWaitForChunkTime();
    break;  // ❌ LEAK: channelReadBuf not released, not resolved
}
```

**Mitigation**:
```java
channelReadBuf = chunkFillerChannel.getNextByteBuf(0);
if (channelReadBuf != null) {
    try {
        // ... process channelReadBuf ...
        if (chunkToFill == null) {
            // break without consuming - must NOT resolve yet
            break;
        }
        bytesFilledSoFar += chunkToFill.fillFrom(channelReadBuf);
        if (!channelReadBuf.isReadable()) {
            chunkFillerChannel.resolveOldestChunk(null);
            channelReadBuf.release();  // ✅ Explicit release
            channelReadBuf = null;
        }
    } catch (Exception e) {
        if (channelReadBuf != null && channelReadBuf.refCnt() > 0) {
            channelReadBuf.release();  // ✅ Clean up on exception
        }
        throw e;
    }
}
```

---

### ⚠️ LEAK-7.4: Channel Closed With Pending Chunks

**Condition**: close() called while chunks are in chunksAwaitingResolution queue.

**Leak Path**:
```java
// Lines 131-138 in ByteBufferAsyncWritableChannel
@Override
public void close() {
    if (channelOpen.compareAndSet(true, false)) {
        resolveAllRemainingChunks(new ClosedChannelException());  // Line 133
    }
    // ...
}

// Lines 273-290: resolveAllRemainingChunks()
private void resolveAllRemainingChunks(Exception e) {
    lock.lock();
    try {
        ChunkData chunkData = chunksAwaitingResolution.poll();  // Line 276
        while (chunkData != null) {
            chunkData.resolveChunk(e);  // Line 278 - ❌ NO RELEASE
            chunkData = chunksAwaitingResolution.poll();
        }
        chunkData = chunks.poll();  // Line 281
        while (chunkData != null) {
            chunkData.resolveChunk(e);  // Line 283 - ❌ NO RELEASE
            chunkData = chunks.poll();
        }
        chunks.add(new ChunkData(null, null));  // Line 286 - Poison pill
    } finally {
        lock.unlock();
    }
}
```

**Analysis**:
- resolveAllRemainingChunks() calls resolveChunk() on all pending ChunkData
- resolveChunk() does NOT release the ByteBuf (line 329-337)
- **CRITICAL LEAK**: All ByteBufs in chunksAwaitingResolution and chunks queues leak!

**Severity**: 🚨 **CRITICAL**
**Likelihood**: **HIGH** - Happens on every channel close with pending data
**Impact**: ALL unretrieved/unresolved ByteBufs leaked

**Mitigation**:
```java
private void resolveAllRemainingChunks(Exception e) {
    lock.lock();
    try {
        ChunkData chunkData = chunksAwaitingResolution.poll();
        while (chunkData != null) {
            chunkData.resolveChunk(e);
            if (chunkData.buf != null) {
                chunkData.buf.release();  // ✅ REQUIRED: Release ByteBuf
            }
            chunkData = chunksAwaitingResolution.poll();
        }
        chunkData = chunks.poll();
        while (chunkData != null) {
            chunkData.resolveChunk(e);
            if (chunkData.buf != null) {
                chunkData.buf.release();  // ✅ REQUIRED: Release ByteBuf
            }
            chunkData = chunks.poll();
        }
        chunks.add(new ChunkData(null, null));
    } finally {
        lock.unlock();
    }
}
```

---

### ⚠️ LEAK-7.5: Writer Doesn't Handle Rejection

**Condition**: write() called when channel is closed.

**Leak Path**:
```java
// Lines 100-119 in ByteBufferAsyncWritableChannel
public Future<Long> write(ByteBuf src, Callback<Long> callback) {
    if (src == null) {
        throw new IllegalArgumentException("Source buffer cannot be null");
    }
    if (!isOpen()) {
        src.release();  // ✅ PROPER CLEANUP
        CompletableFuture<Long> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new ClosedChannelException());
        if (callback != null) {
            callback.onCompletion(0L, new ClosedChannelException());
        }
        return failedFuture;
    }
    ChunkData chunkData = new ChunkData(src, callback);  // Line 113
    chunks.add(chunkData);  // Line 114
    // ...
    return chunkData.future;
}
```

**Analysis**: ✅ **SAFE** - write() properly releases src when channel is closed (line 105)

**Severity**: ✅ **SAFE**
**Likelihood**: N/A
**Impact**: None - Proper cleanup

---

## Caller Analysis - ByteBufferAsyncWritableChannel$ChunkData

### Production Callers

1. **ByteBufferAsyncWritableChannel.write()** - ✅ **CREATES ChunkData**
   ```java
   // Lines 100-119
   ChunkData chunkData = new ChunkData(src, callback);
   chunks.add(chunkData);
   ```
   - Transfers ownership from caller's ByteBuf to ChunkData
   - If channel closed, properly releases ByteBuf (line 105)

2. **PutOperation.fillChunks()** - ⚠️ **CONSUMES BUT MAY LEAK**
   ```java
   // Lines 684-711
   channelReadBuf = chunkFillerChannel.getNextByteBuf(0);  // Get ByteBuf from ChunkData
   if (channelReadBuf != null) {
       chunkToFill = getChunkToFill();
       if (chunkToFill == null) {
           break;  // ❌ LEAK: channelReadBuf not released
       }
       bytesFilledSoFar += chunkToFill.fillFrom(channelReadBuf);
       if (!channelReadBuf.isReadable()) {
           chunkFillerChannel.resolveOldestChunk(null);  // Resolves ChunkData
           channelReadBuf = null;  // ⚠️ No explicit release
       }
   }
   ```
   - Gets ByteBuf from ChunkData
   - Uses readRetainedSlice() to extract slices (increments ref count)
   - Calls resolveOldestChunk() when fully consumed
   - **BUT**: No explicit release of channelReadBuf
   - **Relies on**: Netty shared ref counting (slices keep original alive)
   - ⚠️ **LEAK RISK**: If break happens before consumption (line 699)

3. **resolveAllRemainingChunks()** - 🚨 **DOES NOT RELEASE**
   ```java
   // Lines 276-285
   ChunkData chunkData = chunksAwaitingResolution.poll();
   while (chunkData != null) {
       chunkData.resolveChunk(e);  // ❌ NO RELEASE
       chunkData = chunksAwaitingResolution.poll();
   }
   ```
   - Resolves all pending ChunkData on close()
   - **Does NOT release ByteBufs**
   - 🚨 **CRITICAL LEAK**

### Test Code Callers

Need to audit test files for proper cleanup:
- ByteBufferAsyncWritableChannelTest.java
- ByteBufferReadableStreamChannelTest.java
- PutOperationTest.java

---

## Recommendations - ByteBufferAsyncWritableChannel$ChunkData

### CRITICAL (Must Fix)

1. **Fix resolveAllRemainingChunks() leak** (LEAK-7.4)
   ```java
   private void resolveAllRemainingChunks(Exception e) {
       lock.lock();
       try {
           // Release all awaiting resolution
           ChunkData chunkData = chunksAwaitingResolution.poll();
           while (chunkData != null) {
               chunkData.resolveChunk(e);
               if (chunkData.buf != null) {
                   chunkData.buf.release();  // ✅ REQUIRED
               }
               chunkData = chunksAwaitingResolution.poll();
           }

           // Release all not yet retrieved
           chunkData = chunks.poll();
           while (chunkData != null) {
               chunkData.resolveChunk(e);
               if (chunkData.buf != null) {
                   chunkData.buf.release();  // ✅ REQUIRED
               }
               chunkData = chunks.poll();
           }
           chunks.add(new ChunkData(null, null));
       } finally {
           lock.unlock();
       }
   }
   ```

2. **Add release() to resolveChunk()** or **Document caller responsibility**
   ```java
   // Option 1: resolveChunk() releases (changes contract)
   private void resolveChunk(Exception exception) {
       if (buf != null) {
           long bytesWritten = buf.readerIndex() - startPos;
           future.done(bytesWritten, exception);
           if (callback != null) {
               callback.onCompletion(bytesWritten, exception);
           }
           buf.release();  // ✅ Release on resolve
           buf = null;
       }
   }

   // Option 2: Document that caller MUST release (current behavior)
   /**
    * IMPORTANT: Caller is responsible for releasing ChunkData.buf
    * after calling resolveChunk(). This method only completes the
    * future and callback, it does NOT release the ByteBuf.
    */
   ```

### HIGH Priority

3. **Fix PutOperation break leak** (LEAK-7.3)
   ```java
   // In PutOperation.fillChunks() around line 696
   chunkToFill = getChunkToFill();
   if (chunkToFill == null) {
       // ✅ Must keep channelReadBuf for next iteration
       // OR release and resolve if breaking permanently
       maybeStartTrackingWaitForChunkTime();
       break;
   }
   ```

4. **Add exception handling in PutOperation**
   ```java
   try {
       channelReadBuf = chunkFillerChannel.getNextByteBuf(0);
       if (channelReadBuf != null) {
           // ... process ...
       }
   } catch (Exception e) {
       if (channelReadBuf != null && channelReadBuf.refCnt() > 0) {
           channelReadBuf.release();  // ✅ Clean up on exception
       }
       throw e;
   }
   ```

### MEDIUM Priority

5. **Add Javadoc warnings to ChunkData**
   ```java
   /**
    * CRITICAL OWNERSHIP CONTRACT:
    *
    * 1. Writer (via write()): Passes ByteBuf to ChunkData, loses ownership
    * 2. Consumer (via getNextByteBuf()): Receives ByteBuf, MUST ensure it's released
    * 3. ChunkData: Temporary holder only, does NOT manage lifecycle
    *
    * The consumer MUST either:
    * - Fully consume the ByteBuf via retained slices (which share ref count), OR
    * - Explicitly release() the ByteBuf before discarding it
    *
    * resolveChunk() does NOT release the ByteBuf!
    */
   ```

6. **Audit all consumers of getNextByteBuf()**
   - Verify every caller properly manages ByteBuf lifecycle
   - Ensure exception paths release ByteBuf
   - Document expected patterns

### LOW Priority

7. **Consider making ChunkData implement ReferenceCounted**
   ```java
   private static class ChunkData implements ReferenceCounted {
       // Delegate to buf for reference counting
   }
   ```

8. **Add leak detection to tests**
   ```java
   @After
   public void checkForLeaks() {
       // Verify all ByteBufs released
   }
   ```

---

## Summary - ByteBufferAsyncWritableChannel$ChunkData

### Overall Risk: 🚨 **HIGH**

**Strengths**:
- ✅ write() properly releases ByteBuf when channel is closed
- ✅ Uses Netty's retained slicing for efficient memory sharing

**Weaknesses**:
- 🚨 resolveAllRemainingChunks() does NOT release ByteBufs (CRITICAL LEAK on close)
- 🚨 resolveChunk() does NOT release ByteBuf (confusing contract)
- ⚠️ PutOperation may leak ByteBuf on break/exception paths
- ⚠️ Complex ownership model easy to misunderstand
- ⚠️ Public mutable buf field allows external modification

**Most Critical Issue**:
- **LEAK-7.4**: close() leaks ALL pending ByteBufs in both queues - happens on every close with pending data

**Recommended Fix Priority**:
1. Fix resolveAllRemainingChunks() to release ByteBufs (CRITICAL)
2. Fix PutOperation exception/break paths (HIGH)
3. Clarify ownership contract in documentation (HIGH)
4. Add resolveChunk() release or document caller responsibility (HIGH)

---

## Classes 8 & 9: com.github.ambry.router.EncryptJob and EncryptJob$EncryptJobResult

**File**: `ambry-router/src/main/java/com/github/ambry/router/EncryptJob.java`

**EncryptJob** - Parent Class: None (implements CryptoJob)
**EncryptJobResult** - Parent Class: None (static inner class)

### Constructor Entry Points

#### EncryptJob:
1. **Line 48-61**: `EncryptJob(accountId, containerId, blobContentToEncrypt, userMetadataToEncrypt, perBlobKey, cryptoService, kms, putBlobOptions, encryptJobMetricsTracker, callback)`
   - Package-private constructor
   - Stores blobContentToEncrypt reference (line 53)
   - **No retain() called - assumes ownership transfer**
   - Input ByteBuf will be released in run() finally block

#### EncryptJobResult:
1. **Line 127-131**: `EncryptJobResult(ByteBuffer encryptedKey, ByteBuffer encryptedUserMetadata, ByteBuf encryptedBlobContent)`
   - Package-private constructor
   - Stores encryptedBlobContent reference (line 130)
   - **No retain() called - assumes ownership of encrypted output**

### ByteBuf Fields

#### EncryptJob:
```java
private ByteBuf blobContentToEncrypt;            // Line 28 - INPUT: Original blob to encrypt
```

#### EncryptJobResult:
```java
private final ByteBuf encryptedBlobContent;      // Line 125 - OUTPUT: Encrypted blob data
private final ByteBuffer encryptedKey;           // Line 123 - Encrypted per-blob key
private final ByteBuffer encryptedUserMetadata;  // Line 124 - Encrypted user metadata
```

### Ownership Model

**CRITICAL OWNERSHIP CHARACTERISTICS**:

**EncryptJob (INPUT side)**:
1. EncryptJob **owns** the input blobContentToEncrypt
2. Constructor does NOT retain - assumes ownership transfer from caller
3. run() **ALWAYS releases** blobContentToEncrypt in finally block (lines 95-98)
4. closeJob() **ALWAYS releases** blobContentToEncrypt (lines 112-115)
5. Caller must retain before passing if they need to keep reference

**EncryptJobResult (OUTPUT side)**:
1. EncryptJobResult **owns** the output encryptedBlobContent
2. Constructor does NOT retain - assumes ownership of newly created ByteBuf
3. Caller has TWO options:
   - **Option A**: Call result.release() to release encryptedBlobContent
   - **Option B**: Extract encryptedBlobContent via getEncryptedBlobContent() and take ownership
4. release() method (lines 145-149) releases encryptedBlobContent

### Normal Flow Path

```
1. PutOperation creates EncryptJob with retained duplicate
   ├─ Line 1590-1592 in PutOperation.java:
   ├─ buf.retainedDuplicate()  // ✅ RETAINS before passing
   ├─ new EncryptJob(..., buf.retainedDuplicate(), ...)
   └─ Ownership of duplicate transferred to EncryptJob

2. EncryptJob.run() encrypts data
   ├─ Line 72-104: run() method
   ├─ Line 80: encryptedBlobContent = cryptoService.encrypt(blobContentToEncrypt, perBlobKey)
   │  └─ Creates NEW ByteBuf with encrypted data
   ├─ Lines 88-93: Exception handling
   │  └─ Releases encryptedBlobContent on error (line 91)
   ├─ Lines 95-98: Finally block
   │  ├─ blobContentToEncrypt.release()  // ✅ ALWAYS RELEASES INPUT
   │  └─ blobContentToEncrypt = null
   └─ Line 101: Creates EncryptJobResult(encryptedKey, encryptedUserMetadata, encryptedBlobContent)

3. EncryptJobResult passed to callback
   ├─ Line 100-102: callback.onCompletion(result, exception)
   └─ Ownership of encryptedBlobContent transferred to callback handler

4. PutOperation.encryptionCallback() processes result
   ├─ Line 1465-1542 in PutOperation.java
   ├─ **Path A - Success**:
   │  ├─ Line 1498: buf = result.getEncryptedBlobContent()
   │  │  └─ Ownership transfer: encryptedBlobContent → PutChunk.buf
   │  ├─ EncryptJobResult NOT released (ByteBuf ownership transferred out)
   │  └─ PutChunk.buf responsible for releasing encryptedBlobContent
   ├─ **Path B - CRC Error**:
   │  ├─ Line 1477: releaseBlobContent()  // Releases PutChunk.buf
   │  └─ Line 1479: result.release()  // ✅ Releases encryptedBlobContent
   ├─ **Path C - Operation Already Complete**:
   │  ├─ Line 1527-1529: result.release()  // ✅ Releases encryptedBlobContent
   │  └─ Line 1540: releaseBlobContent()  // Releases PutChunk.buf
   └─ **Path D - Exception**:
      └─ Line 1517: result is null, no release needed

5. PutChunk.buf lifecycle (from earlier analysis)
   └─ Eventually released when PutChunk is freed
```

### release() Method Analysis

#### EncryptJobResult.release():
```java
// Lines 145-149
void release() {
    if (encryptedBlobContent != null) {
        encryptedBlobContent.release();  // ✅ Releases encrypted output
    }
}
```

**Note**: This is a simple release, not reference-counted. Only call once!

---

## Potential Leak Scenarios - EncryptJob & EncryptJobResult

### ✅ SAFE-8.1: Input ByteBuf Always Released

**Condition**: EncryptJob.run() always releases input blobContentToEncrypt.

**Analysis**:
- Finally block (lines 95-98) **ALWAYS** releases blobContentToEncrypt
- closeJob() (lines 112-115) also releases blobContentToEncrypt
- Regardless of success or exception, input is cleaned up

**Severity**: ✅ **SAFE**
**Likelihood**: N/A
**Impact**: None - Proper cleanup guaranteed

---

### ⚠️ LEAK-8.2: Caller Forgets to Retain Before Passing

**Condition**: Caller passes ByteBuf to EncryptJob without retaining first.

**Leak Path**:
```java
ByteBuf myBuf = allocator.buffer(1024);
// ... fill myBuf ...

// ❌ WRONG: No retain before passing
new EncryptJob(..., myBuf, ..., callback);
// Ownership transferred, myBuf released by EncryptJob
// If caller tries to use myBuf again: USE-AFTER-FREE!

// ✅ CORRECT: Retain before passing
new EncryptJob(..., myBuf.retainedDuplicate(), ..., callback);
// Or: myBuf.retain(); new EncryptJob(..., myBuf, ...);
// Caller and EncryptJob each hold one reference
```

**Severity**: 🚨 **HIGH** (not a leak, but use-after-free)
**Likelihood**: **LOW** - PutOperation correctly uses retainedDuplicate() (line 1591)
**Impact**: Crash or corruption if caller uses ByteBuf after EncryptJob releases it

**Mitigation**: Document that caller must retain if they need to keep using the ByteBuf

---

### ⚠️ LEAK-8.3: Exception During Encryption, encryptedBlobContent Leaked

**Condition**: Exception thrown after encryptedBlobContent created but before exception handling.

**Leak Path**:
```java
// In EncryptJob.run() lines 72-104
ByteBuf encryptedBlobContent = null;
try {
    if (blobContentToEncrypt != null) {
        encryptedBlobContent = cryptoService.encrypt(blobContentToEncrypt, perBlobKey);  // Line 80
    }
    // ⚠️ encryptedBlobContent created (new ByteBuf allocated)

    if (userMetadataToEncrypt != null) {
        encryptedUserMetadata = cryptoService.encrypt(userMetadataToEncrypt, perBlobKey);  // Line 83
        // ⚠️ If exception thrown here
    }
    Object containerKey = kms.getKey(...);  // Line 85-86
    // ⚠️ Or if exception thrown here
    encryptedKey = cryptoService.encryptKey(perBlobKey, containerKey);  // Line 87
    // ⚠️ Or if exception thrown here
} catch (Exception e) {
    exception = e;
    if (encryptedBlobContent != null) {
        encryptedBlobContent.release();  // ✅ PROPER CLEANUP
        encryptedBlobContent = null;
    }
}
```

**Analysis**: ✅ **SAFE** - Exception handler properly releases encryptedBlobContent (lines 90-93)

**Severity**: ✅ **SAFE**
**Likelihood**: N/A
**Impact**: None - Exception path properly releases

---

### ⚠️ LEAK-8.4: Callback Never Processes EncryptJobResult

**Condition**: EncryptJob completes successfully but callback is never invoked.

**Leak Path**:
```java
// If CryptoJobHandler is shutdown before callback completes
cryptoJobHandler.submitJob(new EncryptJob(..., callback));
// Job runs, creates EncryptJobResult
// CryptoJobHandler shuts down
// Callback never invoked
// EncryptJobResult never released
```

**Severity**: ⚠️ **MEDIUM**
**Likelihood**: **LOW** - Only during shutdown
**Impact**: Encrypted ByteBuf leaked per incomplete job

**Mitigation**: CryptoJobHandler should release pending results on shutdown

---

### 🚨 LEAK-8.5: Callback Doesn't Release Result on All Paths

**Condition**: Callback extracts encryptedBlobContent but forgets to release result in some code path.

**Leak Path**:
```java
private void encryptionCallback(EncryptJobResult result, Exception exception) {
    if (exception == null) {
        buf = result.getEncryptedBlobContent();  // Line 1498 - Ownership transfer
        // EncryptJobResult NOT released - THIS IS INTENTIONAL
        // buf now owns encryptedBlobContent, will be released later
        prepareForSending();
        // ⚠️ If exception thrown here, buf not released yet!
        // ⚠️ But will be cleaned up in line 1537-1540
    } else {
        // result is null when exception != null (line 1517)
        // No release needed
    }

    // Lines 1537-1540: Safety net
    if (isOperationComplete()) {
        releaseBlobContent();  // ✅ Releases buf if operation completed
    }
}
```

**Current Code Analysis**:
- Line 1479: result.release() on CRC error ✅
- Line 1498: Ownership transfer (no release, intentional) ✅
- Line 1528: result.release() if operation already complete ✅
- Line 1540: releaseBlobContent() safety net ✅

**Severity**: ✅ **SAFE** (well-handled in current code)
**Likelihood**: N/A
**Impact**: None - All paths covered

---

### 📝 LEAK-8.6: Double Release Risk

**Condition**: Caller extracts encryptedBlobContent AND calls result.release().

**Leak Path**:
```java
EncryptJobResult result = ...; // From callback

// Path 1: Extract ownership
ByteBuf encrypted = result.getEncryptedBlobContent();
// ... use encrypted ...
encrypted.release();  // ✅ Correct: release after use

// ❌ WRONG: Also calling result.release()
result.release();  // DOUBLE RELEASE! encrypted already released above
```

**Severity**: 🚨 **HIGH** (double release causes crash)
**Likelihood**: **LOW** - PutOperation correctly manages lifecycle
**Impact**: Crash or corruption

**Mitigation**:
- Document that calling result.release() releases the encryptedBlobContent
- If caller extracts encryptedBlobContent, they take ownership and must NOT call result.release()

---

## Caller Analysis - EncryptJob & EncryptJobResult

### Production Callers

1. **PutOperation.encryptChunk()** - ✅ **CREATES EncryptJob**
   ```java
   // Lines 1589-1592
   cryptoJobHandler.submitJob(
       new EncryptJob(...,
           isMetadataChunk() ? null : buf.retainedDuplicate(),  // ✅ RETAINS
           ..., this::encryptionCallback));
   ```
   - Properly uses retainedDuplicate() before passing ByteBuf
   - Caller (PutChunk) retains ownership of original buf
   - EncryptJob receives and owns the duplicate

2. **PutOperation.encryptionCallback()** - ✅ **PROCESSES EncryptJobResult**
   ```java
   // Lines 1465-1542
   private void encryptionCallback(EncryptJobResult result, Exception exception) {
       if (exception == null && !isOperationComplete()) {
           buf = result.getEncryptedBlobContent();  // Line 1498 - Ownership transfer
           // EncryptJobResult NOT released, buf owns encryptedBlobContent
       } else {
           // Multiple paths handle cleanup
       }

       // Safety nets:
       if (!verifyCRC()) { result.release(); }  // Line 1479
       if (isOperationComplete() && result != null) { result.release(); }  // Line 1528
       if (isOperationComplete()) { releaseBlobContent(); }  // Line 1540
   }
   ```
   - Well-designed with multiple safety nets
   - Ownership transfer pattern (extract without releasing result)
   - All exception paths properly release

### Test Code Callers

Need to audit:
- CryptoJobHandlerTest.java
- EncryptJobTest.java (if exists)

---

## Recommendations - EncryptJob & EncryptJobResult

### HIGH Priority

1. **Document ownership contract clearly**
   ```java
   /**
    * CRITICAL OWNERSHIP CONTRACT FOR EncryptJob:
    *
    * INPUT (blobContentToEncrypt):
    * - EncryptJob takes ownership of the ByteBuf passed to constructor
    * - run() ALWAYS releases blobContentToEncrypt in finally block
    * - Caller must retain() or retainedDuplicate() before passing if they need to keep using it
    *
    * DO NOT pass a ByteBuf that you intend to use after submitting the EncryptJob!
    * Use buf.retainedDuplicate() to create a separate reference.
    */
   ```

2. **Document EncryptJobResult release contract**
   ```java
   /**
    * CRITICAL OWNERSHIP CONTRACT FOR EncryptJobResult:
    *
    * OUTPUT (encryptedBlobContent):
    * - EncryptJobResult owns the encrypted ByteBuf
    * - Caller has TWO mutually exclusive options:
    *
    * OPTION A - Take ownership:
    *     ByteBuf encrypted = result.getEncryptedBlobContent();
    *     // Caller now owns 'encrypted', must release it later
    *     // DO NOT call result.release()
    *
    * OPTION B - Release via result:
    *     result.release();
    *     // Releases encryptedBlobContent
    *     // DO NOT use encryptedBlobContent after this
    *
    * NEVER do both! Choose one or the other.
    */
   ```

### MEDIUM Priority

3. **Add CryptoJobHandler shutdown cleanup**
   ```java
   public void shutdown() {
       // Cancel pending jobs and release their results
       for (CryptoJob job : pendingJobs) {
           if (job instanceof EncryptJob) {
               ((EncryptJob) job).closeJob(new GeneralSecurityException("Shutdown"));
           }
       }
   }
   ```

4. **Consider making EncryptJobResult AutoCloseable**
   ```java
   static class EncryptJobResult implements AutoCloseable {
       // ... existing code ...

       @Override
       public void close() {
           release();
       }
   }
   ```

### LOW Priority

5. **Add defensive null checks**
   ```java
   // In EncryptJob.run() finally block
   finally {
       if (blobContentToEncrypt != null && blobContentToEncrypt.refCnt() > 0) {
           blobContentToEncrypt.release();
           blobContentToEncrypt = null;
       }
   }
   ```

6. **Add leak detection to tests**
   ```java
   @After
   public void checkForLeaks() {
       // Verify all EncryptJob ByteBufs released
       // Verify all EncryptJobResult ByteBufs released
   }
   ```

---

## Summary - EncryptJob & EncryptJobResult

### Overall Risk: ⚠️ **MEDIUM-LOW**

**Strengths**:
- ✅ EncryptJob ALWAYS releases input ByteBuf in finally block
- ✅ Exception handling properly releases partial results
- ✅ PutOperation correctly uses retainedDuplicate()
- ✅ PutOperation has multiple safety nets for cleanup
- ✅ Clear ownership transfer pattern (extract vs release)

**Weaknesses**:
- ⚠️ Ownership contract not documented (easy to misuse)
- ⚠️ Risk of double-release if caller misunderstands contract
- ⚠️ CryptoJobHandler may not clean up on shutdown

**Most Critical Issue**:
- **Documentation gap**: Ownership contract not clearly documented, could lead to misuse in future code

**Recommended Fix Priority**:
1. Document ownership contracts (HIGH) - prevents future bugs
2. Add CryptoJobHandler shutdown cleanup (MEDIUM) - prevents shutdown leaks
3. Make EncryptJobResult AutoCloseable (MEDIUM) - enables try-with-resources
4. Add defensive programming (LOW) - extra safety

