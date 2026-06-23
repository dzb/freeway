package com.jujin.freeway.http.event;

/** Published after the HTTP server starts listening. */
public record HttpServerStartedEvent(String host, int port) {}
