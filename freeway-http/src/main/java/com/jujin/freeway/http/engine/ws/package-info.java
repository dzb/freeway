/**
 * WebSocket frame codec and the built-in engine's session implementation —
 * part of {@code engine}, <strong>no stability promise</strong>. This
 * package wires frames for {@code engine.WebSocketUpgrade} (frame model,
 * op codes, close codes, read loop); the application-facing WebSocket
 * vocabulary — groups, routes, listener and session faces — is in
 * {@code com.jujin.freeway.http.websocket} behind the exchange seam.
 */
package com.jujin.freeway.http.engine.ws;
