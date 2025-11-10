#!/bin/bash

################################################################################
# GCMCryptoService.decrypt() Leak Test Runner
#
# This script runs only the leak tests related to GCMCryptoService.decrypt()
# with ByteBuf tracking enabled and without Gradle build cache.
#
# It will run the following tests:
# 1. testDecryptSingleBufferNoLeak - Baseline decrypt test
# 2. testDecryptCompositeBufferNoLeak - Composite buffer decrypt test
# 3. testDecryptExceptionLeaksDecryptedContent - CRITICAL BUG (catch block releases wrong buffer)
# 4. testConcurrentOperationsNoRaceConditions - Concurrent encrypt/decrypt
# 5. testRoundTripVariousSizesNoLeak - Various size round-trip tests
#
# Expected Results:
# - testDecryptExceptionLeaksDecryptedContent: SHOULD SHOW LEAK (demonstrating bug)
# - All other tests: SHOULD SHOW NO LEAKS (proper cleanup)
################################################################################

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "================================================================================"
echo "GCMCryptoService.decrypt() Memory Leak Test Runner"
echo "================================================================================"
echo ""
echo "This script will:"
echo "  1. Clean Gradle cache to ensure fresh build"
echo "  2. Build the ByteBuf tracker agent"
echo "  3. Run decrypt-related leak tests with ByteBuf tracking enabled"
echo ""
echo "Branch: $(git branch --show-current)"
echo "Commit: $(git rev-parse --short HEAD)"
echo ""
echo "================================================================================"
echo ""

# Step 1: Clean Gradle cache
echo "[1/3] Cleaning Gradle cache..."
echo ""
./gradlew clean --no-build-cache
echo ""
echo "✓ Gradle cache cleaned"
echo ""

# Step 2: Build ByteBuf tracker agent
echo "[2/3] Building ByteBuf tracker agent..."
echo ""
./gradlew buildByteBufAgent
echo ""
echo "✓ ByteBuf tracker agent built"
echo ""

# Step 3: Run decrypt-related tests
echo "[3/3] Running GCMCryptoService.decrypt() leak tests..."
echo ""
echo "Tests to run:"
echo "  • testDecryptSingleBufferNoLeak"
echo "  • testDecryptCompositeBufferNoLeak"
echo "  • testDecryptExceptionLeaksDecryptedContent (BUG-EXPOSING)"
echo "  • testConcurrentOperationsNoRaceConditions"
echo "  • testRoundTripVariousSizesNoLeak"
echo ""
echo "================================================================================"
echo ""

# Run the tests with ByteBuf tracking enabled
# Use --no-build-cache to ensure no cached results
# Use --tests with wildcard to run specific test methods
./gradlew :ambry-router:test \
  --no-build-cache \
  -PwithByteBufTracking \
  --tests 'GCMCryptoServiceLeakTest.testDecrypt*' \
  --tests 'GCMCryptoServiceLeakTest.testConcurrentOperationsNoRaceConditions' \
  --tests 'GCMCryptoServiceLeakTest.testRoundTripVariousSizesNoLeak' \
  --info

echo ""
echo "================================================================================"
echo "Test Execution Complete"
echo "================================================================================"
echo ""
echo "Review the ByteBuf Flow Tracker Report above to analyze leaks."
echo ""
echo "Expected Tracker Output:"
echo ""
echo "1. testDecryptExceptionLeaksDecryptedContent:"
echo "   EXPECTED: Unreleased ByteBufs: 1"
echo "   This test demonstrates the CRITICAL bug where the catch block"
echo "   releases toDecrypt (caller's buffer) instead of decryptedContent"
echo "   (allocated buffer that leaked)."
echo ""
echo "   Bug Location: ambry-router/.../GCMCryptoService.java:196-200"
echo ""
echo "2. All other decrypt tests:"
echo "   EXPECTED: Unreleased ByteBufs: 0"
echo "   These tests verify proper cleanup in success and error paths."
echo ""
echo "================================================================================"
echo ""
echo "For detailed analysis, see:"
echo "  • BYTEBUF_LEAK_TEST_EXPECTATIONS.md"
echo "  • BYTEBUF_TRACKER_OUTPUT_ANALYSIS_GUIDE.md"
echo "  • HIGH_RISK_LEAK_TEST_SUMMARY.md"
echo ""
