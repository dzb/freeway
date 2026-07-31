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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a connected {@code Socket} with buffered I/O streams.
 * Each connection is owned by a single virtual thread — no synchronization needed.
 */
public final class Http11Connection {

    private static final Logger LOG = LoggerFactory.getLogger(Http11Connection.class);

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


    /** Returns true when this connection is transported over TLS (HTTPS). */
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
        try { bufferedOut.flush(); } catch (Exception e) { LOG.trace("Flush error during close", e); }
        try { bufferedIn.close(); } catch (Exception e) { LOG.trace("Input close error", e); }
        try { bufferedOut.close(); } catch (Exception e) { LOG.trace("Output close error", e); }
        try { socket.close(); } catch (Exception e) { LOG.trace("Socket close error", e); }
    }

}
