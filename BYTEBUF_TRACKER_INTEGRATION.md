# ByteBuf Flow Tracker Integration

This document describes the integration of the ByteBuddy ByteBuf Flow Tracker into the Ambry codebase for detecting and analyzing ByteBuf memory leaks during test runs.

## Overview

The ByteBuf Flow Tracker is a lightweight Java agent that tracks Netty ByteBuf object flows through the application using a Trie data structure. It helps identify memory leaks by monitoring ByteBuf reference counts and detecting unreleased buffers.

**Key Features:**
- Zero allocation overhead - no stack trace collection
- First-touch root approach - initial handler becomes the Trie root
- Memory efficient - shared Trie prefixes minimize storage
- Dual output formats - human-readable trees and LLM-optimized structured text
- JMX integration - runtime monitoring via MBean
- **Gradle-native** - fully integrated into Ambry's build system
- **Constructor tracking** - tracks ByteBufs passed to constructors and stored in wrapped objects (NEW!)

## Quick Start

### Enable ByteBuf Tracking

Simply add `-PwithByteBufTracking` to any Gradle command:

```bash
# Build with tracking enabled
./gradlew build -PwithByteBufTracking

# Run integration tests with tracking
./gradlew intTest -PwithByteBufTracking
```

**That's it!** Gradle automatically:
1. Builds the tracker agent JAR (if needed)
2. Attaches it to test JVMs
3. Instruments your code
4. Prints a report at the end

### Run Without Tracking (Normal Operation)

Simply omit the flag:

```bash
./gradlew build
```

Tests run normally with no overhead.

## Integration Components

### 1. Git Submodule
- **Location**: `modules/bytebuddy-bytebuf-tracer`
- **Repository**: https://github.com/j-tyler/bytebuddy-bytebuf-tracer
- **Purpose**: Contains the tracker source code (NOT copied into Ambry)
- **Build**: Uses Maven to build the agent JAR when needed
- **Output**: `modules/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar`

### 2. Test Listener (Ambry-Specific Integration)
- **File**: `ambry-test-utils/src/main/java/com/github/ambry/testutils/ByteBufTrackerListener.java`
- **Purpose**: JUnit RunListener that prints tracker output at the end of test execution
- **Behavior**:
  - Uses reflection to detect if tracker agent is running
  - Prints summary statistics and detailed flow trees
  - Highlights memory leaks with ⚠️ warnings
  - Gracefully handles case when agent is not present

### 3. Build Configuration
- **File**: `build.gradle` (root)
- **Key Changes**:
  - Defines `buildByteBufAgent` task that runs Maven on the submodule
  - Test tasks check for `withByteBufTracking` property
  - When enabled, automatically builds the agent from the submodule
  - Adds `-javaagent` JVM argument with agent path and configuration
  - Registers `ByteBufTrackerListener` for output
  - Configuration applies to both `test` and `intTest` tasks
  - Includes constructor tracking for Ambry ByteBuf wrapper classes

## How It Works

When you use `-PwithByteBufTracking`:

1. **Gradle detects the property** in `build.gradle`
2. **Builds the agent JAR** by executing `buildByteBufAgent` task (runs Maven on the submodule)
3. **Maven creates fat JAR** with ByteBuddy and all dependencies at `modules/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/`
4. **Attaches agent** to test JVM via `-javaagent:path/to/agent.jar` with configuration:
   - `include=com.github.ambry` - Track all Ambry packages
   - `exclude=org.mockito` - Exclude Mockito to avoid ByteBuddy conflicts
   - `trackConstructors=...` - Track Ambry ByteBuf wrapper classes
5. **ByteBuddy instruments** all methods in `com.github.ambry` packages at JVM startup
6. **Tracks ByteBuf flows** using a Trie data structure (zero allocation)
7. **Test listener** retrieves tracking data and prints report at test completion

## Understanding the Output

At the end of test execution, you'll see:

```
================================================================================
ByteBuf Flow Tracker Report
================================================================================
=== ByteBuf Flow Summary ===
Total Root Methods: 15
Total Traversals: 342
Unique Paths: 28
Leak Paths: 2

--------------------------------------------------------------------------------
Flow Tree:
--------------------------------------------------------------------------------
ROOT: NettyRequest.readInto [count=45]
└── ChunkProcessor.processChunk [ref=1, count=45]
    └── ChunkProcessor.validate [ref=1, count=45]
        └── ChunkProcessor.release [ref=0, count=45]

ROOT: LeakyHandler.handleRequest [count=3]
└── ErrorHandler.logError [ref=1, count=3] ⚠️ LEAK

--------------------------------------------------------------------------------
Flat Paths (Leaks Highlighted):
--------------------------------------------------------------------------------
LeakyHandler.handleRequest -> ErrorHandler.logError [FINAL REF=1] ⚠️ LEAK
...
```

### Interpreting Results

