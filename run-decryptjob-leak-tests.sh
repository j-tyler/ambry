#!/bin/bash

###############################################################################
# DecryptJob Leak Test Runner
#
# This script runs all DecryptJob-related *LeakTest tests with:
# - ByteBuf tracking enabled (via bytebuddy-bytebuf-tracer)
# - Gradle build cache disabled
# - Clean build to ensure fresh bytecode instrumentation
#
# The DecryptJob.closeJob() bug is CRITICAL - closeJob() does NOT release
# encryptedBlobContent when called before run() executes, causing memory leaks.
#
# Tests in DecryptJobLeakTest.java:
# 1. testCloseJobBeforeRunLeaksBuffer - Demonstrates the CRITICAL closeJob() bug
# 2. testCloseJobLeakIsRealWithoutFix - Proves the bug with manual verification
# 3. testExceptionDuringDecryptAfterDecryptKey - Exception handling test
# 4. testCallbackExceptionInFinallyBlock - Callback exception test
# 5. testDecryptJobResultMustBeReleased - Result lifecycle test
# 6. testSuccessfulDecryptionNoLeak - Baseline success test
#
# Expected Results:
# - Tests #1 and #2 will PASS (they expect leaks and disable leak detection)
# - Tests #3-6 should PASS (they expect NO leaks)
# - ByteBuf tracker will report leaked buffers for tests #1 and #2
#
# Usage:
#   ./run-decryptjob-leak-tests.sh
###############################################################################

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "================================================================================"
echo "DecryptJob Leak Test Runner"
echo "================================================================================"
echo ""
echo "Target: DecryptJobLeakTest.java (6 tests)"
echo "Focus: DecryptJob.closeJob() memory leak (CRITICAL BUG)"
echo ""
echo "Configuration:"
echo "  - ByteBuf tracking: ENABLED (-PwithByteBufTracking)"
echo "  - Gradle cache: DISABLED (--no-build-cache)"
echo "  - Clean build: YES"
echo ""
echo "================================================================================"
echo ""

# Step 1: Clean the ambry-router module
echo "Step 1/4: Cleaning ambry-router module..."
./gradlew :ambry-router:clean --no-build-cache

# Step 2: Build the ByteBuf tracker agent (if needed)
echo ""
echo "Step 2/4: Building ByteBuf tracker agent..."
./gradlew buildByteBufAgent --no-build-cache

# Step 3: Run DecryptJob leak tests with ByteBuf tracking
echo ""
echo "Step 3/4: Running DecryptJobLeakTest with ByteBuf tracking..."
echo ""
echo "================================================================================"
echo "TEST EXECUTION"
echo "================================================================================"

# Run the tests and capture the exit code
set +e  # Don't exit on test failure
./gradlew :ambry-router:test \
  --no-build-cache \
  --tests 'DecryptJobLeakTest' \
  -PwithByteBufTracking \
  --info

TEST_EXIT_CODE=$?
set -e  # Re-enable exit on error

# Step 4: Report results
echo ""
echo "================================================================================"
echo "TEST RESULTS SUMMARY"
echo "================================================================================"
echo ""

if [ $TEST_EXIT_CODE -eq 0 ]; then
  echo "✓ All DecryptJob leak tests PASSED"
  echo ""
  echo "Expected Results:"
  echo "  ✓ testCloseJobBeforeRunLeaksBuffer - PASSED (expects leak, disabled detection)"
  echo "  ✓ testCloseJobLeakIsRealWithoutFix - PASSED (expects leak, disabled detection)"
  echo "  ✓ testExceptionDuringDecryptAfterDecryptKey - PASSED (expects NO leak)"
  echo "  ✓ testCallbackExceptionInFinallyBlock - PASSED (expects NO leak)"
  echo "  ✓ testDecryptJobResultMustBeReleased - PASSED (expects NO leak)"
  echo "  ✓ testSuccessfulDecryptionNoLeak - PASSED (expects NO leak)"
  echo ""
  echo "Review ByteBuf Tracker Output Above:"
  echo "  - Look for 'ByteBuf Flow Tracker Report' sections"
  echo "  - Tests #1 and #2 should show 'Unreleased ByteBufs: 1'"
  echo "  - These leaks prove the DecryptJob.closeJob() bug exists"
  echo "  - Tests #3-6 should show 'Unreleased ByteBufs: 0'"
else
  echo "✗ Some tests FAILED (exit code: $TEST_EXIT_CODE)"
  echo ""
  echo "This may indicate:"
  echo "  1. Unexpected leaks in tests #3-6 (would indicate NEW bugs)"
  echo "  2. Test infrastructure issues"
  echo "  3. ByteBuf tracker not properly attached"
  echo ""
  echo "Check the test output above for details."
fi

echo ""
echo "================================================================================"
echo "NEXT STEPS"
echo "================================================================================"
echo ""
echo "To investigate further:"
echo ""
echo "1. Review the ByteBuf Flow Tracker output above"
echo "   - Search for 'ByteBuf Flow Tracker Report'"
echo "   - Look for 'Unreleased ByteBufs' count"
echo "   - Examine flow paths showing buffer allocation and release"
echo ""
echo "2. Run specific tests individually:"
echo "   ./gradlew :ambry-router:test --tests 'DecryptJobLeakTest.testCloseJobBeforeRunLeaksBuffer' -PwithByteBufTracking"
echo ""
echo "3. View detailed test reports:"
echo "   open ambry-router/build/reports/tests/test/index.html"
echo ""
echo "4. Check ByteBuf tracker logs:"
echo "   cat ambry-router/build/test-results/test/TEST-*.xml | grep -A 50 'ByteBuf'"
echo ""
echo "5. To fix the DecryptJob.closeJob() bug:"
echo "   Edit: ambry-router/src/main/java/com/github/ambry/router/DecryptJob.java:112-114"
echo "   Add: encryptedBlobContent.release() in closeJob() method"
echo ""
echo "================================================================================"

exit $TEST_EXIT_CODE
