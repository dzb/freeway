package com.jujin.freeway.http.engine.ws;

import com.jujin.freeway.http.websocket.WebSocketListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
