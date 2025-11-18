# ByteBuf Lifecycle Flow Diagrams

## Diagram 1: Overall ByteBuf Flow (Unencrypted Path)

```mermaid
flowchart TD
    Start([HTTP Request Starts]) --> Channel[ReadableStreamChannel]

    Channel -->|writes ByteBuf| Queue[chunkFillerChannel Queue]
    Queue -->|refcount = 1| QueueOwns[Channel owns ByteBuf]

    subgraph ChunkFillerThread[ChunkFiller Thread]
        GetBuf[getNextByteBuf] -->|retrieves| ChannelReadBuf[channelReadBuf<br/>refcount = 1]
        ChannelReadBuf -->|readRetainedSlice| Retain1[RETAIN +1<br/>refcount = 2]
        Retain1 --> BufSlice[chunk.buf = slice]
        BufSlice --> Resolve[resolveOldestChunk]
        Resolve -->|RELEASE -1| Release1[Channel releases<br/>refcount = 1]
        Release1 --> FillMore{More data<br/>to fill?}
        FillMore -->|Yes| ChannelReadBuf
        FillMore -->|No| FillComplete[onFillComplete]
        FillComplete --> Compress{Compress?}
        Compress -->|Yes| CompressOp[compressChunk]
        CompressOp -->|buf.release| ReleaseOld1[RELEASE old -1<br/>refcount = 0]
        ReleaseOld1 -->|buf = newBuffer| NewBuf1[buf = compressed<br/>refcount = 1]
        Compress -->|No| NewBuf1
        NewBuf1 --> PrepSend[prepareForSending<br/>state = Ready]
    end

    subgraph MainThread[Main Thread]
        PrepSend --> CreateReq[createPutRequest]
        CreateReq -->|retainedDuplicate| Retain2[RETAIN +1<br/>refcount = 2]
        Retain2 --> NetSend[Network Send<br/>PutRequest owns duplicate]
        NetSend --> NetDone[Send Complete]
        NetDone -->|PutRequest releases| Release2[RELEASE -1<br/>refcount = 1]
        Release2 --> ChunkComplete[Chunk Complete]
        ChunkComplete --> Clear[clear]
        Clear --> RelContent[releaseBlobContent]
        RelContent -->|safeRelease| FinalRelease[RELEASE -1<br/>refcount = 0]
        FinalRelease --> Freed1([ByteBuf Freed])
    end

    style Retain1 fill:#ff9999
    style Retain2 fill:#ff9999
    style Release1 fill:#99ff99
    style ReleaseOld1 fill:#99ff99
    style Release2 fill:#99ff99
    style FinalRelease fill:#99ff99
    style Freed1 fill:#ffff99
```

## Diagram 2: Encrypted Path ByteBuf Flow

