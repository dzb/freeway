package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes exactly {@code contentLength} bytes to the underlying stream.
 * Excess writes throw {@code IOException}.
 */
final class FixedLengthOutputStream extends OutputStream {

    private final OutputStream out;
    private long remaining;
    private boolean closed;

    FixedLengthOutputStream(OutputStream out, long contentLength) {
        this.out = out;
        this.remaining = contentLength;
    }

    @Override
    public void write(int b) throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (remaining == 0) throw new IOException("Content-Length exceeded");
        out.write(b);
        remaining--;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (remaining == 0) throw new IOException("Content-Length exceeded");
        if (len > remaining) {
            len = (int) remaining;
        }
        out.write(b, off, len);
        remaining -= len;
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        out.flush();
    }
}
