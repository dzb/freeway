package com.jujin.freeway.http;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.sse.SseEmitter;
import com.jujin.freeway.http.sse.SseEvent;

class SseEmitterTest {

    @Test
    void sendsBasicDataEvent() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos);
        emitter.send("hello");
        emitter.close();

        String out = baos.toString(StandardCharsets.UTF_8);
        assertEquals("data: hello\n\n", out);
    }

    @Test
    void sendsEventWithAllFields() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos);
        emitter.send(new SseEvent("payload", "evt-1", "update", 3000L));
        emitter.close();

        String out = baos.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("id: evt-1\n"));
        assertTrue(out.contains("event: update\n"));
        assertTrue(out.contains("retry: 3000\n"));
        assertTrue(out.contains("data: payload\n"));
    }

    @Test
    void splitsMultilineData() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos);
        emitter.send("line1\nline2\nline3");
        emitter.close();

        String out = baos.toString(StandardCharsets.UTF_8);
        assertEquals("data: line1\ndata: line2\ndata: line3\n\n", out);
    }

    @Test
    void multipleEvents() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos);
        emitter.send("first");
        emitter.send("second");
        emitter.close();

        String out = baos.toString(StandardCharsets.UTF_8);
        assertEquals("data: first\n\ndata: second\n\n", out);
    }

    @Test
    void doubleCloseIsIdempotent() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos);
        emitter.send("ok");
        emitter.close();
        emitter.close(); // should not throw
        assertTrue(true);
    }

    @Test
    void sendAfterCloseIsNoop() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos);
        emitter.close();
        emitter.send("should be ignored");
        assertEquals(0, baos.size());
    }

    @Test
    void nullDataThrows() {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos);
        assertThrows(NullPointerException.class, () ->
            emitter.send((SseEvent) null)
        );
        assertThrows(NullPointerException.class, () -> new SseEvent(null));
    }
}
