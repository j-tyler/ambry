# NettyRequest Leak Investigation Scripts

This directory contains scripts to help identify ByteBuf memory leaks in NettyRequest and NettyMultipartRequest classes.

## Scripts

### 1. `run_netty_request_leak_tests.sh` - Complete Test Suite

**Purpose**: Runs ALL tests for NettyRequest and NettyMultipartRequest with ByteBuf tracking enabled.

**Usage**:
```bash
./run_netty_request_leak_tests.sh
```

**What it does**:
1. Verifies you're on the correct branch
2. Cleans all build caches
3. Builds the bytebuf-tracker agent
4. Runs all 15 NettyRequestTest tests
5. Runs all 7 NettyMultipartRequestTest tests
6. Generates ByteBuf tracking reports
7. Displays leak summaries

**Output files**:
- `netty_request_test_output.log` - Full test output for NettyRequestTest
- `netty_multipart_request_test_output.log` - Full test output for NettyMultipartRequestTest
- `ambry-rest/build/reports/bytebuf-tracking/*.txt` - ByteBuf flow tracking reports

**When to use**:
- Initial investigation to get complete picture
- Before committing fixes to verify all tests pass
- When you need comprehensive leak analysis

**Estimated time**: 2-5 minutes

---

### 2. `run_focused_leak_tests.sh` - Critical Tests Only

**Purpose**: Runs only the most critical tests likely to reveal ByteBuf leaks for faster iteration.

**Usage**:
```bash
./run_focused_leak_tests.sh
```

**Tests executed**:

**NettyRequestTest** (5 critical tests):
- `contentAddAndReadTest` - Tests ByteBuf content handling
- `readIntoExceptionsTest` - Tests error path cleanup
- `closeTest` - Tests resource cleanup on close
- `operationsAfterCloseTest` - Tests post-close state
- `backPressureTest` - Tests flow control and buffering

**NettyMultipartRequestTest** (all 7 tests):
- `instantiationTest` - Tests object creation
- `multipartRequestDecodeTest` - Tests multipart parsing
- `refCountsAfterCloseTest` - **⭐ Tests ByteBuf reference counting**
- `operationsAfterCloseTest` - Tests post-close state
- `readIntoExceptionsTest` - Tests error handling
- `prepareTest` - Tests request preparation

**Output files**:
- `focused_netty_request_output.log` - Focused test output
- `focused_multipart_request_output.log` - Multipart test output
- `ambry-rest/build/reports/bytebuf-tracking/*.txt` - ByteBuf flow reports

**When to use**:
- During active debugging/fixing of leaks
- Quick validation after making changes
- Iterative development workflow

**Estimated time**: 1-2 minutes

---

## Understanding the Output

### ByteBuf Tracking Reports

Reports are saved to: `ambry-rest/build/reports/bytebuf-tracking/`

**Report format**:
```
================================================================================
ByteBuf Flow Tracker Report
================================================================================
=== ByteBuf Flow Summary ===
Total Root Methods: 15          # Number of allocation points
Total Traversals: 342           # Total ByteBuf method calls tracked
Unique Paths: 28                # Distinct flow paths
Leak Paths: 2                   # 🚨 PATHS WITH UNRELEASED BYTEBUFS

--------------------------------------------------------------------------------
Flow Tree:
--------------------------------------------------------------------------------
ROOT: NettyRequest.addContent [count=45]
└── HttpContent.content [ref=1, count=45]
    └── ByteBuf.readableBytes [ref=1, count=45]
        └── ByteBuf.release [ref=0, count=45]    ✅ Properly released

ROOT: NettyRequest.close [count=3]
└── processContent [ref=1, count=3] ⚠️ LEAK      🚨 NOT RELEASED!
```

### Reading Flow Trees

**ref=N**: ByteBuf reference count at this point
- `ref=0` = ByteBuf has been released (good!)
- `ref=1` or higher at leaf node = LEAK

**count=N**: Number of times this path was executed

**⚠️ LEAK**: Indicates ByteBuf was not released before end of flow

### Common Leak Patterns

1. **Missing release on error paths**:
```
ROOT: allocate [count=1]
└── processContent [ref=1, count=1]
    └── throwException [ref=1, count=1] ⚠️ LEAK
```
*Fix: Add try-finally with release in catch block*

