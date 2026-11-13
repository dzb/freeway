package com.jujin.freeway.http.body;

import com.jujin.freeway.http.HttpExchange;

/**
 * Handles an HTTP request whose body has been deserialized to a specific
 * type {@code T}. Implementations process the deserialized body along
 * with the request and response.
 */
@FunctionalInterface
public interface BodyHandler<T> {
    /**
     * Processes the request with the given deserialized body.
     *
     * @param ctx  the exchange context
     * @param body the deserialized request body
     */
    void handle(HttpExchange ctx, T body) throws Exception;
}
