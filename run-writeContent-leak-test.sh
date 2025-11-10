#!/bin/bash

# Script to run NettyRequestWriteContentLeakTest
# This test should FAIL with current production code due to the leak bug
# After fixing NettyRequest.writeContent(), this test should PASS

set -e

echo "=================================================="
echo "NettyRequest.writeContent() Leak Test"
echo "=================================================="
echo ""
echo "This test demonstrates the CRITICAL bug:"
echo "  Location: NettyRequest.java:494-495"
echo "  Bug: httpContent.retain() without release on write() exception"
echo ""
echo "Expected with current code: TEST FAILURE (leak detected)"
echo "Expected after fix: TEST PASSES (no leak)"
echo "=================================================="
echo ""

echo "[1/2] Running test WITHOUT ByteBuf tracker..."
echo "=================================================="
./gradlew :ambry-rest:test \
  --tests "NettyRequestWriteContentLeakTest" \
  --no-build-cache \
  --rerun-tasks

echo ""
echo "[2/2] Running test WITH ByteBuf tracker for detailed leak analysis..."
echo "=================================================="
./gradlew :ambry-rest:test \
  --tests "NettyRequestWriteContentLeakTest" \
  -PwithByteBufTracking \
  --no-build-cache \
  --rerun-tasks

echo ""
echo "=================================================="
echo "Test run complete!"
echo "=================================================="
echo ""
echo "If tests FAILED:"
echo "  ✓ This confirms the production bug exists"
echo "  → Review the NettyByteBufLeakHelper error output"
echo "  → Fix NettyRequest.writeContent() line 494-495"
echo ""
echo "If tests PASSED:"
echo "  ✓ The bug has been fixed!"
echo "  → The fix in writeContent() is working correctly"
echo ""
echo "=================================================="
