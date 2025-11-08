# ByteBuf Memory Tracking with bytebuddy-bytebuf-tracer

This document explains how to use the ByteBuf Flow Tracker agent to detect memory leaks during test execution in Ambry.

## What is ByteBuf Flow Tracker?

The ByteBuf Flow Tracker is a lightweight Java agent that monitors Netty ByteBuf object lifecycle through your application using a Trie data structure. It helps identify memory leaks by tracking ByteBuf reference counts and detecting unreleased buffers.

**Key Features:**
- Zero allocation overhead - no expensive stack trace collection
- First-touch-as-root methodology - initial handler becomes the Trie root
- Memory efficient - shared Trie prefixes minimize storage
- Tracks complete flow paths through your application
- JMX integration for runtime monitoring

**Project:** https://github.com/j-tyler/bytebuddy-bytebuf-tracer

## Setup

### 1. Clone and Build the Tracker

The tracker agent must be built separately before use:

```bash
# Clone the repository (outside of ambry)
cd ~
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git
cd bytebuddy-bytebuf-tracer

# Build with Maven (requires Maven 3.6+ and Java 8+)
mvn clean package -DskipTests
```

This produces the agent JAR at:
```
~/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar
```

### 2. Verify the Agent JAR Exists

```bash
ls -lh ~/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar
```

You should see a JAR file around 2-3 MB in size.

## Usage

### Running Tests WITHOUT Tracking (Normal Build)

The default behavior is unchanged - tests run normally without any tracking overhead:

```bash
./gradlew build
./gradlew test
./gradlew intTest
./gradlew allTest
```

### Running Tests WITH Tracking

Enable ByteBuf tracking by adding the `-PwithByteBufTracking` flag:

```bash
# Run all unit tests with tracking
./gradlew test -PwithByteBufTracking

# Run integration tests with tracking
./gradlew intTest -PwithByteBufTracking

# Run all tests with tracking
./gradlew allTest -PwithByteBufTracking

# Run specific module tests with tracking
./gradlew :ambry-commons:test -PwithByteBufTracking
./gradlew :ambry-network:test -PwithByteBufTracking
```

### Alternative Flag Names

All of these work:
```bash
./gradlew test -PwithByteBufTracking
./gradlew test -PenableByteBufTracking
```

### Custom Agent Path

If you built the agent in a different location, specify it:

```bash
./gradlew test -PwithByteBufTracking -Dbytebuf.agent.path=/custom/path/to/agent.jar
```

## Understanding the Output

When tracking is enabled, you'll see:

```
ByteBuf Flow Tracker ENABLED
Using agent: /home/user/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar
```

The agent will track all ByteBuf flows through `com.github.ambry` packages during test execution.

### Accessing Tracking Data

The tracker exposes data via JMX MBean. You can access it:

1. **Via JMX Console** (if JMX is enabled):
   ```bash
   jconsole <pid>
   # Navigate to: MBeans → com.example → ByteBufFlowTracker
   ```

2. **Programmatically** in test code:
   ```java
   MBeanServer server = ManagementFactory.getPlatformMBeanServer();
   ObjectName name = new ObjectName("com.example:type=ByteBufFlowTracker");
   String treeView = (String) server.invoke(name, "getTreeView", null, null);
   System.out.println(treeView);
   ```

3. **Via custom test listener** (future enhancement):
   - A JUnit listener can be added to automatically print reports

### Example Output Format

The tracker shows ByteBuf flow trees like:

```
ROOT: NettyRequest.readInto [count=45]
└── ChunkProcessor.processChunk [ref=1, count=45]
    └── ChunkProcessor.validate [ref=1, count=45]
        └── ChunkProcessor.release [ref=0, count=45]

ROOT: LeakyHandler.handleRequest [count=3]
└── ErrorHandler.logError [ref=1, count=3] ⚠️ LEAK
```

**Legend:**
- `ref=N` - Current reference count at this point
- `count=N` - Number of times this path was traversed
- `⚠️ LEAK` - ByteBuf was not released (refCount > 0)

## Troubleshooting

### Error: "ByteBuf tracker agent JAR not found"

**Symptom:**
```
ByteBuf tracker agent JAR not found at: /home/user/bytebuddy-bytebuf-tracer/...
```

**Solution:**
1. Make sure you've cloned and built the tracker (see Setup section)
2. Verify the agent JAR exists:
   ```bash
   ls -lh ~/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/*.jar
   ```
3. If built in a different location, use `-Dbytebuf.agent.path=...`

### Build Works Without Flag But Fails With Flag

This is expected if the agent JAR hasn't been built. The tracking feature is opt-in and doesn't affect normal builds.

### No Tracking Output Visible

The agent tracks in the background. To see output:
1. Enable JMX and use JConsole
2. Add a custom test listener (see below)
3. Use programmatic access in test code

### Performance Impact

The tracker has minimal overhead but will slow tests slightly. For faster iteration:
- Run tracking only on suspected modules: `./gradlew :ambry-network:test -PwithByteBufTracking`
- Don't use tracking for routine development builds
- Reserve for leak investigation and CI/CD analysis

## Advanced Configuration

### Tracking Additional Packages

By default, only `com.github.ambry` packages are tracked. To include more:

Edit `build.gradle` and change:
```groovy
jvmArgs "-javaagent:${agentJar.absolutePath}=include=com.github.ambry,io.netty.buffer"
```

### Excluding Packages

To exclude specific packages:
```groovy
jvmArgs "-javaagent:${agentJar.absolutePath}=include=com.github.ambry;exclude=com.github.ambry.test"
```

### Enable JMX Remote Monitoring

Add to test configuration in `build.gradle`:
```groovy
systemProperty 'com.sun.management.jmxremote', 'true'
systemProperty 'com.sun.management.jmxremote.port', '9999'
systemProperty 'com.sun.management.jmxremote.authenticate', 'false'
systemProperty 'com.sun.management.jmxremote.ssl', 'false'
```

Then connect with:
```bash
jconsole localhost:9999
```

## Future Enhancements

Planned improvements:
- [ ] JUnit test listener that auto-prints reports after test completion
- [ ] HTML report generation with flow diagrams
- [ ] Per-test leak isolation and reporting
- [ ] Automatic leak detection with build failure on leaks
- [ ] Integration with CI/CD pipelines

## More Information

- **Tracker Documentation**: https://github.com/j-tyler/bytebuddy-bytebuf-tracer/blob/master/README.md
- **Architecture Details**: https://github.com/j-tyler/bytebuddy-bytebuf-tracer/blob/master/ARCHITECTURE.md
- **ByteBuddy**: https://bytebuddy.net/
- **Netty ByteBuf**: https://netty.io/wiki/reference-counted-objects.html

## Example Workflow

```bash
# One-time setup
cd ~
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git
cd bytebuddy-bytebuf-tracer
mvn clean package -DskipTests

# Regular development (no tracking)
cd ~/ambry
./gradlew build
./gradlew test

# Investigating suspected memory leak in ambry-network
./gradlew :ambry-network:test -PwithByteBufTracking

# Review JMX output or add custom listener to see results
```
