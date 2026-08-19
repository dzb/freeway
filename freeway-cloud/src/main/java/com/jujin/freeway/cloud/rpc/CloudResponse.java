package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.commons.json.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Outbound HTTP response: status + headers + raw body.
 */
public record CloudResponse(int status, Map<String, List<String>> headers, byte[] body) {

    public CloudResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public boolean is2xx() {
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
