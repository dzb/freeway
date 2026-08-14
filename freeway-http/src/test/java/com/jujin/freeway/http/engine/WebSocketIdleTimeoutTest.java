package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.WebServerBuilder;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: a healthy idle WebSocket connection must survive the socket
 * read timeout. Before the fix the server applied SO_TIMEOUT (readTimeout)
 * to the upgraded socket, so a connection that simply sent no frames for
 * longer than readTimeout was killed with a 1006 abnormal closure. The fix
 * clears SO_TIMEOUT after the 101 response (dead peers are still reclaimed
 * by the TCP keepalive probes; no server-side ping is sent).
 */
class WebSocketIdleTimeoutTest {

    @Test
    void idleWebSocketSurvivesReadTimeout() throws Exception {
        // 1s read timeout — far below the idle gap this test enforces.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ZERO,
            HttpServerConfig.DEFAULT_MAX_BODY_SIZE, Duration.ofSeconds(1), 0))
            .webSocketRoute(WebSocketRoute.of("/ws", session -> WebSocketListener.NOOP))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            String key = Base64.getEncoder().encodeToString(new byte[16]);
            out.write(("GET /ws HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = socket.getInputStream();
            assertTrue(readHandshake(in).startsWith("HTTP/1.1 101"),
                "server must accept the upgrade");

            // Stay silent for several read-timeouts — a healthy idle
            // connection must not be torn down.
            Thread.sleep(2500);

            // The connection must still be usable: a masked Ping must get a
            // Pong echoing the payload.
            byte[] payload = "alive".getBytes(StandardCharsets.UTF_8);
            out.write(maskedFrame(0x9, payload));
            out.flush();

            byte[] pong = readFrame(in);
            assertEquals(0xA, pong[0] & 0x0F,
                "server must answer the ping with a Pong frame");
            assertArrayEquals(payload, framePayload(pong),
                "pong must echo the ping payload");
        } finally {
            server.stop();
        }
    }

    @Test
    void idleWebSocketStillReceivesAndEchoesAfterTimeout() throws Exception {
        // Same scenario, but drive the application path too: after the idle
        // gap the client sends a text frame and the endpoint's onText must
        // fire (the read loop is alive, not a zombie that only answers pings).
        var echoed = new java.util.concurrent.atomic.AtomicReference<String>();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ZERO,
            HttpServerConfig.DEFAULT_MAX_BODY_SIZE, Duration.ofSeconds(1), 0))
            .webSocketRoute(WebSocketRoute.of("/ws", session -> new WebSocketListener() {
                @Override
                public void onText(String text) {
                    echoed.set(text);
                    try {
                        session.sendText("echo:" + text);
                    } catch (IOException ignored) {
                    }
                }
            }))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            String key = Base64.getEncoder().encodeToString(new byte[16]);
            out.write(("GET /ws HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            assertTrue(readHandshake(in).startsWith("HTTP/1.1 101"));

            Thread.sleep(2500);

            byte[] textPayload = "hello".getBytes(StandardCharsets.UTF_8);
            out.write(maskedFrame(0x1, textPayload));
            out.flush();

            byte[] textFrame = readFrame(in);
            assertEquals(0x1, textFrame[0] & 0x0F,
                "server must deliver a text frame");
            assertEquals("echo:hello",
                new String(framePayload(textFrame), StandardCharsets.UTF_8));
        } finally {
            server.stop();
        }
    }

    // --- helpers ---

    private static String readHandshake(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) break;
            sb.append((char) b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = 0;
        }
        return sb.toString();
    }

    /** Client-side frame: FIN + opcode, masked, 1-byte length (payloads here
     *  are all < 126 bytes). */
    private static byte[] maskedFrame(int opcode, byte[] payload) {
        byte[] frame = new byte[6 + payload.length];
        frame[0] = (byte) (0x80 | opcode);
        frame[1] = (byte) (0x80 | payload.length);
        byte[] key = {0x11, 0x22, 0x33, 0x44};
        System.arraycopy(key, 0, frame, 2, 4);
        for (int i = 0; i < payload.length; i++) {
            frame[6 + i] = (byte) (payload[i] ^ key[i % 4]);
        }
        return frame;
    }

    /** Reads one complete server frame (unmasked, payloads < 126 bytes). */
    private static byte[] readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) throw new IOException("EOF reading frame");
        int len = b1 & 0x7F;
        byte[] frame = new byte[2 + len];
        frame[0] = (byte) b0;
        frame[1] = (byte) b1;
        int off = 2;
        if (len == 126) {
            int ext = (in.read() << 8) | in.read();
            frame = new byte[2 + 2 + ext];
            frame[0] = (byte) b0;
            frame[1] = (byte) b1;
            frame[2] = (byte) (ext >> 8);
            frame[3] = (byte) ext;
            off = 4;
            len = ext;
        }
        readFully(in, frame, off, len);
        return frame;
    }

    private static byte[] framePayload(byte[] frame) {
        int b1 = frame[1] & 0xFF;
        int len = b1 & 0x7F;
        int off = 2;
        if (len == 126) {
            len = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
            off = 4;
        }
        var payload = new byte[len];
        System.arraycopy(frame, off, payload, 0, len);
        return payload;
    }

    private static void readFully(InputStream in, byte[] b, int off, int len)
            throws IOException {
        while (len > 0) {
            int n = in.read(b, off, len);
            if (n < 0) throw new IOException("EOF reading frame payload");
            off += n;
            len -= n;
        }
    }
}
