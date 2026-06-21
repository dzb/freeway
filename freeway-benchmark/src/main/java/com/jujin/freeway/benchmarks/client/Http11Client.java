package com.jujin.freeway.benchmarks.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/** Raw-socket HTTP/1.1 ping client. */
public final class Http11Client implements AutoCloseable {
    private static final byte[] PING = ("GET /ping HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PONG = "pong".getBytes(StandardCharsets.ISO_8859_1);
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public Http11Client(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    public boolean sendPing() throws IOException {
        out.write(PING); out.flush();
        String s = readLine(in);
        if (s == null || !s.startsWith("HTTP/1.1 200")) return false;
        int cl = -1;
        while (true) { String l = readLine(in); if (l == null || l.isEmpty()) break; int c = l.indexOf(':'); if (c > 0 && l.substring(0, c).equalsIgnoreCase("Content-Length")) cl = Integer.parseInt(l.substring(c + 1).trim()); }
        if (cl < 0) return false;
        byte[] b = new byte[cl]; int o = 0; while (o < cl) { int n = in.read(b, o, cl - o); if (n < 0) throw new IOException("EOF"); o += n; }
        return Arrays.equals(b, PONG);
    }

    @Override public void close() throws IOException { socket.close(); }

    static String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder(64); int p = -1;
        while (true) { int c = in.read(); if (c < 0) return sb.isEmpty() ? null : sb.toString(); if (p == '\r' && c == '\n') { sb.setLength(sb.length() - 1); return sb.toString(); } sb.append((char) c); p = c; }
    }
}
