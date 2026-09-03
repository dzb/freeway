package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.metrics.Metrics;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Builtins that depend on {@link Metrics} must observe a module-supplied
 * primary registry. They realize lazily — on first resolution, after every
 * module has bound — instead of freezing the pre-load NoopMetrics builtin,
 * which would make their counters invisible to any real registry.
 */
class BuiltinMetricsWiringTest {

    @Test
    void eventBusCountersReachThePrimaryMetrics() {
        RecordingMetrics metrics = new RecordingMetrics();
        try (Container container = Freeway.create(
            binder -> binder.bind(Metrics.class).to(c -> metrics).primary())) {
            container.get(EventBus.class).publish(new UnsubscribedEvent());

            assertEquals(1, metrics.counterValue("eventbus.published"),
                "the primary registry must observe the bus's counters");
        }
    }

    @Test
    void callBusCountersReachThePrimaryMetrics() {
        RecordingMetrics metrics = new RecordingMetrics();
        try (Container container = Freeway.create(
            binder -> binder.bind(Metrics.class).to(c -> metrics).primary())) {
            container.get(CallBus.class).call("nobody");

            assertEquals(1, metrics.counterValue("callbus.called"),
                "the primary registry must observe the bus's counters");
            assertEquals(1, metrics.counterValue("callbus.dead"));
        }
    }

    /** A class-channel event nobody subscribes to. */
    static final class UnsubscribedEvent {}

    /** Minimal recording registry: counters counted, timers/gauges dropped. */
    static final class RecordingMetrics implements Metrics {

        private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

        @Override
        public Counter counter(String name) {
            LongAdder adder = counters.computeIfAbsent(name, k -> new LongAdder());
            return new Counter() {
                @Override public void increment() {
                    adder.increment();
                }

                @Override public void add(long delta) {
                    adder.add(delta);
                }

                @Override public long value() {
                    return adder.sum();
                }
            };
        }

        @Override
        public void gauge(String name, Supplier<Number> value) {
            // not needed by the buses' counters
        }

        long counterValue(String name) {
            LongAdder adder = counters.get(name);
            return adder == null ? 0 : adder.sum();
        }
    }
}
