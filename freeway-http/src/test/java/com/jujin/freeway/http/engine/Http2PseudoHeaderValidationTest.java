package com.jujin.freeway.http.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.WebServerBuilder;
import com.jujin.freeway.http.route.Route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * HTTP/2 pseudo-header validation (RFC 7540 §8.1.2.3 / §8.3.1): {@code
 * :path} must be origin-form ("/..." or "*" for OPTIONS only — no "//…"
 * authority-form, no absolute-form), and {@code :authority} must follow the
 * HTTP/1.1 Host character rules. {@code :authority} is optional for
 * non-CONNECT requests. Invalid pseudo-headers fail the connection with
 * GOAWAY(PROTOCOL_ERROR), matching how HeaderFields.validate() errors are
 * surfaced by Http2Connection.
 */
class Http2PseudoHeaderValidationTest {

    private static final byte[] PREFACE =
        "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    // Static-table shorthands (RFC 7541 Appendix A)
    private static final byte GET = (byte) 0x82;        // indexed :method GET
    private static final byte SCHEME_HTTP = (byte) 0x86; // indexed :scheme http
    private static final byte PATH_INDEX = (byte) 0x84;  // indexed :path /

    @Test
    void pathWithoutLeadingSlashIsRejected() throws Exception {
        // :path "nope" — must start with '/' (RFC 9113 §8.3.1).
        byte[] block = concat(new byte[] {GET, (byte) 0x44, 0x04},
            "nope".getBytes(StandardCharsets.ISO_8859_1),
            new byte[] {SCHEME_HTTP}, authority("localhost"));
        assertGoawayProtocolError(block, "a :path without a leading '/'");
    }

    @Test
    void networkPathReferenceIsRejected() throws Exception {
        // :path "//evil.com/x" — authority-form must not reach the router.
        byte[] block = concat(new byte[] {GET, (byte) 0x44, 0x0C},
            "//evil.com/x".getBytes(StandardCharsets.ISO_8859_1),
            new byte[] {SCHEME_HTTP}, authority("localhost"));
        assertGoawayProtocolError(block, "a network-path :path '//…'");
    }

    @Test
    void absoluteFormPathIsRejected() throws Exception {
        // :path "http://evil.com/x" — absolute-form must not reach the router.
        byte[] block = concat(new byte[] {GET, (byte) 0x44, 0x11},
            "http://evil.com/x".getBytes(StandardCharsets.ISO_8859_1),
            new byte[] {SCHEME_HTTP}, authority("localhost"));
        assertGoawayProtocolError(block, "an absolute-form :path");
    }

    @Test
    void authorityWithUserInfoIsRejected() throws Exception {
        // :authority "user@host" — '@' is forbidden in authority/Host.
        byte[] block = concat(new byte[] {GET, PATH_INDEX, SCHEME_HTTP,
            (byte) 0x41, 0x09}, "user@host".getBytes(StandardCharsets.ISO_8859_1));
        assertGoawayProtocolError(block, "an :authority with userinfo");
    }

    @Test
    void authorityWithWhitespaceIsRejected() throws Exception {
        byte[] block = concat(new byte[] {GET, PATH_INDEX, SCHEME_HTTP,
            (byte) 0x41, 0x05}, "ho st".getBytes(StandardCharsets.ISO_8859_1));
        assertGoawayProtocolError(block, "an :authority with whitespace");
    }

