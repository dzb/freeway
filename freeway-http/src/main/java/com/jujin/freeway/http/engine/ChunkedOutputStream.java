package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes HTTP chunked transfer-coding to the underlying stream.
 * Each write is wrapped in a chunk header ({@code hex-size\r\ndata\r\n}).
 * {@code close()} writes the final zero-length chunk.
 */
final class ChunkedOutputStream extends OutputStream {

    private final OutputStream out;
    private boolean closed;

    ChunkedOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (len == 0) return;
        // chunk header
        String header = Integer.toHexString(len) + "\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        // data
        out.write(b, off, len);
        // chunk trailer
        out.write('\r');
        out.write('\n');
        out.flush();
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        // final zero-length chunk
        out.write('0');
        out.write('\r');
        out.write('\n');
        out.write('\r');
        out.write('\n');
        out.flush();
    }
}
