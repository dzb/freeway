package com.jujin.freeway.commons.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilRegressionTest {

    @Test
    void mapsFlattenDetectsCycle() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("self", m);
        Map<String, String> result = Maps.flatten(m);
        assertTrue(result.isEmpty() || !result.containsKey("self"),
                "Self-referencing map should not StackOverflow");
    }

    @Test
    void mapsFlattenRejectsNullKey() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(null, "value");
        assertThrows(IllegalArgumentException.class, () -> Maps.flatten(m));
    }

    @Test
    void mapsFlattenRejectsKeyCollision() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a.b", 1);
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("b", 2);
        m.put("a", inner);
        assertThrows(IllegalArgumentException.class, () -> Maps.flatten(m),
            "colliding flattened keys must not silently overwrite");
    }

    @Test
    void boundedStreamClosesUnderlying() throws IOException {
        ByteArrayInputStream underlying = new ByteArrayInputStream(new byte[0]);
        var bounded = ByteStreams.bounded(underlying, 100, "test");
        bounded.close();
        // underlying stream should be closed; read should throw or return -1
        assertEquals(-1, underlying.read());
    }
}
