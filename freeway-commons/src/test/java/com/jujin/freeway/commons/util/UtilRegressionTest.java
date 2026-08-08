package com.jujin.freeway.commons.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void mapsFlattenSharedSubtreeAppearsUnderBothKeys() {
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("x", 1);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("a", shared);
        root.put("b", shared);

        Map<String, String> result = Maps.flatten(root);

        // Same instance under two sibling keys: both paths must be flattened,
        // not silently dropped by the cycle guard.
        assertEquals("1", result.get("a.x"));
        assertEquals("1", result.get("b.x"));
        assertEquals(2, result.size());
    }

    @Test
    void mapsFlattenSharedListAppearsUnderBothKeys() {
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("x", 1);
        List<Object> list = new ArrayList<>();
        list.add(shared);
        list.add(shared);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("a", list);
        root.put("b", list);

        Map<String, String> result = Maps.flatten(root);

        assertEquals("1", result.get("a.0.x"));
        assertEquals("1", result.get("a.1.x"));
        assertEquals("1", result.get("b.0.x"));
        assertEquals("1", result.get("b.1.x"));
        assertEquals(4, result.size());
    }

    @Test
    void mapsFlattenDeepCycleStillCut() {
        Map<String, Object> a = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        a.put("b", b);
        b.put("a", a);

        Map<String, String> result = Maps.flatten(a);

        // b.a revisits ancestor a — must be cut, not StackOverflow.
        assertTrue(result.isEmpty(), "ancestor cycle must be cut");
    }

    @Test
    void boundedStreamClosesUnderlying() throws IOException {
        ByteArrayInputStream underlying = new ByteArrayInputStream(new byte[0]);
        var bounded = ByteStreams.bounded(underlying, 100, "test");
        bounded.close();
        // underlying stream should be closed; read should throw or return -1
        assertEquals(-1, underlying.read());
    }

    @Test
    void boundedStreamExactMaxReadsToEof() throws IOException {
        byte[] data = new byte[10];
        Arrays.fill(data, (byte) 'x');
        InputStream bounded = ByteStreams.bounded(new ByteArrayInputStream(data), 10, "test");

        assertArrayEquals(data, ByteStreams.readBytes(bounded, 10, "test"));
        // Content ended exactly at the cap: reading past it is EOF, not an error.
        assertEquals(-1, bounded.read(), "read past exact cap must signal EOF");
        assertEquals(-1, bounded.read(new byte[4], 0, 4), "bulk read past exact cap must signal EOF");
    }

    @Test
    void boundedStreamOverMaxThrows() throws IOException {
        byte[] data = new byte[11];
        Arrays.fill(data, (byte) 'y');
        InputStream bounded = ByteStreams.bounded(new ByteArrayInputStream(data), 10, "test");

        byte[] chunk = new byte[16];
        assertEquals(10, bounded.read(chunk, 0, chunk.length));
        IOException ex = assertThrows(IOException.class,
            () -> bounded.read(chunk, 0, chunk.length));
        assertTrue(ex.getMessage().contains("exceeds"),
            "overrun must report the too-large error");

        // Single-byte reads detect the overrun the same way.
        InputStream single = ByteStreams.bounded(new ByteArrayInputStream(data), 10, "test");
        for (int i = 0; i < 10; i++) {
            assertTrue(single.read() >= 0);
        }
        assertThrows(IOException.class, single::read);
    }

    @Test
    void readBytesExactMaxReturnsFullContent() throws IOException {
        byte[] data = new byte[10];
        Arrays.fill(data, (byte) 'z');
        assertArrayEquals(data, ByteStreams.readBytes(new ByteArrayInputStream(data), 10, "test"));
    }
}
