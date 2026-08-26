package com.jujin.freeway.http.sse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>Long-lived streams can be fed from a JDK {@link Flow.Publisher} (e.g.
 * an event bus stream) via {@link #from(Flow.Publisher)}: the call blocks the
 * current thread — virtual-thread friendly — until the source completes or
 * the emitter is closed, so a handler's try-with-resources close happens only
 * after the stream ends.</p>
 */
public class SseEmitter implements AutoCloseable {
    static final Logger LOG = LoggerFactory.getLogger(SseEmitter.class);
    /** Default heartbeat interval. Kept well below common intermediary idle
     *  timeouts (typically ≥ 60s) so a quiet stream stays alive through them. */
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 15_000;

    private final OutputStream outputStream;
    private final long heartbeatIntervalMillis;
    private final Thread heartbeatThread;
    private volatile boolean closed;
    private volatile Flow.Subscription streamSubscription;
    private final AtomicBoolean streamAttached = new AtomicBoolean();
    /** The live pump's done-latch; complete() counts it down to wake it. */
    private volatile CountDownLatch pumpDone;

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
            CountDownLatch pump = pumpDone;
            if (pump != null) pump.countDown();
            cancelStream(); // release an attached stream pump, if any
            outputStream.close();
        }
    }

    @Override
    public void close() throws IOException { complete(); }

    // ==================== reactive streams (JDK Flow) ====================

    /**
     * Pumps SSE events from the given JDK {@link Flow.Publisher} until the
     * source completes or the emitter is closed. Equivalent to
     * {@code from(publisher, Function.identity())} on an event-valued stream.
     *
     * @param publisher source of events; must emit {@link SseEvent} items
     */
    public void from(Flow.Publisher<SseEvent> publisher) {
        from(publisher, Function.identity());
    }

    /**
     * Pumps a stream of items into this emitter, mapping each to an
     * {@link SseEvent} (e.g. {@code emitter.from(strings, SseEvent::new)}).
     *
     * <p><b>Backpressure:</b> demand is one-in-flight ({@code request(1)}
     * after each write), so TCP write speed propagates upstream as natural
     * backpressure — a slow client throttles the source instead of buffering
     * unboundedly.</p>
     *
     * <p><b>Blocking:</b> blocks the current thread (cheap on virtual
     * threads) until the source completes/fails or the emitter is closed by
     * another thread — so a handler's try-with-resources close fires only
     * after the stream ends. The heartbeat keeps running while idle.</p>
     *
     * <p><b>Lifecycle:</b> source completion closes the response normally;
     * a failing source logs at debug and closes it; a client disconnect
     * cancels the upstream subscription and stops the pump. An emitter may
     * carry only one attached stream; a second {@code from} call throws
     * {@link IllegalStateException}.</p>
     *
     * @param <T>      source item type
     * @param publisher source of items
     * @param mapper   maps each item to an SSE event
     */
    public <T> void from(
        Flow.Publisher<T> publisher,
        Function<? super T, SseEvent> mapper
    ) {
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(mapper, "mapper");
        if (!streamAttached.compareAndSet(false, true)) {
            throw new IllegalStateException(
                "A stream is already attached to this emitter");
        }
        CountDownLatch done = new CountDownLatch(1);
        // Register the latch under the same lock complete() holds: either
        // this pump is visible to a concurrent close, or close has already
        // won and there is nothing left to pump.
        synchronized (this) {
            if (closed) return;
            pumpDone = done;
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<T>() {
            private Flow.Subscription subscription;

            @Override public void onSubscribe(Flow.Subscription s) {
                subscription = s;
                streamSubscription = s;
                if (closed) {
                    // The emitter died between from()'s latch registration and
                    // this callback: cancel here — an external close() already
                    // ran cancelStream() against a still-null subscription.
                    s.cancel();
                    done.countDown();
                    return;
                }
                s.request(1);
            }

            @Override public void onNext(T item) {
                if (closed) {
                    // Close raced the delivery: cut demand instead of draining
                    // the source forever against a dead response.
                    cancelStream();
                    done.countDown();
                    return;
                }
                try {
                    send(mapper.apply(item));
                    subscription.request(1);
                } catch (IOException e) {
                    // Broken pipe — send() has flipped the emitter closed.
                    done.countDown();
                } catch (RuntimeException e) {
                    // Bad event data: end the stream rather than loop.
                    cancelStream();
                    failure.compareAndSet(null, e);
                    done.countDown();
                }
            }

            @Override public void onError(Throwable t) {
                failure.compareAndSet(null, t);
                done.countDown();
            }

            @Override public void onComplete() { done.countDown(); }
        });

        try {
            // Wakes on source end OR emitter close — no polling.
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // server shutdown
            cancelStream();
            return;
        }

        if (closed) {
            // Emitter was closed externally (client disconnect): release
            // the upstream and stop without touching the dead response.
            cancelStream();
            return;
        }
        cancelStream(); // idempotent safety net
        Throwable t = failure.get();
        if (t != null && !closed && LOG.isDebugEnabled()) {
            LOG.debug("SSE source failed; closing stream", t);
        }
        try {
            complete();
        } catch (IOException ignored) {
            // response channel already broken — nothing further to do
        }
    }

    private void cancelStream() {
        Flow.Subscription s = streamSubscription;
        if (s != null) s.cancel();
    }

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
