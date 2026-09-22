package com.java2nb.novel.auth.password;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Explicitly selected benchmark runner; excluded from ordinary Surefire naming conventions. */
class Argon2Benchmark {
    private static final Set<Integer> MEMORY_KIB = Set.of(19456, 32768, 65536);
    private static final Set<Integer> ITERATIONS = Set.of(2, 3, 4);
    private static final Set<Integer> PARALLELISM = Set.of(1, 2, 4);
    private static final Set<Integer> SAMPLES = Set.of(20, 50, 100);
    private static final Set<Integer> CONCURRENCY = Set.of(2, 4, 8, 16);

    @Test
    void benchmarkConfiguredVerificationCost() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("novel.argon2.benchmark.enabled"));
        int memoryKiB = allowed("novel.argon2.benchmark.memory-kib", MEMORY_KIB);
        int iterations = allowed("novel.argon2.benchmark.iterations", ITERATIONS);
        int parallelism = allowed("novel.argon2.benchmark.parallelism", PARALLELISM);
        int samples = allowed("novel.argon2.benchmark.samples", SAMPLES);
        int targetConcurrency = allowed("novel.argon2.benchmark.target-concurrency", CONCURRENCY);
        if ((long) memoryKiB * targetConcurrency > 524288L) {
            throw new IllegalArgumentException("Requested benchmark memory exceeds the 512 MiB safety budget.");
        }

        Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, parallelism, memoryKiB, iterations);
        byte[] inputBytes = new byte[24];
        new SecureRandom().nextBytes(inputBytes);
        String benchmarkInput = Base64.getEncoder().encodeToString(inputBytes);
        String encoded = encoder.encode(benchmarkInput);
        for (int concurrency : new int[]{1, targetConcurrency}) {
            runStage("encode", concurrency, samples, () -> {
                String result = encoder.encode(benchmarkInput);
                assertTrue(result.startsWith("$argon2id$"));
            });
            runStage("verify", concurrency, samples,
                () -> assertTrue(encoder.matches(benchmarkInput, encoded)));
        }
    }

    private static void runStage(String operation, int concurrency, int samples,
                                 CheckedOperation operationBody) throws Exception {
        operationBody.run();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>(samples);
        try {
            for (int index = 0; index < samples; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    long before = System.nanoTime();
                    operationBody.run();
                    long elapsed = System.nanoTime() - before;
                    return elapsed;
                }));
            }
            long wallStart = System.nanoTime();
            start.countDown();
            List<Long> timings = new ArrayList<>(samples);
            for (Future<Long> future : futures) timings.add(future.get());
            long wallNanos = System.nanoTime() - wallStart;
            Collections.sort(timings);
            double p50 = nanosToMillis(percentile(timings, 0.50));
            double p95 = nanosToMillis(percentile(timings, 0.95));
            double throughput = samples / (wallNanos / 1_000_000_000.0);
            System.out.printf(Locale.ROOT,
                "ARGON2_BENCHMARK_RESULT operation=%s concurrency=%d samples=%d p50_ms=%.3f p95_ms=%.3f throughput_ops_per_sec=%.3f failures=0%n",
                operation, concurrency, samples, p50, p95, throughput);
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static int allowed(String name, Set<Integer> allowlist) {
        int value;
        try {
            value = Integer.parseInt(System.getProperty(name, ""));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Missing or invalid benchmark parameter: " + name);
        }
        if (!allowlist.contains(value)) {
            throw new IllegalArgumentException("Benchmark parameter is not allowlisted: " + name);
        }
        return value;
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(index);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }
}
