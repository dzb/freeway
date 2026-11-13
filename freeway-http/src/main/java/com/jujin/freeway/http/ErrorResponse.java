package com.jujin.freeway.http;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A stable, machine-readable error body. The mapper decides; {@link
 * #write(HttpResponse)} is the only place that turns it into bytes.
 *
 * <p>{@code error} remains the human-readable message for compatibility with
 * existing clients; {@code code} is the stable machine token every failure
 * carries. {@code details} is present only when it has content (validation
 * fields, 405 Allow, ...), and {@code headers} carries protocol-level facts
 * such as {@code Allow}.</p>
 */
public record ErrorResponse(
        int status,
        String code,
        String message,
        Map<String, Object> details,
        Map<String, String> headers) {

    public ErrorResponse {
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("error status must be 400..599: " + status);
        }
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        details = details == null ? Map.of() : Map.copyOf(details);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static ErrorResponse of(int status, String code, String message) {
        return new ErrorResponse(status, code, message, Map.of(), Map.of());
    }

    /** Same response with structured details. */
    public ErrorResponse withDetails(Map<String, Object> more) {
        if (more == null || more.isEmpty()) return this;
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.putAll(more);
        return new ErrorResponse(status, code, message, merged, headers);
    }

    /** Same response with one protocol header, e.g. {@code Allow}. */
    public ErrorResponse withHeader(String name, String value) {
        Map<String, String> next = new LinkedHashMap<>(headers);
        next.put(name, value);
        return new ErrorResponse(status, code, message, details, next);
    }

    /** The JSON object written to the wire. */
    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", code);
        if (!details.isEmpty()) {
            body.put("details", details);
        }
        return body;
    }

    /** Writes this response; the one execution point for error output. */
    public void write(HttpResponse response) throws IOException {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            response.setHeader(header.getKey(), header.getValue());
        }
        response.sendJson(status, body());
    }
}
