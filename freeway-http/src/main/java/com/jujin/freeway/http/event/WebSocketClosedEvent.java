package com.jujin.freeway.http.event;
import com.jujin.freeway.http.websocket.WebSocketSession;

/** Published when a WebSocket connection closes. */
public record WebSocketClosedEvent(WebSocketSession session, int statusCode, String reason) {}
