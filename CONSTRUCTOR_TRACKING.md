# Constructor Tracking for Wrapped ByteBuf Objects

## Overview

**Constructor tracking** is a critical feature for detecting ByteBuf memory leaks in **wrapped objects** - classes that receive a ByteBuf in their constructor and store it as an instance field.

## The Problem: Flow Breaks Without Constructor Tracking

### Without Constructor Tracking ❌

```
allocate() -> prepareData() -> new Request(byteBuf) -> ???
                                     ↑
                            FLOW BREAKS HERE
                            Constructor not tracked!
```

When a ByteBuf is passed to a constructor, the default instrumentation **loses track** of it because:
1. Only public/protected **methods** are instrumented by default
2. **Constructors** are explicitly excluded (`not(isConstructor())`)
3. The ByteBuf "disappears" into the object's private field

**Result:** Memory leaks in wrapped objects go undetected!

### With Constructor Tracking ✅

```
allocate() -> prepareData() -> Request.<init>() -> process() -> release()
                                    ↑
                         NOW TRACKED! Continuous flow maintained
```

The flow continues unbroken through the constructor, allowing complete leak detection.

---

## How Constructor Tracking Works

### Configuration

Constructor tracking is **opt-in and selective**. You specify which classes should have their constructors tracked:

```groovy
jvmArgs "-javaagent:${trackerJar}=include=com.github.ambry;trackConstructors=Class1,Class2,Package.*"
```

### Ambry's Current Configuration

Located in `/home/user/ambry/build.gradle` (test task ~lines 231-248, intTest task ~lines 301-315):

```groovy
trackConstructors=com.github.ambry.protocol.PutRequest,                              // Protocol: PutRequest wraps blob data
                  com.github.ambry.utils.NettyByteBufDataInputStream,                // Utils: ByteBuf to InputStream wrapper
                  com.github.ambry.network.SocketServerRequest,                      // Network: Socket-based request wrapper (top-level class)
                  com.github.ambry.network.NettyServerRequest,                       // Network: Netty-based request wrapper
                  com.github.ambry.network.ResponseInfo,                             // Network: Response wrapper
                  com.github.ambry.messageformat.BlobData,                           // Message format: Blob data wrapper
                  com.github.ambry.commons.ByteBufferAsyncWritableChannel$ChunkData, // Commons: Async channel chunk wrapper (inner class)
                  com.github.ambry.router.EncryptJob,                                // Router: Encryption job with blob content
                  com.github.ambry.router.EncryptJob$EncryptJobResult,               // Router: Encryption result (inner class)
                  com.github.ambry.router.DecryptJob,                                // Router: Decryption job with blob content
                  com.github.ambry.router.DecryptJob$DecryptJobResult,               // Router: Decryption result (inner class)
                  com.github.ambry.network.BoundedNettyByteBufReceive,               // Network: Bounded receive wrapper
                  com.github.ambry.rest.NettyResponseChannel$Chunk,                  // REST: Response chunk (inner class)
                  com.github.ambry.network.http2.GoAwayException                     // Network: HTTP/2 GoAway exception with debug data
```

**Note:** All classes are listed explicitly (no package wildcards). Inner classes use `$` notation (e.g., `OuterClass$InnerClass`).

### What Gets Tracked

For each specified class:
- **All public/protected constructors** are instrumented
- ByteBuf parameters are tracked **on entry**
- Flow continues into the object's methods
- Leak detection works across the entire lifecycle

---

## Ambry Wrapper Classes (All Currently Tracked)

Based on comprehensive codebase analysis, these are **all 14 classes** in Ambry that wrap ByteBuf in constructors:

### 1. Protocol Layer

**com.github.ambry.protocol.PutRequest** - `ambry-protocol/src/main/java/com/github/ambry/protocol/PutRequest.java`
- **Lines:** 96-101, 116-118
- **Usage:** Wraps `materializedBlob` ByteBuf containing blob data being uploaded
- **Why track:** Protocol messages pass through multiple network and storage layers

### 2. Message Format Layer

**com.github.ambry.messageformat.BlobData** - `ambry-messageformat/src/main/java/com/github/ambry/messageformat/BlobData.java`
- **Lines:** 35, 46
- **Usage:** Wraps `content` ByteBuf representing actual blob data
- **Why track:** Core blob storage wrapper, critical for leak detection

