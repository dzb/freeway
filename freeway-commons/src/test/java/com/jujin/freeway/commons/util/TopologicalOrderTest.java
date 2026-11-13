package com.jujin.freeway.commons.util;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologicalOrderTest {

    @Test
    void ordersByEdgesAndUsesInputOrderForTies() {
        var result = TopologicalOrder.sort(
            List.of("notify", "index", "cache", "unconstrained"),
            Map.of(
                "cache", Set.of("index", "notify"),
                "index", Set.of("notify")),
            String::compareTo);

        assertEquals(List.of("cache", "index", "notify", "unconstrained"),
            result.ordered());
        assertTrue(result.complete());
    }

    @Test
    void reportsTheUnplaceableRemainderOnACycle() {
        var result = TopologicalOrder.sort(
            List.of("a", "b", "free"),
            Map.of("a", Set.of("b"), "b", Set.of("a")),
            String::compareTo);

        assertFalse(result.complete());
        assertEquals(List.of("free"), result.ordered());
        assertEquals(List.of("a", "b"), result.remaining());
    }

    @Test
    void rejectsEdgesToUnknownNodes() {
        assertThrows(IllegalArgumentException.class, () -> TopologicalOrder.sort(
            List.of("a"), Map.of("a", Set.of("missing")), String::compareTo));
    }

    @Test
    void rejectsDuplicateNodes() {
        assertThrows(IllegalArgumentException.class, () -> TopologicalOrder.sort(
            List.of("a", "a"), Map.of(), String::compareTo));
    }
}
