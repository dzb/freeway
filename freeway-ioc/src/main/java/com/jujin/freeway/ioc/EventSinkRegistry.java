package com.jujin.freeway.ioc;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal registry of {@link EventSink} instances for
 * {@link com.jujin.freeway.ioc.EventBus}.
 *
 * <p>Copy-on-write behind a volatile reference: the publish hot path
 * ({@code isEmpty}/{@code snapshot}) is a plain read — no lock, no copy —
 * while add/remove/clear (rare, lifecycle-bound) build the next immutable
 * list under a monitor.</p>
 */
final class EventSinkRegistry {

    private volatile List<EventSink> sinks = List.of();

    void add(EventSink sink) {
        synchronized (this) {
            for (EventSink installed : sinks) {
                if (installed == sink) {
                    return;
                }
            }
            List<EventSink> next = new ArrayList<>(sinks.size() + 1);
            next.addAll(sinks);
            next.add(sink);
            sinks = List.copyOf(next);
        }
    }

    boolean remove(EventSink sink) {
        synchronized (this) {
            for (int i = 0; i < sinks.size(); i++) {
                if (sinks.get(i) == sink) { // identity, like add
                    List<EventSink> next = new ArrayList<>(sinks);
                    next.remove(i);
                    sinks = List.copyOf(next);
                    return true;
                }
            }
            return false;
        }
    }

    boolean isEmpty() {
        return sinks.isEmpty();
    }

    /** The live immutable snapshot — callers iterate, never mutate. */
    List<EventSink> snapshot() {
        return sinks;
    }

    void clear() {
        synchronized (this) {
            sinks = List.of();
        }
    }
}
