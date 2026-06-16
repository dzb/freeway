package com.jujin.freeway.http.event;
import com.jujin.freeway.http.websocket.WebSocketSession;

/** Published when a new WebSocket connection is established. */
public record WebSocketOpenedEvent(WebSocketSession session, String method, String path) {}
