package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import software.amazon.awssdk.regions.Region;

public final class LbConfig {
    private static final String DEFAULT_METRICS_TABLE = "cnv-metrics";

    private final int listenPort;
    private final int workerPort;
    private final String workerProtocol;
    private final String workerTagKey;
    private final String workerTagValue;
    private final Region awsRegion;
    private final String metricsTableName;
    private final int requestRetryCount;
    private final Duration forwardTimeout;
    private final int minWorkers;
    private final int maxWorkers;
    private final long scaleOutPressure;
    private final long scaleInPressure;
    private final Duration scalerPeriod;
    private final Duration scalerCooldown;
    private final String workerLaunchTemplateId;
    private final String workerLaunchTemplateVersion;
    private final List<String> staticWorkers;
    private final Duration cacheRefreshInterval;
    private final boolean lambdaEnabled;
    private final String lambdaFunctionFractals;
    private final String lambdaFunctionDna;
    private final String lambdaFunctionGrayscott;
    private final long lambdaComplexityThreshold;
    private final long lambdaPressureThreshold;
    private final double cpuScaleOutThreshold;
    private final double cpuScaleInThreshold;

    private LbConfig(
            int listenPort,
            int workerPort,
            String workerProtocol,
            String workerTagKey,
            String workerTagValue,
            Region awsRegion,
            String metricsTableName,
            int requestRetryCount,
            Duration forwardTimeout,
            int minWorkers,
            int maxWorkers,
            long scaleOutPressure,
            long scaleInPressure,
            Duration scalerPeriod,
            Duration scalerCooldown,
            String workerLaunchTemplateId,
            String workerLaunchTemplateVersion,
            List<String> staticWorkers,
            Duration cacheRefreshInterval,
            boolean lambdaEnabled,
            String lambdaFunctionFractals,
            String lambdaFunctionDna,
            String lambdaFunctionGrayscott,
            long lambdaComplexityThreshold,
            long lambdaPressureThreshold,
            double cpuScaleOutThreshold,
            double cpuScaleInThreshold) {
        this.listenPort = listenPort;
        this.workerPort = workerPort;
        this.workerProtocol = workerProtocol;
        this.workerTagKey = workerTagKey;
        this.workerTagValue = workerTagValue;
        this.awsRegion = awsRegion;
        this.metricsTableName = metricsTableName;
        this.requestRetryCount = requestRetryCount;
        this.forwardTimeout = forwardTimeout;
        this.minWorkers = minWorkers;
        this.maxWorkers = maxWorkers;
        this.scaleOutPressure = scaleOutPressure;
        this.scaleInPressure = scaleInPressure;
        this.scalerPeriod = scalerPeriod;
        this.scalerCooldown = scalerCooldown;
        this.workerLaunchTemplateId = workerLaunchTemplateId;
        this.workerLaunchTemplateVersion = workerLaunchTemplateVersion;
        this.staticWorkers = staticWorkers;
        this.cacheRefreshInterval = cacheRefreshInterval;
        this.lambdaEnabled = lambdaEnabled;
        this.lambdaFunctionFractals = lambdaFunctionFractals;
        this.lambdaFunctionDna = lambdaFunctionDna;
        this.lambdaFunctionGrayscott = lambdaFunctionGrayscott;
        this.lambdaComplexityThreshold = lambdaComplexityThreshold;
        this.lambdaPressureThreshold = lambdaPressureThreshold;
        this.cpuScaleOutThreshold = cpuScaleOutThreshold;
        this.cpuScaleInThreshold = cpuScaleInThreshold;
    }

