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
 * <p>
 * A heartbeat comment line ({@code : ping}) is written every
 * {@link #DEFAULT_HEARTBEAT_INTERVAL_MILLIS} so intermediaries (proxies, load
 * balancers) with an idle timeout do not tear down a long-lived event stream
 * that happens to be quiet.
 */
public class SseEmitter implements AutoCloseable {
    /** Default heartbeat interval. Kept well below common intermediary idle
     *  timeouts (typically ≥ 60s) so a quiet stream stays alive through them. */
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 15_000;

    private final OutputStream outputStream;
    private final long heartbeatIntervalMillis;
    private final Thread heartbeatThread;
    private volatile boolean closed;

    /** Creates a new SseEmitter that writes to the given output stream,
     *  with the default heartbeat interval. */
    public SseEmitter(OutputStream outputStream) {
        this(outputStream, DEFAULT_HEARTBEAT_INTERVAL_MILLIS);
    }

    /** Creates a new SseEmitter with a custom heartbeat interval.
     *  @param heartbeatIntervalMillis interval between heartbeat comment
     *         lines; {@code 0} disables the heartbeat */
    public SseEmitter(OutputStream outputStream, long heartbeatIntervalMillis) {
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream");
        if (heartbeatIntervalMillis < 0) {
            throw new IllegalArgumentException("heartbeatIntervalMillis must be >= 0");
        }
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.heartbeatThread = heartbeatIntervalMillis > 0
            ? Thread.ofVirtual().name("sse-heartbeat").start(this::heartbeatLoop)
            : null;
    }

    /** Writes a single SSE event to the output stream with full event metadata. */
    public void send(SseEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        validateField(event.id(), "id");
        validateField(event.event(), "event");
        if (event.retry() != null && event.retry() < 0) {
            throw new IllegalArgumentException("retry must be >= 0");
        }
        if (closed) return;

        // The whole event is written under one lock so the heartbeat thread
        // can never split a multi-line event.
        synchronized (this) {
            if (closed) return;
            try {
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
                String data = event.data();
                int start = 0;
                for (int i = 0; i < data.length(); i++) {
                    char c = data.charAt(i);
                    if (c == '\n' || c == '\r') {
                        write("data: ");
                        write(data.substring(start, i));
                        write("\n");
                        // skip \n after \r (CRLF)
                        if (c == '\r' && i + 1 < data.length() && data.charAt(i + 1) == '\n') i++;
                        start = i + 1;
                    }
                }
                write("data: ");
                write(data.substring(start));
                write("\n\n");
                outputStream.flush();
            } catch (IOException e) {
                closed = true; // the stream is broken — stop further sends
                throw e;
            }
        }
    }

    /** Sends a simple SSE event with the given data and no additional metadata. */
    public void send(String data) throws IOException {
        send(new SseEvent(data));
    }

    /** Closes the emitter. Subsequent sends are silently ignored. */
    public void complete() throws IOException {
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (heartbeatThread != null) heartbeatThread.interrupt();
            outputStream.close();
        }
    }

    @Override
    public void close() throws IOException { complete(); }

    private void write(String text) throws IOException {
        outputStream.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void validateField(String value, String field) {
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException(field + " must not contain CR/LF");
        }
    }

    private void heartbeatLoop() {
        try {
            while (!closed) {
                Thread.sleep(heartbeatIntervalMillis);
                synchronized (this) {
                    if (closed) break;
                    // SSE comment line: ignored by clients, keeps the stream
                    // alive through intermediaries.
                    write(": ping\n\n");
                    outputStream.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // shutting down
        } catch (IOException e) {
            closed = true; // the stream is broken — stop the heartbeat
        }
    }
}