2. **Transferred ownership not documented**:
```
ROOT: createRequest [count=5]
└── getContent [ref=1, count=5] ⚠️ LEAK
```
*Fix: Ensure caller releases, or document that callee should release*

3. **Constructor wrapping without tracking**:
```
ROOT: allocate [count=1]
└── writeData [ref=1, count=1]
    # Flow breaks here - constructor not tracked!
```
*Fix: Add class to trackConstructors in build.gradle*

---

## Interpreting Test Results

### ✅ Success Case
```
✓ All tests passed!
✅ No leaks detected
```
No action needed - ByteBuf lifecycle is correct.

### ⚠️ Test Failures
```
⚠️ WARNING: Some tests failed! Check the logs for details.
```
**Action**: Review log files for exception stack traces.

### 🚨 Leak Detection
```
⚠️ LEAKS FOUND!

Leak paths:
NettyRequest.addContent -> processData [FINAL REF=1] ⚠️ LEAK
NettyMultipartRequest.prepare -> parseContent [FINAL REF=2] ⚠️ LEAK
```

**Action Steps**:
1. Note the leak path (which methods were called)
2. Find the source code for the root and leaf methods
3. Trace ByteBuf lifecycle through the path
4. Identify missing `release()` calls
5. Add proper cleanup (typically in try-finally or close methods)
6. Re-run tests to verify fix

---

## Investigation Workflow

### Step 1: Initial Run
```bash
./run_netty_request_leak_tests.sh
```
Get complete picture of all leaks.

### Step 2: Analyze Reports
```bash
cat ambry-rest/build/reports/bytebuf-tracking/*.txt
```
Review flow trees and identify leak patterns.

### Step 3: Identify Root Cause
- Find source files mentioned in leak paths
- Review ByteBuf allocation and release logic
- Check error handling paths (most common leak location)

### Step 4: Make Fixes
- Add proper `release()` calls
- Use try-finally for exception safety
- Document ownership transfer

### Step 5: Quick Validation
```bash
./run_focused_leak_tests.sh
```
Verify fix works for critical tests.

### Step 6: Full Validation
```bash
./run_netty_request_leak_tests.sh
```
Ensure no regressions in other tests.

---

## Troubleshooting

### "No ByteBuf tracking reports found"

**Causes**:
- ByteBuf tracker agent didn't load
- No ByteBuf allocations occurred (unlikely)
- Tests failed before generating reports

**Solutions**:
```bash
# Verify agent JAR exists
ls -lh bytebuf-tracker/build/libs/bytebuf-tracker-agent.jar

# Rebuild agent
./gradlew :bytebuf-tracker:clean :bytebuf-tracker:agentJar -PwithByteBufTracking

# Run with verbose output
./gradlew :ambry-rest:test --info -PwithByteBufTracking --tests "NettyRequestTest.closeTest"
```

### "Tests pass but still show leaks"

This is the expected behavior! The ByteBuf tracker detects leaks that tests might not catch.

**Action**: Fix the production code to properly release ByteBufs.

### Scripts fail with permission errors

```bash
chmod +x run_netty_request_leak_tests.sh
chmod +x run_focused_leak_tests.sh
```

---

## Key Files to Examine

Based on test names, likely leak locations:

### NettyRequest.java
- `addContent()` - Handles incoming ByteBuf chunks
- `readInto()` - Reads ByteBuf into channel
- `close()` - Cleanup and resource release
- `content()` - Returns accumulated ByteBuf

### NettyMultipartRequest.java
- Constructor - May wrap ByteBuf from parent
- `prepare()` - Prepares multipart data
- `close()` - Cleanup and resource release
- `refCounts` - Reference count management

### Common Leak Locations
1. Exception handling paths (catch blocks)
2. Early returns before release
3. Async callbacks that never complete
4. Objects stored but never released

---

## Next Steps After Running Tests

1. **Review this document** for your specific results
2. **Examine source code** for methods in leak paths
3. **Check for missing releases** especially in error paths
4. **Apply fixes** with proper try-finally blocks
5. **Re-test** to verify fixes
6. **Commit** once all leaks resolved

---

## Related Documentation

- `BYTEBUF_TRACKER_INTEGRATION.md` - How ByteBuf tracking works
- `CONSTRUCTOR_TRACKING.md` - Constructor tracking configuration
- `bytebuf-tracker/README.md` - Tracker module details

---

**Remember**: Leaks often hide in error paths and cleanup logic. Focus on exception handling first!
