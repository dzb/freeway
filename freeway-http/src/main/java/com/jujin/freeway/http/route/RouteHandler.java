package com.jujin.freeway.http.route;
import com.jujin.freeway.http.HttpContext;

/**
 * Handles a matched HTTP route. Implementations read the request
 * from the context and write a response.
 */
@FunctionalInterface
public interface RouteHandler {
    /** Processes the matched HTTP request. */
    void handle(HttpContext ctx) throws Exception;
}
