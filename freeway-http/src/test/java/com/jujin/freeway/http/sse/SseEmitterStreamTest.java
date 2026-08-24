package com.jujin.freeway.http.sse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link SseEmitter#from(Flow.Publisher, Function)}:
 * pumping until source completion, mapping, client-disconnect cancelling the
 * upstream, source failure closing the emitter, single-attachment guard, and
 * one-in-flight demand.
 */
class SseEmitterStreamTest {

    // ==================== pump lifecycle ====================

    @Test
    void pumpsEventsUntilSourceCompletion() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos, 0);
        var publisher = new SubmissionPublisher<SseEvent>();
        Thread pump = Thread.ofVirtual().start(() -> emitter.from(publisher));

        awaitUntil(2000, () -> publisher.getNumberOfSubscribers() == 1);
        publisher.submit(new SseEvent("one"));
        publisher.submit(new SseEvent("two"));
        publisher.close();
        pump.join(2000);

        assertFalse(pump.isAlive(), "pump must end when the source completes");
        assertEquals("data: one\n\ndata: two\n\n",
            baos.toString(StandardCharsets.UTF_8));
    }

    @Test
    void mapsPlainStringsViaMapper() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos, 0);
        var publisher = new SubmissionPublisher<String>();
        Thread pump = Thread.ofVirtual().start(
            () -> emitter.from(publisher, SseEvent::new));

        awaitUntil(2000, () -> publisher.getNumberOfSubscribers() == 1);
        publisher.submit("hello");
        publisher.close();
        pump.join(2000);

        assertEquals("data: hello\n\n", baos.toString(StandardCharsets.UTF_8));
    }

    @Test
    void clientCloseCancelsUpstream() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos, 0);
        var publisher = new SubmissionPublisher<SseEvent>();
        Thread pump = Thread.ofVirtual().start(() -> emitter.from(publisher));

        awaitUntil(2000, () -> publisher.getNumberOfSubscribers() == 1);
        // Client disconnect path: the handler closes the emitter while the
        // pump is blocked — upstream must be cancelled and the pump released.
        emitter.close();
        pump.join(2000);
        assertFalse(pump.isAlive(), "pump must end when the emitter is closed");

        // BufferedSubscription is removed asynchronously after cancel().
        awaitUntil(2000, () -> publisher.getNumberOfSubscribers() == 0);
    }

    @Test
    void sourceFailureClosesEmitter() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos, 0);
        var publisher = new SubmissionPublisher<SseEvent>();
        Thread pump = Thread.ofVirtual().start(() -> emitter.from(publisher));

        awaitUntil(2000, () -> publisher.getNumberOfSubscribers() == 1);
        publisher.submit(new SseEvent("before-crash"));
        publisher.closeExceptionally(new RuntimeException("source exploded"));
        pump.join(2000);

        assertFalse(pump.isAlive(), "pump must end when the source fails");
        assertTrue(baos.toString(StandardCharsets.UTF_8)
                .contains("data: before-crash\n\n"),
            "events delivered before the failure must reach the wire");
        // The emitter must be closed: further sends are no-ops.
        emitter.send("after-close");
        assertEquals("data: before-crash\n\n",
            baos.toString(StandardCharsets.UTF_8));
    }

    @Test
    void secondAttachRejected() throws Exception {
        var emitter = new SseEmitter(new ByteArrayOutputStream(), 0);
        var first = new SubmissionPublisher<SseEvent>();
        Thread pump = Thread.ofVirtual().start(() -> emitter.from(first));
        awaitUntil(2000, () -> first.getNumberOfSubscribers() == 1);

        assertThrows(IllegalStateException.class,
            () -> emitter.from(new SubmissionPublisher<SseEvent>()));

        emitter.close();
        pump.join(2000);
    }

    // ==================== backpressure ====================

    @Test
    void demandIsOneInFlight() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos, 0);
        var recording = new RecordingPublisher();
        Thread pump = Thread.ofVirtual().start(() -> emitter.from(recording));

        awaitUntil(2000, () -> !recording.requests.isEmpty());
        assertEquals(List.of(1L), recording.requests,
            "initial demand must be exactly one event");

        recording.deliver(new SseEvent("a"));
        assertEquals(List.of(1L, 1L), recording.requests,
            "each written event must request exactly one more");

        recording.deliver(new SseEvent("b"));
        recording.complete();
        pump.join(2000);

        assertFalse(pump.isAlive());
        assertEquals("data: a\n\ndata: b\n\n",
            baos.toString(StandardCharsets.UTF_8));
    }

    /** Manual publisher exposing subscription and demand for assertions. */
    static final class RecordingPublisher implements Flow.Publisher<SseEvent> {
        final List<Long> requests = new ArrayList<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile boolean done;
        private volatile Flow.Subscriber<? super SseEvent> downstream;

        boolean subscribed() { return downstream != null; }
        boolean isCancelled() { return cancelled.get(); }

        void deliver(SseEvent event) {
            downstream.onNext(event);
        }

        void complete() {
            done = true;
            downstream.onComplete();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super SseEvent> subscriber) {
            this.downstream = subscriber;
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { requests.add(n); }
                @Override public void cancel() { cancelled.set(true); }
            });
        }
    }

    private static void awaitUntil(long timeoutMs, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not met within " + timeoutMs + " ms");
            }
            Thread.sleep(10);
        }
    }
}
