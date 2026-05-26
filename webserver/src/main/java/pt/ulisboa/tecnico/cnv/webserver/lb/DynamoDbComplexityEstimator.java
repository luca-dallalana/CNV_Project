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
    private final LbConfig config;
    private final DynamoDbClient dynamoDbClient;
    private final ScheduledExecutorService refreshScheduler;
    private volatile Map<String, Long> bucketMedians = Collections.emptyMap();
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
        String bucket = bucketFor(workload, params);
        long predictedLoops = predictLoops(workload, params);

        Long bucketMedian = bucketMedians.get(bucket);
        if (bucketMedian != null) {
            return bucketMedian + predictedLoops;
        }

        Long workloadMedian = workloadMedians.get(workload);
        if (workloadMedian != null) {
            return workloadMedian + predictedLoops;
        }

        return heuristic(workload, params);
    }

    private void refreshCache() {
        try {
            Map<String, List<Long>> bucketSamples = new HashMap<>();
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
                String bucket = bucketFor(workload, itemParams);
                bucketSamples.computeIfAbsent(bucket, k -> new ArrayList<>()).add(complexity);
                workloadSamples.computeIfAbsent(workload, k -> new ArrayList<>()).add(complexity);
            }

            Map<String, Long> newBucketMedians = new HashMap<>();
            for (Map.Entry<String, List<Long>> entry : bucketSamples.entrySet()) {
                newBucketMedians.put(entry.getKey(), median(entry.getValue()));
            }
            Map<String, Long> newWorkloadMedians = new HashMap<>();
            for (Map.Entry<String, List<Long>> entry : workloadSamples.entrySet()) {
                newWorkloadMedians.put(entry.getKey(), median(entry.getValue()));
            }

            this.bucketMedians = Collections.unmodifiableMap(newBucketMedians);
            this.workloadMedians = Collections.unmodifiableMap(newWorkloadMedians);
            System.out.println(String.format("[CACHE] Refreshed: %d buckets, %d workload types",
                    newBucketMedians.size(), newWorkloadMedians.size()));
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
                    return (seq1 * seq2) / 10L;
                }
                default:
                    return 0L;
            }
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }

    private static long complexityFromItem(Map<String, AttributeValue> item) {
        long branches = parseLong(item.get("branches"));
        long methodCalls = parseLong(item.get("methodCalls"));
        return branches + (methodCalls * 1L);
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

    private static String bucketFor(String workload, Map<String, String> params) {
        try {
            if ("fractals".equals(workload)) {
                long w = parsePositive(params.get("w"));
                long h = parsePositive(params.get("h"));
                long it = parsePositive(params.get("iterations"));
                return "px=" + bucketByMagnitude(w * h) + ",it=" + bucketByMagnitude(it);
            }
            if ("grayscott".equals(workload)) {
                long size = parsePositive(params.get("size"));
                long maxIterations = parsePositive(params.get("maxIterations"));
                return "cell=" + bucketByMagnitude(size * size) + ",it=" + bucketByMagnitude(maxIterations);
            }
            if ("dna".equals(workload)) {
                long seq1 = sequenceLength(params.get("seq1"));
                long seq2 = sequenceLength(params.get("seq2"));
                long minLength = parsePositive(params.get("minLength"));
                return "cmp=" + bucketByMagnitude(seq1 * seq2) + ",min=" + bucketByMagnitude(minLength);
            }
            return "generic";
        } catch (IllegalArgumentException e) {
            return "generic";
        }
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

    private static long bucketByMagnitude(long value) {
        long safe = Math.max(1L, value);
        long bucket = 1L;
        while (bucket * 10L <= safe) {
            bucket *= 10L;
        }
        return bucket;
    }
}
