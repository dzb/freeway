package com.jujin.freeway.http.sse;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    void emitsHeartbeatComment() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos, 50);
        Thread.sleep(180);
        emitter.close();

        String out = baos.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains(": ping\n\n"),
            "a quiet stream must still emit heartbeat comments");
    }

    @Test
    void heartbeatDoesNotSplitEvents() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos, 10);
        emitter.send("first");
        emitter.send("second");
        emitter.close();

        String out = baos.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("data: first\n\ndata: second\n\n"),
            "heartbeat comments must never interleave into an event");
    }

    @Test
    void heartbeatDisabledWithZeroInterval() throws Exception {
        var baos = new ByteArrayOutputStream();
        var emitter = new SseEmitter(baos, 0);
        Thread.sleep(100);
        emitter.send("data");
        emitter.close();

        String out = baos.toString(StandardCharsets.UTF_8);
        assertEquals("data: data\n\n", out,
            "zero heartbeat interval must disable the heartbeat entirely");
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

    @Test
    void writeFailureClosesEmitter() throws Exception {
        // A broken stream must flip the emitter closed: the IOException is
        // rethrown to the caller, and subsequent sends become no-ops instead
        // of hammering the dead stream.
        var counting = new OutputStream() {
            int writes;
            @Override
            public void write(int b) throws IOException {
                throw new IOException("connection reset");
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                writes++;
                throw new IOException("connection reset");
            }
            @Override
            public void flush() throws IOException {
                throw new IOException("connection reset");
            }
        };
        var emitter = new SseEmitter(counting);

        assertThrows(IOException.class, () -> emitter.send("boom"));
        // Second send must not touch the stream at all (closed short-circuit).
        emitter.send("ignored");
        assertEquals(1, counting.writes,
            "after a write failure the emitter must stop writing to the stream");
    }

    @Test
    void rejectsLineInjectionInEventMetadata() {
        var emitter = new SseEmitter(new ByteArrayOutputStream(), 0);
        assertThrows(IllegalArgumentException.class,
            () -> emitter.send(new SseEvent("data", "bad\nid", null, null)));
    }
}
