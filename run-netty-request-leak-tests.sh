#!/bin/bash

# Script to run NettyRequest and NettyMultipartRequest leak tests
# with ByteBuf tracking enabled and without Gradle build cache
#
# This script runs only the *LeakTest tests related to NettyRequest/NettyMultipartRequest
# to investigate potential memory leaks in the REST request handling code.
#
# Usage: ./run-netty-request-leak-tests.sh

set -e  # Exit on error

echo "=================================================="
echo "NettyRequest/NettyMultipartRequest Leak Tests"
echo "=================================================="
echo ""
echo "This script will:"
echo "  1. Clean Gradle build cache"
echo "  2. Build the ByteBuf tracker agent"
echo "  3. Run NettyRequestLeakTest with ByteBuf tracking"
echo "  4. Run NettyMultipartRequestLeakTest with ByteBuf tracking"
echo ""
echo "Output will include ByteBuf Flow Tracker leak reports"
echo "=================================================="
echo ""

# Clean Gradle build cache to ensure fresh test runs
echo "[1/4] Cleaning Gradle build cache..."
./gradlew clean --no-build-cache

echo ""
echo "[2/4] Building ByteBuf tracker agent..."
./gradlew buildByteBufAgent

echo ""
echo "[3/4] Running NettyRequestLeakTest with ByteBuf tracking..."
echo "=================================================="
./gradlew :ambry-rest:test \
  --tests "NettyRequestLeakTest" \
  -PwithByteBufTracking \
  --no-build-cache \
  --rerun-tasks

echo ""
echo "[4/4] Running NettyMultipartRequestLeakTest with ByteBuf tracking..."
echo "=================================================="
./gradlew :ambry-rest:test \
  --tests "NettyMultipartRequestLeakTest" \
  -PwithByteBufTracking \
  --no-build-cache \
  --rerun-tasks

echo ""
echo "=================================================="
echo "Test run complete!"
echo "=================================================="
echo ""
echo "Review the ByteBuf Flow Tracker reports above."
echo "Look for:"
echo "  - 'Unreleased ByteBufs: N' where N > 0 (indicates leak)"
echo "  - Flow paths ending without .release() calls"
echo "  - refCnt > 0 at leaf nodes"
echo ""
echo "Known bugs to watch for:"
echo "  1. NettyRequest.writeContent() - Exception after retain() leaks buffer"
echo "  2. NettyRequest.addContent() - Should be SAFE (exception before retain)"
echo "  3. NettyMultipartRequest similar patterns"
echo ""
echo "For detailed analysis, see:"
echo "  - BYTEBUF_LEAK_TEST_EXPECTATIONS.md"
echo "  - BYTEBUF_TRACKER_OUTPUT_ANALYSIS_GUIDE.md"
echo "=================================================="
