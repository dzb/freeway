package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads exactly {@code contentLength} bytes from the underlying stream,
 * then reports EOF. {@code close()} drains any remaining unread bytes
 * so the connection can be reused for keep-alive.
 */
final class FixedLengthInputStream extends InputStream {

    private final InputStream in;
    private long remaining;
    private boolean closed;
    private boolean eof;
    private final byte[] oneByte = new byte[1];
    private final byte[] drainBuf = new byte[2048];

    public FixedLengthInputStream(InputStream in, long contentLength) {
        this.in = in;
        this.remaining = contentLength;
        this.eof = contentLength == 0;
    }

    @Override
    public int read() throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (eof) return -1;
        int n = read(oneByte, 0, 1);
        return n == -1 ? -1 : oneByte[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (eof) return -1;
        if (len > remaining) {
            len = (int) remaining;
        }
        int n = in.read(b, off, len);
        if (n > 0) {
            remaining -= n;
        }
        if (remaining == 0) {
            eof = true;
        }
        if (n < 0 && !eof) {
            throw new IOException("Connection closed before all data received");
        }
        return n;
    }

    @Override
    public int available() throws IOException {
        if (closed || eof) return 0;
        int a = in.available();
        return (int) Math.min(a, remaining);
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (!eof) {
            drain(remaining);
            eof = true;
        }
    }

    private void drain(long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped > 0) {
                n -= skipped;
            } else {
                int len = (int) Math.min(n, drainBuf.length);
                int r = in.read(drainBuf, 0, len);
                if (r < 0) break;
                n -= r;
            }
        }
    }
}
