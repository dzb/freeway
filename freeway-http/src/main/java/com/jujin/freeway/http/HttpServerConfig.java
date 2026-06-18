package com.jujin.freeway.http;

public record HttpServerConfig(String host, int port, int backlog, int shutdownGraceSeconds) {
    public HttpServerConfig {
        host = host == null || host.isBlank() ? "127.0.0.1" : host;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
        }
        if (backlog < 0) {
            throw new IllegalArgumentException("backlog must be >= 0: " + backlog);
        }
        if (shutdownGraceSeconds < 0) {
            throw new IllegalArgumentException(
                "shutdownGraceSeconds must be >= 0: " + shutdownGraceSeconds);
        }
    }
}
