#!/bin/bash
# Script to run all PutRequest-related ByteBuf leak tests without Gradle build cache
# This ensures fresh test runs with the ByteBuf tracer for accurate leak detection

set -e  # Exit on error

echo "========================================="
echo "PutRequest Leak Detection Test Suite"
echo "========================================="
echo ""
echo "This script runs all *LeakTest tests related to PutRequest with:"
echo "  - ByteBuf Flow Tracker enabled (-PwithByteBufTracking)"
echo "  - No Gradle build cache (--no-build-cache)"
echo "  - No Gradle daemon cache (--rerun-tasks)"
echo ""
echo "Tests included:"
echo "  1. PutRequestLeakTest (6 baseline tests)"
echo "  2. PutOperationRetainedDuplicateLeakTest (4 baseline tests - tests fixes for retainedDuplicate leaks)"
echo ""
echo "Total: 10 tests (all baseline - verifying fixes work correctly)"
echo ""

# Clean any previous test results and caches
echo "Cleaning previous test results and build caches..."
./gradlew clean --no-daemon --no-build-cache

echo ""
echo "========================================="
echo "Running PutRequest-specific leak tests"
echo "========================================="
echo ""

# Run PutRequestLeakTest (6 tests)
echo "[1/2] Running PutRequestLeakTest (6 baseline tests)..."
./gradlew :ambry-protocol:test \
  --tests "com.github.ambry.protocol.PutRequestLeakTest" \
  -PwithByteBufTracking \
  --no-build-cache \
  --rerun-tasks \
  --info

echo ""
echo "[2/2] Running PutOperationRetainedDuplicateLeakTest (4 baseline tests)..."
./gradlew :ambry-router:test \
  --tests "com.github.ambry.router.PutOperationRetainedDuplicateLeakTest" \
  -PwithByteBufTracking \
  --no-build-cache \
  --rerun-tasks \
  --info

echo ""
echo "========================================="
echo "Test Suite Complete"
echo "========================================="
echo ""
echo "Results:"
echo "  - Check test output above for ByteBuf Flow Tracker reports"
echo "  - Look for 'Unreleased ByteBufs: N' in the output"
echo "  - All tests should show 0 leaks (fixes are in place)"
echo ""
echo "Tests verify fixes for:"
echo "  1. Bug #3: Exception during KMS getRandomKey() after retainedDuplicate()"
echo "     - Fixed in PutOperation.encryptChunk() with try-catch and cleanup"
echo "     - Tested by: testEncryptJobConstructorExceptionHandledProperly"
echo ""
echo "  2. Bug #4: Exception during RequestInfo construction after PutRequest creation"
echo "     - Fixed in PutOperation.fetchRequests() with try-catch and cleanup"
echo "     - Tested by: testRequestInfoConstructionExceptionHandledProperly"
echo ""
