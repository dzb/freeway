package com.jujin.freeway.http.route;

import com.jujin.freeway.http.HttpContext;

/**
 * Handles a matched HTTP route. Implementations work against the combined
 * exchange context: the convenience methods on {@link HttpContext} cover the
 * common cases, and the request/response faces are
 * {@link com.jujin.freeway.http.HttpRequest} and
 * {@link com.jujin.freeway.http.HttpResponse}.
 */
@FunctionalInterface
public interface RouteHandler {
    /** Processes the matched HTTP exchange. */
    void handle(HttpContext ctx) throws Exception;
}
