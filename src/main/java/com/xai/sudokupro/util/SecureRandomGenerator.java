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

    public long nextLongRange(long min, long max) {
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("nextLongRange");
        return min + (long) (localRand.get().nextDouble() * (max - min + 1));
    }

    public List<Integer> getShuffledNumbers(int start, int end) {
        if (start > end) throw new IllegalArgumentException("Start must be <= end");
        methodLog.add("getShuffledNumbers");
        List<Integer> numbers = new ArrayList<>();
        for (int i = start; i <= end; i++) numbers.add(i);
        Collections.shuffle(numbers, localRand.get());
        return numbers;
    }

    public List<Integer> getShuffledSudokuNumbers() {
        methodLog.add("getShuffledSudokuNumbers");
        List<Integer> numbers = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));
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

    public float nextFloat() {
        methodLog.add("nextFloat");
        return localRand.get().nextFloat();
    }

    public float nextFloatRange(float min, float max) {
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("nextFloatRange");
        return min + (max - min) * localRand.get().nextFloat();
    }

    public <T> T pickRandom(List<T> items) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("List must not be null/empty");
        meterRegistry.counter("sudokupro.rng.pick_random").increment();
        methodLog.add("pickRandom");
        return items.get(localRand.get().nextInt(items.size()));
    }

    public <T> List<T> pickRandomMultiple(List<T> items, int count) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("List must not be null/empty");
        if (count < 0 || count > items.size()) throw new IllegalArgumentException("Count must be 0 to list size");
        methodLog.add("pickRandomMultiple");
        List<T> copy = new ArrayList<>(items);
        Collections.shuffle(copy, localRand.get());
        return copy.subList(0, count);
    }

    public Set<Integer> pickUniqueInts(int min, int max, int count) {
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        int rangeSize = max - min + 1;
        if (count < 0 || count > rangeSize) throw new IllegalArgumentException("Count must be 0 to range size");
        methodLog.add("pickUniqueInts");
        Set<Integer> unique = new HashSet<>();
        while (unique.size() < count) {
            unique.add(min + localRand.get().nextInt(rangeSize));
        }
        return unique;
    }

    public boolean chance(double probability) {
        if (probability < 0 || probability > 1) throw new IllegalArgumentException("Probability must be 0-1");
        methodLog.add("chance");
        return localRand.get().nextDouble() < probability;
    }

    public int weightedPick(int[] weights) {
        int total = 0;
        for (int weight : weights) {
            if (weight < 0) throw new IllegalArgumentException("Weights must be non-negative");
            total += weight;
        }
        if (total == 0) throw new IllegalArgumentException("Total weight must be positive");
        meterRegistry.counter("sudokupro.rng.weighted_pick").increment();
        methodLog.add("weightedPick");
        int pick = localRand.get().nextInt(total);
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += weights[i];
            if (pick < sum) return i;
        }
        return weights.length - 1;
    }

    public String generateRandomString(int length, String characters) {
        if (length <= 0) throw new IllegalArgumentException("Length must be positive");
        if (characters == null || characters.isEmpty()) throw new IllegalArgumentException("Characters must not be null/empty");
        meterRegistry.counter("sudokupro.rng.generate_random_string").increment();
        methodLog.add("generateRandomString");
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(characters.charAt(localRand.get().nextInt(characters.length())));
        }
        return sb.toString();
    }

    public List<Integer> generateRandomSudokuRow() {
        methodLog.add("generateRandomSudokuRow");
        List<Integer> row = new ArrayList<>(Collections.nCopies(9, 0));
        List<Integer> numbers = getShuffledSudokuNumbers();
        for (int i = 0; i < numbers.size(); i++) {
            row.set(i, numbers.get(i));
        }
        return row;
    }

    public int[][] generateRandom2DArray(int rows, int cols, int min, int max) {
        if (rows <= 0 || cols <= 0) throw new IllegalArgumentException("Rows and cols must be positive");
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("generateRandom2DArray");
        int[][] array = new int[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                array[i][j] = min + localRand.get().nextInt(max - min + 1);
            }
        }
        return array;
    }

    public List<List<Integer>> generateRandomGrid(int rows, int cols, int min, int max) {
        if (rows <= 0 || cols <= 0) throw new IllegalArgumentException("Rows and cols must be positive");
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("generateRandomGrid");
        List<List<Integer>> grid = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            List<Integer> row = new ArrayList<>();
            for (int j = 0; j < cols; j++) {
                row.add(min + localRand.get().nextInt(max - min + 1));
            }
            grid.add(row);
        }
        return grid;
    }

    public int[] generateRandomArray(int size, int min, int max) {
        if (size < 0) throw new IllegalArgumentException("Size must be non-negative");
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("generateRandomArray");
        int[] array = new int[size];
        for (int i = 0; i < size; i++) {
            array[i] = min + localRand.get().nextInt(max - min + 1);
        }
        return array;
    }

    public List<Integer> generatePermutation(int n) {
        if (n < 0) throw new IllegalArgumentException("N must be non-negative");
        methodLog.add("generatePermutation");
        List<Integer> perm = new ArrayList<>();
        for (int i = 0; i < n; i++) perm.add(i);
        Collections.shuffle(perm, localRand.get());
        return perm;
    }

    public int rollDice(int sides) {
        if (sides <= 0) throw new IllegalArgumentException("Sides must be positive");
        meterRegistry.counter("sudokupro.rng.roll_dice").increment();
        methodLog.add("rollDice");
        return 1 + localRand.get().nextInt(sides);
    }

    public int[] rollMultipleDice(int sides, int count) {
        if (sides <= 0 || count < 0) throw new IllegalArgumentException("Sides must be positive, count non-negative");
        methodLog.add("rollMultipleDice");
        int[] rolls = new int[count];
        for (int i = 0; i < count; i++) {
            rolls[i] = 1 + localRand.get().nextInt(sides);
        }
        return rolls;
    }

    public <T> T pickFromMap(Map<T, Integer> weightedItems) {
        if (weightedItems == null || weightedItems.isEmpty()) throw new IllegalArgumentException("Map must not be null/empty");
        int total = 0;
        for (int weight : weightedItems.values()) {
            if (weight < 0) throw new IllegalArgumentException("Weights must be non-negative");
            total += weight;
        }
        if (total == 0) throw new IllegalArgumentException("Total weight must be positive");
        methodLog.add("pickFromMap");
        int pick = localRand.get().nextInt(total);
        int sum = 0;
        for (Map.Entry<T, Integer> entry : weightedItems.entrySet()) {
            sum += entry.getValue();
            if (pick < sum) return entry.getKey();
        }
        return weightedItems.keySet().iterator().next();
    }

    public List<Integer> generateUniqueSortedInts(int min, int max, int count) {
        methodLog.add("generateUniqueSortedInts");
        Set<Integer> unique = pickUniqueInts(min, max, count);
        return new ArrayList<>(new TreeSet<>(unique));
    }

    public int[][] generateRandomSudokuGrid() {
        methodLog.add("generateRandomSudokuGrid");
        int[][] grid = new int[9][9];
        for (int i = 0; i < 9; i++) {
            List<Integer> row = getShuffledSudokuNumbers();
            for (int j = 0; j < 9; j++) {
                grid[i][j] = row.get(j);
            }
        }
        return grid;
    }

    public char nextChar(String charset) {
        if (charset == null || charset.isEmpty()) throw new IllegalArgumentException("Charset must not be null/empty");
        methodLog.add("nextChar");
        return charset.charAt(localRand.get().nextInt(charset.length()));
    }

    public byte[] generateRandomBytes(int length) {
        if (length < 0) throw new IllegalArgumentException("Length must be non-negative");
        methodLog.add("generateRandomBytes");
        byte[] bytes = new byte[length];
        localRand.get().nextBytes(bytes);
        return bytes;
    }

    public List<String> shuffleStrings(List<String> strings) {
        if (strings == null || strings.isEmpty()) throw new IllegalArgumentException("List must not be null/empty");
        methodLog.add("shuffleStrings");
        List<String> copy = new ArrayList<>(strings);
        Collections.shuffle(copy, localRand.get());
        return copy;
    }

    public int gaussianInt(int mean, int stdDev) {
        if (stdDev < 0) throw new IllegalArgumentException("StdDev must be non-negative");
        methodLog.add("gaussianInt");
        return (int) (mean + localRand.get().nextGaussian() * stdDev);
    }

    public double gaussianDouble(double mean, double stdDev) {
        if (stdDev < 0) throw new IllegalArgumentException("StdDev must be non-negative");
        methodLog.add("gaussianDouble");
        return mean + localRand.get().nextGaussian() * stdDev;
    }

    public int[] generatePoissonArray(int size, double lambda) {
        if (size < 0 || lambda <= 0) throw new IllegalArgumentException("Size must be non-negative, lambda positive");
        methodLog.add("generatePoissonArray");
        int[] array = new int[size];
        for (int i = 0; i < size; i++) {
            array[i] = nextPoisson(lambda);
        }
        return array;
    }

    private int nextPoisson(double lambda) {
        methodLog.add("nextPoisson");
        double L = Math.exp(-lambda);
        double p = 1.0;
        int k = 0;
        do {
            k++;
            p *= localRand.get().nextDouble();
        } while (p > L);
        return k - 1;
    }

    public List<Integer> generateRandomWalk(int steps, int min, int max) {
        if (steps < 0) throw new IllegalArgumentException("Steps must be non-negative");
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("generateRandomWalk");
        List<Integer> walk = new ArrayList<>();
        int current = nextIntRange(min, max);
        walk.add(current);
        for (int i = 1; i < steps; i++) {
            current += flipCoin() ? 1 : -1;
            current = Math.max(min, Math.min(max, current));
            walk.add(current);
        }
        return walk;
    }

    public boolean[][] generateRandomBooleanGrid(int rows, int cols, double trueProb) {
        if (rows <= 0 || cols <= 0) throw new IllegalArgumentException("Rows and cols must be positive");
        if (trueProb < 0 || trueProb > 1) throw new IllegalArgumentException("Probability must be 0-1");
        methodLog.add("generateRandomBooleanGrid");
        boolean[][] grid = new boolean[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                grid[i][j] = localRand.get().nextDouble() < trueProb;
            }
        }
        return grid;
    }

    public int[][][] generateRandom3DArray(int depth, int rows, int cols, int min, int max) {
        if (depth <= 0 || rows <= 0 || cols <= 0) throw new IllegalArgumentException("Dimensions must be positive");
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("generateRandom3DArray");
        int[][][] array = new int[depth][rows][cols];
        for (int d = 0; d < depth; d++) {
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    array[d][i][j] = min + localRand.get().nextInt(max - min + 1);
                }
            }
        }
        return array;
    }

    public List<Integer> generateRandomSubset(int n, int subsetSize) {
        if (n < 0 || subsetSize < 0 || subsetSize > n) throw new IllegalArgumentException("Invalid n or subsetSize");
        methodLog.add("generateRandomSubset");
        List<Integer> numbers = IntStream.range(0, n).boxed().collect(Collectors.toList());
        Collections.shuffle(numbers, localRand.get());
        return numbers.subList(0, subsetSize);
    }

    public String generateUuid() {
        methodLog.add("generateUuid");
        byte[] bytes = new byte[16];
        localRand.get().nextBytes(bytes);
        bytes[6] &= 0x0f;
        bytes[6] |= 0x40;
        bytes[8] &= 0x3f;
        bytes[8] |= 0x80;
        return String.format("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]);
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

    public double nextExponential(double lambda) {
        if (lambda <= 0) throw new IllegalArgumentException("Lambda must be positive");
        methodLog.add("nextExponential");
        return -Math.log(1 - localRand.get().nextDouble()) / lambda;
    }

    public int[][] generateRandomLatinSquare(int n) {
        if (n <= 0) throw new IllegalArgumentException("N must be positive");
        methodLog.add("generateRandomLatinSquare");
        int[][] square = new int[n][n];
        List<Integer> baseRow = getShuffledNumbers(0, n - 1);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                square[i][j] = (baseRow.get(j) + i) % n;
            }
        }
        for (int i = 0; i < n; i++) {
            Collections.shuffle(Arrays.stream(square[i]).boxed().collect(Collectors.toList()), localRand.get());
        }
        return square;
    }

    public List<Integer> generateRandomCycle(int n) {
        if (n < 1) throw new IllegalArgumentException("N must be positive");
        methodLog.add("generateRandomCycle");
        List<Integer> cycle = getShuffledNumbers(0, n - 1);
        cycle.add(cycle.get(0));
        return cycle;
    }

    public Queue<Integer> generateRandomQueue(int size, int min, int max) {
        if (size < 0) throw new IllegalArgumentException("Size must be non-negative");
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("generateRandomQueue");
        Queue<Integer> queue = new LinkedList<>();
        for (int i = 0; i < size; i++) {
            queue.add(min + localRand.get().nextInt(max - min + 1));
        }
        return queue;
    }

    public double[][] generateRandomMatrix(int rows, int cols, double min, double max) {
        if (rows <= 0 || cols <= 0) throw new IllegalArgumentException("Rows and cols must be positive");
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("generateRandomMatrix");
        double[][] matrix = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                matrix[i][j] = min + (max - min) * localRand.get().nextDouble();
            }
        }
        return matrix;
    }

    public int nextGeometric(double p) {
        if (p <= 0 || p > 1) throw new IllegalArgumentException("P must be between 0 and 1");
        methodLog.add("nextGeometric");
        return (int) Math.ceil(Math.log(1 - localRand.get().nextDouble()) / Math.log(1 - p));
    }

    public List<int[]> generateRandomPairs(int count, int min, int max) {
        if (count < 0) throw new IllegalArgumentException("Count must be non-negative");
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("generateRandomPairs");
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pairs.add(new int[]{nextIntRange(min, max), nextIntRange(min, max)});
        }
        return pairs;
    }

    public int[] generateShuffledArray(int[] array) {
        if (array == null) throw new IllegalArgumentException("Array must not be null");
        methodLog.add("generateShuffledArray");
        List<Integer> list = Arrays.stream(array).boxed().collect(Collectors.toList());
        Collections.shuffle(list, localRand.get());
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    public List<List<Integer>> generateRandomPartition(int n, int parts) {
        if (n <= 0 || parts <= 0 || parts > n) throw new IllegalArgumentException("Invalid n or parts");
        methodLog.add("generateRandomPartition");
        List<Integer> points = new ArrayList<>(pickUniqueInts(1, n - 1, parts - 1));
        points.add(0);
        points.add(n);
        Collections.sort(points);
        List<List<Integer>> partition = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            int start = points.get(i);
            int end = points.get(i + 1);
            partition.add(IntStream.range(start, end).boxed().collect(Collectors.toList()));
        }
        return partition;
    }

    public <T> void shuffleArray(T[] array) {
        if (array == null || array.length == 0) throw new IllegalArgumentException("Array must not be null/empty");
        methodLog.add("shuffleArray");
        List<T> list = Arrays.asList(array);
        Collections.shuffle(list, localRand.get());
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

    public int nextZipf(int size, double exponent) {
        if (size <= 0 || exponent <= 0) throw new IllegalArgumentException("Size and exponent must be > 0");
        methodLog.add("nextZipf");
        double total = 0.0;
        double[] weights = new double[size];
        for (int i = 1; i <= size; i++) {
            weights[i - 1] = 1.0 / Math.pow(i, exponent);
            total += weights[i - 1];
        }
        double r = localRand.get().nextDouble() * total;
        for (int i = 0; i < size; i++) {
            r -= weights[i];
            if (r <= 0) return i;
        }
        return size - 1;
    }

    public double nextCauchy(double median, double scale) {
        if (scale <= 0) throw new IllegalArgumentException("Scale must be positive");
        methodLog.add("nextCauchy");
        return median + scale * Math.tan(Math.PI * (localRand.get().nextDouble() - 0.5));
    }

    public int nextUniformDiscrete(int min, int max) {
        if (min > max) throw new IllegalArgumentException("Min must be <= max");
        methodLog.add("nextUniformDiscrete");
        return min + localRand.get().nextInt(max - min + 1);
    }

    public double nextPareto(double scale, double shape) {
        if (scale <= 0 || shape <= 0) throw new IllegalArgumentException("Scale and shape must be positive");
        methodLog.add("nextPareto");
        return scale / Math.pow(1 - localRand.get().nextDouble(), 1.0 / shape);
    }

    public double nextWeibull(double scale, double shape) {
        if (scale <= 0 || shape <= 0) throw new IllegalArgumentException("Scale and shape must be positive");
        methodLog.add("nextWeibull");
        return scale * Math.pow(-Math.log(1 - localRand.get().nextDouble()), 1.0 / shape);
    }

    public int nextHypergeometric(int population, int successes, int sample) {
        if (population < 0 || successes < 0 || sample < 0 || successes > population || sample > population) {
            throw new IllegalArgumentException("Invalid hypergeometric parameters");
        }
        methodLog.add("nextHypergeometric");
        int result = 0;
        int remainingPopulation = population;
        int remainingSuccesses = successes;
        for (int i = 0; i < sample && remainingSuccesses > 0; i++) {
            if (localRand.get().nextDouble() < (double) remainingSuccesses / remainingPopulation) {
                result++;
                remainingSuccesses--;
            }
            remainingPopulation--;
        }
        return result;
    }

    public double[][] generatePerlinNoise(int width, int height, double scale) {
        if (width <= 0 || height <= 0 || scale <= 0) throw new IllegalArgumentException("Dimensions and scale must be positive");
        methodLog.add("generatePerlinNoise");
        double[][] noise = new double[width][height];
        Timer timer = meterRegistry.timer("sudokupro.rng.perlin");
        for (int x = 0; x < width; x++) {
    for (int y = 0; y < height; y++) {

        final int finalX = x;
        final int finalY = y;
        final double finalScale = scale;

        noise[finalX][finalY] =
            timer.record(() -> perlin(finalX / finalScale, finalY / finalScale));
    }
}
        return noise;
    }

    private double perlin(double x, double y) {
        int x0 = (int) Math.floor(x);
        int x1 = x0 + 1;
        int y0 = (int) Math.floor(y);
        int y1 = y0 + 1;
        double sx = x - x0;
        double sy = y - y0;
        double n0 = dotGridGradient(x0, y0, x, y);
        double n1 = dotGridGradient(x1, y0, x, y);
        double ix0 = lerp(n0, n1, sx);
        double n2 = dotGridGradient(x0, y1, x, y);
        double n3 = dotGridGradient(x1, y1, x, y);
        double ix1 = lerp(n2, n3, sx);
        return lerp(ix0, ix1, sy);
    }

    private double dotGridGradient(int ix, int iy, double x, double y) {
        double dx = x - ix;
        double dy = y - iy;
        double[] gradient = {localRand.get().nextDouble() * 2 - 1, localRand.get().nextDouble() * 2 - 1};
        return dx * gradient[0] + dy * gradient[1];
    }

    private double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    public List<List<Integer>> generateRandomTree(int nodes) {
        if (nodes < 1) throw new IllegalArgumentException("Nodes must be positive");
        methodLog.add("generateRandomTree");
        List<List<Integer>> tree = new ArrayList<>();
        for (int i = 0; i < nodes; i++) tree.add(new ArrayList<>());
        for (int i = 1; i < nodes; i++) {
            int parent = nextIntRange(0, i - 1);
            tree.get(i).add(parent);
            tree.get(parent).add(i);
        }
        return tree;
    }

    public int[][] generateRandomGraph(int nodes, double edgeProb) {
        if (nodes < 0 || edgeProb < 0 || edgeProb > 1) throw new IllegalArgumentException("Invalid nodes or edgeProb");
        methodLog.add("generateRandomGraph");
        int[][] graph = new int[nodes][nodes];
        for (int i = 0; i < nodes; i++) {
            for (int j = i + 1; j < nodes; j++) {
                if (localRand.get().nextDouble() < edgeProb) {
                    graph[i][j] = 1;
                    graph[j][i] = 1;
                }
            }
        }
        return graph;
    }

    public int[] generateRandomPermutationWithFixedPoint(int n) {
        if (n < 0) throw new IllegalArgumentException("N must be non-negative");
        methodLog.add("generateRandomPermutationWithFixedPoint");
        List<Integer> perm = generatePermutation(n);
        int fixed = nextIntRange(0, n - 1);
        perm.set(fixed, fixed);
        return perm.stream().mapToInt(Integer::intValue).toArray();
    }

    public double nextLogNormal(double mean, double stdDev) {
        if (stdDev < 0) throw new IllegalArgumentException("StdDev must be non-negative");
        methodLog.add("nextLogNormal");
        return Math.exp(mean + stdDev * localRand.get().nextGaussian());
    }

    public int nextNegativeBinomial(int r, double p) {
        if (r <= 0 || p <= 0 || p > 1) throw new IllegalArgumentException("Invalid r or p");
        methodLog.add("nextNegativeBinomial");
        int failures = 0;
        for (int i = 0; i < r; i++) {
            failures += nextGeometric(p) - 1;
        }
        return failures;
    }

    public double[][] generateRandomSimplex(int rows, int cols) {
        if (rows <= 0 || cols <= 0) throw new IllegalArgumentException("Rows and cols must be positive");
        methodLog.add("generateRandomSimplex");
        double[][] simplex = new double[rows][cols];
        Timer timer = meterRegistry.timer("sudokupro.rng.simplex");
        return timer.record(() -> {
            for (int i = 0; i < rows; i++) {
                double sum = 0;
                for (int j = 0; j < cols; j++) {
                    simplex[i][j] = nextExponential(1.0);
                    sum += simplex[i][j];
                }
                for (int j = 0; j < cols; j++) {
                    simplex[i][j] /= sum;
                }
            }
            return simplex;
        });
    }

    public int[] generateRandomHistogram(int bins, int samples) {
        if (bins <= 0 || samples < 0) throw new IllegalArgumentException("Bins must be positive, samples non-negative");
        methodLog.add("generateRandomHistogram");
        int[] hist = new int[bins];
        for (int i = 0; i < samples; i++) {
            hist[nextInt(bins)]++;
        }
        return hist;
    }

    public List<Integer> generateRandomSpiral(int size) {
        if (size < 1) throw new IllegalArgumentException("Size must be positive");
        methodLog.add("generateRandomSpiral");
        List<Integer> spiral = new ArrayList<>();
        int[][] grid = generateRandom2DArray(size, size, 0, 1);
        int top = 0, bottom = size - 1, left = 0, right = size - 1;
        while (top <= bottom && left <= right) {
            for (int i = left; i <= right && top <= bottom; i++) spiral.add(grid[top][i]);
            top++;
            for (int i = top; i <= bottom && left <= right; i++) spiral.add(grid[i][right]);
            right--;
            for (int i = right; i >= left && top <= bottom; i--) spiral.add(grid[bottom][i]);
            bottom--;
            for (int i = bottom; i >= top && left <= right; i--) spiral.add(grid[i][left]);
            left++;
        }
        return spiral;
    }

    public int getSystemEntropyBits() {
        methodLog.add("getSystemEntropyBits");
        try {
            String output = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/sys/kernel/random/entropy_avail")));
            return Integer.parseInt(output.trim());
        } catch (Exception e) {
            log.warn("Could not read entropy stats: {}", e.getMessage());
            return -1;
        }
    }

    public String generateTimeBasedUuid() {
        methodLog.add("generateTimeBasedUuid");
        long timestamp = System.currentTimeMillis();
        long randomBits = localRand.get().nextLong();
        return new UUID(timestamp, randomBits).toString();
    }

    public String generateNameBasedUuid(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Name must not be null/empty");
        methodLog.add("generateNameBasedUuid");
        byte[] nameBytes = name.getBytes();
        byte[] hash;
        try {
            hash = java.security.MessageDigest.getInstance("SHA-1").digest(nameBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
        hash[6] &= 0x0f;
        hash[6] |= 0x50;
        hash[8] &= 0x3f;
        hash[8] |= 0x80;
        return String.format("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
            hash[0], hash[1], hash[2], hash[3], hash[4], hash[5], hash[6], hash[7],
            hash[8], hash[9], hash[10], hash[11], hash[12], hash[13], hash[14], hash[15]);
    }

    public interface DistributionFunction {
        double generate(SecureRandom random);
    }

    public double nextCustom(DistributionFunction fn) {
        methodLog.add("nextCustom");
        return fn.generate(localRand.get());
    }

    public long getSeedSnapshot() {
        methodLog.add("getSeedSnapshot");
        return localRand.get().nextLong();
    }

    public void replayWithSeed(long seed) {
        methodLog.add("replayWithSeed");
        setSeed(seed);
    }

    public String exportRngSessionJson() {
        methodLog.add("exportRngSessionJson");
        Map<String, Object> session = new HashMap<>();
        session.put("lastSeed", getSeedSnapshot());
        session.put("lastMethods", methodLog.size() > 5 ? methodLog.subList(methodLog.size() - 5, methodLog.size()) : methodLog);
        session.put("entropyBits", getSystemEntropyBits());
        session.put("flipCoinCount", meterRegistry.counter("sudokupro.rng.flip_coin").count());
        session.put("pickRandomCount", meterRegistry.counter("sudokupro.rng.pick_random").count());
        session.put("weightedPickCount", meterRegistry.counter("sudokupro.rng.weighted_pick").count());
        session.put("generateRandomStringCount", meterRegistry.counter("sudokupro.rng.generate_random_string").count());
        session.put("rollDiceCount", meterRegistry.counter("sudokupro.rng.roll_dice").count());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(session);
        } catch (Exception e) {
            log.error("Failed to export RNG session JSON: {}", e.getMessage());
            return "{\"error\": \"JSON export failed\"}";
        }
    }

    public void boostEntropy(byte[] extraBytes) {
        if (extraBytes == null || extraBytes.length == 0) throw new IllegalArgumentException("Extra bytes must not be null/empty");
        methodLog.add("boostEntropy");
        localRand.get().setSeed(extraBytes);
        log.info("Entropy boosted with {} bytes", extraBytes.length);
    }
}
