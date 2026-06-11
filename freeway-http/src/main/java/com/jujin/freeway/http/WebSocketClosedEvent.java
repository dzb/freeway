package com.jujin.freeway.http;

/** Published when a WebSocket connection closes. */
public record WebSocketClosedEvent(WebSocketSession session, int statusCode, String reason) {}
