package com.jujin.freeway.http.engine.http2;

import com.jujin.freeway.http.engine.HttpContextDefault;
import com.jujin.freeway.http.engine.HttpResponseWriter;
import com.jujin.freeway.http.sse.SseEmitter;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * HTTP/2 {@link HttpResponseWriter}: frames the response as HEADERS/DATA onto
 * the owning stream. Mirrors {@code Http11ResponseWriter} so the two transport
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
    public void writeHead(HttpContextDefault ctx) {
        stream.responseHeaders.clear();
        stream.responseHeaders.put(":status", List.of(String.valueOf(ctx.status())));
        Set<String> connectionTokens = connectionHeaderTokens(ctx.responseHeaders());
        for (var entry : ctx.responseHeaders().entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_RESPONSE_HEADERS.contains(lower)
                    || connectionTokens.contains(lower)) {
                continue;
            }
            stream.responseHeaders.put(lower, List.of(entry.getValue()));
        }
    }

    @Override
    public void writeBody(HttpContextDefault ctx, byte[] data) throws IOException {
        boolean headRequest = "HEAD".equalsIgnoreCase(ctx.method());
        if (!headRequest && ctx.allowsResponseBody() && data.length > 0) {
            stream.outputStream.write(data);
        }
    }

    @Override
    public void writeBody(HttpContextDefault ctx, byte[] data, int offset, int length)
            throws IOException {
        boolean headRequest = "HEAD".equalsIgnoreCase(ctx.method());
        if (!headRequest && ctx.allowsResponseBody() && length > 0) {
            stream.outputStream.write(data, offset, length);
        }
    }

    @Override
    public void end(HttpContextDefault ctx) throws IOException {
        stream.outputStream.close();
    }

    @Override
    public SseEmitter openSse(HttpContextDefault ctx) throws IOException {
        writeHead(ctx);
        stream.writeResponseHeaders(false);
        return new SseEmitter(stream.outputStream);
    }

    private static Set<String> connectionHeaderTokens(
            java.util.Map<String, String> responseHeaders) {
        String connection = null;
        for (var entry : responseHeaders.entrySet()) {
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
