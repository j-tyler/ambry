#!/bin/bash

# Script to run NettyRequest and NettyMultipartRequest tests with ByteBuf tracking
# This helps identify memory leaks in the REST layer

set -e  # Exit on error

echo "=========================================="
echo "NettyRequest Memory Leak Investigation"
echo "=========================================="
echo ""

# Check current branch
CURRENT_BRANCH=$(git branch --show-current)
echo "Current branch: $CURRENT_BRANCH"
echo ""

# Verify we're on the right branch
if [[ "$CURRENT_BRANCH" != "claude/fix-netty-memory-leaks-011CUyWdLovNuJTQEoGR8E8C" ]]; then
    echo "WARNING: Not on expected branch. Expected: claude/fix-netty-memory-leaks-011CUyWdLovNuJTQEoGR8E8C"
    echo "Do you want to continue anyway? (y/n)"
    read -r response
    if [[ "$response" != "y" ]]; then
        echo "Exiting..."
        exit 1
    fi
fi

echo "=========================================="
echo "Step 1: Cleaning build cache"
echo "=========================================="
echo ""

# Stop any running Gradle daemons
./gradlew --stop

# Clean the bytebuf-tracker and ambry-rest modules
./gradlew :bytebuf-tracker:clean :ambry-rest:clean

echo ""
echo "=========================================="
echo "Step 2: Building bytebuf-tracker agent"
echo "=========================================="
echo ""

# Build the tracker agent first
./gradlew :bytebuf-tracker:agentJar -PwithByteBufTracking

echo ""
echo "=========================================="
echo "Step 3: Running NettyRequest tests"
echo "=========================================="
echo ""

# Run NettyRequestTest with ByteBuf tracking enabled
# --no-build-cache: Don't use build cache
# --rerun-tasks: Force rerun even if up-to-date
# -PwithByteBufTracking: Enable ByteBuf tracking
# --tests: Run specific test class
./gradlew :ambry-rest:test \
    --no-build-cache \
    --rerun-tasks \
    -PwithByteBufTracking \
    --tests "com.github.ambry.rest.NettyRequestTest" \
    2>&1 | tee netty_request_test_output.log

echo ""
echo "=========================================="
echo "Step 4: Running NettyMultipartRequest tests"
echo "=========================================="
echo ""

# Run NettyMultipartRequestTest with ByteBuf tracking enabled
./gradlew :ambry-rest:test \
    --no-build-cache \
    --rerun-tasks \
    -PwithByteBufTracking \
    --tests "com.github.ambry.rest.NettyMultipartRequestTest" \
    2>&1 | tee netty_multipart_request_test_output.log

echo ""
echo "=========================================="
echo "Test Execution Complete"
echo "=========================================="
echo ""

# Check if ByteBuf tracking reports were generated
REPORT_DIR="./ambry-rest/build/reports/bytebuf-tracking"
if [ -d "$REPORT_DIR" ]; then
    echo "ByteBuf tracking reports found in: $REPORT_DIR"
    echo ""
    echo "Report files:"
    ls -lh "$REPORT_DIR"
    echo ""

    # Display summary from reports if they exist
    for report in "$REPORT_DIR"/*.txt; do
        if [ -f "$report" ]; then
            echo "----------------------------------------"
            echo "Report: $(basename "$report")"
            echo "----------------------------------------"
            # Show the summary section
            sed -n '/ByteBuf Flow Summary/,/^$/p' "$report" | head -20
            echo ""
            # Show any leak warnings
            if grep -q "LEAK" "$report"; then
                echo "⚠️  LEAKS DETECTED in $(basename "$report")"
                echo ""
                grep -A 5 "LEAK" "$report" | head -20
                echo ""
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
    echo "This might indicate:"
    echo "  - ByteBuf tracking was not enabled properly"
    echo "  - No ByteBuf allocations occurred during tests"
    echo "  - Tests failed before completion"
fi

echo ""
echo "=========================================="
echo "Output Logs"
echo "=========================================="
echo "NettyRequestTest output: netty_request_test_output.log"
echo "NettyMultipartRequestTest output: netty_multipart_request_test_output.log"
echo ""
echo "Full ByteBuf tracking reports: $REPORT_DIR"
echo ""

# Check test results
RESULT_DIR="./ambry-rest/build/test-results/test"
if [ -d "$RESULT_DIR" ]; then
    FAILED_TESTS=$(find "$RESULT_DIR" -name "*.xml" -exec grep -l 'failures="[1-9]' {} \; 2>/dev/null | wc -l)
    if [ "$FAILED_TESTS" -gt 0 ]; then
        echo "⚠️  WARNING: Some tests failed! Check the logs for details."
    else
        echo "✓ All tests passed!"
    fi
fi

echo ""
echo "=========================================="
echo "Next Steps"
echo "=========================================="
echo "1. Review the ByteBuf tracking reports in: $REPORT_DIR"
echo "2. Check test output logs for any errors"
echo "3. Look for LEAK warnings in the reports"
echo "4. Analyze the flow trees to understand ByteBuf lifecycle"
echo ""