    public static LbConfig fromEnv() {
        int minWorkers = envInt("LB_MIN_WORKERS", 1);
        int maxWorkers = envInt("LB_MAX_WORKERS", 6);
        if (maxWorkers < minWorkers) {
            maxWorkers = minWorkers;
        }

        String launchTemplateId = envString("LB_WORKER_LAUNCH_TEMPLATE_ID", "");
        String launchTemplateVersion = envString("LB_WORKER_LAUNCH_TEMPLATE_VERSION", "$Default");

        String regionRaw = envString("AWS_REGION", "us-east-1");
        Region region = Region.of(regionRaw);

        long scaleOutPressure = envLong("LB_SCALE_OUT_PRESSURE", 30000000L);

        return new LbConfig(
                envInt("LB_PORT", 8000),
                envInt("LB_WORKER_PORT", 8000),
                envString("LB_WORKER_PROTOCOL", "http"),
                envString("LB_WORKER_TAG_KEY", "cnv-role"),
                envString("LB_WORKER_TAG_VALUE", "worker"),
                region,
                envString("CNV_METRICS_TABLE", DEFAULT_METRICS_TABLE),
                envInt("LB_REQUEST_RETRY_COUNT", 1),
                Duration.ofMillis(envInt("LB_FORWARD_TIMEOUT_MS", 15000)),
                minWorkers,
                maxWorkers,
                scaleOutPressure,
                envLong("LB_SCALE_IN_PRESSURE", 8000000L),
                Duration.ofMillis(envInt("LB_SCALER_PERIOD_MS", 10000)),
                Duration.ofMillis(envInt("LB_SCALER_COOLDOWN_MS", 60000)),
                launchTemplateId,
                launchTemplateVersion,
                parseStaticWorkers(envString("LB_STATIC_WORKERS", "")),
                Duration.ofMillis(envInt("LB_CACHE_REFRESH_MS", 30000)),
                envBoolean("LB_LAMBDA_ENABLED", false),
                envString("LB_LAMBDA_FUNCTION_FRACTALS", ""),
                envString("LB_LAMBDA_FUNCTION_DNA", ""),
                envString("LB_LAMBDA_FUNCTION_GRAYSCOTT", ""),
                envLong("LB_LAMBDA_COMPLEXITY_THRESHOLD", 1000000L),
                envLong("LB_LAMBDA_PRESSURE_THRESHOLD", scaleOutPressure),
                envDouble("LB_CPU_SCALE_OUT_THRESHOLD", 60.0),
                envDouble("LB_CPU_SCALE_IN_THRESHOLD", 20.0));
    }

    private static List<String> parseStaticWorkers(String raw) {
        List<String> workers = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return workers;
        }
        String[] tokens = raw.split(",");
        for (String token : tokens) {
            String value = token.trim();
            if (!value.isEmpty()) {
                workers.add(value);
            }
        }
        return workers;
    }

    private static String envString(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }

    private static long envLong(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Long.parseLong(value.trim());
    }

    private static double envDouble(String name, double fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Double.parseDouble(value.trim());
    }

    private static boolean envBoolean(String name, boolean fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    public int getListenPort() {
        return listenPort;
    }

    public int getWorkerPort() {
        return workerPort;
    }

    public String getWorkerProtocol() {
        return workerProtocol;
    }


    public String getWorkerTagKey() {
        return workerTagKey;
    }

    public String getWorkerTagValue() {
        return workerTagValue;
    }

    public Region getAwsRegion() {
        return awsRegion;
    }

    public String getMetricsTableName() {
        return metricsTableName;
    }

    public int getRequestRetryCount() {
        return requestRetryCount;
    }

    public Duration getForwardTimeout() {
        return forwardTimeout;
    }

    public int getMinWorkers() {
        return minWorkers;
    }

    public int getMaxWorkers() {
        return maxWorkers;
    }

    public long getScaleOutPressure() {
        return scaleOutPressure;
    }

    public long getScaleInPressure() {
        return scaleInPressure;
    }

    public Duration getScalerPeriod() {
        return scalerPeriod;
    }

    public Duration getScalerCooldown() {
        return scalerCooldown;
    }

    public String getWorkerLaunchTemplateId() {
        return workerLaunchTemplateId;
    }

    public String getWorkerLaunchTemplateVersion() {
        return workerLaunchTemplateVersion;
    }

    public List<String> getStaticWorkers() {
        return staticWorkers;
    }

    public boolean usesStaticWorkers() {
        return !staticWorkers.isEmpty();
    }

    public boolean hasLaunchTemplate() {
        return workerLaunchTemplateId != null && !workerLaunchTemplateId.isBlank();
    }

    public Duration getCacheRefreshInterval() {
        return cacheRefreshInterval;
    }

    public boolean isLambdaEnabled() {
        return lambdaEnabled;
    }

    public String getLambdaFunctionName(String workload) {
        switch (workload) {
            case "fractals": return lambdaFunctionFractals.isBlank() ? null : lambdaFunctionFractals;
            case "dna":      return lambdaFunctionDna.isBlank() ? null : lambdaFunctionDna;
            case "grayscott": return lambdaFunctionGrayscott.isBlank() ? null : lambdaFunctionGrayscott;
            default: return null;
        }
    }

    public long getLambdaComplexityThreshold() {
        return lambdaComplexityThreshold;
    }

    public long getLambdaPressureThreshold() {
        return lambdaPressureThreshold;
    }

    public double getCpuScaleOutThreshold() {
        return cpuScaleOutThreshold;
    }

    public double getCpuScaleInThreshold() {
        return cpuScaleInThreshold;
    }
}
