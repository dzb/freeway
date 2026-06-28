package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Non-synchronized buffered output stream designed for virtual threads
 * where each connection has exclusive access to its streams.
 */
public final class BufferedOutputStream extends OutputStream {

    private final OutputStream out;
    private byte[] buf = new byte[1024];
    private int count;

    public BufferedOutputStream(OutputStream out) {
        this(out, 1024);
    }

    public BufferedOutputStream(OutputStream out, int bufferSize) {
        this.out = out;
        if (bufferSize < 256) throw new IllegalArgumentException("bufferSize must be at least 256");
        this.buf = new byte[bufferSize];
    }

    @Override
    public void write(int b) throws IOException {
        if (count >= buf.length) flushBuffer();
        buf[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len >= buf.length) {
            flushBuffer();
            out.write(b, off, len);
            return;
        }
        if (len > buf.length - count) flushBuffer();
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    @Override
    public void flush() throws IOException {
        flushBuffer();
        out.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
        buf = null;
        out.close();
    }

    private void flushBuffer() throws IOException {
        if (count > 0) {
            out.write(buf, 0, count);
            count = 0;
        }
    }
}