```mermaid
flowchart TD
    Start([chunk.buf populated]) --> CheckEncrypt{Encrypted?}

    CheckEncrypt -->|No| Unencrypted[See Diagram 1]

    CheckEncrypt -->|Yes| CompressHeap[compressChunk<br/>outputDirectMemory=false]

    subgraph ChunkFillerThread[ChunkFiller Thread]
        CompressHeap --> BufState1[buf = compressed heap buffer<br/>refcount = 1]
        BufState1 --> EncryptStart[encryptChunk]
        EncryptStart --> StateChange[state = Encrypting]
        StateChange -->|retainedDuplicate| Retain1[RETAIN +1<br/>refcount = 2]
        Retain1 --> SubmitJob[Submit EncryptJob<br/>job owns duplicate]
    end

    subgraph EncryptionThread[Encryption Thread]
        SubmitJob --> EncCallback[encryptionCallback]
        EncCallback --> CheckError{Error?}

        CheckError -->|Yes| ErrorPath[Exception path]
        ErrorPath --> JobRelease1[EncryptJob releases<br/>duplicate]
        JobRelease1 -->|RELEASE -1| RefCount1[refcount = 1]
        RefCount1 --> RelOrig1[releaseBlobContent<br/>original buf]
        RelOrig1 -->|RELEASE -1| Freed1([Original Freed<br/>refcount = 0])

        CheckError -->|No| SuccessPath[Success path]
        SuccessPath --> RelOrig2[releaseBlobContent<br/>Line 1510]
        RelOrig2 -->|RELEASE -1| RefCount2[original buf refcount = 1]
        RefCount2 --> JobProcess[EncryptJob processes<br/>retainedCopy]
        JobProcess --> CreateEnc[Create encrypted ByteBuf]
        CreateEnc --> JobRelease2[EncryptJob releases<br/>original retainedCopy]
        JobRelease2 -->|RELEASE -1| OrigFreed([Original Freed<br/>refcount = 0])
        OrigFreed --> Transfer[buf = encrypted buffer<br/>from EncryptJob result]
        Transfer --> EncBufOwned[buf = encrypted<br/>refcount = 1]
        EncBufOwned --> PrepSend[prepareForSending<br/>state = Ready]

        PrepSend --> DoubleCheck{operationCompleted?}
        DoubleCheck -->|Yes| RelAgain[releaseBlobContent<br/>Line 1557]
        RelAgain -->|RELEASE -1| FreedEarly([Encrypted Freed<br/>refcount = 0])
        DoubleCheck -->|No| Continue[Continue to Main Thread]
    end

    subgraph MainThread[Main Thread]
        Continue --> CreateReq[createPutRequest]
        CreateReq -->|retainedDuplicate| Retain2[RETAIN +1<br/>encrypted refcount = 2]
        Retain2 --> NetSend[Network Send]
        NetSend --> NetDone[Send Complete]
        NetDone -->|PutRequest releases| Release2[RELEASE -1<br/>encrypted refcount = 1]
        Release2 --> ChunkDone[Chunk Complete]
        ChunkDone --> Clear[clear]
        Clear --> RelFinal[releaseBlobContent]
        RelFinal -->|RELEASE -1| FreedFinal([Encrypted Freed<br/>refcount = 0])
    end

    style Retain1 fill:#ff9999
    style Retain2 fill:#ff9999
    style RelOrig1 fill:#99ff99
    style RelOrig2 fill:#99ff99
    style JobRelease1 fill:#99ff99
    style JobRelease2 fill:#99ff99
    style Release2 fill:#99ff99
    style RelFinal fill:#99ff99
    style Freed1 fill:#ffff99
    style OrigFreed fill:#ffff99
    style FreedEarly fill:#ffff99
    style FreedFinal fill:#ffff99
```

## Diagram 3: Error/Abort Path - releaseChunksOnCompletion

```mermaid
flowchart TD
    Start([Error Occurs]) --> AnyThread{Which Thread?}

    AnyThread -->|Response Thread| RespError[Response Error]
    AnyThread -->|Channel Thread| ChanError[Channel Read Error]
    AnyThread -->|ChunkFiller Thread| FillerError[Fill Error]

    RespError --> SetComplete[setOperationExceptionAndComplete]
    ChanError --> SetComplete
    FillerError --> SetComplete

    SetComplete --> SetFlag[operationCompleted = true<br/>⚠️ NOT ATOMIC]
    SetFlag --> RelChunks[releaseChunksOnCompletion]

    subgraph PutOperationLock[synchronized on PutOperation]
        RelChunks --> CloseChannel[chunkFillerChannel.close]
        CloseChannel --> CloseCallback[Channel close callback]
        CloseCallback --> ReleaseQueued[RELEASE all queued<br/>unconsumed ByteBufs]

        ReleaseQueued --> IterChunks[Iterate all PutChunks]
        IterChunks --> CheckState1{Chunk State?}

        CheckState1 -->|Free| Skip1[Skip - no content]
        CheckState1 -->|Encrypting| Skip2[Skip - encryption<br/>thread owns lifecycle]
        CheckState1 -->|Building| RelBuilding[releaseBlobContent]
        CheckState1 -->|Ready| RelReady[releaseBlobContent]
        CheckState1 -->|Complete| RelComplete[releaseBlobContent]

        RelBuilding --> RelContent1[Release chunk ByteBuf]
        RelReady --> RelContent1
        RelComplete --> RelContent1
    end

    subgraph PutChunkLock[synchronized on PutChunk]
        RelContent1 --> CheckNull{buf != null?}
        CheckNull -->|Yes| SafeRelease[ReferenceCountUtil.safeRelease]
        SafeRelease --> SetNull[buf = null]
        SetNull --> Freed([ByteBuf Freed])
        CheckNull -->|No| AlreadyNull[Already released]
    end

    subgraph RACE_CONDITION[⚠️ RACE CONDITION]
        ConcurrentFill[ChunkFiller Thread:<br/>fillFrom - NO LOCK]
        ConcurrentFill -.->|reads buf| BufAccess[Access buf field]
        RelContent1 -.->|RELEASES buf| BufAccess
        BufAccess -.-> UseAfterFree([💀 USE-AFTER-FREE])
    end

    style SetFlag fill:#ff9999
    style ReleaseQueued fill:#99ff99
    style SafeRelease fill:#99ff99
    style Freed fill:#ffff99
    style UseAfterFree fill:#ff0000,color:#ffffff
    style RACE_CONDITION fill:#ffcccc
```

