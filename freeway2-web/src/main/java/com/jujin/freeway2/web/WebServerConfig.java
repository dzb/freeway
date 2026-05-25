package com.jujin.freeway2.web;

public record WebServerConfig(String host, int port, int backlog, int shutdownGraceSeconds) {
    public WebServerConfig {
        host = host == null || host.isBlank() ? "127.0.0.1" : host;
        port = Math.max(0, Math.min(65535, port));
        backlog = Math.max(0, backlog);
        shutdownGraceSeconds = Math.max(0, shutdownGraceSeconds);
    }
}
