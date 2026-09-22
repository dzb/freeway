/**
 * Server-sent events — application-facing: {@code SseEmitter} wraps the
 * exchange's output stream (obtained through {@code ctx.sse()}) and writes
 * SSE-formatted data with a heartbeat so idle intermediaries do not tear
 * the stream down; long-lived streams can be pumped from a JDK
 * {@link java.util.flow.Publisher}. {@code SseEvent} is the event value
 * (data, id, event, retry).
 */
package com.jujin.freeway.http.sse;
