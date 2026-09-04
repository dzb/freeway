package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.jujin.freeway.http.engine.HttpContextImpl;
import com.jujin.freeway.http.engine.HttpResponseWriter;
import com.jujin.freeway.http.engine.ResponseFraming;
import com.jujin.freeway.http.sse.SseEmitter;

/**
 * HTTP/2 {@link HttpResponseWriter}: frames the response as HEADERS/DATA onto
 * the owning stream. Mirrors {@code Http1xResponseWriter} so the two transport
 * writers are directly comparable. The stream itself stays a pure request
 * processor — it does not implement the writer contract.
 */
public final class Http2ResponseWriter implements HttpResponseWriter {

    private final Http2Stream stream;
    private static final Set<String> FORBIDDEN_RESPONSE_HEADERS = Set.of(
        "connection", "keep-alive", "proxy-connection",
        "transfer-encoding", "upgrade", "te");

    public Http2ResponseWriter(Http2Stream stream) {
        this.stream = stream;
    }

    @Override
    public void writeHead(HttpContextImpl ctx) {
        stream.responseHeaders.clear();
        stream.responseHeaders.put(":status", List.of(String.valueOf(ctx.status())));
        Set<String> connectionTokens = connectionHeaderTokens(ctx.responseHeaderEntries());
        for (var entry : ctx.responseHeaderEntries()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_RESPONSE_HEADERS.contains(lower)
                    || connectionTokens.contains(lower)) {
                continue;
            }
            stream.responseHeaders
                .computeIfAbsent(lower, k -> new ArrayList<>())
                .add(entry.getValue());
        }
    }

    @Override
    public void writeBody(HttpContextImpl ctx, byte[] data) throws IOException {
        boolean suppressBody = ResponseFraming.suppressBodyBytes(
            ctx.allowsResponseBody(), ctx.method());
        if (!suppressBody && data.length > 0) {
            stream.outputStream.write(data);
        }
    }

    @Override
    public void writeBody(HttpContextImpl ctx, byte[] data, int offset, int length)
            throws IOException {
        boolean suppressBody = ResponseFraming.suppressBodyBytes(
            ctx.allowsResponseBody(), ctx.method());
        if (!suppressBody && length > 0) {
            stream.outputStream.write(data, offset, length);
        }
    }

    @Override
    public void end(HttpContextImpl ctx) throws IOException {
        stream.outputStream.close();
    }

    @Override
    public SseEmitter openSse(HttpContextImpl ctx) throws IOException {
        writeHead(ctx);
        stream.writeResponseHeaders(false);
        return new SseEmitter(stream.outputStream);
    }

    @Override
    public void onLengthMismatch(HttpContextImpl ctx) throws IOException {
        stream.abortResponse();
    }

    private static Set<String> connectionHeaderTokens(
            List<Map.Entry<String, String>> responseHeaders) {
        String connection = null;
        for (var entry : responseHeaders) {
            if (entry.getKey().equalsIgnoreCase("connection")) {
                connection = entry.getValue();
                break;
            }
        }
        if (connection == null) return Set.of();
        var tokens = new HashSet<String>();
        for (String token : connection.split(",")) {
            tokens.add(token.trim().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }
}
