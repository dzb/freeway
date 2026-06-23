package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.HttpServerHandle;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifecycle handle for the built-in HTTP engine.
 */
public final class ServerHandle implements HttpServerHandle {

    private static final Logger LOG = LoggerFactory.getLogger(ServerHandle.class);

    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private final Duration shutdownGrace;
    private final AtomicBoolean finished;
    private final String host;
    private final int port;

    public ServerHandle(ServerSocket serverSocket, Thread acceptor,
                 Duration shutdownGrace, AtomicBoolean finished,
                 String host, int port) {
        this.serverSocket = serverSocket;
        this.acceptor = acceptor;
        this.shutdownGrace = shutdownGrace;
        this.finished = finished;
        this.host = host;
        this.port = port;
    }

    @Override
    public String host() { return host; }

    @Override
    public int port() { return port; }

    @Override
    public void close() {
        finished.set(true);
        try { serverSocket.close(); } catch (IOException ignored) {}
        try { acceptor.join(1000); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        LOG.info("Freeway HTTP engine stopped");
    }
}
