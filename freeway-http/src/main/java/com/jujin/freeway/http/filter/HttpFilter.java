package com.jujin.freeway.http.filter;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;

/**
 * Intercepts incoming HTTP requests. Implementations can pre-process,
 * delegate to the next filter or route handler via the chain, and
 * post-process the response.
 */
@FunctionalInterface
public interface HttpFilter {
    /**
     * Intercepts the request context and either handles it directly
     * or passes it to the next filter or route handler in the chain
     * by invoking {@code next.handle(ctx)}.
     */
    void doFilter(HttpContext ctx, RouteHandler next) throws Exception;
}
