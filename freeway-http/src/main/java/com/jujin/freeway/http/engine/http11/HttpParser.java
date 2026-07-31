package com.jujin.freeway.http.engine.http11;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.*;

/**
 * Parses HTTP/1.x request line and headers from a raw {@code InputStream}.
 * Bulk-reads into a reusable buffer to minimize socket calls.
 */
public final class HttpParser {

    private static final int MAX_HEADER_COUNT = 200;
    private static final int MAX_HEADER_SIZE = 8192;
    private static final int MAX_REQUEST_LINE_SIZE = 8192;
    static final char CR = '\r';
    static final char LF = '\n';

    private InputStream in;
    private final byte[] buf = new byte[4096]; // reusable bulk-read buffer
    private int pos;  // current read position in buf
    private int end;  // valid bytes in buf (pos..end)

    // Reusable builders — allocated once per parser, reset per request
    private final StringBuilder reqLineBuf = new StringBuilder(64);
    private final StringBuilder headerKeyBuf = new StringBuilder(32);
    private final StringBuilder headerValBuf = new StringBuilder(128);

    public HttpParser(InputStream in) { this.in = in; }

    /** Reuse this parser for a new request on the same connection. */
    public void reset(InputStream newIn) {
        this.in = newIn;
        pos = 0; end = 0;
        reqLineBuf.setLength(0);
        headerKeyBuf.setLength(0);
        headerValBuf.setLength(0);
    }

    public ParsedRequest parse() throws IOException {
        // Preserve unread bytes from previous pipelined request
        if (pos > 0 && pos < end) {
            System.arraycopy(buf, pos, buf, 0, end - pos);
            end -= pos;
        } else {
            end = 0;
        }
        pos = 0;

        String requestLine = readRequestLine();
        if (requestLine == null) return null;
        // RFC 7230 §3.5: servers MUST ignore at least one empty line received
        // before the request-line (clients occasionally emit a stray CRLF on
        // keep-alive connections).
        int emptyLines = 0;
        while (requestLine.isEmpty()) {
            if (++emptyLines > 5) {
                throw new IOException("Too many empty lines before request");
            }
            requestLine = readRequestLine();
            if (requestLine == null) return null;
        }

        // Manual space-scan — avoids split(" ", 3) regex + String[] allocation
        int sp1 = requestLine.indexOf(' ');
        int sp2 = requestLine.indexOf(' ', sp1 + 1);
        if (sp1 < 0 || sp2 < 0)
            throw new IOException("Malformed request line: " + requestLine);
        String method = requestLine.substring(0, sp1);
        String rawUri = requestLine.substring(sp1 + 1, sp2);
        String httpVersion = requestLine.substring(sp2 + 1);

        // Fast path/path+query extraction — avoids expensive URI parsing
        String path, queryString = null;
        int qIdx = rawUri.indexOf('?');
        if (qIdx >= 0) {
            path = rawUri.substring(0, qIdx);
            queryString = rawUri.substring(qIdx + 1);
        } else {
            path = rawUri;
        }
        if (path.isEmpty() || path.charAt(0) != '/') path = "/" + path;

        // Switch on raw version string — avoids toUpperCase(Locale.ROOT) allocation
        boolean isHttp10, isHttp2Preface;
        switch (httpVersion) {
            case "HTTP/1.0" -> { isHttp10 = true;  isHttp2Preface = false; }
            case "HTTP/2.0" -> { isHttp10 = false; isHttp2Preface = true; }
            case "HTTP/1.1" -> { isHttp10 = false; isHttp2Preface = false; }
            default -> throw new IOException("Unsupported HTTP version: " + httpVersion);
        }
        if (isHttp2Preface && !"PRI".equals(method))
            throw new IOException("Unsupported HTTP version: " + httpVersion);
        if (isHttp2Preface)
            return new ParsedRequest("PRI", "*", null, "HTTP/2.0",
                Map.of(), -1, false, false, false, false, true);

        Map<String, List<String>> headers = parseHeaders();
        long contentLength = -1;
        boolean isChunked = false, keepAlive = !isHttp10;
        boolean connectionUpgrade = false, upgradeWebsocket = false;

        for (var entry : headers.entrySet()) {
            switch (entry.getKey().toLowerCase(Locale.ROOT)) {
                case "content-length" -> {
                    if (contentLength >= 0) throw new IOException("Duplicate Content-Length header");
                    String v = entry.getValue().getFirst();
                    if (entry.getValue().size() > 1) throw new IOException("Duplicate Content-Length values");
                    if (v != null) {
                        try { contentLength = Long.parseLong(v); }
                        catch (NumberFormatException e) { throw new IOException("Invalid Content-Length: " + v); }
                        if (contentLength < 0) throw new IOException("Invalid Content-Length: " + v);
                    }
                }
                case "transfer-encoding" -> {
                    for (String v : entry.getValue()) {
                        if (v != null) {
                            for (String token : v.split(",")) {
                                token = token.trim();
                                if ("chunked".equalsIgnoreCase(token)) {
                                    isChunked = true;
                                } else if (!token.isEmpty()) {
                                    throw new IOException("Unsupported Transfer-Encoding: " + token);
                                }
                            }
                        }
                    }
                }
                case "connection" -> {
                    String v = entry.getValue().getFirst();
                    if (v != null) {
                        // RFC 7230 §6.1: Connection = #token → comma-separated list
                        for (String token : v.split(",")) {
                            token = token.trim();
                            if ("keep-alive".equalsIgnoreCase(token)) keepAlive = true;
                            if ("close".equalsIgnoreCase(token)) keepAlive = false;
                            if ("upgrade".equalsIgnoreCase(token)) connectionUpgrade = true;
                        }
                    }
                }
                case "upgrade" -> {
                    String v = entry.getValue().getFirst();
                    if (v != null && "websocket".equalsIgnoreCase(v)) upgradeWebsocket = true;
                }
            }
        }

        if (contentLength >= 0 && isChunked) {
            throw new IOException("Invalid request: both Content-Length and Transfer-Encoding: chunked");
        }

        return new ParsedRequest(method, path, queryString, httpVersion, headers,
            contentLength, isChunked, isHttp10, keepAlive,
            connectionUpgrade && upgradeWebsocket, false);
    }

