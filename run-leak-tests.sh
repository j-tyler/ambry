#!/bin/bash

# Script to run all ByteBuf leak tests and collect tracker output
# Usage: ./run-leak-tests.sh [output-file]

set -e

OUTPUT_FILE="${1:-leak-test-results.txt}"
TEMP_LOG="$(mktemp)"

echo "========================================" | tee "$OUTPUT_FILE"
echo "Running ByteBuf Leak Tests" | tee -a "$OUTPUT_FILE"
echo "Started at: $(date)" | tee -a "$OUTPUT_FILE"
echo "========================================" | tee -a "$OUTPUT_FILE"
echo "" | tee -a "$OUTPUT_FILE"

# Clean previous test results
echo "Cleaning previous test results..." | tee -a "$OUTPUT_FILE"
./gradlew clean > /dev/null 2>&1

# Modules that have LeakTest classes
MODULES_WITH_LEAK_TESTS=(
    "ambry-api"
    "ambry-commons"
    "ambry-network"
    "ambry-protocol"
    "ambry-rest"
    "ambry-router"
)

# Run all LeakTest classes with ByteBuf tracking enabled
echo "Running leak tests with ByteBuf tracker enabled..." | tee -a "$OUTPUT_FILE"
echo "Modules: ${MODULES_WITH_LEAK_TESTS[*]}" | tee -a "$OUTPUT_FILE"
echo "This may take several minutes..." | tee -a "$OUTPUT_FILE"
echo "" | tee -a "$OUTPUT_FILE"

# Build the task list for each module
TASKS=""
for module in "${MODULES_WITH_LEAK_TESTS[@]}"; do
    TASKS="$TASKS :$module:test"
done

# Run tests and capture output
# --no-build-cache: Disable Gradle build cache to ensure fresh build
# --rerun-tasks: Force all tasks to run even if up-to-date
./gradlew $TASKS -PwithByteBufTracking --tests '*LeakTest' --no-build-cache --rerun-tasks 2>&1 | tee "$TEMP_LOG"

# Extract ByteBuf tracker output from the test results
echo "" | tee -a "$OUTPUT_FILE"
echo "========================================" | tee -a "$OUTPUT_FILE"
echo "ByteBuf Tracker Results" | tee -a "$OUTPUT_FILE"
echo "========================================" | tee -a "$OUTPUT_FILE"
echo "" | tee -a "$OUTPUT_FILE"

# Look for ByteBuf tracker output in test logs
# Extract all ByteBuf-related output including the final report
if grep -q "ByteBuf" "$TEMP_LOG" 2>/dev/null; then
    # Extract lines with [ByteBuf prefix and the final report section
    grep -E "\[ByteBuf|=== ByteBuf|Object ID|Leak detected|Reference count|PASS|FAIL" "$TEMP_LOG" | tee -a "$OUTPUT_FILE" || true
else
    echo "No ByteBuf tracker output found in test logs" | tee -a "$OUTPUT_FILE"
fi

# Extract the final ByteBuf Flow Report if present
echo "" | tee -a "$OUTPUT_FILE"
echo "========================================" | tee -a "$OUTPUT_FILE"
echo "ByteBuf Flow Final Reports by Module" | tee -a "$OUTPUT_FILE"
echo "========================================" | tee -a "$OUTPUT_FILE"
echo "" | tee -a "$OUTPUT_FILE"

if grep -q "=== ByteBuf Flow Final Report ===" "$TEMP_LOG" 2>/dev/null; then
    # Extract complete test task sections
    # From "> Task :module:test" to the next non-test task
    awk '
        /^> Task :.*:test$/ {
            # Start of a test task
            in_test = 1
            print ""
            print $0
            print ""
            next
        }
        /^> Task :/ && !/^> Task :.*:test$/ {
            # Hit a non-test task, stop capturing
            in_test = 0
            next
        }
        /BUILD SUCCESSFUL|BUILD FAILED/ {
            # End of build output
            in_test = 0
            next
        }
        in_test {
            # Print everything within test task sections
            print $0
        }
    ' "$TEMP_LOG" | tee -a "$OUTPUT_FILE" || true
else
    echo "No final report found in console output" | tee -a "$OUTPUT_FILE"
fi

# Also check individual test report files for tracker output
echo "" | tee -a "$OUTPUT_FILE"
echo "========================================" | tee -a "$OUTPUT_FILE"
echo "Individual Test Outputs" | tee -a "$OUTPUT_FILE"
echo "========================================" | tee -a "$OUTPUT_FILE"
echo "" | tee -a "$OUTPUT_FILE"

# Find all test result XML files for LeakTest classes
for module in ambry-api ambry-commons ambry-network ambry-protocol ambry-rest ambry-router; do
    TEST_RESULTS_DIR="$module/build/test-results/test"
    if [ -d "$TEST_RESULTS_DIR" ]; then
        echo "=== $module ===" | tee -a "$OUTPUT_FILE"

        # Find XML files for LeakTest classes
        find "$TEST_RESULTS_DIR" -name "*LeakTest.xml" 2>/dev/null | while read -r xml_file; do
            test_name=$(basename "$xml_file" .xml)
            echo "  Test: $test_name" | tee -a "$OUTPUT_FILE"

            # Extract stdout/stderr from XML if present
            if grep -q "system-out\|system-err" "$xml_file" 2>/dev/null; then
                # Extract content between <system-out> and </system-out>
                # Include final report and all ByteBuf-related output
                sed -n '/<system-out>/,/<\/system-out>/p' "$xml_file" | \
                    grep -E "\[ByteBuf|=== ByteBuf|Object ID|Leak detected|Reference count|PASS|FAIL" | \
                    sed 's/^[[:space:]]*/    /' | tee -a "$OUTPUT_FILE" || true

                sed -n '/<system-err>/,/<\/system-err>/p' "$xml_file" | \
                    grep -E "\[ByteBuf|=== ByteBuf|Object ID|Leak detected|Reference count|PASS|FAIL" | \
                    sed 's/^[[:space:]]*/    /' | tee -a "$OUTPUT_FILE" || true
            fi
        done
        echo "" | tee -a "$OUTPUT_FILE"
    fi
done

# Check for test failures
echo "" | tee -a "$OUTPUT_FILE"
echo "========================================" | tee -a "$OUTPUT_FILE"
echo "Test Summary" | tee -a "$OUTPUT_FILE"
echo "========================================" | tee -a "$OUTPUT_FILE"

if grep -q "BUILD SUCCESSFUL" "$TEMP_LOG"; then
    echo "✓ All leak tests passed!" | tee -a "$OUTPUT_FILE"
    EXIT_CODE=0
elif grep -q "BUILD FAILED" "$TEMP_LOG"; then
    echo "✗ Some tests failed. Check output for details." | tee -a "$OUTPUT_FILE"
    echo "" | tee -a "$OUTPUT_FILE"
    echo "Failed tests:" | tee -a "$OUTPUT_FILE"
    grep "FAILED" "$TEMP_LOG" | tee -a "$OUTPUT_FILE" || true
    EXIT_CODE=1
else
    echo "? Unable to determine test status" | tee -a "$OUTPUT_FILE"
    EXIT_CODE=2
fi

echo "" | tee -a "$OUTPUT_FILE"
echo "Completed at: $(date)" | tee -a "$OUTPUT_FILE"
echo "Full output saved to: $OUTPUT_FILE" | tee -a "$OUTPUT_FILE"

# Cleanup
rm -f "$TEMP_LOG"

exit $EXIT_CODE
