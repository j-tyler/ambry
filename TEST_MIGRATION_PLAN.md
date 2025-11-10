# Test Migration Analysis

## Existing Test Class: BoundedNettyByteBufReceiveTest

Has:
1. `testBoundedByteBufferReceive()` - Basic successful read test
2. `testBoundedByteBufferReceiveOnLargeRequest()` - Request too large test

## Leak Test Class: BoundedNettyByteBufReceiveLeakTest

Contains 6 tests:

### Tests to DELETE (debugging/duplicate tests):
1. **testReadFromIOExceptionAfterSizeBufferAllocation** - General error handling, not specific to the bug
2. **testReadFromIOExceptionOnRequestTooLarge** - DUPLICATE of `testBoundedByteBufferReceiveOnLargeRequest`
3. **testSuccessfulReadWithProperCleanup** - DUPLICATE of `testBoundedByteBufferReceive`
4. **testEOFExceptionDuringSizeRead** - General error handling, not specific to the bug

### Tests to KEEP (regression tests for the specific bug):
5. **testReadFromIOExceptionDuringContentRead** - ✅ Regression test for the intermediate buffer fix
6. **testEOFExceptionDuringContentRead** - ✅ Regression test for the intermediate buffer fix

## Why These Two Are Critical

These two tests specifically verify the bug we fixed:
- The channel must return 0 after providing size to force `readFrom()` to return
- Then on the next `readFrom()` call, an exception occurs during content read
- This tests the exact scenario where the intermediate buffer fix was needed
- Without the fix, these would have leaked ByteBufs allocated for content

## Recommendation

1. Move `testReadFromIOExceptionDuringContentRead` and `testEOFExceptionDuringContentRead` to `BoundedNettyByteBufReceiveTest`
2. Delete `BoundedNettyByteBufReceiveLeakTest.java` entirely
