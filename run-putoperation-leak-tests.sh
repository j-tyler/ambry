#!/bin/bash
#
# Run PutOperation-related leak tests without Gradle build cache
#
# This script runs all *LeakTest tests related to PutOperation with the following features:
# - Disables Gradle build cache to ensure clean test runs
# - Runs tests without ByteBuf tracking (for initial verification)
# - Tests production code, not test code
# - Note: It's normal for some classes to create ByteBuf and return it for callers to manage
#
# Usage:
#   ./run-putoperation-leak-tests.sh              # Run without ByteBuf tracking
#   ./run-putoperation-leak-tests.sh --with-tracker  # Run with ByteBuf tracking enabled

set -e  # Exit on error

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Parse arguments
WITH_TRACKER=false
if [[ "$1" == "--with-tracker" ]]; then
    WITH_TRACKER=true
fi

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}PutOperation Leak Test Runner${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# PutOperation-related leak test classes
LEAK_TESTS=(
    "PutOperationCompressionLeakTest"
    "PutOperationRetainedDuplicateLeakTest"
    "PutRequestLeakTest"
)

echo -e "${YELLOW}Configuration:${NC}"
echo "  - Gradle build cache: DISABLED (--no-build-cache)"
echo "  - Gradle cache: DISABLED (--no-daemon forces fresh state)"
echo "  - Test classes:"
for test in "${LEAK_TESTS[@]}"; do
    echo "    * $test"
done
if [ "$WITH_TRACKER" = true ]; then
    echo -e "  - ByteBuf tracking: ${GREEN}ENABLED${NC} (-PwithByteBufTracking)"
else
    echo -e "  - ByteBuf tracking: ${YELLOW}DISABLED${NC} (use --with-tracker to enable)"
fi
echo ""

# Build tracker arguments
TRACKER_ARG=""
if [ "$WITH_TRACKER" = true ]; then
    TRACKER_ARG="-PwithByteBufTracking"
fi

# Run tests
echo -e "${BLUE}Running PutOperation-related leak tests...${NC}"
echo ""

# Run router tests (PutOperationCompressionLeakTest, PutOperationRetainedDuplicateLeakTest)
echo -e "${YELLOW}[1/2] Running ambry-router leak tests...${NC}"
./gradlew :ambry-router:test \
    --no-build-cache \
    --no-daemon \
    --tests "PutOperationCompressionLeakTest" \
    --tests "PutOperationRetainedDuplicateLeakTest" \
    $TRACKER_ARG

echo ""
echo -e "${GREEN}✓ Router tests completed${NC}"
echo ""

# Run protocol tests (PutRequestLeakTest)
echo -e "${YELLOW}[2/2] Running ambry-protocol leak tests...${NC}"
./gradlew :ambry-protocol:test \
    --no-build-cache \
    --no-daemon \
    --tests "PutRequestLeakTest" \
    $TRACKER_ARG

echo ""
echo -e "${GREEN}✓ Protocol tests completed${NC}"
echo ""

# Summary
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Test Execution Complete${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${GREEN}All PutOperation-related leak tests have finished.${NC}"
echo ""
echo "Test classes executed:"
for test in "${LEAK_TESTS[@]}"; do
    echo "  ✓ $test"
done
echo ""

if [ "$WITH_TRACKER" = true ]; then
    echo -e "${YELLOW}Next steps:${NC}"
    echo "1. Review the ByteBuf Flow Tracker output above"
    echo "2. Look for '⚠️ LEAK' markers indicating unreleased buffers"
    echo "3. Check 'Unreleased ByteBufs: N' count (N > 0 indicates leaks)"
    echo "4. Examine flow paths to identify where releases should occur"
    echo ""
    echo "For detailed test expectations, see:"
    echo "  - BYTEBUF_LEAK_TEST_EXPECTATIONS.md"
    echo "  - BYTEBUF_TRACKER_OUTPUT_ANALYSIS_GUIDE.md"
else
    echo -e "${YELLOW}Tip:${NC} Run with --with-tracker to see detailed ByteBuf flow analysis:"
    echo "  ./run-putoperation-leak-tests.sh --with-tracker"
fi
echo ""

# Test breakdown
echo -e "${BLUE}Test Breakdown:${NC}"
echo ""
echo -e "${YELLOW}PutOperationCompressionLeakTest (3 tests):${NC}"
echo "  - 2 bug-exposing tests (compression/encryption ownership bugs)"
echo "  - 1 baseline test (successful compression)"
echo ""
echo -e "${YELLOW}PutOperationRetainedDuplicateLeakTest (4 tests):${NC}"
echo "  - 2 bug-exposing tests (retainedDuplicate ownership bugs)"
echo "  - 2 baseline tests (successful ownership transfer)"
echo ""
echo -e "${YELLOW}PutRequestLeakTest (6 tests):${NC}"
echo "  - 0 bug-exposing tests"
echo "  - 6 baseline tests (ownership semantics and exception handling)"
echo ""
echo -e "${BLUE}Total: 13 tests (4 bug-exposing + 9 baseline)${NC}"
echo ""

# Expected results
echo -e "${BLUE}Expected Results:${NC}"
echo ""
echo -e "${GREEN}Tests that should PASS (9 baseline):${NC}"
echo "  - PutOperationCompressionLeakTest.testSuccessfulCompressionNoLeak"
echo "  - PutOperationRetainedDuplicateLeakTest.testSuccessfulEncryptJobConstructionNoLeak"
echo "  - PutOperationRetainedDuplicateLeakTest.testSuccessfulPutRequestCreationAndReleaseNoLeak"
echo "  - All 6 PutRequestLeakTest tests"
echo ""
echo -e "${RED}Tests that will FAIL (demonstrating bugs - 4 bug-exposing):${NC}"
echo "  - PutOperationCompressionLeakTest.testCrcCalculationExceptionAfterCompressionLeaksCompressedBuffer"
echo "    → Bug: PutOperation.java:1562-1576 (no try-catch after compression)"
echo "  - PutOperationCompressionLeakTest.testEncryptionCallbackCrcExceptionLeaksEncryptedBuffer"
echo "    → Bug: PutOperation.java:1498-1503 (no try-catch in encryption callback)"
echo "  - PutOperationRetainedDuplicateLeakTest.testEncryptJobConstructorExceptionAfterRetainedDuplicateLeaksBuffer"
echo "    → Bug: PutOperation.java:1589-1592 (argument evaluation order)"
echo "  - PutOperationRetainedDuplicateLeakTest.testRequestInfoConstructionExceptionAfterPutRequestCreationLeaksBuffer"
echo "    → Bug: PutOperation.java:1825-1830 (no try-catch after PutRequest creation)"
echo ""
echo -e "${YELLOW}Note:${NC} Bug-exposing tests use leakHelper.setDisabled(true) to intentionally allow leaks"
echo "      and manually clean up. This demonstrates real production bugs that need fixing."
echo ""
