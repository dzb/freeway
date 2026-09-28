package com.jujin.freeway.cloud.context;

import com.jujin.freeway.cloud.internal.AuthPropagator;
import com.jujin.freeway.cloud.internal.BaggagePropagator;
import com.jujin.freeway.cloud.internal.PropagationFilter;
import com.jujin.freeway.cloud.internal.TracePropagator;
import com.jujin.freeway.ioc.event.AsyncCarrier;
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
        b.bind(AsyncCarrier.class).to(c -> work -> {
            var captured = InvocationContext.current();
            if (captured.isEmpty() || isBlank(captured.get())) {
                return work;
            }
            InvocationContext ctx = captured.get();
            return () -> InvocationContext.runWith(ctx, work);
        });
        b.contribute(Propagator.class).add("freeway.cloud.propagation.trace", new TracePropagator());
        // Container-created so the auth propagator can read the
        // freeway.cloud.auth.extract.enabled switch from the SymbolSource.
        b.contribute(Propagator.class).add(AuthPropagator.class);
        // After trace/auth: their extract() leaves unset baggage as null, so
        // the merge keeps the baggage parsed here (null wins only when absent).
        b.contribute(Propagator.class).add("freeway.cloud.propagation.baggage", new BaggagePropagator());
        b.contribute(HttpFilter.class).add(PropagationFilter.class);
    }

    /** A present context with every sub-context unset carries nothing — binding it would invent one. */
    private static boolean isBlank(InvocationContext ctx) {
        return ctx.trace() == null && ctx.principal() == null && ctx.baggage() == null;
    }
}
