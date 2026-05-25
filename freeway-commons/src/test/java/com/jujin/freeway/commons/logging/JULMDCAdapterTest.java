package com.jujin.freeway2.commons.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JULMDCAdapterTest {
    private final JULMDCAdapter adapter = new JULMDCAdapter();

    @AfterEach
    void cleanup() {
        adapter.clear();
    }

    @Test
    void putAndGet() {
        adapter.put("requestId", "abc123");

        assertEquals("abc123", adapter.get("requestId"));
    }

    @Test
    void remove() {
        adapter.put("key", "value");
        adapter.remove("key");

        assertNull(adapter.get("key"));
    }

    @Test
    void clearClearsBothContextAndDequeMap() {
        adapter.put("key", "value");
        adapter.pushByKey("stack", "frame1");

        adapter.clear();

        assertNull(adapter.get("key"));
        assertNull(adapter.getCopyOfDequeByKey("stack"));
        assertNull(adapter.getCopyOfContextMap());
    }

    @Test
    void pushAndPopByKey() {
        adapter.pushByKey("stack", "a");
        adapter.pushByKey("stack", "b");

        assertEquals("b", adapter.popByKey("stack"));
        assertEquals("a", adapter.popByKey("stack"));
        assertNull(adapter.popByKey("stack"));
    }

    @Test
    void popByKeyOnMissingKeyReturnsNull() {
        assertNull(adapter.popByKey("nonexistent"));
    }

    @Test
    void clearDequeByKey() {
        adapter.pushByKey("stack", "a");
        adapter.pushByKey("stack", "b");

        adapter.clearDequeByKey("stack");

        assertNull(adapter.getCopyOfDequeByKey("stack"));
        assertNull(adapter.popByKey("stack"));
    }

    @Test
    void clearDequeByKeyOnMissingKeyIsNoop() {
        adapter.clearDequeByKey("nonexistent");
    }

    @Test
    void getCopyOfContextMapReturnsNullWhenEmpty() {
        assertNull(adapter.getCopyOfContextMap());
    }

    @Test
    void getCopyOfContextMapReturnsSnapshot() {
        adapter.put("a", "1");
        adapter.put("b", "2");

        Map<String, String> snapshot = adapter.getCopyOfContextMap();
        assertNotNull(snapshot);
        assertEquals(Map.of("a", "1", "b", "2"), snapshot);

        adapter.remove("a");
        assertEquals(Map.of("a", "1", "b", "2"), snapshot);
    }

    @Test
    void setContextMapReplacesAllEntries() {
        adapter.put("old", "value");

        adapter.setContextMap(Map.of("new", "data"));

        assertNull(adapter.get("old"));
        assertEquals("data", adapter.get("new"));
    }

    @Test
    void getCopyOfDequeByKeyReturnsNullWhenEmpty() {
        assertNull(adapter.getCopyOfDequeByKey("nonexistent"));
    }

    @Test
    void getCopyOfDequeByKeyPreservesOrder() {
        adapter.pushByKey("stack", "first");
        adapter.pushByKey("stack", "second");

        var snapshot = adapter.getCopyOfDequeByKey("stack");
        assertNotNull(snapshot);
        assertEquals(2, snapshot.size());
        assertEquals("second", snapshot.pop());
        assertEquals("first", snapshot.pop());
        assertEquals(0, snapshot.size());
    }

    @Test
    void threadsHaveIsolatedContext() throws Exception {
        adapter.put("key", "main");

        java.util.concurrent.atomic.AtomicReference<String> otherValue = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            adapter.put("key", "other");
            otherValue.set(adapter.get("key"));
        });
        t.start();
        t.join();

        assertEquals("main", adapter.get("key"));
        assertEquals("other", otherValue.get());
    }
}
