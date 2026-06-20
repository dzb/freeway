package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads an HTTP chunked transfer-coding request body.
 * Parses chunk headers ({@code hex-size\r\n}), reads chunk data,
 * and detects the final zero-length chunk.
 */
final class ChunkedInputStream extends InputStream {

    private static final int MAX_CHUNK_HEADER_SIZE = 2050;
    static final char CR = '\r';
    static final char LF = '\n';

    private final InputStream in;
    private int remaining;
    private boolean needToReadHeader = true;
    private boolean closed;
    private boolean eof;

    ChunkedInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (eof) return -1;
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (eof) return -1;
        if (needToReadHeader) {
            remaining = readChunkHeader();
            if (remaining == 0) {
                eof = true;
                consumeCRLF();
                return -1;
            }
            needToReadHeader = false;
        }
        if (len > remaining) {
            len = remaining;
        }
        int n = in.read(b, off, len);
        if (n > 0) {
            remaining -= n;
        }
        if (remaining == 0) {
            needToReadHeader = true;
            consumeCRLF();
        }
        if (n < 0 && !eof) {
            throw new IOException("Connection closed before all chunk data received");
        }
        return n;
    }

    @Override
    public int available() throws IOException {
        if (closed || eof) return 0;
        int a = in.available();
        return Math.min(a, remaining);
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (!eof) {
            // drain remaining chunk data
            try {
                while (!eof) {
                    byte[] buf = new byte[2048];
                    int n = read(buf, 0, buf.length);
                    if (n < 0) break;
                }
            } catch (IOException ignored) { /* best-effort drain */ }
        }
    }

    private int readChunkHeader() throws IOException {
        boolean gotCR = false;
        char[] lenArr = new char[16];
        int lenSize = 0;
        boolean endOfLen = false;
        int read = 0;

        while (true) {
            int c = in.read();
            if (c == -1) throw new IOException("End of stream reading chunk header");
            char ch = (char) c;
            read++;
            if (lenSize == lenArr.length - 1 || read > MAX_CHUNK_HEADER_SIZE) {
                throw new IOException("Invalid chunk header");
            }
            if (gotCR) {
                if (ch == LF) {
                    return parseHex(lenArr, lenSize);
                }
                gotCR = false;
                if (!endOfLen) {
                    lenArr[lenSize++] = ch;
                }
            } else {
                if (ch == CR) {
                    gotCR = true;
                } else if (ch == ';') {
                    endOfLen = true; // chunk extension, ignore
                } else if (!endOfLen) {
                    lenArr[lenSize++] = ch;
                }
            }
        }
    }

    private static int parseHex(char[] arr, int nchars) throws IOException {
        int len = 0;
        for (int i = 0; i < nchars; i++) {
            char c = arr[i];
            int val;
            switch (c) {
                case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> val = c - '0';
                case 'a', 'b', 'c', 'd', 'e', 'f' -> val = c - 'a' + 10;
                case 'A', 'B', 'C', 'D', 'E', 'F' -> val = c - 'A' + 10;
                default -> throw new IOException("Invalid chunk length character: " + c);
            }
            len = len * 16 + val;
        }
        return len;
    }

    private void consumeCRLF() throws IOException {
        char c = (char) in.read();
        if (c != CR) throw new IOException("Invalid chunk end: expected CR");
        c = (char) in.read();
        if (c != LF) throw new IOException("Invalid chunk end: expected LF");
    }
}
