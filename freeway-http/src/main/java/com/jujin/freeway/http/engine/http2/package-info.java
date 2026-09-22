/**
 * HTTP/2 frame codec and connection state — part of the built-in engine,
 * <strong>no stability promise</strong>.
 *
 * <p>Every type here serves the engine's protocol bridges: frame classes
 * with their serializer, validator and writer, connection/stream state, and
 * HPACK in {@code engine.http2.hpack} — assembled by {@code engine.Http2Session}
 * (ALPN h2) and {@code engine.Http1xSession} (h2c upgrade), with
 * {@code Http2ResponseWriter} framing responses onto the wire. Types that
 * cross no package boundary stay package-private; the {@code public}
 * remainder assembles with {@code engine} and must not be depended on
 * outside this module.</p>
 */
package com.jujin.freeway.http.engine.http2;
