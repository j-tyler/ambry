/*
 * Copyright 2025 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
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
 * Benchmark comparing Apache Commons Base64 vs Java 8 java.util.Base64
 * for encoding and decoding operations across different blob sizes.
 * <p>
 * This benchmark measures:
 * - Throughput (operations per second)
 * - Average execution time
 * <p>
 * Tested blob sizes:
 * - 1 KB (typical small metadata or identifier)
 * - 128 KB (medium-sized blob)
 * - 4 MB (large blob)
 * <p>
 * Configuration (optimized for fast feedback):
 * - Warmup: 1 iteration × 5 seconds
 * - Measurement: 2 iterations × 5 seconds
 * - Forks: 1
 * - Total runtime: ~6 minutes for all tests
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 2, time = 5)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@State(Scope.Thread)
public class Base64Benchmark {

  /**
   * Size of the blob to test.
   * 1024 = 1 KB
   * 131072 = 128 KB
   * 4194304 = 4 MB
   */
  @Param({"1024", "131072", "4194304"})
  private int blobSize;

  private byte[] randomData;
  private String apacheEncodedData;
  private String java8EncodedData;

  @Setup(Level.Trial)
  public void setup() {
    // Generate random binary data for testing
    randomData = new byte[blobSize];
    new Random(42).nextBytes(randomData); // Use fixed seed for reproducibility

    // Pre-encode data for decoding tests
    apacheEncodedData = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(randomData);
    java8EncodedData = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomData);
  }

  //
  // Apache Commons Base64 Benchmarks
  //

  @Benchmark
  public void apacheCommonsEncode(Blackhole blackhole) {
    String encoded = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(randomData);
    blackhole.consume(encoded);
  }

  @Benchmark
  public void apacheCommonsDecode(Blackhole blackhole) {
    byte[] decoded = org.apache.commons.codec.binary.Base64.decodeBase64(apacheEncodedData);
    blackhole.consume(decoded);
  }

  //
  // Java 8 Base64 Benchmarks
  //

  @Benchmark
  public void java8Encode(Blackhole blackhole) {
    String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomData);
    blackhole.consume(encoded);
  }

  @Benchmark
  public void java8Decode(Blackhole blackhole) {
    byte[] decoded = java.util.Base64.getUrlDecoder().decode(java8EncodedData);
    blackhole.consume(decoded);
  }

  //
  // Standard (non-URL-safe) encoding benchmarks for comparison
  //

  @Benchmark
  public void apacheCommonsEncodeStandard(Blackhole blackhole) {
    String encoded = org.apache.commons.codec.binary.Base64.encodeBase64String(randomData);
    blackhole.consume(encoded);
  }

  @Benchmark
  public void java8EncodeStandard(Blackhole blackhole) {
    String encoded = java.util.Base64.getEncoder().encodeToString(randomData);
    blackhole.consume(encoded);
  }

  //
  // Additional memory allocation benchmarks
  //

  /**
   * Tests encoding with explicit byte array allocation to measure allocation overhead.
   */
  @Benchmark
  public void apacheCommonsEncodeBytes(Blackhole blackhole) {
    byte[] encoded = org.apache.commons.codec.binary.Base64.encodeBase64URLSafe(randomData);
    blackhole.consume(encoded);
  }

  /**
   * Tests encoding with explicit byte array allocation to measure allocation overhead.
   */
  @Benchmark
  public void java8EncodeBytes(Blackhole blackhole) {
    byte[] encoded = java.util.Base64.getUrlEncoder().withoutPadding().encode(randomData);
    blackhole.consume(encoded);
  }
}
