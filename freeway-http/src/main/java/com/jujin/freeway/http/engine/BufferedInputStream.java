package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.InputStream;

/**
 * Non-synchronized buffered input stream designed for virtual threads
 * where each connection has exclusive access to its streams.
 */
final class BufferedInputStream extends InputStream {

    private final InputStream in;
    private byte[] buf = new byte[1024];
    private int count;
    private int pos;

    BufferedInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        if (pos >= count) {
            fill();
            if (pos >= count) return -1;
        }
        return buf[pos++] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int avail = count - pos;
        if (avail <= 0) {
            if (len >= buf.length) {
                return in.read(b, off, len);
            }
            fill();
            avail = count - pos;
            if (avail <= 0) return -1;
        }
        int n = Math.min(avail, len);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    @Override
    public int available() throws IOException {
        int n = count - pos;
        return n > 0 ? n : in.available();
    }

    @Override
    public long skip(long n) throws IOException {
        long avail = count - pos;
        if (avail <= 0) return in.skip(n);
        long skipped = Math.min(avail, n);
        pos += (int) skipped;
        return skipped;
    }

    @Override
    public void close() throws IOException {
        buf = null;
        in.close();
    }

    private void fill() throws IOException {
        pos = 0;
        count = 0;
        int n = in.read(buf);
        if (n > 0) count = n;
    }
}
