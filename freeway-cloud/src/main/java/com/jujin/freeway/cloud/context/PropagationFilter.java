package com.jujin.freeway.cloud.context;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.RouteHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inbound propagation filter: extracts the invocation context from request
 * headers via every contributed {@link Propagator}, merges the parts, and
 * binds the result for the request scope (ScopedValue, virtual-thread safe).
 * Outbound injection happens in the {@code CloudHttpClient} layer — the same
 * context, the same propagators, one pipeline.
 */
public final class PropagationFilter implements HttpFilter {

    private final List<Propagator> propagators;

    public PropagationFilter(List<Propagator> propagators) {
        this.propagators = List.copyOf(propagators);
    }

    @Override
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        Map<String, String> headers = flatten(ctx.headers());
        InvocationContext inbound = null;
        for (Propagator propagator : propagators) {
            inbound = merge(inbound, propagator.extract(headers));
        }
        if (inbound == null) {
            next.handle(ctx);
            return;
        }
        InvocationContext.runWith(inbound, () -> {
            next.handle(ctx);
            return null;
        });
    }

    private static InvocationContext merge(InvocationContext acc, InvocationContext part) {
        if (acc == null) {
            return part;
        }
        if (part == null) {
            return acc;
        }
        return InvocationContext.of(
            part.trace() != null ? part.trace() : acc.trace(),
            part.principal() != null ? part.principal() : acc.principal(),
            part.baggage() != null ? part.baggage() : acc.baggage());
    }

    private static Map<String, String> flatten(Map<String, java.util.List<String>> multi) {
        Map<String, String> single = new HashMap<>();
        multi.forEach((name, values) -> {
            if (values != null && !values.isEmpty()) {
                single.put(name, values.getFirst());
            }
        });
        return single;
    }
}
