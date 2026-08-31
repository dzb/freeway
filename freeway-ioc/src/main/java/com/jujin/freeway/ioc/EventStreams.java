package com.jujin.freeway.ioc;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns {@link EventBus}'s reactive-stream surface: the per-publisher subscriptions
 * from bus dispatch to JDK {@link Flow}, and the live-subscription registry that
 * bus shutdown completes. Extracted from {@code EventBus} so the broadcast
 * core stays readable; the public contract is documented on
 * {@link EventBus#stream(Class)}/{@link EventBus#stream(String)}.
 */
final class EventStreams {

    private static final Logger LOG = LoggerFactory.getLogger(EventStreams.class);

    private final EventBus bus;
    private final Set<StreamSubscription<?>> subscriptions = ConcurrentHashMap.newKeySet();

    EventStreams(EventBus bus) {
        this.bus = bus;
    }

    <E> Flow.Publisher<E> stream(Class<E> eventType) {
        StreamSubscription<E> subscription = new StreamSubscription<>();
        return downstream -> subscription.startAndSubscribe(
            () -> bus.subscribe(eventType, subscription::onEvent),
            downstream
        );
    }

    Flow.Publisher<Object> stream(String topic) {
        StreamSubscription<Object> subscription = new StreamSubscription<>();
        return downstream -> subscription.startAndSubscribe(
            () -> bus.subscribe(topic, subscription::onEvent),
            downstream
        );
    }

    /** Completes every live subscription (bus shutdown): subscribers must not be
     *  left hanging on a dead bus. */
    void closeAll() {
        for (StreamSubscription<?> subscription : subscriptions.toArray(new StreamSubscription[0])) {
            subscription.close();
        }
    }

    /** Demand-ignoring subscription for pre-subscribe failure paths. */
    private static final Flow.Subscription NOOP_SUBSCRIPTION = new Flow.Subscription() {
        @Override public void request(long n) {}
        @Override public void cancel() {}
    };

    /**
     * Connects bus dispatch to JDK Flow semantics: {@link SubmissionPublisher}
     * provides demand tracking, buffering and per-subscriber delivery;
     * {@link SubmissionPublisher#offer(Object)} keeps dispatch non-blocking.
     * Lazily attached — the bus subscription exists only after the first
     * downstream subscribe. Any downstream cancel closes the subscription, which
     * unsubscribes from the bus and completes remaining subscribers.
     */
    private final class StreamSubscription<T> extends SubmissionPublisher<T> {
        private final AtomicBoolean attached = new AtomicBoolean();
        private volatile Runnable detach;

        void startAndSubscribe(
            Supplier<Subscription<T>> attach,
            Flow.Subscriber<? super T> downstream
        ) {
            // Two distinct closed states: THIS subscription (a cancelled stream
            // rejects late subscribers via onError — clean-close would give
            // them a misleading onComplete) and the BUS (detach whatever we
            // own, then settle). Checked up front and again after attach.
            if (isClosed() || bus.isBusClosed()) {
                settleClosed(downstream);
                return;
            }
            // Attach once, atomically. Registration into the live set must
            // precede the second check below: a concurrent bus close()
            // either snapshots this subscription into its shutdown pass or has
            // already set closed before we registered — both orderings end
            // settled, none leaves a live subscription on a dead bus.
            if (attached.compareAndSet(false, true)) {
                try {
                    Subscription<T> sub = attach.get();
                    detach = () -> bus.unsubscribe(sub);
                } catch (RuntimeException e) {
                    // The bus closed between the open check and attach —
                    // subscribe() fails fast there.
                    attached.set(false);
                    downstream.onSubscribe(NOOP_SUBSCRIPTION);
                    downstream.onError(e);
                    return;
                }
                subscriptions.add(this);
                if (isClosed() || bus.isBusClosed()) {
                    close(); // release the just-created bus subscription
                    settleClosed(downstream);
                    return;
                }
            } else if (isClosed()) {
                // Another thread cancelled the stream while we raced in.
                settleClosed(downstream);
                return;
            }
            // Inherited SubmissionPublisher.subscribe — registers the wrapped
            // downstream with THIS publisher (not the bus).
            subscribe(new Flow.Subscriber<T>() {
                @Override public void onSubscribe(Flow.Subscription s) {
                    downstream.onSubscribe(new Flow.Subscription() {
                        @Override public void request(long n) { s.request(n); }
                        @Override public void cancel() {
                            s.cancel();
                            close();
                        }
                    });
                }
                @Override public void onNext(T item) { downstream.onNext(item); }
                @Override public void onError(Throwable t) { downstream.onError(t); }
                @Override public void onComplete() { downstream.onComplete(); }
            });
        }

        /** Explicit failure for post-close subscribe: SubmissionPublisher
         *  itself is silent there, which would strand the subscriber. */
        private void settleClosed(Flow.Subscriber<? super T> downstream) {
            downstream.onSubscribe(NOOP_SUBSCRIPTION);
            downstream.onError(new IllegalStateException("Stream already closed"));
        }

        /** Non-blocking feed: overflow-drop instead of stalling dispatch. */
        void onEvent(T event) {
            if (event == null) return; // topic channel allows null; Flow forbids it
            // Drop-immediately predicate: never retry, never block dispatch.
            int lag = offer(event, (subscriber, item) -> false);
            if (lag < 0) {
                LOG.debug("Stream subscriber overflowed; event dropped");
            }
        }

        @Override
        public void close() {
            subscriptions.remove(this);
            Runnable d = detach;
            if (d != null) d.run();
            super.close(); // onComplete to live subscribers
        }
    }
}
