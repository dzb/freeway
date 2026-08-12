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
final class HttpServerHandleDefault implements HttpServerHandle {

    private static final Logger LOG = LoggerFactory.getLogger(HttpServerHandleDefault.class);

    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private final Duration shutdownGrace;
    private final AtomicBoolean finished;
    private final ConnectionRegistry registry;
    private final String host;
    private final int port;

    public HttpServerHandleDefault(ServerSocket serverSocket, Thread acceptor,
                 Duration shutdownGrace, AtomicBoolean finished,
                 ConnectionRegistry registry,
                 String host, int port) {
        this.serverSocket = serverSocket;
        this.acceptor = acceptor;
        this.shutdownGrace = shutdownGrace;
        this.finished = finished;
        this.registry = registry;
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
        registry.beginShutdown();
        try { serverSocket.close(); } catch (IOException ignored) {}
        // Announce shutdown to HTTP/2 peers (GOAWAY) before the grace window
        // so they stop opening new streams and can retry elsewhere.
        registry.preCloseAll();
        try { acceptor.join(1000); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // Grace window: let in-flight requests finish; sessions close their
        // connection as soon as they observe isStopping().
        long graceMillis = shutdownGrace.toMillis();
        if (graceMillis > 0 && registry.activeCount() > 0) {
            long deadline = System.currentTimeMillis() + graceMillis;
            while (registry.activeCount() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        registry.closeAll();
        LOG.info("Freeway HTTP engine stopped");
    }
}