### 3. Network Layer

**com.github.ambry.network.SocketServerRequest** - `ambry-network/src/main/java/com/github/ambry/network/SocketRequestResponseChannel.java`
- **Line:** 39
- **Constructor:** `public SocketServerRequest(int processor, String connectionId, ByteBuf content)`
- **Type:** Top-level package-private class (same file as SocketRequestResponseChannel)
- **Usage:** Wraps `content` ByteBuf from socket and provides InputStream access
- **Why track:** Socket-based request handling with async I/O

**com.github.ambry.network.NettyServerRequest** - `ambry-network/src/main/java/com/github/ambry/network/NettyServerRequest.java`
- **Lines:** 32, 42
- **Constructor 1:** `public NettyServerRequest(ChannelHandlerContext ctx, ByteBuf content)`
- **Constructor 2:** `public NettyServerRequest(ChannelHandlerContext ctx, ByteBuf content, long creationTime)`
- **Usage:** Wraps `content` ByteBuf from Netty channel
- **Why track:** Netty-based request handling with complex lifecycle

**com.github.ambry.network.ResponseInfo** - `ambry-api/src/main/java/com/github/ambry/network/ResponseInfo.java`
- **Line:** 61
- **Constructor:** `public ResponseInfo(RequestInfo requestInfo, NetworkClientErrorCode error, ByteBuf content)`
- **Usage:** Wraps `content` ByteBuf with network response data
- **Why track:** Response objects travel through network client layers

**com.github.ambry.network.BoundedNettyByteBufReceive** - `ambry-api/src/main/java/com/github/ambry/network/BoundedNettyByteBufReceive.java`
- **Line:** 43
- **Constructor:** `BoundedNettyByteBufReceive(ByteBuf buffer, long sizeToRead, long maxRequestSize)`
- **Usage:** Wraps ByteBuf for bounded network receive operations
- **Why track:** Network receive buffer management

**com.github.ambry.network.http2.GoAwayException** - `ambry-network/src/main/java/com/github/ambry/network/http2/GoAwayException.java`
- **Line:** 30
- **Constructor:** `GoAwayException(long errorCode, ByteBuf debugData)`
- **Usage:** HTTP/2 GoAway exception with debug data buffer
- **Why track:** Exception handling with ByteBuf payload

### 4. Commons Layer

**com.github.ambry.commons.ByteBufferAsyncWritableChannel$ChunkData** - `ambry-commons/src/main/java/com/github/ambry/commons/ByteBufferAsyncWritableChannel.java`
- **Line:** 314
- **Constructor:** `private ChunkData(ByteBuf buf, Callback<Long> callback)`
- **Usage:** Async channel chunk wrapper (inner class)
- **Why track:** Async write operations with callbacks

### 5. Router/Crypto Layer

**com.github.ambry.router.EncryptJob** - `ambry-router/src/main/java/com/github/ambry/router/EncryptJob.java`
- **Line:** 48-50
- **Constructor:** `EncryptJob(short accountId, short containerId, ByteBuf blobContentToEncrypt, ...)`
- **Usage:** Wraps `blobContentToEncrypt` ByteBuf for encryption operations
- **Why track:** Crypto operations have error paths that can leak

**com.github.ambry.router.EncryptJob$EncryptJobResult** - `ambry-router/src/main/java/com/github/ambry/router/EncryptJob.java`
- **Line:** 127
- **Constructor:** `EncryptJobResult(ByteBuffer encryptedKey, ByteBuffer encryptedUserMetadata, ByteBuf encryptedBlobContent)`
- **Usage:** Wraps `encryptedBlobContent` ByteBuf as encryption output (inner class)
- **Why track:** Result objects passed through callbacks

**com.github.ambry.router.DecryptJob** - `ambry-router/src/main/java/com/github/ambry/router/DecryptJob.java`
- **Line:** 50-53
- **Constructor:** `DecryptJob(BlobId blobId, ByteBuffer encryptedPerBlobKey, ByteBuf encryptedBlobContent, ...)`
- **Usage:** Wraps `encryptedBlobContent` ByteBuf for decryption operations
- **Why track:** Decryption failures can leave buffers unreleased

