package pt.ulisboa.tecnico.cnv.webserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class RootHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange he) throws IOException {
        he.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        he.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
        he.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");

        if ("OPTIONS".equalsIgnoreCase(he.getRequestMethod())) {
            he.sendResponseHeaders(204, -1);
            return;
        }

        String body = "Nature@Cloud LB is running";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        he.sendResponseHeaders(200, payload.length);
        try (OutputStream os = he.getResponseBody()) {
            os.write(payload);
        }
    }
}