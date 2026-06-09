package com.jujin.freeway.http;

import java.util.Objects;

/**
 * A Server-Sent Event (SSE) data object.
 *
 * @param data  the event data (required)
 * @param id    optional event ID for Last-Event-ID tracking
 * @param event optional event type name
 * @param retry optional reconnection time in milliseconds
 */
public record SseEvent(String data, String id, String event, Long retry) {
    public SseEvent {
        Objects.requireNonNull(data, "data");
    }

    public SseEvent(String data) {
        this(data, null, null, null);
    }
}
