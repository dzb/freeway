package com.jujin.freeway.http;

import java.io.IOException;

/**
 * Starts an HTTP server on the given configuration and dispatches
 * incoming requests to the provided handler.
 */
public interface HttpEngine {

    /**
     * Starts the HTTP server using the supplied configuration and returns
     * a handle representing the running server.
     *
     * @param config  the server address, port, and other configuration
     * @param handler receives all incoming HTTP requests
     * @return a handle that exposes the bound host/port and can shut down the server
     * @throws IOException if the server fails to bind or start
     */
    HttpServerHandle start(HttpServerConfig config, ExchangeHandler handler) throws IOException;
}
