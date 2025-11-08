# ByteBuf Tracking - Quick Start

## One-Time Setup

```bash
# 1. Clone the tracker repository
cd ~
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git

# 2. Build the agent JAR
cd ~/bytebuddy-bytebuf-tracer
mvn clean package -DskipTests

# 3. Verify the agent JAR was created
ls -lh ~/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar
```

## Usage

### Normal Build (No Tracking)

```bash
cd ~/ambry
./gradlew build       # Normal build
./gradlew test        # Normal tests
./gradlew intTest     # Normal integration tests
```

Everything works as before - no overhead, no changes.

### With ByteBuf Tracking

```bash
cd ~/ambry
./gradlew test -PwithByteBufTracking        # Track unit tests
./gradlew intTest -PwithByteBufTracking     # Track integration tests
./gradlew allTest -PwithByteBufTracking     # Track all tests

# Track specific modules
./gradlew :ambry-network:test -PwithByteBufTracking
./gradlew :ambry-commons:test -PwithByteBufTracking
```

### Custom Agent Location

If you built the agent somewhere else:

```bash
./gradlew test -PwithByteBufTracking -Dbytebuf.agent.path=/path/to/agent.jar
```

## What You'll See

When tracking is enabled:
```
ByteBuf Flow Tracker ENABLED
Using agent: /home/user/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar
```

The agent tracks ByteBuf flows during test execution. Access results via JMX or custom test listeners.

## More Information

See [BYTEBUF_TRACKING.md](BYTEBUF_TRACKING.md) for complete documentation.
