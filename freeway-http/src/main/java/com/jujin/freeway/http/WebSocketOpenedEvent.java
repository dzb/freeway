package com.jujin.freeway.http;

/** Published when a new WebSocket connection is established. */
public record WebSocketOpenedEvent(WebSocketSession session, String method, String path) {}
