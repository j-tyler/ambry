#!/bin/bash
#
# Script to run ByteBufferAsyncWritableChannel BUG-EXPOSING tests
# These tests demonstrate actual production bugs where ByteBuf wrappers leak
#
# ⚠️ WARNING: These tests EXPECT LEAKS and use leakHelper.setDisabled(true)
#
# Usage: ./run_bytebuffer_bug_tests.sh
#

set -e  # Exit on error

echo "======================================================================================================"
echo "ByteBufferAsyncWritableChannel BUG-EXPOSING Test Runner"
echo "======================================================================================================"
echo ""
echo "⚠️  WARNING: These tests demonstrate ACTUAL PRODUCTION BUGS"
echo ""
echo "These tests will show memory leaks in the ByteBuf Flow Tracker output."
echo "This is EXPECTED behavior - the tests are proving bugs exist in production code."
echo ""
echo "Bug Summary:"
echo "  - write(ByteBuffer) creates internal ByteBuf wrappers via Unpooled.wrappedBuffer()"
echo "  - These wrappers have refCnt=1 and are NEVER released by the channel"
echo "  - Every call to write(ByteBuffer) leaks a direct ByteBuf wrapper"
echo "  - Direct buffers are NEVER garbage collected - this is a critical memory leak"
echo ""
echo "Tests to run:"
echo "  - ByteBufferAsyncWritableChannelBugTest (5 bug-exposing tests)"
echo "  - UnpooledWrapperLeakProofTest (6 proof tests demonstrating GC prevention)"
echo "  Total: 11 tests"
echo ""
echo "======================================================================================================"
echo ""

# Change to the repository root directory
cd "$(dirname "$0")"

echo "[1/3] Cleaning Gradle cache for ambry-commons..."
echo "      This ensures a fresh build without cached test results"
echo ""
./gradlew :ambry-commons:clean --no-build-cache

echo ""
echo "======================================================================================================"
echo "[2/3] Running ByteBufferAsyncWritableChannel BUG tests..."
echo "      Test classes:"
echo "        - ByteBufferAsyncWritableChannelBugTest (5 bug-exposing tests)"
echo "        - UnpooledWrapperLeakProofTest (6 proof tests)"
echo "      Module: ambry-commons"
echo "      Total: 11 tests demonstrating leak mechanism"
echo ""

# Check if ByteBuf tracking infrastructure is available
BYTEBUF_TRACKING=""
if [ -f "modules/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar" ]; then
    echo "      ByteBuf tracking: ENABLED (agent JAR found)"
    echo "      ⚠️  Leak detection is CRITICAL for these tests"
    BYTEBUF_TRACKING="-PwithByteBufTracking"
elif [ -f "/Users/jumarsh/personal-ws/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar" ]; then
    echo "      ByteBuf tracking: ENABLED (agent JAR found at alternate location)"
    echo "      ⚠️  Leak detection is CRITICAL for these tests"
    BYTEBUF_TRACKING="-PwithByteBufTracking"
else
    echo "      ByteBuf tracking: DISABLED (agent JAR not found)"
    echo ""
    echo "      ⚠️  WARNING: Without ByteBuf tracking, you won't see the leaks!"
    echo ""
    echo "      To enable tracking:"
    echo "        1. git submodule update --init modules/bytebuddy-bytebuf-tracer"
    echo "        2. cd modules/bytebuddy-bytebuf-tracer"
    echo "        3. mvn clean package -DskipTests"
    echo ""
fi

echo ""
echo "------------------------------------------------------------------------------------------------------"
echo ""

# Run the bug tests
./gradlew :ambry-commons:test \
    --tests 'ByteBufferAsyncWritableChannelBugTest*' \
    --tests 'UnpooledWrapperLeakProofTest*' \
    --no-build-cache \
    --rerun-tasks \
    -i \
    $BYTEBUF_TRACKING

TEST_EXIT_CODE=$?

echo ""
echo "======================================================================================================"
echo "[3/3] Bug Test Execution Complete"
echo "======================================================================================================"
echo ""

