package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.ScopeBoundary;
import java.util.Objects;

/**
 * Opt-in request-scoped boundary: opens a container value scope around one HTTP
 * request, so {@code Scope.BOUNDARY} services and scoped cache values live for the
 * request and are released when it ends.
 *
 * <p>It is not a transaction boundary — commit/rollback and deferred events
 * remain {@code Defer}'s job. It is also not async propagation: work handed to
 * another thread must run on an executor wrapped with
 * {@code ScopeSession.propagate(...)}, otherwise the worker sees no request
 * scope (and must not outlive it).</p>
 *
 * <p>Contribute it directly for an application-owned ordering, or enable
 * {@code freeway.http.request-scope.enabled} and let {@code HttpModule} add it
 * once. {@link #ORDER} runs after CORS ({@code -100}) and health ({@code -50}),
 * before application filters ({@code 0}).</p>
 */
public final class ScopeFilter implements HttpFilter {

    /** After the built-in CORS/health filters, before application filters. */
    public static final int ORDER = -40;

    private final ScopeBoundary boundary;

    public ScopeFilter(ScopeBoundary boundary) {
        this.boundary = Objects.requireNonNull(boundary, "boundary");
    }

    @Override
    public void doFilter(HttpExchange ctx, RouteHandler next) throws Exception {
        boundary.withinChecked(() -> next.handle(ctx));
    }

    @Override
    public int order() {
        return ORDER;
    }
}
