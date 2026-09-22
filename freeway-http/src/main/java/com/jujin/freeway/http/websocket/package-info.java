/**
 * The application-facing WebSocket vocabulary: {@code WebSocketGroup} and
 * {@code WebSocketRoute} declare endpoints (prefixes expanded with
 * {@code PathJoiner}), {@code WebSocketListener} observes them, and
 * {@code WebSocketSession} is the upgraded session's face — sharing
 * {@code RequestView} (read-only) and {@code ExchangeMeta} with HTTP
 * exchanges, so one helper serves both. {@code WebSocketIndex} is the
 * compiled lookup {@code HttpPipeline} builds, {@code HttpModule} binds
 * and {@code HttpServer} consults; {@code WebSocketMatch} is what the
 * seam's {@code websocket()} handshake returns. Frame wiring lives behind
 * the seam in {@code engine.ws}.
 */
package com.jujin.freeway.http.websocket;
