package com.jujin.freeway.http.sse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Wraps an {@link OutputStream} and writes SSE (Server-Sent Events) formatted data.
 * <p>
 * Usage:
 * <pre>{@code
 * try (var emitter = ctx.sse()) {
 *     emitter.send("hello");
 *     emitter.send(new SseEvent("data", "evt1"));
 * }
 * }</pre>
 */
public class SseEmitter implements AutoCloseable {
    private final OutputStream outputStream;
    private volatile boolean closed;

    public SseEmitter(OutputStream outputStream) {
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream");
    }

    public void send(SseEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        if (closed) return;

        if (event.id() != null) {
            write("id: ");
            write(event.id());
            write("\n");
        }
        if (event.event() != null) {
            write("event: ");
            write(event.event());
            write("\n");
        }
        if (event.retry() != null) {
            write("retry: ");
            write(Long.toString(event.retry()));
            write("\n");
        }
        // Split data by newlines — each line becomes "data: <line>"
        String data = event.data();
        int start = 0;
        for (int i = 0; i < data.length(); i++) {
            if (data.charAt(i) == '\n') {
                write("data: ");
                write(data.substring(start, i));
                write("\n");
                start = i + 1;
            }
        }
        write("data: ");
        write(data.substring(start));
        write("\n\n");
        outputStream.flush();
    }

    public void send(String data) throws IOException {
        send(new SseEvent(data));
    }

    public void complete() throws IOException {
        if (closed) return;
        closed = true;
        outputStream.close();
    }

    @Override
    public void close() throws IOException {
        complete();
    }

    private void write(String text) throws IOException {
        outputStream.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