- **⚠️ LEAK**: ByteBuf was not released (refCount > 0 at leaf node)
- **ref=N**: Current reference count at this point in the flow
- **count=N**: Number of times this path was traversed
- **FINAL REF=N**: Reference count at the end of the flow (should be 0)
- **ROOT**: The first method that handled the ByteBuf

### Identifying Leaks

Look for:
1. **Leaf nodes with ⚠️ LEAK** - These are the final methods holding unreleased ByteBufs
2. **FINAL REF > 0** - Indicates the ByteBuf wasn't fully released
3. **Paths in "Flat Paths" section** - Shows the complete journey of leaked ByteBufs

## Advanced Configuration

### Tracking Additional Packages

Edit `build.gradle` to include more packages:

```groovy
jvmArgs "-javaagent:${trackerJar.absolutePath}=include=com.github.ambry,io.netty.buffer"
```

### Excluding Packages

```groovy
jvmArgs "-javaagent:${trackerJar.absolutePath}=include=com.github.ambry;exclude=com.github.ambry.test"
```

### Alternative Property Names

All of these work:

```bash
./gradlew build -PwithByteBufTracking
./gradlew build -PenableByteBufTracking
./gradlew build -DenableByteBufTracking=true
```

### JMX Monitoring

Enable JMX to monitor ByteBuf flows in real-time. Add to `build.gradle`:

```groovy
systemProperty 'com.sun.management.jmxremote', 'true'
systemProperty 'com.sun.management.jmxremote.port', '9999'
systemProperty 'com.sun.management.jmxremote.authenticate', 'false'
systemProperty 'com.sun.management.jmxremote.ssl', 'false'
```

Then connect with JConsole:
```bash
jconsole localhost:9999
# Navigate to: MBeans → com.example → ByteBufFlowTracker
```

**JMX Operations:**
- `getTreeView()` - Visual tree representation
- `getFlatView()` - Flat path list with leaks
- `getCsvView()` - CSV export
- `getJsonView()` - JSON export
- `getSummary()` - Statistics summary
- `reset()` - Clear all tracking data

## Troubleshooting

### "ByteBuf tracker agent JAR not found"

**Symptom**: Test fails with error about missing agent JAR

**Solution**: Make sure to use the `-PwithByteBufTracking` flag:
```bash
./gradlew build -PwithByteBufTracking
```

The flag tells Gradle to build the agent JAR before running tests.

### No Tracker Output Appears

**Symptom**: Tests run but no ByteBuf flow report is printed

**Solutions**:
1. Verify you used the flag: `-PwithByteBufTracking`
2. Check agent was loaded:
   ```bash
   ./gradlew build -PwithByteBufTracking --info | grep "ByteBuf"
   ```
   You should see: `Using ByteBuf Flow Tracker agent: ...`
3. Verify ByteBufs are actually used in your tests

### Build Failures

**Symptom**: Build fails when trying to compile bytebuf-tracker

**Common Causes & Solutions**:

