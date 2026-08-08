package com.jujin.freeway.http.engine.ws;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jujin.freeway.http.websocket.WebSocketListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketReadLoopTest {

    @Test
    void utf8CodePointSplitAcrossFragmentsIsDelivered() throws Exception {
        byte[] first = maskedFrame(0x01, false, new byte[]{(byte) 0xE2, (byte) 0x82});
        byte[] last = maskedFrame(0x00, true, new byte[]{(byte) 0xAC});
        byte[] wire = concat(first, last);

        var in = new ByteArrayInputStream(wire);
        var out = new ByteArrayOutputStream();
        var session = new WebSocketSessionImpl(
            "GET", "/", null, Map.of(), in, out, Map.of());
        var texts = new ArrayList<String>();
        WebSocket.readLoop(in, out, session, new WebSocketListener() {
            @Override
            public void onText(String text) {
                texts.add(text);
            }
        });

        assertEquals(List.of("€"), texts,
            "A UTF-8 code point split across fragments must be reassembled");
    }

    @Test
    void invalidUtf8InFragmentedMessageClosesWith1007() throws Exception {
        byte[] first = maskedFrame(0x01, false, new byte[]{(byte) 0xE2});
        byte[] last = maskedFrame(0x00, true, new byte[]{(byte) 0x28});
        byte[] wire = concat(first, last);

        var in = new ByteArrayInputStream(wire);
        var out = new ByteArrayOutputStream();
        var session = new WebSocketSessionImpl(
            "GET", "/", null, Map.of(), in, out, Map.of());
        WebSocket.readLoop(in, out, session, WebSocketListener.NOOP);

        assertFalse(session.isOpen(),
            "Invalid reassembled UTF-8 must close the session");
    }

    @Test
    void serverCloseWakesBlockingReadLoop() throws Exception {
        // A server-initiated close while the read loop is parked on a live
        // (blocking) stream must wake the loop: close() closes the input,
        // the blocked read throws, and readLoop returns instead of hanging
        // until the peer responds.
        var blockingIn = new InputStream() {
            volatile boolean closed;
            @Override
            public int read() throws IOException {
                while (!closed) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
                throw new IOException("stream closed");
            }
            @Override
            public void close() {
                closed = true;
            }
        };
        var out = new ByteArrayOutputStream();
        var session = new WebSocketSessionImpl(
            "GET", "/", null, Map.of(), blockingIn, out, Map.of());

        var loopDone = new AtomicBoolean();
        Thread loop = Thread.startVirtualThread(() -> {
            WebSocket.readLoop(blockingIn, out, session, WebSocketListener.NOOP);
            loopDone.set(true);
        });

        // Give the loop time to park on read(), then close from "another thread".
        Thread.sleep(100);
        session.close(1000, "server shutdown");
        loop.join(3000);
        assertTrue(loopDone.get(),
            "readLoop must return when the session is closed, not hang on the blocked read");
    }

    private static byte[] maskedFrame(int opcode, boolean fin, byte[] payload) {
        byte[] frame = new byte[6 + payload.length];
        frame[0] = (byte) ((fin ? 0x80 : 0) | opcode);
        frame[1] = (byte) (0x80 | payload.length);
        byte[] key = {1, 2, 3, 4};
        System.arraycopy(key, 0, frame, 2, 4);
        for (int i = 0; i < payload.length; i++) {
            frame[6 + i] = (byte) (payload[i] ^ key[i % 4]);
        }
        return frame;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
