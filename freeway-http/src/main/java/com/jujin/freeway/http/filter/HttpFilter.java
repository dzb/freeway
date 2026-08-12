package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

/**
 * Intercepts incoming HTTP requests. Implementations can pre-process,
 * delegate to the next filter or route handler via the chain, and
 * post-process the response.
 *
 * <p>Filters are ordered by {@link #order()}: lower values run earlier
 * (closer to the request entry, farther from the route handler). Filters
 * with the same order keep their registration order.</p>
 */
@FunctionalInterface
public interface HttpFilter {
    /**
     * Intercepts the exchange and either handles it directly or passes it to
     * the next filter or route handler in the chain by invoking
     * {@code next.handle(ctx)}.
     */
    void doFilter(HttpContext ctx, RouteHandler next) throws Exception;

    /** Execution order; lower values run earlier. Defaults to 0 for
     *  application filters. Built-ins use negative orders:
     *  CORS = -100, health = -50. */
    default int order() {
        return 0;
    }
}