## Diagram 4: Thread Interaction and Lock Hierarchy

```mermaid
flowchart LR
    subgraph Threads[Thread Types]
        T1[ChunkFiller Thread]
        T2[Encryption Thread]
        T3[Main/Response Thread]
        T4[Channel Callback Thread]
    end

    subgraph Operations[Operations by Thread]
        T1 --> Op1[fillChunks<br/>fillFrom ⚠️ NO LOCK<br/>onFillComplete<br/>compressChunk<br/>encryptChunk]
        T2 --> Op2[encryptionCallback<br/>releaseBlobContent]
        T3 --> Op3[handleResponse 🔒 PutChunk<br/>createPutRequest<br/>setOperationCompleted]
        T4 --> Op4[readInto callback<br/>setOperationExceptionAndComplete]
    end

    subgraph Locks[Lock Hierarchy]
        L1[PutOperation Lock]
        L2[PutChunk Lock]

        L1 -.->|holds then needs| L2

        L1Methods[releaseChunksOnCompletion<br/>releaseDataForAllChunks]
        L2Methods[releaseBlobContent<br/>handleResponse<br/>isDataReleased]

        L1 --> L1Methods
        L2 --> L2Methods
    end

    subgraph Issues[Synchronization Issues]
        I1[❌ fillFrom: NO LOCK<br/>Can race with releaseBlobContent]
        I2[❌ operationCompleted:<br/>NOT ATOMIC with cleanup]
        I3[❌ REMOVED to avoid deadlock:<br/>fillFrom → setOperationCompleted]
        I4[✓ handleResponse: PutChunk lock<br/>Prevents race with releaseBlobContent]
    end

    style I1 fill:#ff0000,color:#ffffff
    style I2 fill:#ff9900,color:#ffffff
    style I3 fill:#ff9900,color:#ffffff
    style I4 fill:#00ff00
```

## Diagram 5: ByteBuf Retention Points (Reference Count Changes)