    // --- bulk-read helpers ---

    /** Ensures at least one byte is available, reading from the stream if needed. */
    private int nextByte() throws IOException {
        if (pos >= end) {
            pos = 0;
            end = in.read(buf, 0, buf.length);
            if (end <= 0) return -1;
        }
        return buf[pos++] & 0xFF;
    }

    // --- request line parser ---

    private String readRequestLine() throws IOException {
        reqLineBuf.setLength(0);
        boolean gotCR = false;
        while (true) {
            int c = nextByte();
            if (c == -1) {
                if (reqLineBuf.isEmpty()) return null; // empty stream → clean
                throw new IOException("EOF while reading HTTP request line");
            }
            if (reqLineBuf.length() >= MAX_REQUEST_LINE_SIZE) {
                throw new IOException(
                    "Request line too long (max " + MAX_REQUEST_LINE_SIZE + " chars)"
                );
            }
            if (gotCR) {
                if (c == LF) return reqLineBuf.isEmpty() ? "" : reqLineBuf.toString();
                gotCR = false;
                reqLineBuf.append(CR).append((char) c);
            } else {
                if (c == CR) gotCR = true;
                else reqLineBuf.append((char) c);
            }
        }
    }

    // --- header parser ---

    private Map<String, List<String>> parseHeaders() throws IOException {
        var headers = new LinkedHashMap<String, List<String>>();
        headerKeyBuf.setLength(0);
        headerValBuf.setLength(0);
        var key = headerKeyBuf;
        var value = headerValBuf;
        var current = key;
        boolean prevCR = false, startOfLine = true, afterColon = false;
        int headerCount = 0, totalSize = 0;

        while (true) {
            int c = nextByte();
            if (c == -1) {
                if (startOfLine) return headers; // clean EOF after complete headers
                throw new IOException("EOF while reading HTTP headers");
            }
            totalSize++;
            if (totalSize > MAX_HEADER_SIZE) throw new IOException("Headers too large");

            if (c == CR) {
                prevCR = true;
            } else if (c == LF && prevCR) {
                if (key.isEmpty() && value.isEmpty()) break;
                if (startOfLine) { addHeader(headers); if (++headerCount > MAX_HEADER_COUNT) throw new IOException("Too many headers"); break; }
                prevCR = false; startOfLine = true;
            } else {
                if (startOfLine && (c == ' ' || c == '\t')) {
                    current = value; startOfLine = false;
                } else {
                    if (startOfLine) {
                        if (!key.isEmpty() || !value.isEmpty()) { addHeader(headers); if (++headerCount > MAX_HEADER_COUNT) throw new IOException("Too many headers"); }
                        current = key; startOfLine = false; afterColon = false;
                    }
                    if (c == ':' && current == key) {
                        current = value; afterColon = true;
                    } else if (afterColon && (c == ' ' || c == '\t')) {
                        // skip leading whitespace after colon
                    } else {
                        afterColon = false;
                        if (current == key) {
                            // Normalize header keys to lowercase per RFC 7230 §3.2
                            char ch = (char) c;
                            key.append(ch >= 'A' && ch <= 'Z' ? (char)(ch + 32) : ch);
                        } else {
                            current.append((char) c);
                        }
                    }
                }
            }
        }
        return headers;
    }

    private void addHeader(Map<String, List<String>> headers) {
        // Strip trailing OWS per RFC 7230 §3.2.6 — clients and intermediaries
        // may append spaces/tabs before CRLF (e.g. "Content-Length: 4 ").
        int end = headerValBuf.length();
        while (end > 0 && (headerValBuf.charAt(end - 1) == ' ' || headerValBuf.charAt(end - 1) == '\t')) {
            end--;
        }
        String value = end == headerValBuf.length()
            ? headerValBuf.toString()
            : headerValBuf.substring(0, end);
        headers.computeIfAbsent(headerKeyBuf.toString(), k -> new ArrayList<>(4))
               .add(value);
        headerKeyBuf.setLength(0);
        headerValBuf.setLength(0);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        int hl = haystack.length(), nl = needle.length();
        for (int i = 0; i <= hl - nl; i++)
            if (haystack.regionMatches(true, i, needle, 0, nl)) return true;
        return false;
    }

    /**
     * Returns an {@code InputStream} for reading the request body.
     * Includes any bytes already buffered past the header boundary,
     * followed by the remaining raw socket input.
     * Call this once after {@link #parse()}.
     */
    public InputStream bodyStream() {
        if (pos >= end) return in;
        var prefix = new ByteArrayInputStream(buf, pos, end - pos);
        pos = end; // consumed
        return new SequenceInputStream(prefix, in);
    }

    public record ParsedRequest(
        String method, String path, String queryString, String httpVersion,
        Map<String, List<String>> headers, long contentLength, boolean isChunked,
        boolean isHttp10, boolean keepAlive, boolean isUpgradeRequest,
        boolean isHttp2Preface
    ) {}
}
