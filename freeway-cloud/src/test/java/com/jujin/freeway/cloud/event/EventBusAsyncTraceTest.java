package com.jujin.freeway.cloud.event;

import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.CloudContextModule;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.PrincipalContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.event.EventBus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documented async-trace gap, closed: a {@code publishAsync} /
 * {@code publishOrdered} handler must observe the submitting thread's
 * {@code InvocationContext}, and a traceless submit must still deliver.
 */
class EventBusAsyncTraceTest {

    private static final TraceContext TRACE = TraceContext.root();

    private static Container containerWithContext() {
        return Freeway.create(new CloudContextModule());
    }

    @Test
    void asyncPublishCarriesSubmitterTrace() throws Exception {
        try (Container container = containerWithContext()) {
            EventBus bus = container.get(EventBus.class);
            var seen = new AtomicReference<Optional<InvocationContext>>();
            var latch = new CountDownLatch(1);
            bus.subscribe(String.class, e -> {
                seen.set(InvocationContext.current());
                latch.countDown();
            });
            InvocationContext.runWith(InvocationContext.of(TRACE, null, null),
                () -> bus.publishAsync("hello"));
            assertTrue(latch.await(2, TimeUnit.SECONDS), "async dispatch must deliver");
            Optional<InvocationContext> ctx = seen.get();
            assertTrue(ctx != null && ctx.isPresent(), "handler must run under the submitter's context");
            assertEquals(TRACE.traceId(), ctx.orElseThrow().trace().traceId());
        }
    }

    @Test
    void orderedPublishCarriesSubmitterTrace() throws Exception {
        try (Container container = containerWithContext()) {
            EventBus bus = container.get(EventBus.class);
            var seen = new AtomicReference<Optional<InvocationContext>>();
            var latch = new CountDownLatch(1);
            bus.subscribe(String.class, e -> {
                seen.set(InvocationContext.current());
                latch.countDown();
            });
            InvocationContext.runWith(InvocationContext.of(TRACE, null, null),
                () -> bus.publishOrdered("hello"));
            assertTrue(latch.await(2, TimeUnit.SECONDS), "ordered dispatch must deliver");
            Optional<InvocationContext> ctx = seen.get();
            assertTrue(ctx != null && ctx.isPresent(), "ordered handler must run under the submitter's context");
            assertEquals(TRACE.traceId(), ctx.orElseThrow().trace().traceId());
        }
    }

    @Test
    void tracelessAsyncSubmitStillDelivers() throws Exception {
        try (Container container = containerWithContext()) {
            EventBus bus = container.get(EventBus.class);
            var seen = new AtomicReference<Optional<InvocationContext>>();
            var latch = new CountDownLatch(1);
            bus.subscribe(String.class, e -> {
                seen.set(InvocationContext.current());
                latch.countDown();
            });
            bus.publishAsync("hello");
            assertTrue(latch.await(2, TimeUnit.SECONDS), "traceless async must deliver");
            Optional<InvocationContext> ctx = seen.get();
            assertTrue(ctx != null && ctx.isEmpty(),
                "with no context on the submitting thread the carrier must dispatch bare"
                    + " — the handler observes no InvocationContext");
        }
    }

    @Test
    void allBlankContextDispatchesBare() throws Exception {
        try (Container container = containerWithContext()) {
            EventBus bus = container.get(EventBus.class);
            var seen = new AtomicReference<Optional<InvocationContext>>();
            var latch = new CountDownLatch(1);
            bus.subscribe(String.class, e -> {
                seen.set(InvocationContext.current());
                latch.countDown();
            });
            // Present but carrying nothing: binding it on the executor would invent a context
            // the submitter never had — the carrier must dispatch bare instead.
            InvocationContext.runWith(InvocationContext.of(null, null, null), () -> {
                assertTrue(InvocationContext.current().isEmpty(),
                    "the submitting thread binds nothing either: one rule, both sides of the handoff");
                bus.publishAsync("hello");
            });
            assertTrue(latch.await(2, TimeUnit.SECONDS), "blank-context async must deliver");
            Optional<InvocationContext> ctx = seen.get();
            assertTrue(ctx != null && ctx.isEmpty(),
                "an all-unset context must not propagate — the handler observes no InvocationContext");
        }
    }

    @Test
    void asyncPublishCarriesSubmitterPrincipalAndBaggage() throws Exception {
        try (Container container = containerWithContext()) {
            EventBus bus = container.get(EventBus.class);
            PrincipalContext principal = PrincipalContext.of("alice", List.of("admin"));
            Baggage baggage = Baggage.of(Map.of("tenant", "acme"));
            var seen = new AtomicReference<Optional<InvocationContext>>();
            var latch = new CountDownLatch(1);
            bus.subscribe(String.class, e -> {
                seen.set(InvocationContext.current());
                latch.countDown();
            });
            InvocationContext.runWith(InvocationContext.of(TRACE, principal, baggage),
                () -> bus.publishAsync("hello"));
            assertTrue(latch.await(2, TimeUnit.SECONDS), "async dispatch must deliver");
            Optional<InvocationContext> ctx = seen.get();
            assertTrue(ctx != null && ctx.isPresent(),
                "handler must run under the submitter's context");
            assertEquals(principal, ctx.orElseThrow().principal(),
                "principal must equal the submitting thread's principal");
            assertEquals(baggage, ctx.orElseThrow().baggage(),
                "baggage must equal the submitting thread's baggage");
        }
    }
}
