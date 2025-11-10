#!/bin/bash

################################################################################
# Production Memory Leak Test Runner
#
# This script runs production-level tests that will FAIL when memory leaks exist
# and PASS after the production code is fixed.
#
# These tests are different from bug-exposing tests - they don't disable leak
# detection and will fail with assertion errors when leaks are detected.
#
# Tests Created:
# 1. DecryptJobProductionLeakTest (ambry-router)
# 2. GCMCryptoServiceProductionLeakTest (ambry-router)
# 3. PutOperationCompressionProductionLeakTest (ambry-router)
# 4. GetBlobOperationDecompressionProductionLeakTest (ambry-router)
# 5. NettyRequestProductionLeakTest (ambry-rest)
# 6. AmbrySendToHttp2AdaptorProductionLeakTest (ambry-network)
#
# Expected Results:
# - WITH BUGS: All tests will FAIL with leak detection errors
# - AFTER FIXES: All tests will PASS
################################################################################

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "================================================================================"
echo "Production Memory Leak Test Runner"
echo "================================================================================"
echo ""
echo "This script will:"
echo "  1. Clean Gradle cache"
echo "  2. Build ByteBuf tracker agent"
echo "  3. Run production leak tests (tests that fail when bugs exist)"
echo ""
echo "Branch: $(git branch --show-current)"
echo "Commit: $(git rev-parse --short HEAD)"
echo ""
echo "================================================================================"
echo ""

# Function to run tests for a module
run_module_tests() {
  local module=$1
  local test_class=$2
  local description=$3

  echo ""
  echo "================================================================================"
  echo "Testing: $description"
  echo "Module: $module"
  echo "Class: $test_class"
  echo "================================================================================"
  echo ""

  ./gradlew :$module:test \
    --no-build-cache \
    -PwithByteBufTracking \
    --tests "$test_class" \
    --info || true  # Continue even if tests fail (expected with bugs)

  echo ""
  echo "Completed: $test_class"
  echo ""
}

# Step 1: Clean
echo "[1/3] Cleaning Gradle cache..."
echo ""
./gradlew clean --no-build-cache
echo ""
echo "✓ Gradle cache cleaned"
echo ""

# Step 2: Build tracker
echo "[2/3] Building ByteBuf tracker agent..."
echo ""
./gradlew buildByteBufAgent
echo ""
echo "✓ ByteBuf tracker agent built"
echo ""

# Step 3: Run production tests
echo "[3/3] Running production leak tests..."
echo ""

# Router module tests
run_module_tests \
  "ambry-router" \
  "DecryptJobProductionLeakTest" \
  "DecryptJob.closeJob() leak"

run_module_tests \
  "ambry-router" \
  "GCMCryptoServiceProductionLeakTest" \
  "GCMCryptoService.decrypt() catch block leak"

run_module_tests \
  "ambry-router" \
  "PutOperationCompressionProductionLeakTest" \
  "PutOperation compression ownership leaks"

run_module_tests \
  "ambry-router" \
  "GetBlobOperationDecompressionProductionLeakTest" \
  "GetBlobOperation decompression ownership leaks"

# Rest module tests
run_module_tests \
  "ambry-rest" \
  "NettyRequestProductionLeakTest" \
  "NettyRequest.writeContent() exception leak"

# Network module tests
run_module_tests \
  "ambry-network" \
  "AmbrySendToHttp2AdaptorProductionLeakTest" \
  "AmbrySendToHttp2Adaptor shared refCnt leak"

echo ""
echo "================================================================================"
echo "All Production Leak Tests Complete"
echo "================================================================================"
echo ""
echo "IMPORTANT: Expected Results"
echo ""
echo "WITH BUGS (current state):"
echo "  ❌ All tests will FAIL with leak detection errors"
echo "  ❌ ByteBuf Flow Tracker will show unreleased buffers"
echo "  ❌ This confirms the bugs exist in production code"
echo ""
echo "AFTER FIXES (after production code is fixed):"
echo "  ✅ All tests will PASS"
echo "  ✅ ByteBuf Flow Tracker will show 0 unreleased buffers"
echo "  ✅ This confirms the fixes work correctly"
echo ""
echo "================================================================================"
echo ""
echo "Bug Locations and Fixes Needed:"
echo ""
echo "1. DecryptJob.java:112-114"
echo "   Fix: Add encryptedBlobContent.release() in closeJob()"
echo ""
echo "2. GCMCryptoService.java:196-200"
echo "   Fix: Release decryptedContent (not toDecrypt) in catch block"
echo ""
echo "3. PutOperation.java:1562-1576"
echo "   Fix: Add try-catch around CRC calculation after compression"
echo ""
echo "4. PutOperation.java:1498-1503"
echo "   Fix: Add try-catch around CRC calculation in encryption callback"
echo ""
echo "5. GetBlobOperation.java:882-885"
echo "   Fix: Add try-catch around chunkIndexToBuf.put() after decompression"
echo ""
echo "6. GetBlobOperation.java:884"
echo "   Fix: Add try-catch around filterChunkToRange() call"
echo ""
echo "7. GetBlobOperation.java:1588-1597"
echo "   Fix: Add try-catch around resolveRange() to ensure safeRelease()"
echo ""
echo "8. NettyRequest.java:208-215"
echo "   Fix: Add try-catch around writeChannel.write() after retain()"
echo ""
echo "9. AmbrySendToHttp2Adaptor.java:82-98"
echo "   Fix: Track all retained slices and release them in catch block"
echo ""
echo "================================================================================"
echo ""
echo "Next Steps:"
echo "1. Review test failures above to confirm bugs"
echo "2. Fix production code at the locations listed"
echo "3. Re-run this script to verify fixes"
echo "4. All tests should PASS after fixes applied"
echo ""
