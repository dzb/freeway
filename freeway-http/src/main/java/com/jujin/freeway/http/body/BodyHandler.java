package com.jujin.freeway.http.body;

import com.jujin.freeway.http.HttpContext;

/**
 * Handles an HTTP request whose body has been deserialized to a specific
 * type {@code T}. Implementations process the deserialized body along
 * with the request context.
 */
@FunctionalInterface
public interface BodyHandler<T> {
    /**
     * Processes the request with the given deserialized body.
     *
     * @param ctx  the request context providing request metadata and response methods
     * @param body the deserialized request body
     */
    void handle(HttpContext ctx, T body) throws Exception;
}
