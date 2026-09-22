package com.jujin.freeway.http;

import java.io.IOException;

/**
 * Starts an HTTP server on the given configuration and dispatches
 * incoming exchanges to the provided handler.
 *
 * <p><b>Honor contract.</b> {@code config} is the server's transport
 * declaration and reaches every engine unchanged; each engine maps it onto
 * its own facilities in three tiers:
 * <ul>
 *   <li><b>Must honor</b> — {@code host}, {@code port}, {@code backlog},
 *       {@code shutdownGrace}: the server's address and lifecycle, plus
 *       {@code readTimeout}, {@code writeTimeout}, {@code receiveBufferSize},
 *       {@code sendBufferSize} and {@code maxConnections} wherever the engine
 *       has a counterpart (the built-in engine has one for all five).</li>
 *   <li><b>Per-exchange policy</b> — {@code maxBodySize} and
 *       {@code compression} are applied by the engine to every exchange it
 *       creates (the built-in engine pushes both into its context at session
 *       start) and enforced with the shared helpers
 *       ({@link AbstractHttpContext#readBody}, {@link Compression}) so body
 *       accounting and q-value negotiation match across engines.</li>
 *   <li><b>Engine-private</b> — {@code h2ResetBurstLimit} / {@code
 *       h2ResetWindow} guard the built-in HTTP/2 implementation; an engine
 *       without that guard must report a non-default setting at startup
 *       rather than ignore it.</li>
 * </ul>
 * A field the engine cannot apply must say so at startup — never silently.
 */
public interface HttpEngine {

    /**
     * Starts the HTTP server using the supplied configuration and returns
     * a handle representing the running server.
     *
     * @param config  the server address, port, and other configuration
     * @param handler receives all incoming HTTP exchanges
     * @return a handle that exposes the bound host/port and can shut down the server
     * @throws IOException if the server fails to bind or start
     */
    HttpServerHandle start(HttpServerConfig config, ExchangeHandler handler) throws IOException;

    /**
     * Whether this engine terminates TLS on the sockets it accepts — the one
     * transport verdict a server can report, because it is answered by the
     * thing that owns the keys. The HTTP module's configuration cannot answer
     * it: an adapter engine selected with {@code primary()} may terminate TLS
     * from its own settings while the built-in {@code freeway.http.ssl.*} keys
     * stay unset, or the reverse.
     *
     * <p>Every engine answers this: it is what {@link HttpServer#secure()} — and
     * behind it the registry scheme an instance publishes — is derived from.
     */
    boolean secure();
}
