#!/bin/bash

# Script to run BoundedNettyByteBufReceive leak tests with ByteBuf tracking enabled
# and without Gradle build cache to ensure fresh execution.
#
# This script:
# 1. Cleans the build to ensure no cached state
# 2. Runs tests with ByteBuf tracking enabled
# 3. Disables Gradle build cache
# 4. Runs only the BoundedNettyByteBufReceiveLeakTest tests

set -e  # Exit on error

echo "============================================================"
echo "Running BoundedNettyByteBufReceive Leak Tests"
echo "============================================================"
echo ""
echo "Configuration:"
echo "  - ByteBuf Tracking: ENABLED"
echo "  - Gradle Build Cache: DISABLED"
echo "  - Test Class: BoundedNettyByteBufReceiveLeakTest"
echo ""

# Clean the network module first
echo "Cleaning ambry-network module..."
./gradlew :ambry-network:clean --no-build-cache

# Build the bytebuf-tracker agent if needed
echo ""
echo "Building ByteBuf tracker agent..."
./gradlew :bytebuf-tracker:agentJar -PwithByteBufTracking --no-build-cache

# Run the leak tests with tracking enabled
echo ""
echo "Running leak tests with ByteBuf tracking..."
echo ""
./gradlew :ambry-network:test \
  --tests "com.github.ambry.network.BoundedNettyByteBufReceiveLeakTest" \
  -PwithByteBufTracking \
  --no-build-cache \
  --rerun-tasks

echo ""
echo "============================================================"
echo "Test execution complete!"
echo "============================================================"
echo ""
echo "ByteBuf tracking report should be at:"
echo "  ambry-network/build/reports/bytebuf-tracking/ambry-network.txt"
echo ""
echo "Regular test report at:"
echo "  ambry-network/build/reports/tests/test/index.html"
echo ""
