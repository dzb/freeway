package com.jujin.freeway.http;

/** Published after the HTTP server starts listening. */
public record HttpServerStartedEvent(String host, int port) {}
