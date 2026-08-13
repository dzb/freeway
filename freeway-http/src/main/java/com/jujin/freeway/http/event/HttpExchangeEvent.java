package com.jujin.freeway.http.event;

/** Published on the EventBus after each HTTP request completes (including error responses). */
public record HttpExchangeEvent(String method, String path, int statusCode, long elapsedMs) {}
