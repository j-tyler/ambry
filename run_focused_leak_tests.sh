#!/bin/bash

# Focused script to run high-priority NettyRequest tests most likely to reveal leaks
# This runs a subset of tests for faster iteration during leak investigation

set -e

echo "=========================================="
echo "Focused NettyRequest Leak Tests"
echo "=========================================="
echo ""
echo "Running high-priority tests most likely to reveal memory leaks:"
echo "  - Content handling tests"
echo "  - Exception handling tests"
echo "  - Close/cleanup tests"
echo "  - Resource management tests"
echo ""

# Clean first
echo "Cleaning..."
./gradlew --stop
./gradlew :bytebuf-tracker:clean :ambry-rest:clean --quiet

echo ""
echo "Building ByteBuf tracker agent..."
./gradlew :bytebuf-tracker:agentJar -PwithByteBufTracking --quiet

echo ""
echo "=========================================="
echo "Running NettyRequest critical tests"
echo "=========================================="

# Run critical tests from NettyRequestTest
./gradlew :ambry-rest:test \
    --no-build-cache \
    --rerun-tasks \
    -PwithByteBufTracking \
    --tests "com.github.ambry.rest.NettyRequestTest.contentAddAndReadTest" \
    --tests "com.github.ambry.rest.NettyRequestTest.readIntoExceptionsTest" \
    --tests "com.github.ambry.rest.NettyRequestTest.closeTest" \
    --tests "com.github.ambry.rest.NettyRequestTest.operationsAfterCloseTest" \
    --tests "com.github.ambry.rest.NettyRequestTest.backPressureTest" \
    2>&1 | tee focused_netty_request_output.log

echo ""
echo "=========================================="
echo "Running NettyMultipartRequest critical tests"
echo "=========================================="

# Run all NettyMultipartRequest tests (only 7 total)
./gradlew :ambry-rest:test \
    --no-build-cache \
    --rerun-tasks \
    -PwithByteBufTracking \
    --tests "com.github.ambry.rest.NettyMultipartRequestTest" \
    2>&1 | tee focused_multipart_request_output.log

echo ""
echo "=========================================="
echo "Results Summary"
echo "=========================================="
echo ""

REPORT_DIR="./ambry-rest/build/reports/bytebuf-tracking"

if [ -d "$REPORT_DIR" ]; then
    for report in "$REPORT_DIR"/*.txt; do
        if [ -f "$report" ]; then
            echo "📊 Report: $(basename "$report")"
            echo ""

            # Show summary
            if grep -q "ByteBuf Flow Summary" "$report"; then
                sed -n '/ByteBuf Flow Summary/,/^---/p' "$report" | grep -E "Total|Leak" | head -10
                echo ""
            fi

            # Highlight leaks
            if grep -q "⚠️.*LEAK" "$report"; then
                echo "⚠️  LEAKS FOUND!"
                echo ""
                echo "Leak paths:"
                grep "⚠️.*LEAK" "$report" | head -10
                echo ""
            else
                echo "✅ No leaks detected"
            fi
            echo "----------------------------------------"
            echo ""
        fi
    done

    echo ""
    echo "Full reports available at: $REPORT_DIR"
else
    echo "⚠️  No tracking reports generated"
fi

echo ""
echo "Logs saved to:"
echo "  - focused_netty_request_output.log"
echo "  - focused_multipart_request_output.log"
echo ""