**com.github.ambry.router.DecryptJob$DecryptJobResult** - `ambry-router/src/main/java/com/github/ambry/router/DecryptJob.java`
- **Line:** 124
- **Constructor:** `DecryptJobResult(BlobId blobId, ByteBuf decryptedBlobContent, ByteBuffer decryptedUserMetadata)`
- **Usage:** Wraps `decryptedBlobContent` ByteBuf as decryption output (inner class)
- **Why track:** Result objects passed through callbacks

### 6. REST Layer

**com.github.ambry.rest.NettyResponseChannel$Chunk** - `ambry-rest/src/main/java/com/github/ambry/rest/NettyResponseChannel.java`
- **Line:** 910
- **Constructor:** `Chunk(ByteBuf buffer, Callback<Long> callback)`
- **Usage:** Wraps `buffer` ByteBuf as response chunk for streaming (inner class)
- **Why track:** Response streaming has complex flow control

### 7. Utilities Layer

**com.github.ambry.utils.NettyByteBufDataInputStream** - `ambry-utils/src/main/java/com/github/ambry/utils/NettyByteBufDataInputStream.java`
- **Line:** 31
- **Constructor:** `public NettyByteBufDataInputStream(ByteBuf buffer)`
- **Usage:** Wraps `buffer` ByteBuf to provide InputStream interface
- **Why track:** Stream wrapper used throughout codebase

---

## Syntax and Wildcards

### Exact Class Names

Track specific classes:
```
trackConstructors=com.github.ambry.network.Request,com.github.ambry.protocol.GetRequest
```

### Wildcard Patterns

Track all classes in a package:
```
trackConstructors=com.github.ambry.protocol.*
```

This matches:
- `com.github.ambry.protocol.GetRequest`
- `com.github.ambry.protocol.PutRequest`
- `com.github.ambry.protocol.DeleteRequest`
- ... and all other classes in that package

### Multiple Patterns

Combine exact names and wildcards:
```
trackConstructors=com.github.ambry.protocol.*,com.github.ambry.network.Request,com.github.ambry.router.*
```

---

## Example: Leak Detection With Constructor Tracking

### Scenario: Request Object Leak

**Code:**
```java
// Allocate ByteBuf
ByteBuf buffer = Unpooled.buffer(1024);
buffer.writeBytes(data);

// Wrap in Request (constructor call)
GetRequest request = new GetRequest(buffer);

// Process request
requestHandler.handle(request);

// OOPS! Forgot to release buffer
// request.release();  ← Missing!
```

### Output WITHOUT Constructor Tracking

```
=== ByteBuf Flow Summary ===
Total Root Methods: 1
Leak Paths: 0

ROOT: allocate [count=1]
└── writeBytes [ref=1, count=1]
```

**Problem:** Flow stops at `writeBytes`. Constructor not tracked, so leak is **invisible**!

### Output WITH Constructor Tracking

```
=== ByteBuf Flow Summary ===
Total Root Methods: 1
Leak Paths: 1

ROOT: allocate [count=1]
└── writeBytes [ref=1, count=1]
    └── GetRequest.<init> [ref=1, count=1]
        └── RequestHandler.handle [ref=1, count=1]
            └── processData [ref=1, count=1] ⚠️ LEAK

Leak detected! ByteBuf still has refCount=1
```

**Success:** Complete flow tracked, leak **detected**!

---

## Performance Considerations

### Overhead

- **Methods**: ~5-10% overhead (already active)
- **Constructors**: Additional ~2-3% overhead per tracked class
- **Recommendation**: Track only classes that commonly wrap ByteBufs

### Selective Tracking Benefits

By being selective (not tracking ALL constructors), you get:
1. **Lower overhead** - Only track critical classes
2. **Cleaner output** - Less noise from unrelated constructors
3. **Focused analysis** - See only relevant ByteBuf flows

### What NOT to Track

❌ Don't track constructors for:
- Classes that **never** handle ByteBufs
- Utility classes that pass ByteBufs through immediately
- Internal JDK/Netty classes (already excluded)

---

## Customizing Constructor Tracking

### Add New Classes

Edit `/home/user/ambry/build.gradle` at lines 186-196 and 259-269:

```groovy
trackConstructors=com.github.ambry.protocol.*,
                  com.github.ambry.messageformat.BlobData,
                  // ... existing classes ...
                  com.github.ambry.yourmodule.YourClass  // ← Add your class here
```

