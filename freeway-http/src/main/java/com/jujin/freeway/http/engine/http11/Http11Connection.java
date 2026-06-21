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
 * Wraps a connected {@code Socket} with buffered I/O streams and activity
 * tracking for idle timeout management. Each connection is owned by a
 * single virtual thread — no synchronization needed.
 */
public final class Http11Connection {

    private final Socket socket;
    private final InputStream bufferedIn;
    private final OutputStream bufferedOut;

    volatile long lastActivityTime;
    public volatile boolean closed;

    public Http11Connection(Socket socket) throws IOException {
        this(socket, 1024);
    }

    public Http11Connection(Socket socket, int bufferSize) throws IOException {
        this.socket = socket;
        this.bufferedIn = new BufferedInputStream(new ActivityTrackingInputStream(socket.getInputStream()));
        this.bufferedOut = new BufferedOutputStream(new ActivityTrackingOutputStream(socket.getOutputStream()), bufferSize);
        this.lastActivityTime = System.currentTimeMillis();
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

    private class ActivityTrackingInputStream extends InputStream {
        private final InputStream delegate;
        ActivityTrackingInputStream(InputStream delegate) { this.delegate = delegate; }
        @Override public int read() throws IOException { return delegate.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }
        @Override public int available() throws IOException { return delegate.available(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    private class ActivityTrackingOutputStream extends OutputStream {
        private final OutputStream delegate;
        ActivityTrackingOutputStream(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int b) throws IOException { delegate.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
