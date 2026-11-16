package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.commons.json.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbound HTTP response: status + headers + raw body.
 */
public record RpcResponse(int status, Map<String, List<String>> headers, byte[] body) {

    public RpcResponse {
        if (headers == null) {
            headers = Map.of();
        } else {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                copy.put(header.getKey(), List.copyOf(header.getValue()));
            }
            headers = Map.copyOf(copy);
        }
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public String bodyAsString() {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    /** Decodes the body as JSON into {@code type} (same {@link JsonCodec} the server side uses). */
    public <T> T bodyAs(Class<T> type, JsonCodec codec) {
        return codec.fromJson(bodyAsString(), type);
    }
}
