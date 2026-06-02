package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

public final class DynamoDbComplexityEstimator implements ComplexityEstimator {
    private static final int NUM_QUANTILES = 5;
    private static final int MIN_QUANTILE_SAMPLES = NUM_QUANTILES;

    private final LbConfig config;
    private final DynamoDbClient dynamoDbClient;
    private final ScheduledExecutorService refreshScheduler;
    private volatile Map<String, long[]> workloadBoundaries = Collections.emptyMap();
    private volatile Map<String, long[]> workloadQuantileMedians = Collections.emptyMap();
    private volatile Map<String, Long> workloadMedians = Collections.emptyMap();

    public DynamoDbComplexityEstimator(LbConfig config) {
        this.config = config;
        this.dynamoDbClient = DynamoDbClient.builder()
                .region(config.getAwsRegion())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        this.refreshScheduler = Executors.newSingleThreadScheduledExecutor();
        long intervalMs = config.getCacheRefreshInterval().toMillis();
        refreshScheduler.scheduleAtFixedRate(this::refreshCache, 0L, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void close() {
        refreshScheduler.shutdownNow();
        if (dynamoDbClient != null) {
            dynamoDbClient.close();
        }
    }

    @Override
    public long estimate(String workload, Map<String, String> params) {
        long driver = predictLoops(workload, params);

        long[] boundaries = workloadBoundaries.get(workload);
        long[] quantileMedians = workloadQuantileMedians.get(workload);
        if (boundaries != null && quantileMedians != null) {
            int q = findQuantile(driver, boundaries);
            return quantileMedians[q];
        }

        Long workloadMedian = workloadMedians.get(workload);
        if (workloadMedian != null) {
            return workloadMedian;
        }

        return heuristic(workload, params);
    }

    private void refreshCache() {
        try {
            Map<String, List<long[]>> driverComplexityByWorkload = new HashMap<>();
            Map<String, List<Long>> workloadSamples = new HashMap<>();

            ScanRequest request = ScanRequest.builder()
                    .tableName(config.getMetricsTableName())
                    .build();

            for (Map<String, AttributeValue> item : dynamoDbClient.scanPaginator(request).items()) {
                AttributeValue wAttr = item.get("workload");
                if (wAttr == null || wAttr.s() == null) {
                    continue;
                }
                String workload = wAttr.s();
                long complexity = complexityFromItem(item);
                if (complexity <= 0L) {
                    continue;
                }
                Map<String, String> itemParams = extractParams(item);
                long driver = predictLoops(workload, itemParams);
                driverComplexityByWorkload.computeIfAbsent(workload, k -> new ArrayList<>())
                        .add(new long[]{driver, complexity});
                workloadSamples.computeIfAbsent(workload, k -> new ArrayList<>()).add(complexity);
            }

            Map<String, long[]> newBoundaries = new HashMap<>();
            Map<String, long[]> newQuantileMedians = new HashMap<>();

            for (Map.Entry<String, List<long[]>> entry : driverComplexityByWorkload.entrySet()) {
                String workload = entry.getKey();
                List<long[]> pairs = entry.getValue();
                if (pairs.size() < MIN_QUANTILE_SAMPLES) {
                    continue;
                }
                pairs.sort((a, b) -> Long.compare(a[0], b[0]));
                int n = pairs.size();

                long[] boundaries = new long[NUM_QUANTILES - 1];
                for (int i = 1; i < NUM_QUANTILES; i++) {
                    int idx = (int) Math.round((double) i * n / NUM_QUANTILES) - 1;
                    boundaries[i - 1] = pairs.get(Math.max(0, Math.min(n - 1, idx)))[0];
                }

                @SuppressWarnings("unchecked")
                List<Long>[] bucketComplexities = new List[NUM_QUANTILES];
                for (int i = 0; i < NUM_QUANTILES; i++) {
                    bucketComplexities[i] = new ArrayList<>();
                }
                for (long[] pair : pairs) {
                    bucketComplexities[findQuantile(pair[0], boundaries)].add(pair[1]);
                }

                long[] quantileMedians = new long[NUM_QUANTILES];
                for (int i = 0; i < NUM_QUANTILES; i++) {
                    quantileMedians[i] = bucketComplexities[i].isEmpty() ? 0L : median(bucketComplexities[i]);
                }

                newBoundaries.put(workload, boundaries);
                newQuantileMedians.put(workload, quantileMedians);
            }

            Map<String, Long> newWorkloadMedians = new HashMap<>();
            for (Map.Entry<String, List<Long>> entry : workloadSamples.entrySet()) {
                newWorkloadMedians.put(entry.getKey(), median(entry.getValue()));
            }

            this.workloadBoundaries = Collections.unmodifiableMap(newBoundaries);
            this.workloadQuantileMedians = Collections.unmodifiableMap(newQuantileMedians);
            this.workloadMedians = Collections.unmodifiableMap(newWorkloadMedians);
            System.out.println(String.format("[CACHE] Refreshed: %d workloads with quantiles, %d workload medians",
                    newBoundaries.size(), newWorkloadMedians.size()));
        } catch (Exception e) {
            System.out.println("[CACHE] Refresh failed: " + e.getMessage());
        }
    }

    private static long predictLoops(String workload, Map<String, String> params) {
        try {
            switch (workload) {
                case "fractals": {
                    long w = parsePositive(params.get("w"));
                    long h = parsePositive(params.get("h"));
                    long it = parsePositive(params.get("iterations"));
                    return (w * h * it) / 2L;
                }
                case "grayscott": {
                    long size = parsePositive(params.get("size"));
                    long maxIterations = parsePositive(params.get("maxIterations"));
                    return size * size * maxIterations;
                }
                case "dna": {
                    long seq1 = sequenceLength(params.get("seq1"));
                    long seq2 = sequenceLength(params.get("seq2"));
                    long minLength = parsePositive(params.getOrDefault("minLength", "1"));
                    return (seq1 * seq2 * minLength) / 10L;
                }
                default:
                    return 0L;
            }
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }

    private static long complexityFromItem(Map<String, AttributeValue> item) {
        long instructions = parseLong(item.get("instructions"));
        long branches = parseLong(item.get("branches"));
        long methodCalls = parseLong(item.get("methodCalls"));
        return instructions + branches + methodCalls;
    }

    private static Map<String, String> extractParams(Map<String, AttributeValue> item) {
        AttributeValue value = item.get("params");
        if (value == null || value.m() == null) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new HashMap<>();
        for (Map.Entry<String, AttributeValue> entry : value.m().entrySet()) {
            params.put(entry.getKey(), entry.getValue().s());
        }
        return params;
    }

    private static long parseLong(AttributeValue value) {
        if (value == null) {
            return 0L;
        }
        if (value.n() == null || value.n().isBlank()) {
            return 0L;
        }
        try {
            return new BigInteger(value.n()).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int size = sorted.size();
        if (size == 0) {
            return 0L;
        }
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get((size / 2) - 1) + sorted.get(size / 2)) / 2L;
    }

    private static int findQuantile(long value, long[] boundaries) {
        for (int i = 0; i < boundaries.length; i++) {
            if (value <= boundaries[i]) return i;
        }
        return boundaries.length;
    }

    private static long heuristic(String workload, Map<String, String> params) {
        return predictLoops(workload, params);
    }

    private static long sequenceLength(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Sequence parameter is required");
        }
        int idx = value.indexOf(':');
        String seq = idx >= 0 ? value.substring(idx + 1) : value;
        return Math.max(1L, seq.length());
    }

    private static long parsePositive(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Parameter value is required");
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return Math.max(1L, parsed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid parameter value: " + value, e);
        }
    }

}
