package com.jujin.freeway.http.engine.http2;

import com.jujin.freeway.http.engine.HttpContextDefault;
import com.jujin.freeway.http.engine.HttpResponseWriter;
import com.jujin.freeway.http.sse.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * HTTP/2 {@link HttpResponseWriter}: frames the response as HEADERS/DATA onto
 * the owning stream. Mirrors {@code Http11ResponseWriter} so the two transport
 * writers are directly comparable. The stream itself stays a pure request
 * processor — it does not implement the writer contract.
 */
public final class Http2ResponseWriter implements HttpResponseWriter {

    private final Http2Stream stream;

    public Http2ResponseWriter(Http2Stream stream) {
        this.stream = stream;
    }

    @Override
    public void writeHead(HttpContextDefault ctx) {
        stream.responseHeaders.clear();
        stream.responseHeaders.put(":status", List.of(String.valueOf(ctx.status())));
        for (var entry : ctx.responseHeaders().entrySet()) {
            stream.responseHeaders.put(entry.getKey().toLowerCase(Locale.ROOT),
                List.of(entry.getValue()));
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
    public void end(HttpContextDefault ctx) throws IOException {
        stream.outputStream.close();
    }

    @Override
    public SseEmitter openSse(HttpContextDefault ctx) throws IOException {
        stream.writeResponseHeaders(false);
        return new SseEmitter(stream.outputStream);
    }
}