if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "✅ All bug-exposing tests PASSED (tests ran successfully)"
    echo ""
    echo "⚠️  IMPORTANT: Tests passing does NOT mean there are no bugs!"
    echo "    These tests use leakHelper.setDisabled(true) to allow leaks."
    echo ""
    echo "To verify the bugs exist:"
    echo "  1. Check the ByteBuf Flow Tracker output above"
    echo "  2. Look for: 'CRITICAL_LEAK|root=UnpooledByteBufAllocator.directBuffer'"
    echo "  3. Count the number of leaked direct buffers"
    echo ""
    echo "Expected leaks per test:"
    echo ""
    echo "  ByteBufferAsyncWritableChannelBugTest:"
    echo "    - testWriteByteBufferToClosedChannelLeaksWrapper: 0-1 leaks (edge case)"
    echo "    - testWriteByteBufferThenCloseLeaksWrapper: 2 leaks"
    echo "    - testWriteByteBufferNormalFlowLeaksWrapper: 1 leak"
    echo "    - testMultipleByteBufferWritesLeakMultipleWrappers: 3 leaks"
    echo "    - testByteBufferVsByteBufLeakComparison: 1 leak (from ByteBuffer write)"
    echo ""
    echo "  UnpooledWrapperLeakProofTest (all tests show wrappers prevent GC):"
    echo "    - testWrapperNotReleased_PreventsGC: 1 wrapper prevents ByteBuffer GC"
    echo "    - testWrapperReleasedImmediately_RequiresCarefulTiming: Shows timing window"
    echo "    - testMultipleWrappers_AccumulatingLeak: 10 wrappers prevent 10 ByteBuffers from GC"
    echo "    - testWrapperHoldsReference_EvenAfterDataConsumed: 1 wrapper prevents GC even after read"
    echo "    - testExactBugScenario_WrapperHiddenFromCaller: Demonstrates exact production bug"
    echo "    - testControlCase_NoByteBufWrapper_ProperCleanup: Shows correct pattern (no leak)"
    echo ""
else
    echo "❌ Some bug-exposing tests FAILED"
    echo ""
    echo "This could mean:"
    echo "  1. Test implementation issue (check test code)"
    echo "  2. Production code was already fixed (good!)"
    echo "  3. Environment issue (check dependencies)"
fi

echo ""
echo "Bug Details:"
echo "  - Location: ByteBufferAsyncWritableChannel.java:89"
echo "  - Method: write(ByteBuffer src, Callback<Long> callback)"
echo "  - Issue: return write(Unpooled.wrappedBuffer(src), callback);"
echo "  - Problem: Unpooled.wrappedBuffer() creates ByteBuf with refCnt=1, never released"
echo ""
echo "Fix Required:"
echo "  Option 1: Release wrappers in close() and resolveOldestChunk()"
echo "  Option 2: Track wrappers separately and release them after conversion"
echo "  Option 3: Don't use wrappers - copy data directly"
echo ""
echo "Test results location:"
echo "  HTML report: file://$(pwd)/ambry-commons/build/reports/tests/test/index.html"
echo "  Test logs:   $(pwd)/ambry-commons/build/test-results/test/"
echo ""

if [ -n "$BYTEBUF_TRACKING" ]; then
    echo "======================================================================================================"
    echo "ByteBuf Tracker Output Analysis"
    echo "======================================================================================================"
    echo ""
    echo "Look for the 'ByteBuf Flow Final Report' section above."
    echo ""
    echo "What to look for:"
    echo "  - 'Leak Paths: N' - Shows number of leaked buffer types"
    echo "  - 'Critical Leaks (🚨): N' - Shows number of direct buffer leaks"
    echo "  - '[LEAK:ref=1] UnpooledByteBufAllocator.directBuffer' - Shows each leak"
    echo ""
    echo "Understanding the output:"
    echo "  - Direct buffer leaks are CRITICAL (🚨) - never garbage collected"
    echo "  - Each leak represents one call to write(ByteBuffer)"
    echo "  - The wrapper ByteBuf has no release path in the code"
    echo ""
fi

echo "======================================================================================================"

exit $TEST_EXIT_CODE
