package com.jujin.freeway.cloud.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.util.ContextCarrier;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The cloud carrier is the single place trace/principal/MDC cross an executor
 * boundary: work sees the submitting thread's context, and the worker is left
 * as it was found.
 */
class ContextCarrierTest {

    @Test
    void restoresInvocationContextAndMdcAndLeavesTheWorkerClean() throws Exception {
        try (Container container = Freeway.create(new CloudContextModule())) {
            ContextCarrier carrier = container.get(ContextCarrier.class);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                TraceContext trace = TraceContext.root();
                InvocationContext context = InvocationContext.of(trace, null, null);
                AtomicReference<String> seenTrace = new AtomicReference<>();
                AtomicReference<String> seenMdc = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);

                InvocationContext.runWith(context, () -> {
                    MDC.put("traceId", trace.traceId());
                    pool.execute(carrier.capture(() -> {
                        seenTrace.set(InvocationContext.current()
                            .map(value -> value.trace().traceId())
                            .orElse(null));
                        seenMdc.set(MDC.get("traceId"));
                        done.countDown();
                    }));
                });

                assertTrue(done.await(5, TimeUnit.SECONDS),
                    "the carried task did not run");
                assertEquals(trace.traceId(), seenTrace.get(),
                    "the worker must see the submitting invocation context");
                assertEquals(trace.traceId(), seenMdc.get(),
                    "the worker must see the captured MDC");

                AtomicReference<String> leftover = new AtomicReference<>("unset");
                pool.submit(() -> leftover.set(MDC.get("traceId")))
                    .get(5, TimeUnit.SECONDS);
                assertNull(leftover.get(),
                    "the worker's ambient state must be restored after the task");
            } finally {
                MDC.clear();
                pool.shutdownNow();
            }
        }
    }
}