1. **Network connectivity issues** (dependencies can't download):
   ```bash
   ./gradlew build -PwithByteBufTracking --refresh-dependencies
   ```

2. **Gradle daemon issues**:
   ```bash
   ./gradlew --stop
   ./gradlew build -PwithByteBufTracking --no-daemon
   ```

3. **Java version compatibility**:
   - Ambry targets Java 8
   - Ensure you're using Java 8, 11, or 17
   - Check version: `java -version`

### No Tracking Data for Specific Classes

**Symptom**: Some classes don't appear in tracking output

**Solution**:
1. Verify the package is included in the `include` pattern
2. Check the class isn't in an excluded package
3. Verify methods are `public` or `protected` (private methods aren't instrumented)

### Performance Impact

**Symptom**: Tests run significantly slower with tracker enabled

**Solutions**:
1. **Narrow package scope** - Only track critical packages
2. **Run selectively** - Use tracker only on suspected leak areas
3. **Exclude test utilities** - Don't track test helper classes
4. **Use on subset** - Run tracker on specific test classes:
   ```bash
   ./gradlew :ambry-commons:test -PwithByteBufTracking --tests SuspectedLeakyTest
   ```

### Class Not Found Errors

**Symptom**: Tests fail with `ClassNotFoundException` for tracker classes

**Cause**: Agent JAR may be incomplete

**Solution**: Clean and rebuild:
```bash
./gradlew :bytebuf-tracker:clean :bytebuf-tracker:agentJar -PwithByteBufTracking
```

## Architecture & Implementation

### Why This Approach?

1. **Zero code changes** - Tracker added via Java agent (bytecode instrumentation)
2. **Gradle-native** - Integrates cleanly with existing Ambry build
3. **Opt-in by default** - No overhead unless explicitly enabled
4. **Comprehensive** - Tracks all ByteBuf flows automatically
5. **Production-safe** - Only active during tests, never in production
6. **Java 8 compatible** - Matches Ambry's target version

### Component Flow

```
User runs: ./gradlew test -PwithByteBufTracking
       ↓
Gradle detects withByteBufTracking property
       ↓
Builds bytebuf-tracker:agentJar (if needed)
       ↓
Test JVM starts with -javaagent
       ↓
ByteBuddy instruments com.github.ambry classes
       ↓
Tests run - ByteBuf flows tracked in Trie
       ↓
ByteBufTrackerListener prints report
```

### Key Files

- `build.gradle` - Root build file with tracker configuration and `buildByteBufAgent` task
- `ambry-test-utils/.../ByteBufTrackerListener.java` - Ambry-specific test integration
- `modules/bytebuddy-bytebuf-tracer/` - Tracker project as git submodule (NOT copied)
  - `bytebuf-flow-tracker/` - Maven module containing the agent source
  - `bytebuf-flow-example/` - Example usage (not used by Ambry)

### Source Code Organization

The tracker source code lives in the git submodule (NOT copied into Ambry):

```
modules/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/src/main/java/
├── com/example/bytebuf/tracker/
│   ├── ByteBufFlowTracker.java        # Main tracking logic
│   ├── ByteBufObjectHandler.java      # ByteBuf-specific handler
│   ├── ObjectTrackerHandler.java      # Interface for custom objects
│   ├── ObjectTrackerRegistry.java     # Handler registry
│   ├── agent/
│   │   ├── ByteBufFlowAgent.java      # Java agent entry point
│   │   ├── ByteBufFlowMBean.java      # JMX interface
│   │   └── ByteBufTrackingAdvice.java # ByteBuddy advice
│   ├── trie/
│   │   └── FlowTrie.java              # Trie data structure
│   └── view/
│       └── TrieRenderer.java          # Output formatting
```

**Note**: Ambry does NOT copy this source code. It builds the agent JAR from the submodule using Maven.

## Comparison with Netty's Leak Detection

| Feature | Netty Leak Detector | ByteBuf Flow Tracker |
|---------|---------------------|----------------------|
| **Approach** | Sampling-based | Complete tracking |
| **Coverage** | Random sample | All ByteBufs |
| **Call paths** | Stack traces | Flow tree (Trie) |
| **Memory usage** | High (stack traces) | Low (shared Trie) |
| **Output** | Console warnings | Structured report |
| **Root cause** | Allocation site | Flow path |
| **False positives** | Possible | Minimal |
| **Performance impact** | Low-medium | Low |

**When to use each:**
- **Netty**: Production monitoring, broad coverage
- **ByteBuf Tracker**: Deep analysis, debugging specific leaks, understanding flow patterns

## Best Practices

### During Development

1. **Run tests with tracker periodically**:
   ```bash
   ./gradlew build -PwithByteBufTracking
   ```

2. **Fix leaks immediately** - Don't accumulate technical debt

3. **Use narrow package scope** for faster iteration:
   ```groovy
   // In build.gradle, modify to track only your module
   jvmArgs "-javaagent:...=include=com.github.ambry.mymodule"
   ```

### Debugging Leaks

1. **Start broad** - Track entire package
2. **Identify leak paths** - Look for ⚠️ LEAK markers
3. **Narrow scope** - Focus on specific classes
4. **Examine flow tree** - Understand ByteBuf journey
5. **Fix root cause** - Not just symptoms

## Related Documentation

- **CONSTRUCTOR_TRACKING.md** - Comprehensive guide to constructor tracking for wrapped objects
- **modules/bytebuddy-bytebuf-tracer/README.md** - Upstream tracker project documentation
- **modules/bytebuddy-bytebuf-tracer/WRAPPER_TRACKING.md** - Guide for tracking custom wrapper objects
- **modules/bytebuddy-bytebuf-tracer/STATIC_METHOD_TRACKING.md** - Static method tracking details

## External Resources

- [ByteBuddy Documentation](https://bytebuddy.net/)
- [Netty ByteBuf Reference Counting](https://netty.io/wiki/reference-counted-objects.html)
- [Java Agents Tutorial](https://www.baeldung.com/java-instrumentation)
- [Original ByteBuf Flow Tracker](https://github.com/j-tyler/bytebuddy-bytebuf-tracer)

## Support & Troubleshooting

### Getting Help

1. Check troubleshooting section above
2. Verify agent JAR exists: `ls -lh modules/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar`
3. Run with verbose logging: `./gradlew build -PwithByteBufTracking --info`
4. Review documentation files listed in Related Documentation section

### Reporting Issues

When reporting issues, include:
- Command used
- Error message
- Java version: `java -version`
- Gradle version: `./gradlew --version`
- Whether agent JAR was built successfully

## License

The ByteBuf Flow Tracker integration follows Ambry's Apache License 2.0.
The original tracker project is also Apache License 2.0.

---

**Quick Links:**
- [Quick Start](#quick-start)
- [Troubleshooting](#troubleshooting)
- [Advanced Configuration](#advanced-configuration)
- [Related Documentation](#related-documentation)
