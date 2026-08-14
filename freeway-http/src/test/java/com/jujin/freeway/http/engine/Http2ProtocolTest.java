package com.jujin.freeway.http.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.WebServerBuilder;
import com.jujin.freeway.http.route.Route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * HTTP/2 wire-protocol regression tests. Each test drives a raw socket with
 * the h2c preface and asserts the exact frames the server emits — protocol
 * errors must produce the right RST_STREAM/GOAWAY without disturbing other
 * streams.
 */
class Http2ProtocolTest {

    private static final byte[] PREFACE_BYTES =
        "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    void responseLengthMismatchResetsStream() throws Exception {
        // A response whose body does not match the advertised Content-Length
        // is malformed: RFC 9113 §8.2.2 requires RST_STREAM(PROTOCOL_ERROR).
        // Only the offending stream is reset — no END_STREAM may follow.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/mismatch", ctx -> {
                ctx.setHeader("Content-Length", "5");
                ctx.output(new ByteArrayInputStream(
                    "hello world".getBytes(StandardCharsets.UTF_8)), 5);
            }))
            .route(Route.get("/ok", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            out.write(PREFACE_BYTES);
            out.flush();

            // consume the server SETTINGS preface
            byte[] settingsHeader = new byte[9];
            readFully(socket.getInputStream(), settingsHeader);
            int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                | ((settingsHeader[1] & 0xff) << 8)
                | (settingsHeader[2] & 0xff);
            readFully(socket.getInputStream(), new byte[settingsLen]);

            // HPACK: indexed :method GET, literal :path "/mismatch",
            // indexed :scheme http, literal :authority "localhost".
            byte[] headerBlock = new byte[] {
                (byte) 0x82, (byte) 0x44, 0x09,
                '/', 'm', 'i', 's', 'm', 'a', 't', 'c', 'h',
                (byte) 0x86, (byte) 0x41, 0x09,
                'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
            };
            out.write(new byte[] {
                (byte) ((headerBlock.length >> 16) & 0xff),
                (byte) ((headerBlock.length >> 8) & 0xff),
                (byte) (headerBlock.length & 0xff),
                0x1,  // HEADERS
                0x5,  // END_HEADERS | END_STREAM
                0, 0, 0, 1  // stream 1
            });
            out.write(headerBlock);
            out.flush();

            var in = socket.getInputStream();
            boolean resetSeen = false;
            boolean endStreamSeen = false;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && !resetSeen) {
                byte[] frameHeader = new byte[9];
                if (!readFullyOrEof(in, frameHeader)) break;
                int len = ((frameHeader[0] & 0xff) << 16)
                    | ((frameHeader[1] & 0xff) << 8)
                    | (frameHeader[2] & 0xff);
                int type = frameHeader[3] & 0xff;
                int flags = frameHeader[4] & 0xff;
                int streamId = ((frameHeader[5] & 0x7f) << 24)
                    | ((frameHeader[6] & 0xff) << 16)
                    | ((frameHeader[7] & 0xff) << 8)
                    | (frameHeader[8] & 0xff);
                byte[] payload = new byte[len];
                readFully(in, payload);
                if (streamId == 1 && (flags & 0x1) != 0) {
                    endStreamSeen = true;
                }
                if (type == 0x3 && streamId == 1 && len == 4) {
                    int errorCode = ((payload[0] & 0xff) << 24)
                        | ((payload[1] & 0xff) << 16)
                        | ((payload[2] & 0xff) << 8)
                        | (payload[3] & 0xff);
                    resetSeen = errorCode == 0x1; // PROTOCOL_ERROR
                }
            }
            assertTrue(resetSeen,
                "length-mismatched response must be reset with "
                    + "RST_STREAM(PROTOCOL_ERROR)");
            assertFalse(endStreamSeen,
                "a length-mismatched response must not end with END_STREAM");
        } finally {
            server.stop();
        }
    }

    @Test
    void connectionWindowUpdateZeroIncrementIsProtocolError() throws Exception {
        // RFC 7540 §6.9: a zero increment on the connection flow-control
        // window is a connection error — GOAWAY(PROTOCOL_ERROR).
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            out.write(PREFACE_BYTES);
            out.flush();
            consumeSettings(socket.getInputStream());

            writeFrame(out, 4, 0x8, 0, 0,
                new byte[] {0, 0, 0, 0});  // WINDOW_UPDATE, increment 0

            boolean goawaySeen = false;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && !goawaySeen) {
                byte[] frameHeader = new byte[9];
                if (!readFullyOrEof(socket.getInputStream(), frameHeader)) break;
                int len = length(frameHeader);
                int type = frameHeader[3] & 0xff;
                byte[] payload = new byte[len];
                readFully(socket.getInputStream(), payload);
                if (type == 0x7 && len >= 8) {
                    int errorCode = int32(payload, 4);
                    goawaySeen = errorCode == 0x1; // PROTOCOL_ERROR
                }
            }
            assertTrue(goawaySeen,
                "zero connection-window increment must trigger "
                    + "GOAWAY(PROTOCOL_ERROR)");
        } finally {
            server.stop();
        }
    }

    @Test
    void streamWindowUpdateZeroIncrementResetsStream() throws Exception {
        // RFC 7540 §6.9: a zero increment on a stream window is a stream
        // error — RST_STREAM(PROTOCOL_ERROR) on that stream only.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ctx.send(200, "ok");
            }))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            out.write(PREFACE_BYTES);
            out.flush();
            consumeSettings(socket.getInputStream());

            // Stream 1: GET / — the handler sleeps so the stream stays open.
            byte[] headerBlock = new byte[] {
                (byte) 0x82, (byte) 0x84, (byte) 0x86,
                (byte) 0x41, 0x09,
                'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
            };
            writeFrame(out, headerBlock.length, 0x1, 0x5, 1, headerBlock);
            writeFrame(out, 4, 0x8, 0, 1,
                new byte[] {0, 0, 0, 0});  // stream WINDOW_UPDATE, increment 0

            boolean resetSeen = false;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && !resetSeen) {
                byte[] frameHeader = new byte[9];
                if (!readFullyOrEof(socket.getInputStream(), frameHeader)) break;
                int len = length(frameHeader);
                int type = frameHeader[3] & 0xff;
                int streamId = streamId(frameHeader);
                byte[] payload = new byte[len];
                readFully(socket.getInputStream(), payload);
                if (type == 0x3 && streamId == 1 && len == 4) {
                    resetSeen = int32(payload, 0) == 0x1; // PROTOCOL_ERROR
                }
            }
            assertTrue(resetSeen,
                "zero stream-window increment must trigger "
                    + "RST_STREAM(PROTOCOL_ERROR) on that stream");
        } finally {
            server.stop();
        }
    }

    private static void consumeSettings(InputStream in) throws IOException {
        byte[] header = new byte[9];
        readFully(in, header);
        readFully(in, new byte[length(header)]);
    }

    private static void writeFrame(OutputStream out, int length, int type,
            int flags, int streamId, byte[] payload) throws IOException {
        out.write(new byte[] {
            (byte) ((length >> 16) & 0xff),
            (byte) ((length >> 8) & 0xff),
            (byte) (length & 0xff),
            (byte) type,
            (byte) flags,
            (byte) ((streamId >> 24) & 0x7f),
            (byte) ((streamId >> 16) & 0xff),
            (byte) ((streamId >> 8) & 0xff),
            (byte) (streamId & 0xff)
        });
        if (payload != null && payload.length > 0) {
            out.write(payload);
        }
        out.flush();
    }

    private static int length(byte[] frameHeader) {
        return ((frameHeader[0] & 0xff) << 16)
            | ((frameHeader[1] & 0xff) << 8)
            | (frameHeader[2] & 0xff);
    }

    private static int streamId(byte[] frameHeader) {
        return ((frameHeader[5] & 0x7f) << 24)
            | ((frameHeader[6] & 0xff) << 16)
            | ((frameHeader[7] & 0xff) << 8)
            | (frameHeader[8] & 0xff);
    }

    private static int int32(byte[] payload, int offset) {
        return ((payload[offset] & 0xff) << 24)
            | ((payload[offset + 1] & 0xff) << 16)
            | ((payload[offset + 2] & 0xff) << 8)
            | (payload[offset + 3] & 0xff);
    }

    private static void readFully(InputStream in, byte[] buffer)
            throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n < 0) throw new IOException("EOF while reading " + buffer.length + " bytes");
            offset += n;
        }
    }

    private static boolean readFullyOrEof(InputStream in, byte[] buffer)
            throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n < 0) return false;
            offset += n;
        }
        return true;
    }


    @Test
    void h2ShutdownClosesStreamWaitingForRequestBody() throws Exception {
        int port = freePort();
        var server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(1)))
            .route(Route.post("/upload", ctx -> {
                ctx.body();
                ctx.send(200, "ok");
            }))
            .build();
        server.start();
        try (var sock = new Socket("127.0.0.1", port)) {
            var out = sock.getOutputStream();
            out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            writeH2Frame(out, 0, 4, 0, 0); // SETTINGS
            // HEADERS: POST /upload with required pseudo-headers, END_HEADERS
            // but NO END_STREAM — the handler blocks waiting for the body.
            byte[] block = {
                0x03, 0x04, 'P', 'O', 'S', 'T',                       // :method POST
                0x06, 0x04, 'h', 't', 't', 'p',                       // :scheme http
                0x04, 0x07, '/', 'u', 'p', 'l', 'o', 'a', 'd',        // :path /upload
                0x01, 0x01, 'x'                                       // :authority x
            };
            writeH2Frame(out, block.length, 1, 0x04, 1); // HEADERS, END_HEADERS
            out.write(block);
            out.flush();

            Thread.sleep(300); // let the stream handler block on body()
            server.stop();
            Thread.sleep(300); // allow the session thread to unwind

            boolean lingering = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().startsWith("http-"));
            assertFalse(lingering,
                "HTTP/2 session thread must exit after shutdown");
        } finally {
            if (server.isRunning()) {
                server.stop();
            }
        }
    }

    @Test
    void h2cRejectsStreamsBeyondConcurrentCap() throws Exception {
        // MAX_CONCURRENT_STREAMS (100): the 101st concurrent stream must be
        // rejected with RST_STREAM(REFUSED_STREAM). Handlers hold streams open
        // so the cap is actually reached.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ctx.send(200, "ok");
            }))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(
                StandardCharsets.US_ASCII);
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();

                // consume the server SETTINGS preface
                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                // HPACK block: indexed :method GET, :path /, :scheme http,
                // literal :authority "localhost" (name-indexed, 4-bit prefix).
                // HPACK block: indexed :method GET (0x82), :path / (0x84),
                // :scheme http (0x86), literal :authority "localhost".
                byte[] headerBlock = new byte[] {
                    (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41, 0x09,
                    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                // 101 HEADERS frames — stream 201 is the one beyond the cap.
                for (int streamId = 1; streamId <= 201; streamId += 2) {
                    out.write(new byte[] {
                        (byte) ((headerBlock.length >> 16) & 0xff),
                        (byte) ((headerBlock.length >> 8) & 0xff),
                        (byte) (headerBlock.length & 0xff),
                        0x1,  // HEADERS
                        0x5,  // END_HEADERS | END_STREAM
                        (byte) ((streamId >> 24) & 0x7f),
                        (byte) ((streamId >> 16) & 0xff),
                        (byte) ((streamId >> 8) & 0xff),
                        (byte) (streamId & 0xff)
                    });
                    out.write(headerBlock);
                }
                out.flush();

                // Read frames until the rejection arrives: RST_STREAM (type 3)
                // on stream 201 with REFUSED_STREAM (0x7).
                var in = socket.getInputStream();
                boolean rejected = false;
                long deadline = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < deadline && !rejected) {
                    byte[] frameHeader = new byte[9];
                    if (!readFullyOrEof(in, frameHeader)) break;
                    int len = ((frameHeader[0] & 0xff) << 16)
                        | ((frameHeader[1] & 0xff) << 8)
                        | (frameHeader[2] & 0xff);
                    int type = frameHeader[3] & 0xff;
                    int streamId = ((frameHeader[5] & 0x7f) << 24)
                        | ((frameHeader[6] & 0xff) << 16)
                        | ((frameHeader[7] & 0xff) << 8)
                        | (frameHeader[8] & 0xff);
                    byte[] payload = new byte[len];
                    readFully(in, payload);
                    if (type == 0x3 && streamId == 201 && len == 4) {
                        int errorCode = ((payload[0] & 0xff) << 24)
                            | ((payload[1] & 0xff) << 16)
                            | ((payload[2] & 0xff) << 8)
                            | (payload[3] & 0xff);
                        rejected = errorCode == 0x7; // REFUSED_STREAM
                    }
                }
                assertTrue(rejected,
                    "stream 201 must be rejected with RST_STREAM(REFUSED_STREAM)");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cAcceptsTrailersWithoutKillingConnection() throws Exception {
        // A trailer HEADERS block after the request body is legitimate
        // (RFC 7540 §8.1.2.2). It must be consumed (HPACK state stays in
        // sync), discarded, and must NOT tear down the connection: a second
        // request on the same connection must still succeed.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> {
                ctx.body();
                ctx.send(200, "ok");
            }))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(
                StandardCharsets.US_ASCII);
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();

                // consume the server SETTINGS preface
                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                // Request header block: indexed :method GET, :path /, :scheme
                // http, literal :authority "localhost".
                byte[] headerBlock = new byte[] {
                    (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41, 0x09,
                    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                // Trailer block: literal without indexing, new name "x: v"
                // (0x00 = name index 0 → new name, then 8-bit length prefixes).
                byte[] trailerBlock = new byte[] {
                    0x00, 0x01, 'x', 0x01, 'v'
                };

                // Stream 1: HEADERS (no END_STREAM) + DATA "hi" + trailer
                // HEADERS (END_HEADERS | END_STREAM).
                writeFrame(out, headerBlock.length, 0x1, 0x4, 1, headerBlock);
                writeFrame(out, 2, 0x0, 0x0, 1, "hi".getBytes(StandardCharsets.US_ASCII));
                writeFrame(out, trailerBlock.length, 0x1, 0x5, 1, trailerBlock);

                // First response must be 200 on stream 1 and the connection
                // must survive (no GOAWAY): a second request on stream 3
                // must also get 200.
                assertTrue(waitForStatus200(socket.getInputStream(), 1, 5000),
                    "stream 1 must complete with 200 despite trailers");
                writeFrame(out, headerBlock.length, 0x1, 0x5, 3, headerBlock);
                assertTrue(waitForStatus200(socket.getInputStream(), 3, 5000),
                    "connection must survive trailers — stream 3 must get 200");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cFragmentedTrailerEndStreamCompletesBody() throws Exception {
        // END_STREAM on the first trailer HEADERS frame must survive a
        // fragmented header block and wake the handler blocked on body().
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(1)))
            .route(Route.post("/trailer", ctx -> {
                ctx.body();
                ctx.send(200, "ok");
            }))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(
                StandardCharsets.US_ASCII);
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();

                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                byte[] postBlock = {
                    (byte) 0x83, 0x04, 0x08,
                    '/', 't', 'r', 'a', 'i', 'l', 'e', 'r',
                    (byte) 0x86, 0x01, 0x09,
                    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                writeFrame(out, postBlock.length, 0x1, 0x4, 1, postBlock);
                writeFrame(out, 2, 0x0, 0x0, 1,
                    "hi".getBytes(StandardCharsets.US_ASCII));

                // Trailer block split across HEADERS(END_STREAM) and
                // CONTINUATION(END_HEADERS).
                byte[] trailerPart1 = {0x00, 0x01, 'x'};
                byte[] trailerPart2 = {0x01, 'v'};
                writeFrame(out, trailerPart1.length, 0x1, 0x1, 1, trailerPart1);
                writeFrame(out, trailerPart2.length, 0x9, 0x4, 1, trailerPart2);
                out.flush();

                assertTrue(waitForStatus200(socket.getInputStream(), 1, 5000),
                    "fragmented trailer END_STREAM must complete the request body");

                byte[] getBlock = {
                    (byte) 0x82, (byte) 0x84, (byte) 0x86,
                    (byte) 0x41, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                writeFrame(out, getBlock.length, 0x1, 0x5, 3, getBlock);
                assertTrue(waitForStatus200(socket.getInputStream(), 3, 5000),
                    "connection must survive a fragmented trailer block");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cRespectsPeerMaxFrameSizeForOutboundSplitting() throws Exception {
        // The peer advertises SETTINGS_MAX_FRAME_SIZE=32768 (> our default
        // 16384): outbound DATA frames must be split to at most the peer's
        // size, and larger than the default when the peer allows it
        // (RFC 7540 §6.5.2 requires the value to be ≥ 16384).
        String big = "x".repeat(50000);
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, big)))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();

                // Consume the server SETTINGS preface.
                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                // SETTINGS with MAX_FRAME_SIZE=32768 (identifier 0x5).
                byte[] settingsPayload = new byte[] {
                    0x00, 0x05, 0x00, 0x00, (byte) 0x80, 0x00
                };
                writeFrame(out, settingsPayload.length, 0x4, 0x0, 0, settingsPayload);

                byte[] headerBlock = new byte[] {
                    (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41, 0x09,
                    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                writeFrame(out, headerBlock.length, 0x1, 0x5, 1, headerBlock);

                // Collect DATA frames until END_STREAM; every frame ≤ 32768,
                // and at least one frame must exceed our default 16384.
                var in = socket.getInputStream();
                int total = 0;
                int maxData = 0;
                boolean done = false;
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline && !done) {
                    byte[] frameHeader = new byte[9];
                    if (!readFullyOrEof(in, frameHeader)) break;
                    int len = ((frameHeader[0] & 0xff) << 16)
                        | ((frameHeader[1] & 0xff) << 8)
                        | (frameHeader[2] & 0xff);
                    int type = frameHeader[3] & 0xff;
                    int flags = frameHeader[4] & 0xff;
                    byte[] payload = new byte[len];
                    readFully(in, payload);
                    if (type == 0x0) { // DATA
                        maxData = Math.max(maxData, len);
                        total += len;
                        done = (flags & 0x1) != 0; // END_STREAM
                    }
                }
                assertEquals(50000, total, "full response body must arrive");
                assertTrue(maxData > 16384 && maxData <= 32768,
                    "outbound DATA must follow the peer's advertised max frame size 32768, got " + maxData);
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cRejectsHeaderBlockOverMaxInbound() throws Exception {
        // A header block larger than the 64 KiB inbound cap must fail the
        // connection with COMPRESSION_ERROR (GOAWAY code 9) instead of being
        // buffered without bound.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();
                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                // 5 frames × 16384 bytes = 81920 > 65536 cap. Payload content
                // is irrelevant — the size check fires during collection.
                byte[] chunk = new byte[16384];
                writeFrame(out, chunk.length, 0x1, 0x0, 1, chunk); // HEADERS, no END_HEADERS
                for (int i = 0; i < 3; i++) {
                    writeFrame(out, chunk.length, 0x9, 0x0, 1, chunk); // CONTINUATION
                }
                writeFrame(out, chunk.length, 0x9, 0x4, 1, chunk); // END_HEADERS
                out.flush();

                var in = socket.getInputStream();
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    byte[] frameHeader = new byte[9];
                    if (!readFullyOrEof(in, frameHeader)) break;
                    int len = ((frameHeader[0] & 0xff) << 16)
                        | ((frameHeader[1] & 0xff) << 8)
                        | (frameHeader[2] & 0xff);
                    int type = frameHeader[3] & 0xff;
                    byte[] payload = new byte[len];
                    readFully(in, payload);
                    if (type == 0x7) { // GOAWAY
                        int errorCode = len >= 8 ? ((payload[4] & 0xff) << 24)
                            | ((payload[5] & 0xff) << 16)
                            | ((payload[6] & 0xff) << 8) | (payload[7] & 0xff) : -1;
                        assertEquals(0x9, errorCode,
                            "oversized header block must fail with COMPRESSION_ERROR");
                        return;
                    }
                }
                fail("expected GOAWAY(COMPRESSION_ERROR) for oversized header block");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cResetStreamWithNoErrorReleasesHandler() throws Exception {
        // RST_STREAM with NO_ERROR must still terminate the stream: a handler
        // blocked reading the request body is released, and the connection
        // survives for the next request.
        var active = new AtomicInteger();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> {
                active.incrementAndGet();
                try {
                    ctx.body();
                } catch (IOException ignored) {
                    // RST closes the body stream — expected.
                } finally {
                    active.decrementAndGet();
                }
                ctx.send(200, "ok");
            }))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();
                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                byte[] headerBlock = new byte[] {
                    (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41, 0x09,
                    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                // Request without END_STREAM: handler parks reading the body.
                writeFrame(out, headerBlock.length, 0x1, 0x4, 1, headerBlock);
                // Wait until the server has actually opened the stream (the
                // handler is parked on the body read) before RST: an RST on a
                // still-idle stream is a connection error (RFC 7540 §5.1) and
                // would tear down the connection instead of testing the
                // reset-releases-handler path. A fixed sleep is racy under
                // load, so poll the handler's active flag instead.
                long started = System.currentTimeMillis();
                while (active.get() < 1 && System.currentTimeMillis() - started < 3000) {
                    Thread.sleep(10);
                }
                assertEquals(1, active.get(),
                    "handler must be parked on the body read before RST");
                // RST with NO_ERROR (code 0).
                writeFrame(out, 4, 0x3, 0x0, 1, new byte[]{0, 0, 0, 0});
                out.flush();

                long deadline = System.currentTimeMillis() + 3000;
                while (active.get() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(0, active.get(),
                    "RST_STREAM(NO_ERROR) must release the blocked handler");
                writeFrame(out, headerBlock.length, 0x1, 0x5, 3, headerBlock);
                out.flush();
                assertTrue(waitForStatus200(socket.getInputStream(), 3, 5000),
                    "connection must survive RST_STREAM(NO_ERROR)");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cPriorKnowledgeGetsServerSettingsFirst() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(
                StandardCharsets.US_ASCII);
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(preface);
                socket.getOutputStream().flush();

                // RFC 7540 §3.5: the server connection preface is a SETTINGS
                // frame — the PRI magic belongs to the client and must never
                // be echoed back.
                byte[] header = new byte[9];
                readFully(socket.getInputStream(), header);
                int length = ((header[0] & 0xff) << 16)
                    | ((header[1] & 0xff) << 8)
                    | (header[2] & 0xff);
                int type = header[3] & 0xff;
                int flags = header[4] & 0xff;
                int streamId = ((header[5] & 0x7f) << 24)
                    | ((header[6] & 0xff) << 16)
                    | ((header[7] & 0xff) << 8)
                    | (header[8] & 0xff);

                assertEquals(0x4, type,
                    "server connection preface must be a SETTINGS frame");
                assertEquals(0, flags, "first SETTINGS frame must not be an ACK");
                assertEquals(0, streamId,
                    "SETTINGS must be a connection-level frame");
                assertTrue(length > 0 && length <= 6 * 6,
                    "SETTINGS payload length must fit at least one parameter, got " + length);

                byte[] payload = new byte[length];
                readFully(socket.getInputStream(), payload);

                Map<Integer, Long> settings = new java.util.HashMap<>();
                for (int i = 0; i + 6 <= payload.length; i += 6) {
                    int id = ((payload[i] & 0xff) << 8) | (payload[i + 1] & 0xff);
                    long value = ((long) (payload[i + 2] & 0xff) << 24)
                        | ((long) (payload[i + 3] & 0xff) << 16)
                        | ((long) (payload[i + 4] & 0xff) << 8)
                        | (payload[i + 5] & 0xff);
                    settings.put(id, value);
                }
                assertEquals(100L, settings.getOrDefault(0x3, -1L),
                    "server must advertise SETTINGS_MAX_CONCURRENT_STREAMS=100");
                assertEquals(65536L, settings.getOrDefault(0x6, -1L),
                    "server must advertise SETTINGS_MAX_HEADER_LIST_SIZE");
                assertEquals(16384L, settings.getOrDefault(0x5, -1L),
                    "server must advertise SETTINGS_MAX_FRAME_SIZE=16384");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cActiveStreamSurvivesReadTimeout() throws Exception {
        // Regression: the socket read timeout must apply only to truly idle
        // connections. An OPEN request stream that pauses mid-body (client
        // sends no frames for longer than readTimeout) must not be torn down.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0,
                Duration.ofSeconds(2), 16 * 1024 * 1024,
                Duration.ofSeconds(2), 64))
            .route(Route.post("/", ctx ->
                ctx.send(200, "ok:" + new String(ctx.body(), StandardCharsets.UTF_8))))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(
                StandardCharsets.US_ASCII);
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(10000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();
                byte[] header = new byte[9];
                readFully(socket.getInputStream(), header);
                int settingsLen = ((header[0] & 0xff) << 16)
                    | ((header[1] & 0xff) << 8)
                    | (header[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                // HEADERS for stream 1: POST / — END_HEADERS set, END_STREAM
                // NOT set, so the stream stays open awaiting its body.
                byte[] headerBlock = new byte[] {
                    (byte) 0x83, // :method POST
                    (byte) 0x84, // :path /
                    (byte) 0x86, // :scheme http
                    (byte) 0x41, 0x09,
                    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                out.write(new byte[] {
                    (byte) ((headerBlock.length >> 16) & 0xff),
                    (byte) ((headerBlock.length >> 8) & 0xff),
                    (byte) (headerBlock.length & 0xff),
                    0x1, // HEADERS
                    0x4, // END_HEADERS (no END_STREAM)
                    0x00, 0x00, 0x00, 0x01
                });
                out.write(headerBlock);
                out.flush();

                // Idle longer than the 2s read timeout while the stream is open.
                Thread.sleep(4500);

                // Send the request body now — the connection must still be alive.
                out.write(new byte[] {
                    0x00, 0x00, 0x02, // length 2
                    0x00,             // DATA
                    0x01,             // END_STREAM
                    0x00, 0x00, 0x00, 0x01
                });
                out.write("hi".getBytes(StandardCharsets.UTF_8));
                out.flush();

                // Collect response frames until END_STREAM on stream 1.
                var in = socket.getInputStream();
                boolean done = false;
                String body = null;
                while (!done) {
                    if (!readFullyOrEof(in, header)) {
                        fail("server closed the H2 connection while stream 1 "
                            + "was still active (read timeout must not kill "
                            + "open streams)");
                    }
                    int len = ((header[0] & 0xff) << 16)
                        | ((header[1] & 0xff) << 8)
                        | (header[2] & 0xff);
                    int type = header[3] & 0xff;
                    int flags = header[4] & 0xff;
                    int streamId = ((header[5] & 0x7f) << 24)
                        | ((header[6] & 0xff) << 16)
                        | ((header[7] & 0xff) << 8)
                        | (header[8] & 0xff);
                    byte[] payload = new byte[len];
                    readFully(in, payload);
                    if (streamId == 1 && type == 0x0) { // DATA
                        String chunk = new String(payload, StandardCharsets.UTF_8);
                        body = body == null ? chunk : body + chunk;
                    }
                    if (streamId == 1 && (flags & 0x1) != 0) { // END_STREAM
                        done = true;
                    }
                }
                assertEquals("ok:hi", body, "response body after idle pause");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cIdleConnectionClosedAfterReadTimeout() throws Exception {
        // The read timeout still applies to a truly idle H2 connection — no
        // open streams — so idle sockets cannot hold resources forever.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0,
                Duration.ofSeconds(2), 16 * 1024 * 1024,
                Duration.ofSeconds(2), 64))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(
                StandardCharsets.US_ASCII);
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(10000);
                socket.getOutputStream().write(preface);
                socket.getOutputStream().flush();
                byte[] header = new byte[9];
                readFully(socket.getInputStream(), header);
                int settingsLen = ((header[0] & 0xff) << 16)
                    | ((header[1] & 0xff) << 8)
                    | (header[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                // No streams opened; the server must close the idle connection
                // after its 2s read timeout.
                Thread.sleep(4500);
                assertFalse(readFullyOrEof(socket.getInputStream(), header),
                    "idle H2 connection must be closed after the read timeout");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cUpgradeViaHttp1UpgradeHeader() throws Exception {
        int port = freePort();
        var server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/ping", ctx -> ctx.send(200, "pong")))
            .build();
        server.start();
        try {
            var client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/ping"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertEquals("pong", resp.body());
            assertEquals(HttpClient.Version.HTTP_2, resp.version(),
                "Upgrade: h2c must negotiate HTTP/2");
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cGoawayRejectsNewStreams() throws Exception {
        // RFC 7540 §6.8: after receiving GOAWAY the server must not create
        // new streams — a later HEADERS must be RST'd (REFUSED_STREAM).
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            var out = socket.getOutputStream();
            out.write(PREFACE_BYTES);
            out.flush();
            byte[] header = new byte[9];
            readFully(socket.getInputStream(), header);
            int settingsLen = ((header[0] & 0xff) << 16)
                | ((header[1] & 0xff) << 8)
                | (header[2] & 0xff);
            readFully(socket.getInputStream(), new byte[settingsLen]);

            // GOAWAY(NO_ERROR, lastStreamId=0)
            out.write(new byte[] {
                0x00, 0x00, 0x08, // length 8
                0x07,             // GOAWAY
                0x00,             // flags
                0x00, 0x00, 0x00, 0x00, // stream 0
                0x00, 0x00, 0x00, 0x00, // errorCode NO_ERROR
                0x00, 0x00, 0x00, 0x00  // lastStreamId
            });
            // HEADERS for stream 3 (new stream after GOAWAY)
            byte[] headerBlock = new byte[] {
                (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41, 0x09,
                'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
            };
            out.write(new byte[] {
                (byte) ((headerBlock.length >> 16) & 0xff),
                (byte) ((headerBlock.length >> 8) & 0xff),
                (byte) (headerBlock.length & 0xff),
                0x1, // HEADERS
                0x5, // END_HEADERS | END_STREAM
                0x00, 0x00, 0x00, 0x03
            });
            out.write(headerBlock);
            out.flush();

            // Expect RST_STREAM(REFUSED_STREAM=0x7) on stream 3.
            var in = socket.getInputStream();
            boolean rejected = false;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline && !rejected) {
                if (!readFullyOrEof(in, header)) break;
                int len = ((header[0] & 0xff) << 16)
                    | ((header[1] & 0xff) << 8)
                    | (header[2] & 0xff);
                int type = header[3] & 0xff;
                int streamId = ((header[5] & 0x7f) << 24)
                    | ((header[6] & 0xff) << 16)
                    | ((header[7] & 0xff) << 8)
                    | (header[8] & 0xff);
                byte[] payload = new byte[len];
                readFully(in, payload);
                if (type == 0x3 && streamId == 3 && len >= 4) { // RST_STREAM
                    int errorCode = ((payload[0] & 0xff) << 24)
                        | ((payload[1] & 0xff) << 16)
                        | ((payload[2] & 0xff) << 8)
                        | (payload[3] & 0xff);
                    rejected = errorCode == 0x7;
                }
            }
            assertTrue(rejected,
                "a new stream after GOAWAY must be rejected with RST_STREAM(REFUSED_STREAM)");
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cInterruptedHeaderBlockIsProtocolError() throws Exception {
        // RFC 7540 §4.3: a header block may be interrupted only by
        // CONTINUATION; SETTINGS mid-block must be a connection error.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            var out = socket.getOutputStream();
            out.write(PREFACE_BYTES);
            out.flush();
            byte[] header = new byte[9];
            readFully(socket.getInputStream(), header);
            int settingsLen = ((header[0] & 0xff) << 16)
                | ((header[1] & 0xff) << 8)
                | (header[2] & 0xff);
            readFully(socket.getInputStream(), new byte[settingsLen]);

            // HEADERS on stream 1 WITHOUT END_HEADERS — block left open.
            out.write(new byte[] {
                0x00, 0x00, 0x04, // length 4
                0x01,             // HEADERS
                0x00,             // no END_HEADERS, no END_STREAM
                0x00, 0x00, 0x00, 0x01,
                (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41 // fragment (incomplete block is fine)
            });
            // SETTINGS while the header block is incomplete.
            out.write(new byte[] {
                0x00, 0x00, 0x00, // length 0
                0x04,             // SETTINGS
                0x00,
                0x00, 0x00, 0x00, 0x00
            });
            out.flush();

            // Expect GOAWAY(PROTOCOL_ERROR=0x1).
            var in = socket.getInputStream();
            boolean sawGoaway = false;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline && !sawGoaway) {
                if (!readFullyOrEof(in, header)) break;
                int len = ((header[0] & 0xff) << 16)
                    | ((header[1] & 0xff) << 8)
                    | (header[2] & 0xff);
                int type = header[3] & 0xff;
                byte[] payload = new byte[len];
                readFully(in, payload);
                if (type == 0x7 && len >= 8) { // GOAWAY: [0..3]=lastStreamId, [4..7]=errorCode
                    int errorCode = ((payload[4] & 0xff) << 24)
                        | ((payload[5] & 0xff) << 16)
                        | ((payload[6] & 0xff) << 8)
                        | (payload[7] & 0xff);
                    sawGoaway = errorCode == 0x1;
                }
            }
            assertTrue(sawGoaway,
                "a SETTINGS frame mid-header-block must trigger GOAWAY(PROTOCOL_ERROR)");
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cWindowUpdateMidHeaderBlockIsProtocolError() throws Exception {
        // RFC 7540 §4.3: ANY frame other than CONTINUATION mid-header-block
        // is a connection error — WINDOW_UPDATE included.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            var out = socket.getOutputStream();
            out.write(PREFACE_BYTES);
            out.flush();
            byte[] header = new byte[9];
            readFully(socket.getInputStream(), header);
            int settingsLen = ((header[0] & 0xff) << 16)
                | ((header[1] & 0xff) << 8)
                | (header[2] & 0xff);
            readFully(socket.getInputStream(), new byte[settingsLen]);

            // HEADERS on stream 1 WITHOUT END_HEADERS — block left open.
            out.write(new byte[] {
                0x00, 0x00, 0x04, // length 4
                0x01,             // HEADERS
                0x00,             // no END_HEADERS, no END_STREAM
                0x00, 0x00, 0x00, 0x01,
                (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41
            });
            // WINDOW_UPDATE (stream 0, increment 1) while the block is open.
            out.write(new byte[] {
                0x00, 0x00, 0x04, // length 4
                0x08,             // WINDOW_UPDATE
                0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x01
            });
            out.flush();

            var in = socket.getInputStream();
            boolean sawGoaway = false;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline && !sawGoaway) {
                if (!readFullyOrEof(in, header)) break;
                int len = ((header[0] & 0xff) << 16)
                    | ((header[1] & 0xff) << 8)
                    | (header[2] & 0xff);
                int type = header[3] & 0xff;
                byte[] payload = new byte[len];
                readFully(in, payload);
                if (type == 0x7 && len >= 8) { // GOAWAY: [0..3]=lastStreamId, [4..7]=errorCode
                    int errorCode = ((payload[4] & 0xff) << 24)
                        | ((payload[5] & 0xff) << 16)
                        | ((payload[6] & 0xff) << 8)
                        | (payload[7] & 0xff);
                    sawGoaway = errorCode == 0x1;
                }
            }
            assertTrue(sawGoaway,
                "a WINDOW_UPDATE frame mid-header-block must trigger GOAWAY(PROTOCOL_ERROR)");
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cAcceptsValidHeaderTableSizeSettings() throws Exception {
        // RFC 7540 §6.5.2: SETTINGS_HEADER_TABLE_SIZE is a 32-bit unsigned
        // value — 0 (no dynamic table) and 0xFFFFFFFF (a wire "negative"
        // would encode as this large positive) are both legal and must not
        // kill the connection or poison the HPACK decoder.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            out.write(PREFACE_BYTES);
            out.flush();
            consumeSettings(socket.getInputStream());

            // SETTINGS: HEADER_TABLE_SIZE=0 then HEADER_TABLE_SIZE=0xFFFFFFFF.
            byte[] settingsPayload = new byte[] {
                0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
            };
            writeFrame(out, settingsPayload.length, 0x4, 0x0, 0, settingsPayload);

            byte[] headerBlock = new byte[] {
                (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41, 0x09,
                'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
            };
            writeFrame(out, headerBlock.length, 0x1, 0x5, 1, headerBlock);

            assertTrue(waitForStatus200(socket.getInputStream(), 1, 5000),
                "valid SETTINGS_HEADER_TABLE_SIZE values must not kill the "
                    + "connection or the HPACK decoder");
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cInvalidEnablePushIsProtocolError() throws Exception {
        // RFC 7540 §6.5.2: SETTINGS_ENABLE_PUSH accepts only 0 or 1;
        // any other value is a connection error.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            var out = socket.getOutputStream();
            out.write(PREFACE_BYTES);
            out.flush();
            byte[] header = new byte[9];
            readFully(socket.getInputStream(), header);
            int settingsLen = ((header[0] & 0xff) << 16)
                | ((header[1] & 0xff) << 8)
                | (header[2] & 0xff);
            readFully(socket.getInputStream(), new byte[settingsLen]);

            // SETTINGS with ENABLE_PUSH (0x2) = 2.
            out.write(new byte[] {
                0x00, 0x00, 0x06, // length 6
                0x04,             // SETTINGS
                0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x02,       // ENABLE_PUSH
                0x00, 0x00, 0x00, 0x02 // value 2 (invalid)
            });
            out.flush();

            var in = socket.getInputStream();
            boolean sawGoaway = false;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline && !sawGoaway) {
                if (!readFullyOrEof(in, header)) break;
                int len = ((header[0] & 0xff) << 16)
                    | ((header[1] & 0xff) << 8)
                    | (header[2] & 0xff);
                int type = header[3] & 0xff;
                byte[] payload = new byte[len];
                readFully(in, payload);
                if (type == 0x7 && len >= 8) { // GOAWAY: [0..3]=lastStreamId, [4..7]=errorCode
                    int errorCode = ((payload[4] & 0xff) << 24)
                        | ((payload[5] & 0xff) << 16)
                        | ((payload[6] & 0xff) << 8)
                        | (payload[7] & 0xff);
                    sawGoaway = errorCode == 0x1;
                }
            }
            assertTrue(sawGoaway,
                "SETTINGS_ENABLE_PUSH with a value other than 0/1 must trigger "
                    + "GOAWAY(PROTOCOL_ERROR)");
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cShutdownSendsGoaway() throws Exception {
        // Graceful shutdown must announce GOAWAY(NO_ERROR) to HTTP/2 peers
        // before closing the socket (RFC 7540 §6.8), so they stop creating
        // streams and can retry on a fresh connection.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            var out = socket.getOutputStream();
            out.write(PREFACE_BYTES);
            out.flush();
            byte[] header = new byte[9];
            readFully(socket.getInputStream(), header);
            int settingsLen = ((header[0] & 0xff) << 16)
                | ((header[1] & 0xff) << 8)
                | (header[2] & 0xff);
            readFully(socket.getInputStream(), new byte[settingsLen]);

            server.stop();

            // The first frame after shutdown must be GOAWAY(NO_ERROR=0),
            // not a bare EOF.
            assertTrue(readFullyOrEof(socket.getInputStream(), header),
                "shutdown must send GOAWAY before closing the connection");
            int len = ((header[0] & 0xff) << 16)
                | ((header[1] & 0xff) << 8)
                | (header[2] & 0xff);
            assertEquals(0x7, header[3] & 0xff,
                "the first shutdown frame must be GOAWAY");
            byte[] payload = new byte[len];
            readFully(socket.getInputStream(), payload);
            assertEquals(8, len, "GOAWAY payload must carry errorCode + lastStreamId");
            int errorCode = ((payload[4] & 0xff) << 24)
                | ((payload[5] & 0xff) << 16)
                | ((payload[6] & 0xff) << 8)
                | (payload[7] & 0xff);
            assertEquals(0, errorCode, "shutdown GOAWAY must carry NO_ERROR");
        } finally {
            server.stop();
        }
    }

    private static void writeH2Frame(OutputStream out, int length, int type,
                                     int flags, int streamId) throws IOException {
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        out.write(type);
        out.write(flags);
        out.write(0);
        out.write(0);
        out.write(streamId >>> 24);
        out.write(streamId >>> 16);
        out.write(streamId >>> 8);
        out.write(streamId);
    }

    private static String readHttpResponse(Socket sock) throws IOException {
        var in = sock.getInputStream();
        var head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.write(b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = 0;
        }
        String headers = head.toString(StandardCharsets.ISO_8859_1);
        int contentLength = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT)
                    .startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        byte[] body = in.readNBytes(contentLength);
        return headers + new String(body, StandardCharsets.UTF_8);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean waitForStatus200(InputStream in, int streamId, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] frameHeader = new byte[9];
            if (!readFullyOrEof(in, frameHeader)) return false;
            int len = ((frameHeader[0] & 0xff) << 16)
                | ((frameHeader[1] & 0xff) << 8)
                | (frameHeader[2] & 0xff);
            int type = frameHeader[3] & 0xff;
            int frameStreamId = ((frameHeader[5] & 0x7f) << 24)
                | ((frameHeader[6] & 0xff) << 16)
                | ((frameHeader[7] & 0xff) << 8)
                | (frameHeader[8] & 0xff);
            byte[] payload = new byte[len];
            readFully(in, payload);
            if (type == 0x7) return false; // GOAWAY — connection killed
            if (type == 0x1 && frameStreamId == streamId && len >= 1
                    && payload[0] == (byte) 0x88) { // indexed :status 200
                return true;
            }
        }
        return false;
    }

}
