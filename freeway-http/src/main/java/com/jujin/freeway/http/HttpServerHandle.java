package com.jujin.freeway.http;

/**
 * A handle to a running HTTP server instance that exposes the bound
 * host and port, and can shut down the server.
 */
public interface HttpServerHandle extends AutoCloseable {
    /** Returns the host address the server is bound to. */
    String host();

    /** Returns the TCP port the server is listening on. */
    int port();

    /** Stops the server and releases all associated resources. */
    @Override
    void close();
}
