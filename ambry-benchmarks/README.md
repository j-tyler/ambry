# Ambry Benchmarks

JMH (Java Microbenchmark Harness) performance benchmarks for Ambry components.

## Running Benchmarks

```bash
# Run all benchmarks (~6 minutes with GC profiler)
./gradlew jmh

# Run specific benchmarks
./gradlew jmh -Pjmh.includes='.*java8.*'

# Run without profilers (faster)
./gradlew jmh -Pjmh.profilers=

# Higher precision (slower, ~32 minutes)
./gradlew jmh -Pjmh.fork=2 -Pjmh.iterations=3 -Pjmh.timeOnIteration='10s'
```

## Configuration Options

```bash
-Pjmh.fork=N                      # Number of JVM forks (default: 1)
-Pjmh.iterations=N                # Measurement iterations (default: 2)
-Pjmh.timeOnIteration='Ns'        # Time per iteration (default: 5s)
-Pjmh.profilers='gc,stack'        # Enable profilers (default: gc)
-Pjmh.includes='.*Pattern.*'      # Filter benchmarks by pattern
-Pjmh.resultFormat=JSON           # Output format
-Pjmh.resultFile=path/to/file     # Custom output location
```

## Current Benchmarks

**Base64Benchmark**: Compares Apache Commons Base64 vs Java 8 Base64
- Tests: encode (bytes→string), decode (string→bytes)
- Blob sizes: 1KB, 128KB, 4MB
- Metrics: throughput, latency, memory allocations

## Output

Results saved to: `ambry-benchmarks/build/reports/jmh/results.txt`

**Key Metrics:**
- **Throughput (ops/ms)**: Higher is better
- **Average Time (ms/op)**: Lower is better
- **Allocation Rate (MB/sec)**: Memory allocation speed (lower is better)
- **Normalized Allocation (B/op)**: Bytes per operation (lower is better)
