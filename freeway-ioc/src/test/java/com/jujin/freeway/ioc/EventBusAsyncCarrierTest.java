package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.event.AsyncCarrier;
import com.jujin.freeway.ioc.event.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the {@link AsyncCarrier} contract on the bus's async paths: capture
 * runs synchronously on the submitting thread, the wrapped task runs on an
 * executor thread, and a container without a bound carrier falls back to
 * identity without breaking delivery (never invents a context).
 */
class EventBusAsyncCarrierTest {

    /**
     * Stub carrier recording where {@link #capture} ran and where the
     * returned (wrapped) runnable later ran.
     */
    private static final class RecordingCarrier implements AsyncCarrier {
        private final AtomicInteger captureCount = new AtomicInteger();
        private volatile Thread captureThread;
        private volatile Thread runThread;

        @Override
        public Runnable capture(Runnable work) {
            captureCount.incrementAndGet();
            captureThread = Thread.currentThread();
            return () -> {
                runThread = Thread.currentThread();
                work.run();
            };
        }
    }

    private static Container containerWith(RecordingCarrier carrier) {
        return Freeway.create(binder ->
            binder.bind(AsyncCarrier.class).to(c -> carrier));
    }

    @Test
    void publishAsyncCapturesOnSubmittingThreadAndRunsOnExecutor() throws Exception {
        RecordingCarrier carrier = new RecordingCarrier();
        try (Container container = containerWith(carrier)) {
            EventBus bus = new EventBus(container);
            List<String> log = new ArrayList<>();
            bus.subscribe(String.class, log::add);

            Thread submitter = Thread.currentThread();
            bus.publishAsync("hello");
            // capture is synchronous on the submitting path — no race with the executor
            assertEquals(1, carrier.captureCount.get(),
                "capture must run exactly once, on the submitting thread");
            assertSame(submitter, carrier.captureThread,
                "capture must see the submitting thread's ambient context");

            Await.until(2000, () -> log.size() == 1);
            assertEquals(List.of("hello"), log, "event must reach the subscriber");
            assertNotSame(submitter, carrier.runThread,
                "the wrapped task must run on an executor thread, not the submitter");
            bus.close();
        }
    }

    @Test
    void publishOrderedCapturesOnSubmittingThreadAndRunsOnExecutor() throws Exception {
        RecordingCarrier carrier = new RecordingCarrier();
        try (Container container = containerWith(carrier)) {
            EventBus bus = new EventBus(container);
            List<String> log = new ArrayList<>();
            bus.subscribe(String.class, log::add);

            Thread submitter = Thread.currentThread();
            bus.publishOrdered("hello");
            assertEquals(1, carrier.captureCount.get(),
                "capture must run exactly once, on the submitting thread");
            assertSame(submitter, carrier.captureThread,
                "capture must see the submitting thread's ambient context");

            Await.until(2000, () -> log.size() == 1);
            assertEquals(List.of("hello"), log, "event must reach the subscriber");
            assertNotSame(submitter, carrier.runThread,
                "the wrapped task must run on the ordered channel's thread, not the submitter");
            bus.close();
        }
    }

    @Test
    void unboundCarrierFallsBackToIdentityAndStillDelivers() throws Exception {
        // No AsyncCarrier binding: EventBus must fall back to the identity
        // function (work -> work) — dispatch stays bare but unbroken.
        try (Container container = Freeway.create()) {
            EventBus bus = new EventBus(container);
            List<String> log = new ArrayList<>();
            bus.subscribe(String.class, log::add);

            bus.publishAsync("hello");
            Await.until(2000, () -> log.size() == 1);
            assertEquals(List.of("hello"), log,
                "delivery must not depend on a carrier being bound");
            bus.close();
        }
    }

    /**
     * Task B nail: two carrier bindings without a primary make the binding
     * index ambiguous, and {@code EventBus} must fail loudly at construction
     * instead of silently dispatching bare.
     */
    @Test
    void ambiguousCarrierBindingFailsBusConstruction() {
        try (Container container = Freeway.create(binder -> {
            // Distinct ids: two id-less bindings of one type are a
            // registration-time duplicate (IllegalStateException), not a
            // lookup-time ambiguity.
            binder.bind(AsyncCarrier.class).id("first").to(c -> new RecordingCarrier());
            binder.bind(AsyncCarrier.class).id("second").to(c -> new RecordingCarrier());
        })) {
            assertThrows(AmbiguousBindingException.class, () -> new EventBus(container),
                "isActiveBinding must raise AmbiguousBindingException, not fall back to identity");
        }
    }
}
