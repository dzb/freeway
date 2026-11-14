package com.jujin.freeway.cloud.context;

import com.jujin.freeway.cloud.context.internal.AuthPropagator;
import com.jujin.freeway.cloud.context.internal.BaggagePropagator;
import com.jujin.freeway.cloud.context.internal.PropagationFilter;
import com.jujin.freeway.cloud.context.internal.TracePropagator;
import com.jujin.freeway.commons.util.ContextCarrier;
import java.util.Map;
import org.slf4j.MDC;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

/**
 * IoC wiring for the context subsystem: {@code InvocationContext} (ScopedValue
 * carrier), {@code Propagator} chain, and the inbound {@code PropagationFilter}.
 */
@Marker(Builtin.class)
public final class CloudContextModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        // Whole context, not just trace: the wire rule ("trace only, never
        // principal") guards a process boundary, while an executor thread is
        // the same trust domain — the handler sees what a synchronous publish
        // on the submitting thread would have seen. A traceless submit
        // dispatches bare (the mesh inbound rule).
        // One carrier for every context this module owns: the invocation
        // context (trace/principal/baggage) *and* the MDC fields the tracer
        // writes. A worker therefore logs the same traceId/spanId a
        // synchronous call would, and the previous worker state is restored
        // afterwards so a pooled thread cannot leak one task into the next.
        b.bind(ContextCarrier.class).to(c -> work -> {
            InvocationContext ctx = InvocationContext.current()
                .filter(value -> !value.carriesNothing())
                .orElse(null);
            Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
            boolean hasMdc = capturedMdc != null && !capturedMdc.isEmpty();
            if (ctx == null && !hasMdc) {
                return work;
            }
            return () -> {
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    if (hasMdc) {
                        MDC.setContextMap(capturedMdc);
                    } else {
                        MDC.clear();
                    }
                    if (ctx != null) {
                        InvocationContext.runWith(ctx, work);
                    } else {
                        work.run();
                    }
                } finally {
                    if (previousMdc == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previousMdc);
                    }
                }
            };
        });
        b.contribute(Propagator.class).add("freeway.cloud.propagation.trace", new TracePropagator());
        // Container-created so the auth propagator can read the
        // freeway.cloud.auth.extract.enabled switch from the SymbolSource.
        b.contribute(Propagator.class).addDeferred(AuthPropagator.class);
        // After trace/auth: their extract() leaves unset baggage as null, so
        // the merge keeps the baggage parsed here (null wins only when absent).
        b.contribute(Propagator.class).add("freeway.cloud.propagation.baggage", new BaggagePropagator());
        b.contribute(HttpFilter.class).addDeferred(PropagationFilter.class);
    }
}
