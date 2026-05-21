package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

public final class DynamoDbComplexityEstimator implements ComplexityEstimator {
    private final LbConfig config;
    private final DynamoDbClient dynamoDbClient;

    public DynamoDbComplexityEstimator(LbConfig config) {
        this.config = config;
        this.dynamoDbClient = DynamoDbClient.builder()
                .region(config.getAwsRegion())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    public void close() {
        if (dynamoDbClient != null) {
            dynamoDbClient.close();
        }
    }

    @Override
    public long estimate(String workload, Map<String, String> params) {
        String targetBucket = bucketFor(workload, params);
        List<Long> sameBucket = new ArrayList<>();
        List<Long> sameWorkload = new ArrayList<>();

        try {
            ScanRequest request = ScanRequest.builder()
                    .tableName(config.getMetricsTableName())
                    .limit(config.getMetricsSampleSize())
                    .filterExpression("#w = :workload")
                    .expressionAttributeNames(Collections.singletonMap("#w", "workload"))
                    .expressionAttributeValues(Collections.singletonMap(":workload", AttributeValue.builder().s(workload).build()))
                    .build();

            for (Map<String, AttributeValue> item : dynamoDbClient.scanPaginator(request).items()) {
                long complexity = complexityFromItem(item);
                if (complexity <= 0L) {
                    continue;
                }
                sameWorkload.add(complexity);
                String itemBucket = bucketFor(workload, extractParams(item));
                if (Objects.equals(targetBucket, itemBucket)) {
                    sameBucket.add(complexity);
                }
            }
        } catch (DynamoDbException | SdkClientException e) {
            System.out.println("[LB] MSS read failed for workload=" + workload + ": " + e.getMessage());
        }

        long predictedLoops = predictLoops(workload, params);

        if (!sameBucket.isEmpty()) {
            return median(sameBucket) + predictedLoops;
        }
        if (!sameWorkload.isEmpty()) {
            return median(sameWorkload) + predictedLoops;
        }

        return heuristic(workload, params);
    }

    private static long predictLoops(String workload, Map<String, String> params) {
        try {
            switch (workload) {
                case "fractals": {
                    long w = parsePositive(params.get("w"));
                    long h = parsePositive(params.get("h"));
                    long it = parsePositive(params.get("iterations"));
                    return (w * h * it) / 2L; // Estimar que precisamos apenas de metade das iteracoes (por causa do escape)
                }
                case "grayscott": {
                    long size = parsePositive(params.get("size"));
                    long maxIterations = parsePositive(params.get("maxIterations"));
                    return size * size * maxIterations;
                }
                case "dna": {
                    long seq1 = sequenceLength(params.get("seq1"));
                    long seq2 = sequenceLength(params.get("seq2"));
                    return (seq1 * seq2) / 10L; // Estimar que apenas 10% tenha match 
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

        return branches + (methodCalls * 1L); // Trocar 1L depois dos tests 
    }

    private static Map<String, String> extractParams(Map<String, AttributeValue> item) {
        AttributeValue value = item.get("params");
        if (value == null || value.m() == null) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new java.util.HashMap<>();
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
