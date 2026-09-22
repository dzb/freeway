/**
 * The built-in {@link com.jujin.freeway.http.HttpEngine} implementation —
 * <strong>no stability promise</strong> across releases.
 *
 * <p>Placement rule: everything only the built-in engine needs lives here —
 * its wiring and lifecycle ({@code FreewayHttpEngine},
 * {@code HttpServerHandleImpl}, {@code SslReloader}: TLS hot reload is
 * engine-self-driven), the pooled exchange context ({@code HttpContextImpl}),
 * the HTTP/1 stack (parser, sessions, connection registry, streams,
 * metrics) and the protocol bridges that speak both this package and a
 * codec sub-package: {@code Http1xSession} (including the h2c upgrade),
 * {@code Http2Session} (ALPN h2) and {@code WebSocketUpgrade}. Pure frame
 * codecs live in {@code engine.http2} ({@code engine.http2.hpack} for
 * HPACK) and {@code engine.ws} — splitting a whole protocol further would
 * only turn the session stack's package-private collaborators public, since
 * Java has no sub-package visibility.</p>
 *
 * <p>The {@code public} types here are assembly surface, not API:
 * {@code HttpResponseWriter} is the h1/h2 writer seam that
 * {@code engine.http2.Http2ResponseWriter} implements across packages;
 * {@code ResponseFraming} and {@code HttpContextImpl} are mirrored by the
 * ext adapters and benchmarks; {@code FreewayHttpEngine} is what the root
 * {@code HttpModule} binds.</p>
 */
package com.jujin.freeway.http.engine;
