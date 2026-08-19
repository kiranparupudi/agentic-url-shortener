package com.agentic.urlshortener;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small hand-rolled JSON helper - just enough for this service's flat request/response bodies. */
public final class JsonUtil {

    private JsonUtil() {
    }

    public static Map<String, String> parseFlatObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) {
            return result;
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            return result;
        }
        if (trimmed.startsWith("{")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("}")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        for (String pair : trimmed.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            if (pair.isBlank()) {
                continue;
            }
            int colon = pair.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = unquote(pair.substring(0, colon).trim());
            String value = unquote(pair.substring(colon + 1).trim());
            result.put(key, value);
        }
        return result;
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public static String object(Object... kvPairs) {
        if (kvPairs.length % 2 != 0) {
            throw new IllegalArgumentException("must be key,value pairs");
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kvPairs.length; i += 2) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(kvPairs[i]).append("\":");
            Object value = kvPairs[i + 1];
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append("\"").append(String.valueOf(value).replace("\"", "\\\"")).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
