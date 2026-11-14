package com.jujin.freeway.http;

import java.util.Map;

/**
 * One HTTP exchange: the exchange metadata, the request face, and the
 * response face, all on a single object handed to application handlers.
 *
 * <p>A handler receives an {@code HttpExchange} and can use every request,
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
 * and the engine frames it to the wire. The exchange is created per request,
 * so there is no reset phase and nothing from this request can reach the
 * next one. That phase partition, not a lock, is what lets one object cross
 * both worlds.</p>
 */
public interface HttpExchange extends ExchangeMeta, HttpRequest, HttpResponse {

    /**
     * Framework-owned attribute key holding the declared route template for
     * this exchange. Set by the server and by the built-in short-circuiting
     * filters; handlers should read {@link #routePattern()} rather than the
     * attribute directly.
     */
    String ROUTE_ATTRIBUTE = "freeway.http.route";

    /**
     * The declared route template associated with this exchange, never the raw
     * request path: {@code /users/{id}} rather than {@code /users/42}.
     *
     * <p><b>When it is available.</b> For a matched route the server
     * pre-matches before the filter chain, so filters and tracing already see
     * the template; dispatch remains the authoritative match and may replace
     * the value with the bounded category it actually selected:
     * {@code static}, {@code spa}, {@code health}, {@code cors-preflight} or
     * {@code unmatched}. Before any match it is {@code null}.</p>
     *
     * <p>It is exposed for diagnostics, access control <em>logging</em> and
     * metrics labels; do not use it for authorization decisions.</p>
     */
    default String routePattern() {
        Object value = attribute(ROUTE_ATTRIBUTE);
        return value instanceof String pattern ? pattern : null;
    }

    /**
     * Sets the path variables extracted by the route match. This is the
     * routing seam and lives here (rather than on the read-only
     * {@link HttpRequest} face) because it mutates exchange state.
     *
     * @return this context for chaining
     */
    HttpExchange setPathVars(Map<String, String> vars);

    /**
     * Sets the maximum allowed request body size in bytes for this exchange.
     * Requests exceeding this limit receive a 413 Payload Too Large
     * response. Default is 10 MiB.
     *
     * <p>An engine gives the context the base value from
     * {@link HttpServerConfig#maxBodySize()} when it creates the exchange (the
     * honor tiers on {@link HttpEngine#start}); a filter may change it, and the
     * change applies to <b>that request only</b> — raising the limit for an
     * upload route must not leave every later request of a keep-alive
     * connection unbounded. The exchange support is created per request, so
     * the limit starts fresh with it; there is no base/reset pair.
     * Narrowing works on every engine. Raising it works
     * where the body is read by the framework's own limiter
     * ({@code http.internal.LimitedInputStream}, which reads this value per
     * read) — an adapter that enforces its own native ceiling before that
     * (Undertow's {@code MAX_ENTITY_SIZE}, fixed at server start) still
     * refuses past it.
     *
     * @return this context for chaining
     */
    HttpExchange setMaxBodySize(long maxBodySize);
}
