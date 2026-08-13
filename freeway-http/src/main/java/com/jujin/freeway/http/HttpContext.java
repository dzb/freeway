package com.jujin.freeway.http;

import java.util.Map;

/**
 * One HTTP exchange: the exchange metadata, the request face, and the
 * response face, all on a single object handed to application handlers.
 *
 * <p>A handler receives an {@code HttpContext} and can use every request,
 * response, and metadata method directly; framework components that need
 * only one side depend on the narrow {@link HttpRequest}/{@link HttpResponse}
 * contracts and accept the context because it is one of them.</p>
 */
public interface HttpContext extends ExchangeMeta, HttpRequest, HttpResponse {

    /**
     * Sets the path variables extracted by the route match. This is the
     * routing seam and lives here (rather than on the read-only
     * {@link HttpRequest} face) because it mutates exchange state.
     *
     * @return this context for chaining
     */
    HttpContext setPathVars(Map<String, String> vars);
}
