package com.jujin.freeway.http.engine.http11;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.http.engine.SessionBufferedInputStream;
import com.jujin.freeway.http.engine.SessionBufferedOutputStream;

/**
 * Wraps a connected {@code Socket} with buffered I/O streams.
 * HTTP/1.1 sessions own the streams from a single virtual thread; HTTP/2
 * serializes socket writes through its leader drain; a watchdog virtual
 * thread enforces the write timeout. The buffered streams themselves are
 * single-writer and need no synchronization.
 */
public final class Http11Connection {

    private static final Logger LOG = LoggerFactory.getLogger(Http11Connection.class);

    private final Socket socket;
    private final InputStream bufferedIn;
    private final OutputStream bufferedOut;
    private final WriteTracker writeTracker = new WriteTracker();
    private final Thread watchdog;
    private final long writeTimeoutMillis;

    public volatile boolean closed;
    /** Best-effort hook run before a shutdown force-close (H2 GOAWAY). */
    private volatile Runnable preCloseHook;

    public Http11Connection(Socket socket) throws IOException {
        this(socket, 1024, 0);
    }

    public Http11Connection(Socket socket, int bufferSize) throws IOException {
        this(socket, bufferSize, 0);
    }

    public Http11Connection(Socket socket, int bufferSize, long writeTimeoutMillis)
            throws IOException {
        this.socket = socket;
        this.writeTimeoutMillis = writeTimeoutMillis;
        this.bufferedIn = new SessionBufferedInputStream(socket.getInputStream());
        this.bufferedOut = new SessionBufferedOutputStream(
            new WriteTimeoutOutputStream(socket.getOutputStream(), writeTracker),
            bufferSize);
        this.watchdog = writeTimeoutMillis > 0
            ? Thread.ofVirtual()
                .name("http-write-watchdog")
                .start(this::watchWrites)
            : null;
    }

    /** Returns the TLS session for this connection, or null for plain HTTP. */
    public SSLSession getSSLSession() {
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

    /**
     * sendfile fast path: transfers {@code count} bytes of {@code channel}
     * straight to the socket, bypassing the user-space copy loop. Only
     * available on plain (non-TLS) channel-backed sockets; the transfer is
     * tracked by the write watchdog so a peer that stops reading still gets
     * the connection closed on write timeout.
     */
    public void transferFile(FileChannel channel, long offset, long count)
            throws IOException {
        SocketChannel socketChannel = socket.getChannel();
        if (socketChannel == null) {
            throw new IOException("sendfile is not available on this connection");
        }
        writeTracker.begin();
        try {
            long transferred = 0;
            while (transferred < count) {
                long n = channel.transferTo(
                    offset + transferred, count - transferred, socketChannel);
                if (n <= 0) {
                    throw new IOException("sendfile made no progress");
                }
                transferred += n;
            }
        } finally {
            writeTracker.end();
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (watchdog != null) watchdog.interrupt();
        try { bufferedOut.flush(); } catch (Exception e) { LOG.trace("Flush error during close", e); }
        try { bufferedIn.close(); } catch (Exception e) { LOG.trace("Input close error", e); }
        try { bufferedOut.close(); } catch (Exception e) { LOG.trace("Output close error", e); }
        try { socket.close(); } catch (Exception e) { LOG.trace("Socket close error", e); }
    }

    /** Immediate shutdown path; never blocks trying to flush a dead peer. */
    public void forceClose() {
        closed = true;
        if (watchdog != null) watchdog.interrupt();
        try { socket.close(); } catch (IOException ignored) {}
        try { bufferedIn.close(); } catch (IOException ignored) {}
        try { bufferedOut.close(); } catch (IOException ignored) {}
    }

    /**
     * Installs a best-effort hook run by {@link #preClose()} before the
     * server handle force-closes connections during shutdown. Used by
     * HTTP/2 sessions to send GOAWAY so the peer stops creating streams.
     */
    public void setPreCloseHook(Runnable hook) {
        this.preCloseHook = hook;
    }

    /** Runs the pre-close hook, if any, swallowing failures (best-effort). */
    public void preClose() {
        Runnable hook = preCloseHook;
        if (hook != null) {
            try {
                hook.run();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Enforces the per-write timeout: parks until a socket write has been
     * in progress longer than {@link #writeTimeoutMillis} (or the connection
     * closes), then closes the raw socket so the blocked write unblocks with
     * an IOException instead of pinning its thread forever.
     */
    private void watchWrites() {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(writeTimeoutMillis);
        while (!closed) {
            long start = writeTracker.writeStartNanos();
            long sleepNanos;
            if (start == 0) {
                sleepNanos = timeoutNanos; // idle — nothing to enforce
            } else {
                long elapsed = System.nanoTime() - start;
                if (elapsed >= timeoutNanos) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                    return;
                }
                sleepNanos = Math.min(timeoutNanos - elapsed, 100_000_000L);
            }
            LockSupport.parkNanos(sleepNanos);
            if (Thread.interrupted()) return;
        }
    }

    /** Tracks the currently executing raw socket write (single-writer model:
     *  HTTP/1.1 sessions, WebSocket frames, and HTTP/2 leader drains all
     *  serialize writes to the underlying stream). */
    private static final class WriteTracker {
        private volatile long writeStartNanos;

        void begin() {
            writeStartNanos = System.nanoTime();
        }

        void end() {
            writeStartNanos = 0;
        }

        long writeStartNanos() {
            return writeStartNanos;
        }
    }

    private static final class WriteTimeoutOutputStream extends OutputStream {
        private final OutputStream out;
        private final WriteTracker tracker;

        WriteTimeoutOutputStream(OutputStream out, WriteTracker tracker) {
            this.out = out;
            this.tracker = tracker;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b});
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) return;
            tracker.begin();
            try {
                out.write(b, off, len);
            } finally {
                tracker.end();
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
