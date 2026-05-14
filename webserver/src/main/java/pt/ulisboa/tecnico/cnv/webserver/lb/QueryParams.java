package pt.ulisboa.tecnico.cnv.webserver.lb;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class QueryParams {
    private QueryParams() {
    }

    static Map<String, String> parse(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isBlank()) {
            return result;
        }

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] entry = pair.split("=", 2);
            String key = decode(entry[0]);
            String value = entry.length > 1 ? decode(entry[1]) : "";
            result.put(key, value);
        }
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
