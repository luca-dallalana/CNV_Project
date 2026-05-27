package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

public final class CloudWatchMetricsPoller {
    private final CloudWatchClient cloudWatch;
    private final LbConfig config;

    public CloudWatchMetricsPoller(LbConfig config) {
        this.config = config;
        this.cloudWatch = CloudWatchClient.builder()
                .region(config.getAwsRegion())
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    public OptionalDouble getAverageCpuPercent(List<String> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return OptionalDouble.empty();
        }

        Instant endTime = Instant.now();
        Instant startTime = endTime.minusSeconds(600);

        double sum = 0.0;
        int count = 0;

        for (String instanceId : instanceIds) {
            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace("AWS/EC2")
                    .metricName("CPUUtilization")
                    .dimensions(Dimension.builder()
                            .name("InstanceId")
                            .value(instanceId)
                            .build())
                    .statistics(Statistic.AVERAGE)
                    .period(300)
                    .startTime(startTime)
                    .endTime(endTime)
                    .build();

            try {
                GetMetricStatisticsResponse response = cloudWatch.getMetricStatistics(request);
                List<Datapoint> datapoints = response.datapoints();
                if (!datapoints.isEmpty()) {
                    double latest = datapoints.stream()
                            .max(Comparator.comparing(Datapoint::timestamp))
                            .get()
                            .average();
                    sum += latest;
                    count++;
                }
            } catch (Exception e) {
                System.out.println("[CW] Failed to get CPU for " + instanceId + ": " + e.getMessage());
            }
        }

        return count > 0 ? OptionalDouble.of(sum / count) : OptionalDouble.empty();
    }

    public void close() {
        cloudWatch.close();
    }
}
