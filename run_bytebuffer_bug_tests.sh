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
echo "These tests will FAIL (red test results) when the production bug exists."
echo "After applying the fix, these tests will PASS (green test results)."
echo ""
echo "Bug Summary:"
echo "  - write(ByteBuffer) creates internal ByteBuf wrappers via Unpooled.wrappedBuffer()"
echo "  - These wrappers have refCnt=1 and are NEVER released by the channel"
echo "  - Every call to write(ByteBuffer) leaks a direct ByteBuf wrapper"
echo "  - Direct buffers are NEVER garbage collected - this is a critical memory leak"
echo ""
echo "Tests to run:"
echo "  - ByteBufferAsyncWritableChannelBugTest (1 test)"
echo "  Total: 1 test demonstrating the wrapper leak"
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
echo "[2/3] Running ByteBufferAsyncWritableChannel BUG test..."
echo "      Test class: ByteBufferAsyncWritableChannelBugTest"
echo "      Module: ambry-commons"
echo "      Test: testWriteByteBufferLeaksWrapper"
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

# Run the bug test (allow to fail without exiting script)
set +e  # Temporarily disable exit-on-error
./gradlew :ambry-commons:test \
    --tests 'com.github.ambry.commons.ByteBufferAsyncWritableChannelBugTest.testWriteByteBufferLeaksWrapper' \
    --no-build-cache \
    --rerun-tasks \
    $BYTEBUF_TRACKING

TEST_EXIT_CODE=$?
set -e  # Re-enable exit-on-error

echo ""
echo "======================================================================================================"
echo "[3/3] Bug Test Execution Complete"
echo "======================================================================================================"
echo ""

if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "✅ Test PASSED"
    echo ""
    echo "🎉 EXCELLENT! The production bug has been FIXED!"
    echo ""
    echo "The wrapper ByteBuf is now properly released after resolveOldestChunk()."
else
    echo "❌ Test FAILED"
    echo ""
    echo "✅ EXPECTED! This confirms the production bug exists."
    echo ""
    echo "What failed:"
    echo "  - After resolveOldestChunk(), wrapper ByteBuf still has refCnt=1"
    echo "  - Expected refCnt=0 (released)"
    echo "  - This proves the wrapper is never released = MEMORY LEAK"
    echo ""
    echo "Next steps:"
    echo "  1. Apply the fix from BYTEBUFFER_LEAK_FIX_SUMMARY.md"
    echo "  2. Re-run this script to verify test passes after fix"
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
