package com.jujin.freeway.http.engine;

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
final class Http11Connection {

    private final Socket socket;
    private final InputStream rawIn;
    private final OutputStream rawOut;
    private final InputStream bufferedIn;
    private final OutputStream bufferedOut;

    volatile long lastActivityTime;
    public volatile boolean closed;

    Http11Connection(Socket socket) throws IOException {
        this.socket = socket;
        this.rawIn = socket.getInputStream();
        this.rawOut = socket.getOutputStream();
        this.bufferedIn = new BufferedInputStream(new ActivityTrackingInputStream(rawIn));
        this.bufferedOut = new BufferedOutputStream(new ActivityTrackingOutputStream(rawOut));
        this.lastActivityTime = System.currentTimeMillis();
    }

    boolean isSSL() { return socket instanceof SSLSocket; }

    SSLSession getSSLSession() {
        return socket instanceof SSLSocket ssl ? ssl.getSession() : null;
    }

    Socket socket() { return socket; }
    InputStream inputStream() { return bufferedIn; }
    OutputStream outputStream() { return bufferedOut; }

    InetSocketAddress remoteAddress() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    InetSocketAddress localAddress() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    void close() {
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
