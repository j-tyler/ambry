#!/bin/bash
#
# Script to run ByteBufferAsyncWritableChannel leak tests without Gradle build cache
# This ensures fresh test runs and accurate ByteBuf tracking results
#
# Usage: ./run_bytebuffer_leak_tests.sh
#

set -e  # Exit on error

echo "======================================================================================================"
echo "ByteBufferAsyncWritableChannel Leak Test Runner"
echo "======================================================================================================"
echo ""
echo "This script will:"
echo "  1. Clean Gradle build cache for ambry-commons module"
echo "  2. Run ByteBufferAsyncWritableChannelLeakTest (6 BASELINE tests)"
echo "  3. Run RetainingAsyncWritableChannelLeakTest (8 BASELINE tests)"
echo "  4. Execute tests with ByteBuf tracking enabled (if infrastructure is available)"
echo ""
echo "Note: These are BASELINE tests that verify correct behavior (expect NO leaks)."
echo "      To run BUG-EXPOSING tests that demonstrate actual leaks, use:"
echo "      ./run_bytebuffer_bug_tests.sh"
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
echo "[2/3] Running ByteBufferAsyncWritableChannel leak tests..."
echo "      Test classes:"
echo "        - ByteBufferAsyncWritableChannelLeakTest (6 tests)"
echo "        - RetainingAsyncWritableChannelLeakTest (8 tests)"
echo "      Module: ambry-commons"
echo "      Total: 14 leak detection tests"
echo ""

# Check if ByteBuf tracking infrastructure is available
BYTEBUF_TRACKING=""
if [ -f "modules/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar" ]; then
    echo "      ByteBuf tracking: ENABLED (agent JAR found)"
    BYTEBUF_TRACKING="-PwithByteBufTracking"
else
    echo "      ByteBuf tracking: DISABLED (agent JAR not found)"
    echo "      Note: To enable tracking, first run: git submodule update --init"
    echo "            Then build the agent: ./gradlew buildByteBufAgent"
fi

echo ""
echo "------------------------------------------------------------------------------------------------------"
echo ""

# Run the tests
# Use --no-build-cache to ensure tests are not cached
# Use --rerun-tasks to force test execution even if inputs haven't changed
# Use -i for info-level logging to see test execution details
./gradlew :ambry-commons:test \
    --tests '*ByteBufferAsyncWritableChannelLeakTest*' \
    --tests '*RetainingAsyncWritableChannelLeakTest*' \
    --no-build-cache \
    --rerun-tasks \
    -i \
    $BYTEBUF_TRACKING

TEST_EXIT_CODE=$?

echo ""
echo "======================================================================================================"
echo "[3/3] Test Execution Complete"
echo "======================================================================================================"
echo ""

if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "✅ All ByteBufferAsyncWritableChannel BASELINE tests PASSED"
    echo ""
    echo "What this means:"
    echo "  - Baseline tests verified correct cleanup behavior"
    echo "  - Tests properly released all ByteBufs they created"
    echo "  - No unexpected leaks in tested scenarios"
    echo ""
    echo "⚠️  NOTE: These baseline tests don't cover all production bugs!"
    echo "    Known bug: write(ByteBuffer) creates internal wrappers that leak"
    echo "    Run ./run_bytebuffer_bug_tests.sh to see the actual production bugs"
else
    echo "❌ Some ByteBufferAsyncWritableChannel leak tests FAILED"
    echo ""
    echo "What to do next:"
    echo "  1. Review the test output above for leak details"
    echo "  2. Check ByteBuf Flow Tracker output (if enabled) for leak paths"
    echo "  3. Investigate the specific test failures"
    echo "  4. Refer to BYTEBUF_LEAK_TEST_EXPECTATIONS.md for expected behavior"
fi

echo ""
echo "Test results location:"
echo "  HTML report: file://$(pwd)/ambry-commons/build/reports/tests/test/index.html"
echo "  Test logs:   $(pwd)/ambry-commons/build/test-results/test/"
echo ""

if [ -n "$BYTEBUF_TRACKING" ]; then
    echo "ByteBuf Tracker Output:"
    echo "  The tracker output is included in the test logs above"
    echo "  Look for sections marked: === ByteBuf Flow Tracker Report ==="
    echo ""
    echo "Understanding the output:"
    echo "  - 'Unreleased ByteBufs: 0' = No leaks detected (good!)"
    echo "  - 'Unreleased ByteBufs: N' = N buffers leaked (investigate!)"
    echo "  - Flow paths show the journey of each ByteBuf through the code"
    echo ""
fi

echo "For more information:"
echo "  - Memory leak analysis:    BYTEBUF_MEMORY_LEAK_ANALYSIS.md"
echo "  - Test expectations:       BYTEBUF_LEAK_TEST_EXPECTATIONS.md"
echo "  - Tracker integration:     BYTEBUF_TRACKER_INTEGRATION.md"
echo "  - High-risk test summary:  HIGH_RISK_LEAK_TEST_SUMMARY.md"
echo ""
echo "======================================================================================================"

exit $TEST_EXIT_CODE