```mermaid
graph TD
    Start([ByteBuf Created by Channel<br/>refcount = 1]) --> R1

    R1[RETAIN +1<br/>fillFrom:1668<br/>readRetainedSlice<br/>refcount = 2] --> R2

    R2[RELEASE -1<br/>resolveOldestChunk<br/>Channel releases<br/>refcount = 1] --> Decision1{Path?}

    Decision1 -->|Compression| Comp
    Decision1 -->|No Compression| NoComp
    Decision1 -->|Encryption| Enc

    subgraph Compression[Compression Path]
        Comp[RELEASE -1<br/>compressChunk:1581<br/>buf.release<br/>refcount = 0]
        Comp --> CompNew[New compressed buffer<br/>refcount = 1]
    end

    subgraph Encryption[Encryption Path]
        Enc[RETAIN +1<br/>encryptChunk:1607<br/>retainedDuplicate<br/>refcount = 2]
        Enc --> EncJob[EncryptJob processes]
        EncJob --> EncRel1[RELEASE -1<br/>encryptionCallback:1510<br/>original released<br/>refcount = 1]
        EncRel1 --> EncRel2[RELEASE -1<br/>EncryptJob releases copy<br/>refcount = 0]
        EncRel2 --> EncNew[New encrypted buffer<br/>refcount = 1]
    end

    NoComp --> Final
    CompNew --> Final
    EncNew --> Final

    subgraph Network[Network Send]
        Final[RETAIN +1<br/>createPutRequest:1879<br/>retainedDuplicate<br/>refcount = 2]
        Final --> NetSend[PutRequest owns copy]
        NetSend --> NetRel[RELEASE -1<br/>PutRequest.release<br/>refcount = 1]
    end

    NetRel --> Cleanup

    subgraph Cleanup[Cleanup]
        Cleanup[RELEASE -1<br/>clear → releaseBlobContent:1262<br/>safeRelease<br/>refcount = 0]
    end

    Cleanup --> End([ByteBuf Freed])

    style R1 fill:#ff9999
    style Enc fill:#ff9999
    style Final fill:#ff9999
    style R2 fill:#99ff99
    style Comp fill:#99ff99
    style EncRel1 fill:#99ff99
    style EncRel2 fill:#99ff99
    style NetRel fill:#99ff99
    style Cleanup fill:#99ff99
    style End fill:#ffff99
```

## Diagram 6: The Race Condition - Timeline View

```mermaid
sequenceDiagram
    participant CF as ChunkFiller Thread
    participant Chunk as PutChunk
    participant Main as Response Thread

    Note over CF: fillFrom() - NO LOCK ⚠️
    CF->>Chunk: Read: if (buf == null)?
    Chunk-->>CF: false (buf exists)
    CF->>Chunk: Read: buf.readableBytes()

    Note over Main: Error received
    Main->>Main: setOperationCompleted()
    Main->>Main: releaseChunksOnCompletion()<br/>[PutOperation Lock]
    Main->>Chunk: releaseBlobContent()<br/>[PutChunk Lock]
    Chunk->>Chunk: ReferenceCountUtil.safeRelease(buf)
    Note over Chunk: ByteBuf FREED to pool
    Chunk->>Chunk: buf = null

    CF->>Chunk: Access: buf instanceof CompositeByteBuf?
    Note over CF: 💀 NullPointerException<br/>OR Use-After-Free

    rect rgb(255, 200, 200)
        Note over CF,Main: CRITICAL BUG:<br/>fillFrom has NO LOCK,<br/>can't prevent concurrent release
    end
```

## Diagram 7: State Transition and ByteBuf Ownership

```mermaid
stateDiagram-v2
    [*] --> Free: Chunk created

    Free --> Building: prepareForBuilding()<br/>buf = null

    Building --> Building: fillFrom()<br/>RETAIN slices<br/>buf = CompositeByteBuf

    Building --> AwaitingBlobTypeResolution: First chunk full<br/>buf has data

    Building --> Ready: onFillComplete()<br/>(no encryption)<br/>buf may be compressed

    Building --> Encrypting: onFillComplete()<br/>(encryption enabled)<br/>RETAIN copy for job

    AwaitingBlobTypeResolution --> Ready: maybeResolveAwaitingChunk()<br/>(no encryption)

    AwaitingBlobTypeResolution --> Encrypting: maybeResolveAwaitingChunk()<br/>(encryption enabled)

    Encrypting --> Ready: encryptionCallback()<br/>RELEASE original<br/>buf = encrypted

    Ready --> Complete: handleResponse()<br/>All replicas succeed<br/>buf unchanged

    Complete --> Free: clear()<br/>RELEASE buf<br/>buf = null

    Building --> Free: ERROR<br/>releaseBlobContent()
    Encrypting --> Free: ERROR<br/>(encryption thread releases)
    Ready --> Free: ERROR<br/>releaseBlobContent()
    Complete --> Free: ERROR<br/>releaseBlobContent()

    note right of Free
        buf = null
        No ownership
    end note

    note right of Building
        buf = retained slices
        ChunkFiller owns
        ⚠️ fillFrom() NO LOCK!
    end note

    note right of Encrypting
        original buf released
        EncryptJob owns copy
        State SKIPPED by
        releaseChunksOnCompletion
    end note

    note right of Ready
        buf = final buffer
        (compressed/encrypted)
        PutChunk owns
        Can be released by:
        - Main thread (clear)
        - Any thread (abort)
    end note

    note right of Complete
        buf = final buffer
        PutChunk owns
        Waiting for clear()
    end note
```

