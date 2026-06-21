package com.jujin.freeway.benchmarks.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/** Raw-socket WebSocket echo client. */
public final class WsClient implements AutoCloseable {
    private static final byte[] MASK = {0x12, 0x34, 0x56, 0x78};
    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private boolean closed;

    public WsClient(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = socket.getOutputStream();
        handshake();
    }

    /** Send text frame, wait for echo, return round-trip nanos. */
    public long echo(String text) throws IOException {
        long t0 = System.nanoTime();
        sendFrame(text);
        readFrame();
        return System.nanoTime() - t0;
    }

    @Override public void close() throws IOException {
        if (closed) return; closed = true;
        try { sendClose(); } catch (IOException ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
    }

    private void handshake() throws IOException {
        byte[] kb = new byte[16]; new SecureRandom().nextBytes(kb);
        String key = Base64.getEncoder().encodeToString(kb);
        String req = "GET /ws/echo HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.ISO_8859_1)); out.flush();
        String s = Http11Client.readLine(in);
        if (s == null || !s.contains("101")) throw new IOException("WS handshake failed: " + s);
        String l; while (!(l = Http11Client.readLine(in)).isEmpty()) { /* skip headers */ }
    }

    private void sendFrame(String text) throws IOException {
        byte[] p = text.getBytes(StandardCharsets.UTF_8); int len = p.length;
        out.write(0x81);
        if (len < 126) { out.write(0x80 | len); }
        else if (len < 65536) { out.write(0xFE); out.write(len >> 8); out.write(len); }
        else { out.write(0xFF); out.write((int)(len>>56)); out.write((int)(len>>48)); out.write((int)(len>>40)); out.write((int)(len>>32)); out.write(len>>24); out.write(len>>16); out.write(len>>8); out.write(len); }
        out.write(MASK);
        for (int i = 0; i < len; i++) out.write(p[i] ^ MASK[i % 4]);
        out.flush();
    }

    private void readFrame() throws IOException {
        int b0 = in.readUnsignedByte(), op = b0 & 0x0F;
        if (op == 0x8) return;
        if (op == 0x9) { int rl = readLen(); in.skipBytes(rl); readFrame(); return; }
        int rl = readLen(); byte[] p = new byte[rl]; in.readFully(p);
    }

    private int readLen() throws IOException { int b = in.readUnsignedByte() & 0x7F; if (b < 126) return b; if (b == 126) return in.readUnsignedShort(); return (int) in.readLong(); }

    private void sendClose() throws IOException { out.write(0x88); out.write(0x00); out.flush(); }
}
