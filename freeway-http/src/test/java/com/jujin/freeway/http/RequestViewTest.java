package com.jujin.freeway.http;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.jujin.freeway.http.websocket.WebSocketSession;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestViewTest {

    @Test
    void httpExchangeAndWebSocketSessionShareTheReadOnlyFace() {
        assertTrue(RequestView.class.isAssignableFrom(HttpRequest.class),
            "HttpRequest extends RequestView");
        assertTrue(RequestView.class.isAssignableFrom(WebSocketSession.class),
            "WebSocketSession extends RequestView");
    }

    @Test
    void theSharedFaceStaysExactlyTheReadAccessors() {
        // The face is one owner of one answer: growing it means every HTTP
        // exchange and every session inherits the addition — a deliberate
        // step, not a side effect of one of them needing a method.
        List<String> declared = Arrays.stream(RequestView.class.getDeclaredMethods())
            .map(method -> method.getName())
            .sorted()
            .collect(Collectors.toList());

        assertEquals(
            List.of("header", "headers", "headers", "method", "path",
                "pathVar", "pathVars", "queryParam", "queryParams", "queryParams"),
            declared,
            "RequestView is exactly these read accessors — add to it consciously, in both halves");
    }
}