## Diagram 8: Deadlock Scenario (Why sync was removed)

```mermaid
sequenceDiagram
    participant CF as ChunkFiller Thread
    participant Chunk as PutChunk
    participant Op as PutOperation
    participant Resp as Response Thread

    Note over CF: If fillFrom() WAS synchronized...

    CF->>Chunk: fillFrom() [ACQUIRE PutChunk Lock]
    activate Chunk
    CF->>Chunk: fillFrom processing...
    CF->>Chunk: onFillComplete()
    CF->>Chunk: encryptChunk()
    Note over Chunk: Exception in encryptChunk!
    CF->>Op: setOperationExceptionAndComplete()<br/>[NEED PutOperation Lock]

    par Concurrent Error
        Resp->>Op: handleResponse error<br/>[ACQUIRE PutOperation Lock]
        activate Op
        Resp->>Op: setOperationCompleted()
        Resp->>Op: releaseChunksOnCompletion()
        Resp->>Chunk: releaseBlobContent()<br/>[NEED PutChunk Lock]
    end

    rect rgb(255, 200, 200)
        Note over CF,Resp: DEADLOCK!<br/>CF: PutChunk → needs PutOperation<br/>Resp: PutOperation → needs PutChunk
    end

    Note over CF,Resp: Author's "Fix": Remove sync from fillFrom()<br/>Result: Deadlock avoided, USE-AFTER-FREE introduced!
```

## Legend

- 🔴 Red boxes: RETAIN operations (refcount +1)
- 🟢 Green boxes: RELEASE operations (refcount -1)
- 🟡 Yellow boxes: ByteBuf freed (refcount = 0)
- ⚠️ Warning symbol: Synchronization issue
- 💀 Skull symbol: Critical bug (use-after-free)
- 🔒 Lock symbol: Synchronized method

## Key Insights

1. **ByteBuf lifecycle spans multiple threads**: ChunkFiller, Encryption, Main, and Channel callback threads all interact with the same ByteBuf reference.

2. **Reference counting is critical**: Each `retainedSlice()` and `retainedDuplicate()` must have a corresponding `release()`.

3. **State transitions determine ownership**:
   - `Building`: ChunkFiller owns
   - `Encrypting`: EncryptJob owns (releaseChunksOnCompletion skips)
   - `Ready`/`Complete`: PutChunk owns (can be released by multiple threads)

4. **The commit introduces a critical race**: Removing `synchronized` from `fillFrom()` allows concurrent access to `buf` while it's being freed.

5. **Lock hierarchy problem**: The code has a natural deadlock between PutOperation lock and PutChunk lock, which is why the author removed synchronization - but this trades a detectable deadlock for a silent use-after-free bug.

6. **Multiple release paths**: The same buffer can potentially be released by:
   - ChunkFiller thread (error during fill)
   - Encryption thread (callback)
   - Main thread (clear after success)
   - Any thread (releaseChunksOnCompletion on abort)

7. **Channel queue is separate**: `chunkFillerChannel.close()` releases queued ByteBufs that were never consumed - this is correct and necessary.
