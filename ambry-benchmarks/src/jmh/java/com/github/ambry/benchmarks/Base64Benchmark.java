package com.github.ambry.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares Apache Commons Base64 vs Java 8 Base64 across 1KB, 128KB, and 4MB blobs.
 * Tests encoding (bytes -> string) and decoding (string -> bytes) which are the primary operations.
 * Runtime: ~3 minutes with default config. Memory profiling enabled via GC profiler.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 2, time = 5)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@State(Scope.Thread)
public class Base64Benchmark {

  @Param({"1024", "131072", "4194304"})
  private int blobSize;

  private byte[] randomData;
  private String apacheEncodedData;
  private String java8EncodedData;

  // Cache encoder/decoder instances to avoid creating new objects on each iteration
  private static final java.util.Base64.Encoder JAVA8_ENCODER =
      java.util.Base64.getUrlEncoder().withoutPadding();
  private static final java.util.Base64.Decoder JAVA8_DECODER =
      java.util.Base64.getUrlDecoder();

  @Setup(Level.Trial)
  public void setup() {
    randomData = new byte[blobSize];
    new Random(42).nextBytes(randomData);

    apacheEncodedData = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(randomData);
    java8EncodedData = JAVA8_ENCODER.encodeToString(randomData);
  }

  @Benchmark
  public void apacheCommonsEncode(Blackhole blackhole) {
    blackhole.consume(org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(randomData));
  }

  @Benchmark
  public void apacheCommonsDecode(Blackhole blackhole) {
    blackhole.consume(org.apache.commons.codec.binary.Base64.decodeBase64(apacheEncodedData));
  }

  @Benchmark
  public void java8Encode(Blackhole blackhole) {
    blackhole.consume(JAVA8_ENCODER.encodeToString(randomData));
  }

  @Benchmark
  public void java8Decode(Blackhole blackhole) {
    blackhole.consume(JAVA8_DECODER.decode(java8EncodedData));
  }
}
