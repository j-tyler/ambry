#!/bin/bash

# Script to run BoundedNettyByteBufReceive leak tests with verbose leak detection

set -e

echo "============================================================"
echo "Running BoundedNettyByteBufReceive Leak Tests (VERBOSE)"
echo "============================================================"
echo ""

# Clean first
./gradlew :ambry-network:clean --no-build-cache

# Run with paranoid leak detection and stack trace on failures
./gradlew :ambry-network:test \
  --tests "com.github.ambry.network.BoundedNettyByteBufReceiveLeakTest" \
  -PwithByteBufTracking \
  --no-build-cache \
  --rerun-tasks \
  --info \
  --stacktrace \
  | tee test-output-verbose.log

echo ""
echo "Full output saved to: test-output-verbose.log"
echo ""
echo "Look for 'LEAK:' messages from NettyByteBufLeakHelper"
echo ""
