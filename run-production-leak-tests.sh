#!/bin/bash

###############################################################################
# Production ByteBuf Leak Tests Runner
#
# This script runs production-quality ByteBuf leak tests that will FAIL
# when bugs exist and PASS when bugs are fixed.
#
# Tests included:
# 1. DecryptJobCloseLeakTest - Tests DecryptJob.closeJob() memory leak
# 2. GCMCryptoServiceDecryptLeakTest - Tests GCMCryptoService.decrypt() bugs
#
# BUGS BEING TESTED:
#
# BUG #1: DecryptJob.closeJob() does NOT release encryptedBlobContent
# - Location: ambry-router/src/main/java/.../DecryptJob.java:112-114
# - Impact: Every aborted decryption job leaks a ByteBuf
# - Tests: DecryptJobCloseLeakTest (4 tests)
#
# BUG #2: GCMCryptoService.decrypt() releases WRONG buffer on exception
# - Location: ambry-router/src/main/java/.../GCMCryptoService.java:196-200
# - Impact: Leaks decryptedContent AND incorrectly releases caller's buffer
# - Tests: GCMCryptoServiceDecryptLeakTest (5 tests)
#
# EXPECTED RESULTS (Before Fix):
# - Tests will FAIL with NettyByteBufLeakHelper assertions
# - GCMCryptoService tests may also fail with IllegalReferenceCountException
#
# EXPECTED RESULTS (After Fix):
# - All tests will PASS
# - No ByteBuf leaks detected
# - No IllegalReferenceCountException
#
# Usage:
#   ./run-production-leak-tests.sh [--with-tracker]
#
# Options:
#   --with-tracker    Also run with ByteBuf Flow Tracker for detailed analysis
###############################################################################

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

WITH_TRACKER=false
if [ "$1" == "--with-tracker" ]; then
  WITH_TRACKER=true
fi

echo "================================================================================"
echo "Production ByteBuf Leak Tests Runner"
echo "================================================================================"
echo ""
echo "Target Tests:"
echo "  - DecryptJobCloseLeakTest (4 tests)"
echo "  - GCMCryptoServiceDecryptLeakTest (5 tests)"
echo ""
echo "Expected Behavior:"
echo "  BEFORE FIX: Tests FAIL (leak detection + possible IllegalReferenceCountException)"
echo "  AFTER FIX:  Tests PASS (no leaks, no exceptions)"
echo ""
echo "Configuration:"
if [ "$WITH_TRACKER" = true ]; then
  echo "  - ByteBuf tracking: ENABLED (-PwithByteBufTracking)"
else
  echo "  - ByteBuf tracking: DISABLED (using NettyByteBufLeakHelper only)"
fi
echo "  - Gradle cache: DISABLED (--no-build-cache)"
echo "  - Clean build: YES"
echo ""
echo "================================================================================"
echo ""

# Step 1: Clean the ambry-router module
echo "Step 1/3: Cleaning ambry-router module..."
./gradlew :ambry-router:clean --no-build-cache

# Step 2: Build ByteBuf tracker agent if needed
if [ "$WITH_TRACKER" = true ]; then
  echo ""
  echo "Step 2/3: Building ByteBuf tracker agent..."
  ./gradlew buildByteBufAgent --no-build-cache
else
  echo ""
  echo "Step 2/3: Skipping ByteBuf tracker agent (not requested)"
fi

# Step 3: Run the production leak tests
echo ""
echo "Step 3/3: Running production leak tests..."
echo ""
echo "================================================================================"
echo "TEST EXECUTION"
echo "================================================================================"

# Build gradle command
GRADLE_CMD="./gradlew :ambry-router:test --no-build-cache --tests 'DecryptJobCloseLeakTest' --tests 'GCMCryptoServiceDecryptLeakTest'"
if [ "$WITH_TRACKER" = true ]; then
  GRADLE_CMD="$GRADLE_CMD -PwithByteBufTracking"
fi
GRADLE_CMD="$GRADLE_CMD --info"

# Run the tests and capture the exit code
set +e  # Don't exit on test failure
eval $GRADLE_CMD
TEST_EXIT_CODE=$?
set -e  # Re-enable exit on error

# Report results
echo ""
echo "================================================================================"
echo "TEST RESULTS SUMMARY"
echo "================================================================================"
echo ""

