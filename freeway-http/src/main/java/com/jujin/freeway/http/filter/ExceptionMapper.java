package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.HttpContext;

/**
 * Maps an exception thrown during request processing to an HTTP response.
 */
@FunctionalInterface
public interface ExceptionMapper {
    /**
     * Attempts to handle the given exception by writing an error response
     * to the request context.
     *
     * @return true if the exception was handled, false to delegate to the
     *         next mapper or the default error handler
     */
    boolean handle(HttpContext ctx, Exception exception) throws Exception;
}
