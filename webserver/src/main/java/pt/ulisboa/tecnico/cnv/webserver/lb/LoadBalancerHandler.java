package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public final class LoadBalancerHandler implements HttpHandler {
    private final String workload;
    private final RequestScheduler scheduler;
    private final ComplexityEstimator complexityEstimator;
    private final WorkerHttpClient workerHttpClient;
    private final LbConfig config;

    public LoadBalancerHandler(
            String workload,
            RequestScheduler scheduler,
            ComplexityEstimator complexityEstimator,
            WorkerHttpClient workerHttpClient,
            LbConfig config) {
        this.workload = workload;
        this.scheduler = scheduler;
        this.complexityEstimator = complexityEstimator;
        this.workerHttpClient = workerHttpClient;
        this.config = config;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeText(exchange, 405, "Only GET and OPTIONS are supported.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = QueryParams.parse(query);
        long predictedComplexity = complexityEstimator.estimate(workload, params);
        System.out.println(String.format("[LB] Request: workload=%s, complexity=%d", workload, predictedComplexity));
        int maxAttempts = Math.max(1, config.getRequestRetryCount() + 1);
        Set<String> excluded = new HashSet<>();
        IOException lastFailure = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            WorkerNode worker = scheduler.selectWorker(excluded, predictedComplexity);
            if (worker != null) {
                System.out.println(String.format("[LB] Selected worker: %s (backlog=%d, inflight=%d)",
                    worker.getInstanceId(), worker.getEstimatedQueuedWork(), worker.getInflightRequests()));
            }
            if (worker == null) {
                writeText(exchange, 503, "No healthy workers available.");
                return;
            }

            worker.registerScheduledRequest(predictedComplexity);
            try {
                WorkerHttpClient.ForwardResult result = workerHttpClient.forward(worker, path, query);
                copyHeaders(exchange, result.getHeaders());
                writeText(exchange, result.getStatusCode(), result.getBody());
                System.out.println(String.format("[LB] Forwarded successfully to %s (status=%d)",
                    worker.getInstanceId(), result.getStatusCode()));
                return;
            } catch (IOException e) {
                System.out.println(String.format("[LB] Forward failed to %s: %s", worker.getInstanceId(), e.getMessage()));
                excluded.add(worker.getInstanceId());
                lastFailure = e;
            } finally {
                worker.completeScheduledRequest(predictedComplexity);
            }
        }

        if (lastFailure == null) {
            writeText(exchange, 502, "Request forwarding failed.");
            return;
        }
        writeText(exchange, 502, "Request forwarding failed: " + lastFailure.getMessage());
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
    }

    private static void copyHeaders(HttpExchange exchange, Map<String, List<String>> sourceHeaders) {
        for (Map.Entry<String, List<String>> entry : sourceHeaders.entrySet()) {
            String key = entry.getKey();
            if (key == null || "Content-Length".equalsIgnoreCase(key) || "Transfer-Encoding".equalsIgnoreCase(key)
                    || key.toLowerCase().startsWith("access-control-")) {
                continue;
            }
            for (String value : entry.getValue()) {
                exchange.getResponseHeaders().add(key, value);
            }
        }
    }

    private static void writeText(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}
