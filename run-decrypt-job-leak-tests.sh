#!/bin/bash

set -e

echo "================================================================================"
echo "Running DecryptJob Leak Tests with ByteBuf Flow Tracker"
echo "================================================================================"
echo ""
echo "This script will run the DecryptJobLeakTest tests with ByteBuf tracking enabled"
echo "to investigate the closeJob() memory leak."
echo ""
echo "Tests to run:"
echo "  1. testCloseJobBeforeRunLeaksBuffer() - CRITICAL BUG (leak expected)"
echo "  2. testCloseJobLeakIsRealWithoutFix() - Leak demonstration (leak expected)"
echo "  3. testExceptionDuringDecryptAfterDecryptKey() - Baseline (no leak)"
echo "  4. testCallbackExceptionInFinallyBlock() - Baseline (no leak)"
echo "  5. testDecryptJobResultMustBeReleased() - Baseline (no leak)"
echo "  6. testSuccessfulDecryptionNoLeak() - Baseline (no leak)"
echo ""
echo "================================================================================"
echo ""

# Clean previous test results
echo "Cleaning previous test results..."
./gradlew :ambry-router:cleanTest

echo ""
echo "Running tests with ByteBuf tracking enabled..."
echo ""

# Run the DecryptJobLeakTest with ByteBuf tracking
# The -PwithByteBufTracking flag enables the ByteBuf Flow Tracker agent
./gradlew :ambry-router:test --tests DecryptJobLeakTest -PwithByteBufTracking --info 2>&1 | tee decrypt-job-test-output.log

echo ""
echo "================================================================================"
echo "Test execution complete!"
echo "================================================================================"
echo ""

# Check for the ByteBuf tracker report file
REPORT_FILE="ambry-router/build/reports/bytebuf-tracking/ambry-router.txt"
if [ -f "$REPORT_FILE" ]; then
    echo "ByteBuf Flow Tracker Report found at: $REPORT_FILE"
    echo ""
    echo "================================================================================"
    echo "BYTEBUF FLOW TRACKER REPORT"
    echo "================================================================================"
    cat "$REPORT_FILE"
else
    echo "Warning: ByteBuf Flow Tracker report file not found at $REPORT_FILE"
    echo "Checking for tracker output in test logs..."
    echo ""
    if grep -q "ByteBuf Flow Tracker Report" decrypt-job-test-output.log; then
        echo "Found tracker output in logs:"
        echo ""
        grep -A 500 "ByteBuf Flow Tracker Report" decrypt-job-test-output.log || true
    fi
fi

echo ""
echo "================================================================================"
echo "Test Summary"
echo "================================================================================"

# Extract test results
if grep -q "DecryptJobLeakTest" decrypt-job-test-output.log; then
    echo "Test results:"
    grep "DecryptJobLeakTest > " decrypt-job-test-output.log || echo "No test result details found"
fi

echo ""
echo "Full test output saved to: decrypt-job-test-output.log"
echo ""
echo "Expected results:"
echo "  - Tests 1-2: Should demonstrate leaks (leak helper disabled)"
echo "  - Tests 3-6: Should pass with no leaks detected"
echo ""
echo "================================================================================"