    @Test
    void missingAuthorityIsAccepted() throws Exception {
        // RFC 7540 §8.1.2.3: :authority is optional for non-CONNECT requests.
        byte[] block = new byte[] {GET, PATH_INDEX, SCHEME_HTTP};
        WebServer server = server();
        server.start();
        try {
            try (var socket = openH2(server.port())) {
                var out = socket.getOutputStream();
                writeHeaders(out, 1, block, true);
                assertTrue(waitForStatus200(socket.getInputStream(), 1, 5000),
                    "a request without :authority must be served normally");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void optionsStarPathIsAccepted() throws Exception {
        // RFC 9113 §8.3.1: "*" is a valid :path for OPTIONS. The router may
        // answer 404/405 (no OPTIONS route) — what matters is that the
        // pseudo-headers are accepted: the stream gets a response instead of
        // the connection being killed with GOAWAY(PROTOCOL_ERROR).
        byte[] block = concat(new byte[] {(byte) 0x02, 0x07},
            "OPTIONS".getBytes(StandardCharsets.ISO_8859_1),
            new byte[] {(byte) 0x44, 0x01}, "*".getBytes(StandardCharsets.ISO_8859_1),
            new byte[] {SCHEME_HTTP}, authority("localhost"));
        WebServer server = server();
        server.start();
        try {
            try (var socket = openH2(server.port())) {
                var out = socket.getOutputStream();
                writeHeaders(out, 1, block, true);
                assertTrue(waitForAnyResponse(socket.getInputStream(), 1, 5000),
                    "OPTIONS * must be served, not killed as a protocol error");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void validRequestStillServed() throws Exception {
        byte[] block = concat(new byte[] {GET, PATH_INDEX, SCHEME_HTTP},
            authority("localhost"));
        WebServer server = server();
        server.start();
        try {
            try (var socket = openH2(server.port())) {
                var out = socket.getOutputStream();
                writeHeaders(out, 1, block, true);
                assertTrue(waitForStatus200(socket.getInputStream(), 1, 5000),
                    "a fully valid request must be served");
            }
        } finally {
            server.stop();
        }
    }

    // --- plumbing ---

    private static WebServer server() {
        return WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
    }

    private static void assertGoawayProtocolError(byte[] block, String what)
            throws Exception {
        WebServer server = server();
        server.start();
        try {
            try (var socket = openH2(server.port())) {
                socket.setSoTimeout(5000);
                writeHeaders(socket.getOutputStream(), 1, block, true);
                long deadline = System.currentTimeMillis() + 5000;
                var in = socket.getInputStream();
                while (System.currentTimeMillis() < deadline) {
                    byte[] header = new byte[9];
                    if (!readFullyOrEof(in, header)) break;
                    int len = ((header[0] & 0xff) << 16)
                        | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
                    int type = header[3] & 0xff;
                    byte[] payload = new byte[len];
                    readFully(in, payload);
                    if (type == 0x7 && len >= 8) { // GOAWAY
                        int errorCode = ((payload[4] & 0xff) << 24)
                            | ((payload[5] & 0xff) << 16)
                            | ((payload[6] & 0xff) << 8) | (payload[7] & 0xff);
                        assertEquals(0x1, errorCode,
                            what + " must fail with GOAWAY(PROTOCOL_ERROR)");
                        return;
                    }
                }
                fail(what + " must produce a GOAWAY(PROTOCOL_ERROR)");
            }
        } finally {
            server.stop();
        }
    }

    /** Sends the h2c preface, consumes the server SETTINGS preface, and
     *  returns the client socket. */
    private static Socket openH2(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10_000);
        var out = socket.getOutputStream();
        out.write(PREFACE);
        out.flush();
        var in = socket.getInputStream();
        byte[] header = new byte[9];
        readFully(in, header);
        int settingsLen = ((header[0] & 0xff) << 16)
            | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
        readFully(in, new byte[settingsLen]);
        return socket;
    }

    /** Literal :authority without indexing (0x40 | name index 1). */
    private static byte[] authority(String value) {
        byte[] v = value.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[2 + v.length];
        out[0] = (byte) 0x41;
        out[1] = (byte) v.length;
        System.arraycopy(v, 0, out, 2, v.length);
        return out;
    }

    private static void writeHeaders(OutputStream out, int streamId,
                                     byte[] block, boolean endStream)
            throws IOException {
        int flags = 0x4 | (endStream ? 0x1 : 0x0); // END_HEADERS [| END_STREAM]
        out.write(new byte[] {
            (byte) ((block.length >> 16) & 0xff),
            (byte) ((block.length >> 8) & 0xff),
            (byte) (block.length & 0xff),
            0x1, // HEADERS
            (byte) flags,
            (byte) ((streamId >> 24) & 0x7f),
            (byte) ((streamId >> 16) & 0xff),
            (byte) ((streamId >> 8) & 0xff),
            (byte) (streamId & 0xff)
        });
        out.write(block);
        out.flush();
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static boolean waitForStatus200(InputStream in, int streamId,
            long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] frameHeader = new byte[9];
            if (!readFullyOrEof(in, frameHeader)) return false;
            int len = ((frameHeader[0] & 0xff) << 16)
                | ((frameHeader[1] & 0xff) << 8) | (frameHeader[2] & 0xff);
            int type = frameHeader[3] & 0xff;
            int frameStreamId = ((frameHeader[5] & 0x7f) << 24)
                | ((frameHeader[6] & 0xff) << 16)
                | ((frameHeader[7] & 0xff) << 8) | (frameHeader[8] & 0xff);
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

    /** True when any response HEADERS frame arrives on the stream before a
     *  GOAWAY/EOF — used where the router's status is not the point (the
     *  pseudo-headers must simply be accepted). */
    private static boolean waitForAnyResponse(InputStream in, int streamId,
            long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] frameHeader = new byte[9];
            if (!readFullyOrEof(in, frameHeader)) return false;
            int len = ((frameHeader[0] & 0xff) << 16)
                | ((frameHeader[1] & 0xff) << 8) | (frameHeader[2] & 0xff);
            int type = frameHeader[3] & 0xff;
            int frameStreamId = ((frameHeader[5] & 0x7f) << 24)
                | ((frameHeader[6] & 0xff) << 16)
                | ((frameHeader[7] & 0xff) << 8) | (frameHeader[8] & 0xff);
            byte[] payload = new byte[len];
            readFully(in, payload);
            if (type == 0x7) return false; // GOAWAY — connection killed
            if (type == 0x1 && frameStreamId == streamId && len >= 1) {
                return true;
            }
        }
        return false;
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
}
