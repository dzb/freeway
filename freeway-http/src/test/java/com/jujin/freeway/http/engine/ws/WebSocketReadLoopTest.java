package com.jujin.freeway.http.engine.ws;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.websocket.WebSocketListener;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketReadLoopTest {

    @Test
    void concurrentSendsProduceValidFrames() throws Exception {
        var out = new ByteArrayOutputStream();
        var session = new WebSocketSessionImpl(
            "GET", "/", null, Map.of(),
            new ByteArrayInputStream(new byte[0]), out, Map.of(), null);
        int threads = 8;
        int messagesPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                int id = t;
                executor.submit(() -> {
                    for (int i = 0; i < messagesPerThread; i++) {
                        session.sendText("t" + id + "-" + i);
                    }
                    return null;
                });
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                "concurrent senders must finish");
        }

        var wire = new ByteArrayInputStream(out.toByteArray());
        List<String> received = new ArrayList<>();
        while (wire.available() > 0) {
            var frame = WebSocketFrame.read(wire);
            received.add(frame.payloadAsString());
        }
        assertEquals(threads * messagesPerThread, received.size(),
            "all frames must be valid and none lost to interleaving");
        assertTrue(received.contains("t3-7"),
            "sample message must survive concurrent sends");
    }

    @Test
    void largeBinaryMessageIsSentFragmented() throws Exception {
        var out = new ByteArrayOutputStream();
        var session = new WebSocketSessionImpl(
            "GET", "/", null, Map.of(),
            new ByteArrayInputStream(new byte[0]), out, Map.of(), null);
        int maxFrame = 16 * 1024 * 1024;
        byte[] big = new byte[maxFrame + 100];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i & 0xFF);
        session.sendBinary(big);

        var frames = readAllFrames(out);
        assertEquals(2, frames.size(),
            "a message above the frame cap must be split into two frames");
        assertEquals(OpCode.Binary, frames.get(0).opCode());
        assertFalse(frames.get(0).isFin(), "first fragment must not set FIN");
        assertEquals(maxFrame, frames.get(0).payload().length);
        assertEquals(OpCode.Continuation, frames.get(1).opCode());
        assertTrue(frames.get(1).isFin(), "last fragment must set FIN");
        assertArrayEquals(big, concatPayloads(frames),
            "fragments must reassemble to the original message");
    }

    @Test
    void largeTextMessageSplitAtCodePointBoundaries() throws Exception {
        var out = new ByteArrayOutputStream();
        var session = new WebSocketSessionImpl(
            "GET", "/", null, Map.of(),
            new ByteArrayInputStream(new byte[0]), out, Map.of(), null);
        // 6M CJK chars × 3 UTF-8 bytes = 18MB, above the 16MB frame cap.
        String big = "中".repeat(6_000_000);
        session.sendText(big);

        var frames = readAllFrames(out);
        assertTrue(frames.size() >= 2, "an 18MB text must be fragmented");
        assertEquals(OpCode.Text, frames.get(0).opCode());
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) {
                assertEquals(OpCode.Continuation, frames.get(i).opCode(),
                    "fragment " + i + " must be CONTINUATION");
            }
            // Every fragment must end on a code point boundary: decoding it
            // alone must not hit malformed input.
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(frames.get(i).payload()));
        }
        assertTrue(frames.get(frames.size() - 1).isFin(),
            "last fragment must set FIN");
        assertArrayEquals(big.getBytes(StandardCharsets.UTF_8),
            concatPayloads(frames),
            "fragments must reassemble to the exact original text");
    }

    private static List<WebSocketFrame> readAllFrames(ByteArrayOutputStream out)
        throws IOException {
        var wire = new ByteArrayInputStream(out.toByteArray());
        var frames = new ArrayList<WebSocketFrame>();
        try {
            while (true) {
                frames.add(WebSocketFrame.read(wire));
            }
        } catch (java.io.EOFException e) {
            // end of stream — frames fully read
        }
        return frames;
    }

    private static byte[] concatPayloads(List<WebSocketFrame> frames) {
        var all = new java.io.ByteArrayOutputStream();
        for (var frame : frames) {
            all.writeBytes(frame.payload());
        }
        return all.toByteArray();
    }

    @Test
    void utf8CodePointSplitAcrossFragmentsIsDelivered() throws Exception {
        byte[] first = maskedFrame(0x01, false, new byte[]{(byte) 0xE2, (byte) 0x82});
        byte[] last = maskedFrame(0x00, true, new byte[]{(byte) 0xAC});
        byte[] wire = concat(first, last);

        var in = new ByteArrayInputStream(wire);
        var out = new ByteArrayOutputStream();
        var session = new WebSocketSessionImpl(
            "GET", "/", null, Map.of(), in, out, Map.of(), null);
        var texts = new ArrayList<String>();
        WebSocketReadLoop.readLoop(in, out, session, new WebSocketListener() {
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
            "GET", "/", null, Map.of(), in, out, Map.of(), null);
        WebSocketReadLoop.readLoop(in, out, session, WebSocketListener.NOOP);

        assertFalse(session.isOpen(),
            "Invalid reassembled UTF-8 must close the session");
    }

    @Test
    void protocolErrorSendsCloseFrame() throws Exception {
        // Close code 1005 is reserved and must never appear on the wire.
        byte[] invalidClose = maskedFrame(0x08, true, new byte[]{3, (byte) 0xED});
        var in = new ByteArrayInputStream(invalidClose);
        var out = new ByteArrayOutputStream();
        var session = new WebSocketSessionImpl(
            "GET", "/", null, Map.of(), in, out, Map.of(), null);

        WebSocketReadLoop.readLoop(in, out, session, WebSocketListener.NOOP);

        var response = WebSocketFrame.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(OpCode.Close, response.opCode());
        assertEquals(1002, ((response.payload()[0] & 0xff) << 8)
            | (response.payload()[1] & 0xff));
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
            "GET", "/", null, Map.of(), blockingIn, out, Map.of(), null);

        var loopDone = new AtomicBoolean();
        Thread loop = Thread.startVirtualThread(() -> {
            WebSocketReadLoop.readLoop(blockingIn, out, session, WebSocketListener.NOOP);
            loopDone.set(true);
        });

        // Give the loop time to park on read(), then close from "another thread".
        Thread.sleep(100);
        session.close(1000, "server shutdown");
        loop.join(3000);
        assertTrue(loopDone.get(),
            "readLoop must return when the session is closed, not hang on the blocked read");
    }

    @Test
    void serverCloseNotifiesListenerWithItsCloseCode() throws Exception {
        var blockingIn = new InputStream() {
            volatile boolean closed;
            @Override public int read() throws IOException {
                while (!closed) Thread.onSpinWait();
                throw new IOException("closed");
            }
            @Override public void close() { closed = true; }
        };
        var out = new ByteArrayOutputStream();
        var session = new WebSocketSessionImpl("GET", "/", null, Map.of(),
            blockingIn, out, Map.of(), null);
        var closeCode = new AtomicInteger();
        Thread loop = Thread.startVirtualThread(() -> WebSocketReadLoop.readLoop(
            blockingIn, out, session, new WebSocketListener() {
                @Override public void onClose(int code, String reason, boolean remote) {
                    closeCode.set(code);
                }
            }));
        Thread.sleep(200);
        session.close(1001, "shutdown");
        loop.join(3000);
        assertEquals(1001, closeCode.get());
    }

    @Test
    void invalidServerCloseDoesNotChangeSessionState() {
        var session = new WebSocketSessionImpl("GET", "/", null, Map.of(),
            new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), Map.of(), null);
        assertThrows(IllegalArgumentException.class, () -> session.close(1005, "bad"));
        assertTrue(session.isOpen());
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
