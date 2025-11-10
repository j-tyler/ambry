#!/bin/bash
#
# Script to run ByteBufferAsyncWritableChannel wrapper release test
# This test verifies that internal ByteBuf wrappers are properly released
#
# Usage: ./run_bytebuffer_bug_tests.sh
#

set -e  # Exit on error

echo "======================================================================================================"
echo "ByteBufferAsyncWritableChannel Wrapper Release Test Runner"
echo "======================================================================================================"
echo ""
echo "This test verifies the fix for a memory leak bug where internal ByteBuf wrappers"
echo "created by write(ByteBuffer) were never released."
echo ""
echo "Bug that was fixed:"
echo "  - write(ByteBuffer) creates internal ByteBuf wrappers via Unpooled.wrappedBuffer()"
echo "  - These wrappers had refCnt=1 and were NEVER released by the channel"
echo "  - Every call to write(ByteBuffer) leaked a direct ByteBuf wrapper"
echo "  - Direct buffers are NEVER garbage collected - this was a critical memory leak"
echo ""
echo "Tests to run:"
echo "  - ByteBufferAsyncWritableChannelTest.testWriteByteBufferReleasesWrapper"
echo "  Total: 1 test verifying the wrapper is properly released"
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
echo "[2/3] Running ByteBufferAsyncWritableChannel wrapper release test..."
echo "      Test class: ByteBufferAsyncWritableChannelTest"
echo "      Module: ambry-commons"
echo "      Test: testWriteByteBufferReleasesWrapper"
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

# Run the wrapper release test (allow to fail without exiting script)
set +e  # Temporarily disable exit-on-error
./gradlew :ambry-commons:test \
    --tests 'com.github.ambry.commons.ByteBufferAsyncWritableChannelTest.testWriteByteBufferReleasesWrapper' \
    --no-build-cache \
    --rerun-tasks \
    $BYTEBUF_TRACKING

TEST_EXIT_CODE=$?
set -e  # Re-enable exit-on-error

echo ""
echo "======================================================================================================"
echo "[3/3] Test Execution Complete"
echo "======================================================================================================"
echo ""

if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "✅ Test PASSED"
    echo ""
    echo "🎉 SUCCESS! The wrapper ByteBuf is properly released."
    echo ""
    echo "The wrapper ByteBuf created by write(ByteBuffer) is correctly released"
    echo "after resolveOldestChunk(), preventing memory leaks."
else
    echo "❌ Test FAILED"
    echo ""
    echo "⚠️  WARNING: The wrapper release test failed!"
    echo ""
    echo "What failed:"
    echo "  - After resolveOldestChunk(), wrapper ByteBuf still has refCnt=1"
    echo "  - Expected refCnt=0 (released)"
    echo "  - This indicates the wrapper is not being released = MEMORY LEAK"
    echo ""
    echo "This likely means:"
    echo "  1. The fix was not applied correctly, OR"
    echo "  2. The fix was reverted/overwritten, OR"
    echo "  3. There's a regression in the wrapper release logic"
fi

echo ""
echo "Fix Details:"
echo "  - Location: ByteBufferAsyncWritableChannel.java"
echo "  - Method: write(ByteBuffer src, Callback<Long> callback)"
echo "  - Solution: Mark ChunkData with isInternalWrapper flag"
echo "  - Release: Wrapper released in resolveOldestChunk() and resolveAllRemainingChunks()"
echo ""
echo "How the fix works:"
echo "  1. write(ByteBuffer) creates wrapper via Unpooled.wrappedBuffer()"
echo "  2. ChunkData is marked with isInternalWrapper=true flag"
echo "  3. When resolveOldestChunk() is called, it checks the flag"
echo "  4. If isInternalWrapper=true, wrapper.release() is called"
echo "  5. This decrements refCnt to 0, preventing memory leak"
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
