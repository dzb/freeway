package com.jujin.freeway.http.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reading a running server's bytes straight off a socket.
 *
 * <p>These tests assert on what went <em>on the wire</em> — the frame header
 * lengths, the {@code :status} pseudo-header, the {@code Content-Length} the
 * server framed a body with. A client API hands over a parsed response and
 * with it the very thing under test, so these helpers do the framing
 * themselves.
 *
 * <p>The HTTP/1 and HTTP/2 halves sit together because that is one job, not
 * two: speaking to a server the way a socket does, rather than the way the
 * framework's own client does. Neither half is a client implementation — they
 * read, and they stop at what the test needs.
 *
 * <p>Previously each of these existed verbatim in two or three test classes.
 * Frame parsing is exactly the kind of code where a fix applied to one copy
 * leaves the other quietly asserting the old behaviour, so it has one home.
 */
final class TestRawHttp {

    private TestRawHttp() {
    }

    // ==================== HTTP/2 frame level ====================

    /**
     * Fills {@code buffer} or throws. The frame header and payload are read
     * with this one, so a truncated frame is an error rather than a zero
     * length that would decode into a plausible-looking wrong answer.
     */
    static void readFully(InputStream in, byte[] buffer)
            throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n < 0) throw new IOException("EOF while reading " + buffer.length + " bytes");
            offset += n;
        }
    }

    /**
     * The same read, but a clean EOF answers {@code false} instead of
     * throwing — for a caller polling frames that may simply not have arrived
     * within its budget.
     */
    static boolean readFullyOrEof(InputStream in, byte[] buffer)
            throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n < 0) return false;
            offset += n;
        }
        return true;
    }

    /**
     * Skips frames until {@code streamId} answers {@code :status 200}, or the
     * budget runs out. A GOAWAY ({@code 0x7}) ends the wait: the connection
     * was killed, so no later frame can arrive on it.
     */
    static boolean waitForStatus200(InputStream in, int streamId, long timeoutMs) throws IOException {
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

    // ==================== HTTP/1 response ====================

    /**
     * The response head and body as one string: headers verbatim (they are
     * what several tests assert on), then {@code Content-Length} bytes of
     * body decoded as UTF-8.
     */
    static String readHttpResponse(Socket sock) throws IOException {
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
}
