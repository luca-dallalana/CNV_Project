package pt.ulisboa.tecnico.cnv.mss;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

public final class DynamoDbMetricsStore {
    private static final String ENV_ACCESS_KEY = "AWS_ACCESS_KEY_ID";
    private static final String ENV_SECRET_KEY = "AWS_SECRET_ACCESS_KEY";
    private static final String ENV_REGION = "AWS_REGION";
    private static final String ENV_TABLE = "CNV_METRICS_TABLE";
    private static final String DEFAULT_TABLE = "cnv-metrics";
    private static final AtomicBoolean TABLE_CHECKED = new AtomicBoolean(false);

    private DynamoDbMetricsStore() {
    }

    public static void store(String workload, Map<String, String> params, Map<String, Long> metrics, long executionTimeMs) {
        String tableName = tableName();
        Region region = awsRegion();

        try (DynamoDbClient client = DynamoDbClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(region)
                .build()) {

            if (TABLE_CHECKED.compareAndSet(false, true)) {
                ensureTableExists(client, tableName);
            }

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("requestId", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
            item.put("workload", AttributeValue.builder().s(workload).build());
            item.put("timestamp", AttributeValue.builder().n(Long.toString(System.currentTimeMillis())).build());
            item.put("instructions", AttributeValue.builder().n(Long.toString(metrics.getOrDefault("instructions", 0L))).build());
            item.put("branches", AttributeValue.builder().n(Long.toString(metrics.getOrDefault("branches", 0L))).build());
            item.put("methodCalls", AttributeValue.builder().n(Long.toString(metrics.getOrDefault("methodCalls", 0L))).build());
            item.put("executionTimeMs", AttributeValue.builder().n(Long.toString(executionTimeMs)).build());

            if (params != null && !params.isEmpty()) {
                Map<String, AttributeValue> paramValues = new HashMap<>();
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    paramValues.put(entry.getKey(), AttributeValue.builder().s(entry.getValue()).build());
                }
                item.put("params", AttributeValue.builder().m(paramValues).build());
            }

            client.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());
        } catch (DynamoDbException | SdkClientException e) {
            System.out.println("[MetricsStore] Failed to store metrics (AWS credentials may not be configured): " + e.getMessage());
        }
    }

    private static String tableName() {
        String name = System.getenv(ENV_TABLE);
        if (name == null || name.isBlank()) {
            return DEFAULT_TABLE;
        }
        return name;
    }

    private static Region awsRegion() {
        String region = System.getenv(ENV_REGION);
        if (region == null || region.isBlank()) {
            return Region.US_EAST_1;
        }
        return Region.of(region);
    }

    private static void ensureTableExists(DynamoDbClient client, String tableName) {
        try {
            client.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .keySchema(KeySchemaElement.builder().attributeName("requestId").keyType(KeyType.HASH).build())
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("requestId")
                            .attributeType(ScalarAttributeType.S)
                            .build())
                    .provisionedThroughput(ProvisionedThroughput.builder()
                            .readCapacityUnits(1L)
                            .writeCapacityUnits(1L)
                            .build())
                    .build());
        } catch (ResourceInUseException ignored) {
            return;
        }

        client.waiter().waitUntilTableExists(r -> r.tableName(tableName));
    }
}
