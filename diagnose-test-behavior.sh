#!/bin/bash

# Diagnostic script to understand test behavior

echo "============================================================"
echo "Diagnosing BoundedNettyByteBufReceive Test Behavior"
echo "============================================================"
echo ""
echo "The test expects:"
echo "  Call 1: readFrom() returns 8 (size only)"
echo "  Call 2: readFrom() throws IOException (content read)"
echo ""
echo "But the implementation:"
echo "  Call 1: Reads size (8), then tries content -> throws IOException"
echo ""
echo "This is a TEST BUG, not a production code bug!"
echo ""
echo "============================================================"
echo ""

# Run just one failing test with stack trace
echo "Running testReadFromIOExceptionDuringContentRead..."
./gradlew :ambry-network:test \
  --tests "com.github.ambry.network.BoundedNettyByteBufReceiveLeakTest.testReadFromIOExceptionDuringContentRead" \
  --no-build-cache \
  --rerun-tasks \
  --info 2>&1 | tee diagnostic-output.log

echo ""
echo "============================================================"
echo "Analysis:"
echo "============================================================"
echo ""
echo "Check diagnostic-output.log for:"
echo "1. Which line the IOException occurs on (should be line 196, first call)"
echo "2. Whether any assertions pass before the exception"
echo "3. Whether NettyByteBufLeakHelper reports leaks"
echo ""
