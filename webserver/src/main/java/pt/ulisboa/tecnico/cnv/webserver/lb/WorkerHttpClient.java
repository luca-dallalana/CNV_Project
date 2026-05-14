package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class WorkerHttpClient {
    private final LbConfig config;

    public WorkerHttpClient(LbConfig config) {
        this.config = config;
    }

    public ForwardResult forward(WorkerNode worker, String path, String rawQuery) throws IOException {
        StringBuilder urlBuilder = new StringBuilder()
                .append(worker.endpointBaseUrl(config.getWorkerProtocol()))
                .append(path);
        if (rawQuery != null && !rawQuery.isBlank()) {
            urlBuilder.append("?").append(rawQuery);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(urlBuilder.toString()).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout((int) config.getForwardTimeout().toMillis());
        connection.setReadTimeout((int) config.getForwardTimeout().toMillis());

        int statusCode = connection.getResponseCode();
        Map<String, List<String>> headers = connection.getHeaderFields();
        InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = readAll(stream);
        connection.disconnect();

        return new ForwardResult(statusCode, headers, body);
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    public static final class ForwardResult {
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final String body;

        public ForwardResult(int statusCode, Map<String, List<String>> headers, String body) {
            this.statusCode = statusCode;
            this.headers = headers == null ? Collections.emptyMap() : headers;
            this.body = body == null ? "" : body;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public Map<String, List<String>> getHeaders() {
            return headers;
        }

        public String getBody() {
            return body;
        }
    }
}
