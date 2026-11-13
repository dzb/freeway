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
        assertTrue(RequestHead.class.isAssignableFrom(RequestView.class),
            "RequestView extends RequestHead");
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
            List.of("header", "headers", "pathVar", "pathVars",
                "queryParam", "queryParams"),
            declared,
            "RequestView is exactly these read accessors — add to it consciously, in both halves");
        assertEquals(
            List.of("headers", "method", "path", "queryParams"),
            Arrays.stream(RequestHead.class.getDeclaredMethods())
                .map(method -> method.getName()).sorted().toList(),
            "RequestHead is the four request facts every transport and view owns");
    }
}
