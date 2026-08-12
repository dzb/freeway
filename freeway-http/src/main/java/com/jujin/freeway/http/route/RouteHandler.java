package com.jujin.freeway.http.route;

import com.jujin.freeway.http.HttpContext;

/**
 * Handles a matched HTTP route. Implementations work against the combined
 * exchange context, using its convenience methods or the {@code request()}/
 * {@code response()} faces for deeper access.
 */
@FunctionalInterface
public interface RouteHandler {
    /** Processes the matched HTTP exchange. */
    void handle(HttpContext ctx) throws Exception;
}
