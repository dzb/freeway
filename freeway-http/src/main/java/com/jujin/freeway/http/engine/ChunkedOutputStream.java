package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * HTTP/1.1 chunked transfer encoding writer: frames each write as
 * {@code hex-length\r\n} + data + {@code \r\n}, and sends the terminating
 * {@code 0\r\n\r\n} on close.
 */
final class ChunkedOutputStream extends OutputStream {

    private final OutputStream out;
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] TERMINAL_CHUNK = {'0', '\r', '\n', '\r', '\n'};
    private boolean closed;

    ChunkedOutputStream(OutputStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b});
    }

    @Override
    public void write(byte[] data, int off, int len) throws IOException {
        if (len == 0) return;
        String hex = Integer.toHexString(len);
        out.write(hex.getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
        out.write(data, off, len);
        out.write(CRLF);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            out.write(TERMINAL_CHUNK);
            out.flush();
        } finally {
            out.close();
        }
    }
}
