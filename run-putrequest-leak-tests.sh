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
echo "  1. PutRequestLeakTest (6 tests)"
echo "  2. PutOperationTest (2 regression tests for retainedDuplicate leak fixes)"
echo ""
echo "Regression tests verify:"
echo "  - Bug #1 fix: KMS exception after retainedDuplicate() is properly handled"
echo "  - Bug #2 fix: RequestInfo exception after PutRequest creation is properly handled"
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
echo "[1/2] Running PutRequestLeakTest (6 tests)..."
./gradlew :ambry-protocol:test \
  --tests "com.github.ambry.protocol.PutRequestLeakTest" \
  -PwithByteBufTracking \
  --no-build-cache \
  --rerun-tasks \
  --info

echo ""
echo "[2/2] Running retainedDuplicate regression tests in PutOperationTest..."
./gradlew :ambry-router:test \
  --tests "com.github.ambry.router.PutOperationTest.testEncryptChunkKmsExceptionReleasesRetainedDuplicate" \
  --tests "com.github.ambry.router.PutOperationTest.testFetchRequestsExceptionReleasesPutRequest" \
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
echo "Regression tests verify fixes for:"
echo "  1. Bug #1: KMS exception after retainedDuplicate() in encryptChunk()"
echo "     - Fixed with try-catch and cleanup"
echo "     - Test: testEncryptChunkKmsExceptionReleasesRetainedDuplicate"
echo ""
echo "  2. Bug #2: RequestInfo exception after PutRequest creation"
echo "     - Fixed with try-catch and cleanup"
echo "     - Test: testFetchRequestsExceptionReleasesPutRequest"
echo ""
