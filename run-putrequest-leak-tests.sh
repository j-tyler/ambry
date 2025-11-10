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
echo "  2. PutOperationCompressionLeakTest (3 tests - uses PutRequest)"
echo "  3. PutOperationRetainedDuplicateLeakTest (4 tests - creates PutRequest with retainedDuplicate)"
echo ""
echo "Total: 13 tests (4 bug-exposing + 9 baseline)"
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
echo "[1/3] Running PutRequestLeakTest (6 tests)..."
./gradlew :ambry-protocol:test \
  --tests "com.github.ambry.protocol.PutRequestLeakTest" \
  -PwithByteBufTracking \
  --no-build-cache \
  --rerun-tasks \
  --info

echo ""
echo "[2/3] Running PutOperationCompressionLeakTest (3 tests - 2 bug-exposing)..."
./gradlew :ambry-router:test \
  --tests "com.github.ambry.router.PutOperationCompressionLeakTest" \
  -PwithByteBufTracking \
  --no-build-cache \
  --rerun-tasks \
  --info

echo ""
echo "[3/3] Running PutOperationRetainedDuplicateLeakTest (4 tests - 2 bug-exposing)..."
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
echo "  - Bug-exposing tests should show leaks (by design)"
echo "  - Baseline tests should show 0 leaks"
echo ""
echo "Next steps:"
echo "  1. Review the ByteBuf Flow Tracker output above"
echo "  2. Check for leak patterns in bug-exposing tests:"
echo "     - testCrcCalculationExceptionAfterCompressionLeaksCompressedBuffer"
echo "     - testEncryptionCallbackCrcExceptionLeaksEncryptedBuffer"
echo "     - testEncryptJobConstructorExceptionAfterRetainedDuplicateLeaksBuffer"
echo "     - testRequestInfoConstructionExceptionAfterPutRequestCreationLeaksBuffer"
echo "  3. These leaks indicate production bugs in PutOperation"
echo ""
