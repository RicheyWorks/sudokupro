package com.xai.sudokupro.util;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SecureRandomGenerator {
    private static final Logger log = LoggerFactory.getLogger(SecureRandomGenerator.class);
    private static final boolean FIPS_MODE = Boolean.getBoolean("sudokupro.fips.mode");
    private static final ThreadLocal<SecureRandom> localRand = ThreadLocal.withInitial(() -> FIPS_MODE ? createFipsSecureRandom() : new SecureRandom());
    private final MeterRegistry meterRegistry;
    private final List<String> methodLog = Collections.synchronizedList(new ArrayList<>());

    public static final String CHARSET_ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    public static final String CHARSET_HEX = "0123456789abcdef";
    public static final String CHARSET_SYMBOLS = "!@#$%^&*()_+-=[]{}|;':,.<>?";

    @Autowired
    public SecureRandomGenerator(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry cannot be null");
    }

    public SecureRandomGenerator(SecureRandom rand, MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry cannot be null");
        localRand.set(rand);
    }

    private static SecureRandom createFipsSecureRandom() {
        try {
            return SecureRandom.getInstance("DRBG");
        } catch (NoSuchAlgorithmException e) {
            log.warn("DRBG not available, falling back to default SecureRandom");
            return new SecureRandom();
        }
    }

    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("Bound must be positive");
        methodLog.add("nextInt");
        return localRand.get().nextInt(bound);
    }

    public int nextIntRange(int min, int max) {
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("nextIntRange");
        return min + localRand.get().nextInt(max - min + 1);
    }

    public long nextLong(long bound) {
        if (bound <= 0) throw new IllegalArgumentException("Bound must be positive");
        methodLog.add("nextLong");
        return Math.abs(localRand.get().nextLong()) % bound;
    }

    public List<Integer> getShuffledNumbers(int start, int end) {
        if (start > end) throw new IllegalArgumentException("Start must be <= end");
        methodLog.add("getShuffledNumbers");
        List<Integer> numbers = new ArrayList<>();
        for (int i = start; i <= end; i++) numbers.add(i);
        Collections.shuffle(numbers, localRand.get());
        return numbers;
    }

    public boolean flipCoin() {
        meterRegistry.counter("sudokupro.rng.flip_coin").increment();
        methodLog.add("flipCoin");
        return localRand.get().nextBoolean();
    }

    public double nextDouble() {
        methodLog.add("nextDouble");
        return localRand.get().nextDouble();
    }

    public double nextDoubleRange(double min, double max) {
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("nextDoubleRange");
        return min + (max - min) * localRand.get().nextDouble();
    }

    public boolean chance(double probability) {
        if (probability < 0 || probability > 1) throw new IllegalArgumentException("Probability must be 0-1");
        methodLog.add("chance");
        return localRand.get().nextDouble() < probability;
    }

    public double gaussianDouble(double mean, double stdDev) {
        if (stdDev < 0) throw new IllegalArgumentException("StdDev must be non-negative");
        methodLog.add("gaussianDouble");
        return mean + localRand.get().nextGaussian() * stdDev;
    }

    public int nextBinomial(int n, double p) {
        if (n < 0 || p < 0 || p > 1) throw new IllegalArgumentException("Invalid n or p");
        methodLog.add("nextBinomial");
        int successes = 0;
        for (int i = 0; i < n; i++) {
            if (localRand.get().nextDouble() < p) successes++;
        }
        return successes;
    }

    public void setSeed(long seed) {
        localRand.set(FIPS_MODE ? createFipsSecureRandom() : new SecureRandom());
        localRand.get().setSeed(seed);
        log.debug("Seed set for thread-local SecureRandom: {}", seed);
        methodLog.add("setSeed");
    }

    public double nextGaussian(double mean, double stddev) {
        methodLog.add("nextGaussian");
        return mean + stddev * localRand.get().nextGaussian();
    }

    public interface DistributionFunction {
        double generate(SecureRandom random);
    }

    public long getSeedSnapshot() {
        methodLog.add("getSeedSnapshot");
        return localRand.get().nextLong();
    }

    public void boostEntropy(byte[] extraBytes) {
        if (extraBytes == null || extraBytes.length == 0) throw new IllegalArgumentException("Extra bytes must not be null/empty");
        methodLog.add("boostEntropy");
        localRand.get().setSeed(extraBytes);
        log.info("Entropy boosted with {} bytes", extraBytes.length);
    }
}
