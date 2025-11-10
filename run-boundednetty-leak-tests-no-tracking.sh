#!/bin/bash

# Run BoundedNettyByteBufReceive leak tests WITHOUT ByteBuf tracking
# to see if the tracker itself is causing the issue

set -e

echo "============================================================"
echo "Running BoundedNettyByteBufReceive Leak Tests (NO TRACKING)"
echo "============================================================"
echo ""

./gradlew :ambry-network:clean --no-build-cache

echo "Running tests WITHOUT ByteBuf tracking..."
./gradlew :ambry-network:test \
  --tests "com.github.ambry.network.BoundedNettyByteBufReceiveLeakTest" \
  --no-build-cache \
  --rerun-tasks

echo ""
echo "============================================================"
echo "Tests complete! Check if they pass without tracking."
echo "============================================================"
