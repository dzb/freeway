package com.jujin.freeway.benchmarks.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/**
 * Raw-socket HTTP/1.1 client for benchmarking.
 *
 * <p>Supports multiple request shapes via {@link RequestPattern}.
 * Defaults to GET /ping → 200 "pong" for backward compatibility.
 */
public final class Http11Client implements AutoCloseable {
    private static final byte[] DEFAULT_REQUEST = buildRequest("GET", "/ping");
    private static final byte[] EXPECTED_PONG = "pong".getBytes(StandardCharsets.ISO_8859_1);

    /** Describes an HTTP request and its expected response. */
    public record RequestPattern(String method, String path, byte[] expectedBody) {
        public static final RequestPattern PING = new RequestPattern("GET", "/ping",
            "pong".getBytes(StandardCharsets.ISO_8859_1));
        public static final RequestPattern JSON = new RequestPattern("GET", "/api/resource",
            "{\"id\":1,\"name\":\"test\"}".getBytes(StandardCharsets.ISO_8859_1));
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final byte[] request;
    private final byte[] expectedBody;

    public Http11Client(int port) throws IOException {
        this(port, RequestPattern.PING);
    }

    public Http11Client(int port, RequestPattern pattern) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        this.request = buildRequest(pattern.method(), pattern.path());
        this.expectedBody = pattern.expectedBody();
    }

    /** Sends the configured request and returns true if the response matches expectations. */
    public boolean send() throws IOException {
        out.write(request); out.flush();
        String s = readLine(in);
        if (s == null || !s.startsWith("HTTP/1.1 200")) return false;
        int cl = -1;
        while (true) {
            String l = readLine(in);
            if (l == null || l.isEmpty()) break;
            int c = l.indexOf(':');
            if (c > 0 && l.substring(0, c).equalsIgnoreCase("Content-Length"))
                cl = Integer.parseInt(l.substring(c + 1).trim());
        }
        if (cl < 0) return false;
        byte[] b = new byte[cl];
        int o = 0;
        while (o < cl) {
            int n = in.read(b, o, cl - o);
            if (n < 0) throw new IOException("EOF");
            o += n;
        }
        return Arrays.equals(b, expectedBody);
    }

    /** Legacy alias: sends GET /ping and checks for "pong". */
    public boolean sendPing() throws IOException {
        return send();
    }

    @Override public void close() throws IOException { socket.close(); }

    static String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder(64); int p = -1;
        while (true) {
            int c = in.read();
            if (c < 0) return sb.isEmpty() ? null : sb.toString();
            if (p == '\r' && c == '\n') {
                sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) c);
            p = c;
        }
    }

    private static byte[] buildRequest(String method, String path) {
        return (method + " " + path + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\n"
            + "Connection: keep-alive\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    }
}
