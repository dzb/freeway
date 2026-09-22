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
 *
 * <p><b>Where it sits.</b> The four anchors ({@link HttpEngine}, {@link
 * HttpServerConfig}, {@link HttpPipeline}, {@link HttpServer}) define the
 * server <em>before start</em>; this interface is the server <em>during
 * handle</em> — the seam's data half (its behaviour half is
 * {@link ExchangeHandler}), and the one place the two declarations meet at
 * runtime: config policy lands in its fields ({@code maxBodySize},
 * {@code compression}) and every pipeline stage runs against it. Writers are
 * partitioned by phase — the engine fills the request side and initializes
 * policy, dispatch writes {@link #setPathVars}, handlers write the response,
 * the engine frames it to the wire and resets the exchange for keep-alive
 * reuse. That phase partition, not a lock, is what lets one object cross
 * both worlds.</p>
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

    /**
     * Sets the maximum allowed request body size in bytes for this exchange.
     * Requests exceeding this limit receive a 413 Payload Too Large
     * response. Default is 10 MiB.
     *
     * <p>Engines initialize this from {@link HttpServerConfig#maxBodySize()}
     * when they create the exchange (the honor tiers on
     * {@link HttpEngine#start}); a filter may narrow it further for one
     * request.
     *
     * @return this context for chaining
     */
    HttpContext setMaxBodySize(long maxBodySize);
}
