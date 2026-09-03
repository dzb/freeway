package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.commons.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Consumer-side bridge from {@link com.jujin.freeway.ioc.CallBus}'s
 * topic contract to a remote process over {@link CloudHttpClient}.
 *
 * <p>Wire shape (see docs/freeway-remote-callbus-design.md §2):
 * {@code POST /rpc/{mapping}/{method}} with the positional arguments as a
 * JSON array. A 200 carries the return value as JSON; business failures of
 * the remote handler map to 4xx plus the {@code X-RPC-Exception} /
 * {@code X-RPC-Message} headers and surface as
 * {@link RemoteInvocationException}; everything else reuses the transport
 * failure semantics of {@link CloudException} (retryable per existing rules),
 * so resilience policies configured for plain RPC apply unchanged.</p>
 *
 * <p>This class never touches {@code CallBus} itself — the local-vs-remote
 * fallback decision belongs to the caller (see design doc §3.3).</p>
 */
public final class RemoteCaller {

    /** Protocol version stamp sent on every request; server rejects others. */
    public static final String VERSION_HEADER = "X-RPC-Version";
    /** Wire protocol version; server rejects other values. */
    public static final String VERSION = "1";
    /** Fully-qualified name of the exception thrown by the remote handler. */
    public static final String EXCEPTION_CLASS_HEADER = "X-RPC-Exception";
    /** URL-encoded exception message from the remote handler. */
    public static final String EXCEPTION_MESSAGE_HEADER = "X-RPC-Message";
    private static final String CONTENT_TYPE = "application/json";

    private final CloudHttpClient http;
    private final JsonCodec codec;

    public RemoteCaller(CloudHttpClient http, JsonCodec codec) {
        this.http = http;
        this.codec = codec;
    }

    /**
     * Invokes the remote handler registered for {@code mapping + "." + method}
     * in the target service.
     *
     * @param serviceId  discovery id of the target service
     * @param mapping    call-topic prefix (e.g. {@code "user"})
     * @param method     call-topic suffix (e.g. {@code "getUser"}); both are
     *                   restricted to {@code [A-Za-z0-9_.]} — this is a wire
     *                   path segment, not free text
     * @param args       positional arguments, JSON-encoded element-wise;
     *                   {@code null} means empty list
     * @param returnType expected answer type (JSON-deserialized); {@code Void}
     *                   or {@code void.class} for handlers that return nothing
     * @return the deserialized reply ({@code null} when the body is empty)
     */
    public <T> T invoke(
        String serviceId,
        String mapping,
        String method,
        List<?> args,
        Class<T> returnType
    ) throws CloudException {
        return invoke(serviceId, mapping, method, args, returnType, null);
    }

    /**
     * As {@link #invoke(String, String, String, List, Class)} with a per-call
     * deadline. {@code null}/{@code Duration.ZERO} uses the transport's
     * configured request timeout; a shorter value narrows the wait via the
     * async transport surface ({@code callAsync} + {@code orTimeout}).
     */
    public <T> T invoke(
        String serviceId,
        String mapping,
        String method,
        List<?> args,
        Class<T> returnType,
        java.time.Duration timeout
    ) throws CloudException {
        RpcPaths.validateSegment(mapping, "mapping");
        RpcPaths.validateSegment(method, "method");
        List<?> positional = args == null ? List.of() : args;

        // Element-wise encoding keeps the wire independent of any envelope:
        // each arg serializes to exactly one JSON value, the array wraps them.
        List<String> encoded = new ArrayList<>(positional.size());
        for (Object arg : positional) {
            encoded.add(arg == null ? "null" : codec.toJson(arg));
        }
        String body = "[" + String.join(",", encoded) + "]";

        CloudRequest request = new CloudRequest("POST",
            RpcPaths.endpoint(mapping, method),
            java.util.Map.of(
                "Content-Type", CONTENT_TYPE,
                VERSION_HEADER, VERSION),
            body.getBytes(StandardCharsets.UTF_8));
        CloudResponse response;
        try {
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                // The deadline rides into the orchestration itself: without it
                // the retry loop keeps working after the caller has given up.
                response = http.callAsync(serviceId, request, timeout)
                    .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .join();
            } else {
                response = http.call(serviceId, request);
            }
        } catch (java.util.concurrent.CompletionException ce) {
            // orTimeout deadline → uniform timeout semantics
            if (ce.getCause() instanceof java.util.concurrent.TimeoutException) {
                throw CloudException.timeout(serviceId, ce.getCause());
            }
            throw ce.getCause() instanceof RuntimeException re ? re : ce;
        }
        if (!response.is2xx()) {
            throw businessException(serviceId, response);
        }
        byte[] reply = response.body();
        if (reply == null || reply.length == 0 || returnType == void.class
            || returnType == Void.class) {
            return null;
        }
        try {
            return codec.fromJson(new String(reply, StandardCharsets.UTF_8), returnType);
        } catch (RuntimeException e) {
            throw CloudException.of(
                "Service '" + serviceId + "' returned an unreadable "
                    + returnType.getName() + ": " + e.getMessage(), false, 200, e);
        }
    }

    /**
     * Business failure inside the remote handler: deterministic and therefore
     * never retryable — unlike transport failures, replaying it cannot help.
     */
    private CloudException businessException(String serviceId, CloudResponse response) {
        String exClass = header(response, EXCEPTION_CLASS_HEADER);
        if (exClass != null) {
            exClass = decode(exClass);
            String message = decode(java.util.Objects.requireNonNullElse(
                header(response, EXCEPTION_MESSAGE_HEADER), ""));
            return CloudException.of(
                "Remote handler '" + exClass + "' on '" + serviceId + "' failed"
                    + (message.isEmpty() ? "" : ": " + sanitizePeerText(message)),
                false, response.status(),
                new RemoteInvocationException(exClass, message));
        }
        // Only the server-authored reject reason is echoed — never the whole
        // header map, which routinely carries tokens and cookies into logs.
        var reason = header(response, "X-RPC-Reject-Reason");
        throw CloudException.of(
            "Service '" + serviceId + "' rejected rpc call"
                + (reason == null ? "" : ": " + sanitizePeerText(decode(reason))),
            false, response.status(), null);
    }

    /**
     * Peer-authored text bound for exception messages (and therefore logs):
     * stripped of control characters — decoded escapes could otherwise forge
     * log lines — and length-capped so a hostile peer cannot bloat the log.
     */
    private static String sanitizePeerText(String value) {
        String cleaned = value.replaceAll("\\p{Cntrl}", "");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }

    /**
     * Wire values are form-encoded. A peer can send a malformed escape (a bare
     * {@code %}) and URLDecoder throws on it — that must not change the
     * failure type callers see, so an undecodable value degrades to its raw
     * text instead of escaping as an IllegalArgumentException.
     */
    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }

    private static String header(CloudResponse response, String name) {
        // Wire headers arrive lower-cased (JDK HttpClient normalizes);
        // match case-insensitively so the constant names stay readable.
        for (var entry : response.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
