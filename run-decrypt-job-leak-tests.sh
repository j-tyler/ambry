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

# Stop Gradle daemon to ensure clean state
echo "Stopping Gradle daemon..."
./gradlew --stop

echo ""
echo "Cleaning previous test results and build cache..."
./gradlew :bytebuf-tracker:clean :ambry-router:clean --no-build-cache

echo ""
echo "================================================================================"
echo "Building ByteBuf tracker agent (CRITICAL: must build before tests)"
echo "================================================================================"
echo ""

# Pre-build the tracker agent to ensure it's available before test JVM starts
# This is CRITICAL - if the agent isn't pre-built, classloading issues occur
./gradlew :bytebuf-tracker:agentJar -PwithByteBufTracking

echo ""
echo "================================================================================"
echo "Running tests with ByteBuf tracking enabled (no cache, fresh run)..."
echo "================================================================================"
echo ""

# Run the DecryptJobLeakTest with ByteBuf tracking
# Flags:
#   -PwithByteBufTracking: Enables the ByteBuf Flow Tracker agent
#   --no-build-cache: Disables build cache for fresh execution
#   --rerun-tasks: Forces all tasks to run even if up-to-date
#   --info: Provides detailed output
./gradlew :ambry-router:test --tests DecryptJobLeakTest -PwithByteBufTracking --no-build-cache --rerun-tasks --info 2>&1 | tee decrypt-job-test-output.log

echo ""
echo "================================================================================"
echo "Test execution complete!"
echo "================================================================================"
echo ""

# Check for ByteBuf tracker reports
REPORT_DIR="ambry-router/build/reports/bytebuf-tracking"
if [ -d "$REPORT_DIR" ]; then
    echo "ByteBuf Flow Tracker reports found in: $REPORT_DIR"
    echo ""
    echo "Report files:"
    ls -lh "$REPORT_DIR"
    echo ""

    # Display each report
    for report in "$REPORT_DIR"/*.txt; do
        if [ -f "$report" ]; then
            echo "================================================================================"
            echo "BYTEBUF FLOW TRACKER REPORT: $(basename "$report")"
            echo "================================================================================"
            cat "$report"
            echo ""

            # Check for leaks
            if grep -q "LEAK" "$report"; then
                echo "⚠️  LEAKS DETECTED in $(basename "$report")"
            else
                echo "✓ No leaks detected in $(basename "$report")"
            fi
            echo ""
        fi
    done
else
    echo "⚠️  WARNING: No ByteBuf tracking reports found!"
    echo "Expected directory: $REPORT_DIR"
    echo ""
    echo "Checking for tracker output in test logs..."
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