if [ $TEST_EXIT_CODE -eq 0 ]; then
  echo "✓ All production leak tests PASSED"
  echo ""
  echo "This means the bugs have been FIXED:"
  echo "  ✓ DecryptJob.closeJob() now releases encryptedBlobContent"
  echo "  ✓ GCMCryptoService.decrypt() now releases decryptedContent on exception"
  echo "  ✓ GCMCryptoService.decrypt() no longer touches caller's input buffer"
  echo ""
  echo "Congratulations! The memory leaks have been resolved."
else
  echo "✗ Some tests FAILED (exit code: $TEST_EXIT_CODE)"
  echo ""
  echo "This is EXPECTED if the bugs have NOT been fixed yet."
  echo ""
  echo "Expected Failure Modes:"
  echo ""
  echo "1. DecryptJobCloseLeakTest failures:"
  echo "   - 'ByteBuf leak detected' from NettyByteBufLeakHelper"
  echo "   - Indicates closeJob() is NOT releasing encryptedBlobContent"
  echo ""
  echo "2. GCMCryptoServiceDecryptLeakTest failures:"
  echo "   - 'ByteBuf leak detected' from NettyByteBufLeakHelper"
  echo "   - 'IllegalReferenceCountException: refCnt: 0'"
  echo "   - Indicates decrypt() is releasing caller's buffer AND leaking its own"
  echo ""
  echo "To fix these bugs, see the test file headers for fix instructions:"
  echo "  - ambry-router/src/test/java/.../DecryptJobCloseLeakTest.java"
  echo "  - ambry-router/src/test/java/.../GCMCryptoServiceDecryptLeakTest.java"
fi

echo ""
echo "================================================================================"
echo "DETAILED ANALYSIS"
echo "================================================================================"
echo ""

if [ $TEST_EXIT_CODE -ne 0 ]; then
  echo "To investigate the failures:"
  echo ""
  echo "1. Check test output above for:"
  echo "   - NettyByteBufLeakHelper leak detection messages"
  echo "   - IllegalReferenceCountException stack traces"
  echo "   - 'BUG DETECTED:' assertion failure messages"
  echo ""
  if [ "$WITH_TRACKER" = true ]; then
    echo "2. Review ByteBuf Flow Tracker output above:"
    echo "   - Search for 'ByteBuf Flow Tracker Report'"
    echo "   - Look for 'Unreleased ByteBufs' count"
    echo "   - Examine flow paths showing leaked buffers"
    echo ""
  fi
  echo "3. Run individual test classes for focused analysis:"
  echo "   ./gradlew :ambry-router:test --tests 'DecryptJobCloseLeakTest'"
  echo "   ./gradlew :ambry-router:test --tests 'GCMCryptoServiceDecryptLeakTest'"
  echo ""
  echo "4. Run with ByteBuf tracker for detailed flow analysis:"
  echo "   ./run-production-leak-tests.sh --with-tracker"
  echo ""
fi

echo "================================================================================"
echo "BUG FIX INSTRUCTIONS"
echo "================================================================================"
echo ""
echo "BUG #1: DecryptJob.closeJob() memory leak"
echo "File: ambry-router/src/main/java/com/github/ambry/router/DecryptJob.java"
echo "Lines: 112-114"
echo ""
echo "Current code:"
echo "  @Override"
echo "  public void closeJob(GeneralSecurityException gse) {"
echo "    callback.onCompletion(null, gse);"
echo "  }"
echo ""
echo "Fixed code:"
echo "  @Override"
echo "  public void closeJob(GeneralSecurityException gse) {"
echo "    if (encryptedBlobContent != null) {"
echo "      encryptedBlobContent.release();"
echo "    }"
echo "    callback.onCompletion(null, gse);"
echo "  }"
echo ""
echo "================================================================================"
echo ""
echo "BUG #2: GCMCryptoService.decrypt() wrong buffer released"
echo "File: ambry-router/src/main/java/com/github/ambry/router/GCMCryptoService.java"
echo "Lines: 196-200"
echo ""
echo "Current code (WRONG):"
echo "  } catch (Exception e) {"
echo "    if (toDecrypt != null) {"
echo "      toDecrypt.release();  // ❌ Releases caller's buffer!"
echo "    }"
echo "    throw new GeneralSecurityException(...);"
echo "  }"
echo ""
echo "Fixed code:"
echo "  } catch (Exception e) {"
echo "    if (decryptedContent != null) {"
echo "      decryptedContent.release();  // ✓ Releases service's buffer"
echo "    }"
echo "    throw new GeneralSecurityException(...);"
echo "  }"
echo ""
echo "================================================================================"

exit $TEST_EXIT_CODE
