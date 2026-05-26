package com.jujin.freeway.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JdkHttpEngine implements HttpEngine {
    private static final Logger LOG = LoggerFactory.getLogger(JdkHttpEngine.class);
    private final JsonCodec jsonCodec;

    public JdkHttpEngine(JsonCodec jsonCodec) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    }

    @Override
    public WebServerHandle start(WebServerConfig config, WebRequestHandler handler) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(handler, "handler");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), config.backlog());
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            RequestContext requestContext = createRequestContext(exchange);
            JdkHttpServerContext ctx = new JdkHttpServerContext(exchange, jsonCodec, requestContext);
            ctx.headerSet("X-Request-Id", requestContext.correlationId());
            try {
                handler.handle(ctx);
            } catch (Exception ex) {
                if (ex instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException("Web request handler failed", ex);
            }
        });
        server.start();
        LOG.info("Freeway JDK web engine started on {}:{}", config.host(), server.getAddress().getPort());
        return new HttpServerHandle(server, executor, config.shutdownGraceSeconds(), config.host());
    }

    private static RequestContext createRequestContext(HttpExchange exchange) {
        String correlationId = HttpContext.blankToNull(exchange.getRequestHeaders().getFirst("X-Request-Id"));
        return correlationId != null ? RequestContext.create(correlationId) : RequestContext.create();
    }

    private record HttpServerHandle(
        HttpServer server,
        ExecutorService executor,
        int shutdownGraceSeconds,
        String host
    ) implements WebServerHandle {
        @Override
        public int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            try {
                server.stop(shutdownGraceSeconds);
            } finally {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                LOG.info("Freeway JDK web engine stopped");
            }
        }
    }
}
