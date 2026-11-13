package com.jujin.freeway.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestViewSupportTest {

    @Test
    void headerNamesAreCanonicalizedForEveryRequestView() {
        RequestView view = new RequestViewSupport(
            "GET", "/", Map.of(), Map.of("Content-Type", List.of("text/plain")));

        assertEquals(List.of("text/plain"), view.headers().get("content-type"));
        assertEquals(Optional.of("text/plain"), view.header("CONTENT-TYPE"));
        assertEquals(List.of("text/plain"), view.headers("content-type"));
    }

    @Test
    void pathVarsAreReplacedNotMerged() {
        RequestViewSupport view = new RequestViewSupport("GET", "/", Map.of(), Map.of());
        view.setPathVars(Map.of("id", "1"));
        view.setPathVars(Map.of("name", "two"));

        assertEquals(Map.of("name", "two"), view.pathVars());
        assertEquals(Optional.empty(), view.pathVar("id"));
        assertTrue(view.pathVar("name").isPresent());
    }
}
