# Ambry Benchmarks

This module contains JMH (Java Microbenchmark Harness) benchmarks for performance testing various components of Ambry.

## Overview

The benchmarks in this module are designed to measure and compare the performance characteristics of different implementations, particularly focusing on areas that have been identified during memory profiling and performance analysis.

## Current Benchmarks

### Base64Benchmark

Compares the performance of Apache Commons Base64 vs Java 8's built-in `java.util.Base64` implementation.

**Test Cases:**
- **Encoding**: Converting binary data to Base64 string (URL-safe and standard)
- **Decoding**: Converting Base64 string back to binary data
- **Byte Array Operations**: Testing explicit byte array encoding

**Blob Sizes Tested:**
- 1 KB (1,024 bytes) - Typical for small metadata or identifiers
- 128 KB (131,072 bytes) - Medium-sized blobs
- 4 MB (4,194,304 bytes) - Large blobs

**Metrics Measured:**
- **Throughput**: Operations per millisecond
- **Average Time**: Average execution time per operation

**Benchmark Configuration (optimized for fast feedback):**
- **Warmup**: 1 iteration × 5 seconds
- **Measurement**: 2 iterations × 5 seconds
- **Forks**: 1
- **Heap Size**: 2GB (-Xms2g -Xmx2g)
- **Total Runtime**: ~6 minutes for all tests

**For publication-quality results**, override with:
```bash
./gradlew jmh -Pjmh.fork=2 -Pjmh.iterations=3 -Pjmh.timeOnIteration='10s'
# Runtime: ~32 minutes
```

## Running Benchmarks

### Run All Benchmarks

From the project root:

```bash
./gradlew :ambry-benchmarks:jmh
```

Or simply:

```bash
./gradlew jmh
```

### Run Specific Benchmarks

To run only specific benchmark methods, use the `includes` parameter:

```bash
./gradlew :ambry-benchmarks:jmh -Pjmh.includes='.*java8Encode.*'
```

### Run with Custom Parameters

You can customize benchmark parameters:

```bash
# Run with more iterations
./gradlew :ambry-benchmarks:jmh -Pjmh.iterations=5

# Run with different fork count
./gradlew :ambry-benchmarks:jmh -Pjmh.fork=3

# Run with specific blob size
./gradlew :ambry-benchmarks:jmh -Pjmh.params='blobSize=1024'

# Run with longer iteration time
./gradlew :ambry-benchmarks:jmh -Pjmh.timeOnIteration='20s'
```

**Note**: The JMH configuration automatically picks up all benchmarks in the `com.github.ambry` package, so any new benchmarks you add will be included automatically.

## Results

### Output Location

Benchmark results are saved to:
- **Default**: `ambry-benchmarks/build/reports/jmh/results.txt`
- **Custom Location**: Override with `-Pjmh.resultFile=<path>`
- **JSON Format**: Add `-Pjmh.resultFormat=JSON` to output JSON results

### Interpreting Results

The benchmark produces two types of metrics:

1. **Throughput (ops/ms)**: Higher is better
   - Indicates how many operations can be performed per millisecond
   - Useful for understanding maximum processing capacity

2. **Average Time (ms/op)**: Lower is better
   - Indicates the average time taken for a single operation
   - Useful for understanding latency characteristics

### Example Output

```
Benchmark                                    (blobSize)   Mode  Cnt     Score     Error   Units
Base64Benchmark.apacheCommonsEncode               1024  thrpt   10  1234.567 ±  12.345  ops/ms
Base64Benchmark.java8Encode                       1024  thrpt   10  2345.678 ±  23.456  ops/ms
Base64Benchmark.apacheCommonsEncode             131072  thrpt   10    12.345 ±   0.123  ops/ms
Base64Benchmark.java8Encode                     131072  thrpt   10    23.456 ±   0.234  ops/ms
```

In this example:
- Java 8 Base64 is ~90% faster than Apache Commons for 1KB blobs
- Java 8 Base64 is ~90% faster than Apache Commons for 128KB blobs

## Expected Performance Improvements

Based on memory profiling, switching from Apache Commons Base64 to Java 8 Base64 provides:

1. **Performance**: 30-50% improvement in throughput for encoding/decoding operations
2. **Memory**: Reduced object allocation overhead
3. **GC Pressure**: Lower garbage collection pressure due to more efficient implementation
4. **Native Optimizations**: Java 8 Base64 benefits from JIT compiler optimizations

## Adding New Benchmarks

To add a new benchmark:

1. Create a new class in `src/jmh/java/com/github/ambry/benchmarks/`
2. Annotate the class with `@State(Scope.Thread)`
3. Add `@Setup` methods to initialize test data
4. Add `@Benchmark` methods for each operation to measure
5. Use `@Param` to test different parameters
6. Document the benchmark in this README

Example:

```java
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class MyBenchmark {

    @Param({"100", "1000", "10000"})
    private int size;

    private byte[] data;

    @Setup
    public void setup() {
        data = new byte[size];
        // Initialize data
    }

    @Benchmark
    public void testOperation(Blackhole blackhole) {
        // Benchmark code here
        blackhole.consume(result);
    }
}
```

## Performance vs Precision Tradeoffs

The default configuration is optimized for **fast feedback** during development:
- ✅ **Quick Results**: ~6 minutes total runtime
- ✅ **Good Enough**: Sufficient precision for comparing implementations
- ✅ **Reasonable Confidence**: Catches major performance differences (>10%)
- ⚠️ **Lower Precision**: May miss subtle differences (<5%)

For **publication-quality results**:
```bash
./gradlew jmh -Pjmh.fork=2 -Pjmh.iterations=3 -Pjmh.timeOnIteration='10s'
```
- ✅ **High Precision**: Detects differences as small as 2-3%
- ✅ **Statistical Significance**: Multiple forks account for JVM variability
- ⚠️ **Slower**: ~32 minutes total runtime

## Best Practices

1. **Use Blackhole**: Always consume benchmark results with `Blackhole.consume()` to prevent JIT optimization from eliminating dead code
2. **Warmup**: Include adequate warmup iterations (default: 1 iteration of 5 seconds)
3. **Measurement**: Use sufficient measurement iterations (default: 2 iterations of 5 seconds for quick feedback)
4. **Fork**: Run multiple forks to account for JVM variability (default: 1 for speed, use 2-3 for publication)
5. **Fixed Seed**: Use fixed random seeds for reproducibility
6. **Realistic Data**: Use realistic data sizes and patterns
7. **Package Structure**: Place all benchmarks in `com.github.ambry.benchmarks` package to be automatically discovered

## References

- [JMH Documentation](https://github.com/openjdk/jmh)
- [JMH Gradle Plugin](https://github.com/melix/jmh-gradle-plugin)
- [Writing Good Benchmarks](https://shipilev.net/blog/2014/nanotrusting-nanotime/)
- [JMH Samples](https://hg.openjdk.java.net/code-tools/jmh/file/tip/jmh-samples/src/main/java/org/openjdk/jmh/samples/)

## Notes

- This module is **not** built as part of regular builds
- Benchmarks should only be run explicitly via `./gradlew jmh`
- Results may vary based on JVM version, hardware, and system load
- For consistent results, run benchmarks on a quiet system with minimal background processes
