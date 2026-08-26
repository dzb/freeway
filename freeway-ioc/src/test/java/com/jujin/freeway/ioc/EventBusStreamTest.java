package com.jujin.freeway.ioc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.scoped.Defer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link EventBus#stream(Class)} and
 * {@link EventBus#stream(String)}: delivery, exact topic matching, cancel
 * detaching from the bus (no zombie subscriptions), Defer-scope buffering,
 * and close-completes-streams lifecycle.
 */
class EventBusStreamTest {

    record Tick(String id) {}

    /** Full {@link Flow.Subscriber} adapter — the interface is not functional. */
    private static <T> Flow.Subscriber<T> collecting(List<? super T> into) {
        return new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(T item) { into.add(item); }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        };
    }

    // ==================== delivery ====================

    @Test
    void classStreamReceivesEventsUntilBusClose() throws Exception {
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);

        List<Object> received = new CopyOnWriteArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();

        bus.stream(Tick.class).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(Tick item) { received.add(item); }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() { completed.set(true); }
        });

        bus.publish(new Tick("a"));
        bus.publish(new Tick("b"));
        Await.until(2000, () -> received.size() == 2);
        assertEquals(List.of(new Tick("a"), new Tick("b")), received);

        // Bus shutdown completes live streams — subscribers must not hang.
        bus.close();
        Await.until(2000, completed::get);
    }

    @Test
    void topicStreamMatchesExactTopicOnly() throws Exception {
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);

        List<Object> received = new CopyOnWriteArrayList<>();
        bus.stream("market.ticker").subscribe(collecting(new ArrayList<>()));
        // Collect via a second stream with a collecting subscriber.
        bus.stream("market.ticker").subscribe(collecting(received));

        bus.publish("other.topic", "noise");
        bus.publish("market.ticker", "price");
        Await.until(2000, () -> received.equals(List.of("price")));

        bus.close();
    }

    @Test
    void independentStreamsFanOut() throws Exception {
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);

        List<Object> first = new CopyOnWriteArrayList<>();
        List<Object> second = new CopyOnWriteArrayList<>();
        bus.stream(Tick.class).subscribe(collecting(first));
        bus.stream(Tick.class).subscribe(collecting(second));

        bus.publish(new Tick("x"));
        Await.until(2000, () -> first.size() == 1 && second.size() == 1);

        bus.close();
    }

    // ==================== cancel / detach ====================

    @Test
    void cancelEndsStreamAndUnsubscribesFromBus() throws Exception {
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);

        List<DeadEvent> deads = new CopyOnWriteArrayList<>();
        bus.subscribe(DeadEvent.class, (Consumer<DeadEvent>) deads::add);

        List<Object> received = new CopyOnWriteArrayList<>();
        AtomicReference<Flow.Subscription> handle = new AtomicReference<>();
        bus.stream(Tick.class).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                handle.set(s);
                s.request(1);
            }
            @Override public void onNext(Tick item) { received.add(item); }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        });

        bus.publish(new Tick("first"));
        Await.until(2000, () -> received.size() == 1);

        // Cancel must detach the bridge: the next publish finds zero
        // subscribers and emits a DeadEvent instead of feeding a zombie.
        handle.get().cancel();
        Await.until(2000, () -> {
            bus.publish(new Tick("after-cancel"));
            return !deads.isEmpty();
        });
    }

    @Test
    void closedBridgeRejectsLateSubscribers() throws Exception {
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);

        Flow.Publisher<Tick> publisher = bus.stream(Tick.class);
        // Subscribe and cancel through the real path to close the bridge.
        AtomicReference<Flow.Subscription> handle = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<Tick>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                handle.set(s);
                s.request(1);
            }
            @Override public void onNext(Tick item) {}
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        });
        Await.until(2000, () -> handle.get() != null);
        handle.get().cancel();

        AtomicBoolean failed = new AtomicBoolean();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(1); }
            @Override public void onNext(Tick item) {}
            @Override public void onError(Throwable t) { failed.set(true); }
            @Override public void onComplete() {}
        });
        Await.until(2000, failed::get);

        bus.close();
    }

    // ==================== Defer integration ====================

    @Test
    void deferScopeDeliversToStreamAfterCommit() throws Exception {
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);

        List<Object> received = new CopyOnWriteArrayList<>();
        bus.stream(Tick.class).subscribe(collecting(received));

        Defer.within(() -> bus.publish(new Tick("tx")));
        Await.until(2000, () -> received.equals(List.of(new Tick("tx"))));

        bus.close();
    }

    // ==================== lifecycle guards ====================

    @Test
    void streamOnClosedBusFailsFast() {
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);
        bus.close();

        assertThrows(IllegalStateException.class, () -> bus.stream(Tick.class));
        assertThrows(IllegalStateException.class, () -> bus.stream("topic"));
    }

    @Test
    void publisherFromBeforeBusCloseNotifiesOnErrorAfterClose() throws Exception {
        // Regression: stream() captured while the bus was open, but the first
        // downstream subscribe happens after the bus closed. The bridge must
        // settle the subscriber via onError — not leak the raw
        // IllegalStateException out of subscribe() (Flow: subscribe must not
        // throw) and not leave the subscription attached to a dead registry.
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);
        Flow.Publisher<Tick> publisher = bus.stream(Tick.class);
        bus.close();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.concurrent.CountDownLatch settled =
            new java.util.concurrent.CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { }
            @Override public void onNext(Tick item) { }
            @Override public void onError(Throwable t) {
                failure.set(t);
                settled.countDown();
            }
            @Override public void onComplete() { settled.countDown(); }
        });

        assertTrue(settled.await(2, java.util.concurrent.TimeUnit.SECONDS),
            "the subscriber must be notified, never left hanging");
        assertTrue(failure.get() instanceof IllegalStateException,
            "got: " + failure.get());
    }

    @Test
    void streamedTopicsEmitNoDeadEvents() throws Exception {
        Container container = Freeway.create();
        EventBus bus = new EventBus(container);

        List<Object> deads = new CopyOnWriteArrayList<>();
        bus.subscribe(DeadEvent.class, (Consumer<DeadEvent>) deads::add);
        List<Object> received = new CopyOnWriteArrayList<>();
        bus.stream("market.ticker").subscribe(collecting(received));

        // Warm up until the lazy bridge is attached (attachment is async),
        // discarding any pre-attach diagnostics from the race window.
        Await.until(2000, () -> {
            bus.publish("market.ticker", "warmup");
            return !received.isEmpty();
        });
        deads.clear();
        int deliveredBefore = received.size();

        // Once attached, the stream IS the subscriber: no DeadEvent.
        bus.publish("market.ticker", "real");
        Await.until(2000, () -> received.size() > deliveredBefore);
        assertTrue(deads.isEmpty(),
            "a live stream must suppress DeadEvent diagnostics for its topic");
        bus.close();
    }
}
