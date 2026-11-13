package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.jujin.freeway.http.ResponseHead;
import com.jujin.freeway.http.engine.HttpResponseWriter;
import com.jujin.freeway.http.internal.ResponseFraming;
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
    public void writeHead(ResponseHead head) {
        stream.responseHeaders.clear();
        stream.responseHeaders.put(":status", List.of(String.valueOf(head.status())));
        Set<String> connectionTokens = connectionHeaderTokens(head.headers());
        for (ResponseHead.Header header : head.headers()) {
            String lower = header.name().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_RESPONSE_HEADERS.contains(lower)
                    || connectionTokens.contains(lower)
                    // Same rule as HTTP/1.1 (ResponseFraming): a bodyless
                    // response must not carry Content-Length (RFC 9110 §8.6).
                    || ResponseFraming.suppressesHeader(lower, head.allowsBody())) {
                continue;
            }
            stream.responseHeaders
                .computeIfAbsent(lower, k -> new ArrayList<>())
                .add(header.value());
        }
    }

    @Override
    public void writeBody(ResponseHead head, byte[] data, int offset, int length)
            throws IOException {
        if (head.sendsBodyBytes() && length > 0) {
            stream.outputStream.write(data, offset, length);
        }
    }

    @Override
    public void end(ResponseHead head) throws IOException {
        stream.outputStream.close();
    }

    @Override
    public SseEmitter openSse(ResponseHead head) throws IOException {
        writeHead(head);
        stream.writeResponseHeaders(false);
        return new SseEmitter(stream.outputStream);
    }

    @Override
    public void onLengthMismatch(ResponseHead head) throws IOException {
        stream.abortResponse();
    }

    private static Set<String> connectionHeaderTokens(
            List<ResponseHead.Header> responseHeaders) {
        String connection = null;
        for (ResponseHead.Header header : responseHeaders) {
            if (header.name().equalsIgnoreCase("connection")) {
                connection = header.value();
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
