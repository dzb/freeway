package com.jujin.freeway.http;

import java.io.IOException;

/**
 * Starts an HTTP server on the given configuration and dispatches
 * incoming exchanges to the provided handler.
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
     * <p>Every engine answers this: it is what {@link WebServer#secure()} — and
     * behind it the registry scheme an instance publishes — is derived from.
     */
    boolean secure();
}
