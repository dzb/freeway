package com.jujin.freeway.http.engine.http11;
import com.jujin.freeway.http.engine.BufferedInputStream;
import com.jujin.freeway.http.engine.BufferedOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * Wraps a connected {@code Socket} with buffered I/O streams.
 * Each connection is owned by a single virtual thread — no synchronization needed.
 */
public final class Http11Connection {

    private final Socket socket;
    private final InputStream bufferedIn;
    private final OutputStream bufferedOut;

    public volatile boolean closed;

    public Http11Connection(Socket socket) throws IOException {
        this(socket, 1024);
    }

    public Http11Connection(Socket socket, int bufferSize) throws IOException {
        this.socket = socket;
        this.bufferedIn = new BufferedInputStream(socket.getInputStream());
        this.bufferedOut = new BufferedOutputStream(socket.getOutputStream(), bufferSize);
    }

    public boolean isSSL() { return socket instanceof SSLSocket; }

    SSLSession getSSLSession() {
        return socket instanceof SSLSocket ssl ? ssl.getSession() : null;
    }

    public Socket socket() { return socket; }
    public InputStream inputStream() { return bufferedIn; }
    public OutputStream outputStream() { return bufferedOut; }

    InetSocketAddress remoteAddress() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    InetSocketAddress localAddress() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    public void close() {
        if (closed) return;
        closed = true;
        try { bufferedOut.flush(); } catch (IOException ignored) {}
        try { bufferedIn.close(); } catch (IOException ignored) {}
        try { bufferedOut.close(); } catch (IOException ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
    }

}