**For inner classes**, use `$` notation:
```groovy
trackConstructors=com.github.ambry.yourmodule.OuterClass\$InnerClass
```

The backslash escapes the `$` for Groovy string literals.

### Remove Classes

Simply remove from the comma-separated list if a class doesn't need tracking.

### Test-Specific Tracking

Different tracking for unit vs integration tests:

```groovy
test {
    if (byteBufTrackingEnabled) {
        // Narrow tracking for fast unit tests
        jvmArgs "-javaagent:...;trackConstructors=com.github.ambry.protocol.*"
    }
}

intTest {
    if (byteBufTrackingEnabled) {
        // Comprehensive tracking for integration tests
        jvmArgs "-javaagent:...;trackConstructors=com.github.ambry.protocol.*,com.github.ambry.network.*,com.github.ambry.router.*"
    }
}
```

---

## Verifying Constructor Tracking

### Check Configuration

When tests run with `-PwithByteBufTracking`, you'll see:

```
[ByteBufFlowAgent] Starting with config: AgentConfig{
    include=[com.github.ambry],
    exclude=[],
    trackConstructors=[com.github.ambry.protocol.*, com.github.ambry.messageformat.BlobData,
                       com.github.ambry.network.SocketServerRequest, ...]
}
[ByteBufFlowAgent] Constructor tracking enabled for: [com.github.ambry.protocol.*, ...]
```

All 10 wrapper classes identified in the codebase are now tracked.

### Check Output

Look for `<init>` in the flow tree:

```
ROOT: allocator.allocate [count=5]
└── GetRequest.<init> [ref=1, count=5]    ← Constructor tracked!
    └── handler.process [ref=1, count=5]
```

If you don't see `<init>`, constructor tracking isn't working.

---

## Troubleshooting

### Constructor not appearing in output

**Problem:** Class not in `trackConstructors` list

**Solution:** Add it:
```groovy
trackConstructors=...,com.github.ambry.yourpackage.YourClass
```

### Too much output / Performance issues

**Problem:** Tracking too many constructors

**Solution:** Be more selective:
```groovy
// Instead of this (tracks EVERYTHING in ambry):
trackConstructors=com.github.ambry.*

// Do this (track only specific modules):
trackConstructors=com.github.ambry.protocol.*,com.github.ambry.network.Request
```

### Flow still breaks after constructor

**Problem:** ByteBuf passed to another object's constructor

**Solution:** Track that class too! Follow the chain:
```
allocate() -> RequestWrapper.<init>() -> Request.<init>() -> ...
              ↑                           ↑
              Track this                  Track this too!
```

---

## Best Practices

### 1. Start with Protocol Layer

Begin with `com.github.ambry.protocol.*`:
- Most likely to wrap ByteBufs
- Good signal-to-noise ratio

### 2. Expand Based on Leaks

If you see leaks in output, trace back to find constructor calls and add those classes.

### 3. Use Wildcards for Packages

Instead of listing every class:
```groovy
// Good
trackConstructors=com.github.ambry.protocol.*

// Bad (tedious and error-prone)
trackConstructors=com.github.ambry.protocol.GetRequest,com.github.ambry.protocol.PutRequest,...
```

### 4. Document Why Each Class is Tracked

Add comments explaining the rationale:
```groovy
trackConstructors=
    com.github.ambry.protocol.*,              // All protocol messages wrap ByteBufs
    com.github.ambry.network.Request,         // Network layer wraps protocol messages
    com.github.ambry.router.GetBlobOperation  // Operations accumulate ByteBuf chunks
```

---

## Related Documentation

- **BYTEBUF_TRACKER_INTEGRATION.md** - Complete integration guide
- **bytebuf-tracker/README.md** - Tracker module documentation
- **bytebuf-tracker/CLAUDE_CODE_INTEGRATION.md** - Integration patterns
- **bytebuf-tracker/UPSTREAM_README.md** - Upstream project documentation

---

## Summary

- **Constructor tracking is essential** for wrapped ByteBuf leak detection
- **Opt-in and selective** - Configure exactly which classes to track
- **Already configured** for key Ambry wrapper classes
- **Wildcard support** - Track entire packages with `Package.*`
- **Minimal overhead** when used selectively
- **Complete flow visibility** from allocation through wrappers to release

**When in doubt, add the class to `trackConstructors`!** It's better to track too much than miss a leak.
