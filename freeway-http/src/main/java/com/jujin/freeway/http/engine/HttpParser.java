package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses HTTP/1.x request line and headers from a raw {@code InputStream}.
 * Bulk-reads into a reusable buffer to minimize socket calls.
 */
final class HttpParser {

    private static final int MAX_HEADER_COUNT = 200;
    private static final int MAX_HEADER_SIZE = 8192;
    static final char CR = '\r';
    static final char LF = '\n';

    private InputStream in;
    private final byte[] buf = new byte[4096]; // reusable bulk-read buffer
    private int pos;  // current read position in buf
    private int end;  // valid bytes in buf (pos..end)

    HttpParser(InputStream in) { this.in = in; }

    /** Reuse this parser for a new request on the same connection. */
    void reset(InputStream newIn) { this.in = newIn; pos = 0; end = 0; }

    ParsedRequest parse() throws IOException {
        pos = 0; end = 0;

        String requestLine = readRequestLine();
        if (requestLine == null || requestLine.isEmpty()) return null;

        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) throw new IOException("Malformed request line: " + requestLine);
        String method = parts[0];
        String rawUri = parts[1];
        String httpVersion = parts[2].toUpperCase(Locale.ROOT);

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

        boolean isHttp10 = "HTTP/1.0".equals(httpVersion);
        boolean isHttp2Preface = "HTTP/2.0".equals(httpVersion) && "PRI".equals(method);
        if (!isHttp10 && !"HTTP/1.1".equals(httpVersion) && !isHttp2Preface)
            throw new IOException("Unsupported HTTP version: " + httpVersion);
        if (isHttp2Preface)
            return new ParsedRequest("PRI", "*", null, "HTTP/2.0",
                Map.of(), -1, false, false, false, false, true);

        Map<String, List<String>> headers = parseHeaders();
        long contentLength = -1;
        boolean isChunked = false, keepAlive = !isHttp10, upgradeRequest = false;

        for (var entry : headers.entrySet()) {
            switch (entry.getKey().toLowerCase(Locale.ROOT)) {
                case "content-length" -> {
                    String v = entry.getValue().getFirst();
                    if (v != null) { try { contentLength = Long.parseLong(v.trim()); } catch (NumberFormatException e) { throw new IOException("Invalid Content-Length: " + v); } }
                }
                case "transfer-encoding" -> {
                    String v = entry.getValue().getFirst();
                    isChunked = v != null && v.equalsIgnoreCase("chunked");
                }
                case "connection" -> {
                    String v = entry.getValue().getFirst();
                    if (v != null) { keepAlive = v.equalsIgnoreCase("keep-alive"); if ("close".equalsIgnoreCase(v)) keepAlive = false; if (containsIgnoreCase(v, "upgrade")) upgradeRequest = true; }
                }
                case "upgrade" -> {
                    String v = entry.getValue().getFirst();
                    if (v != null && v.equalsIgnoreCase("websocket")) upgradeRequest = true;
                }
            }
        }

        return new ParsedRequest(method, path, queryString, httpVersion, headers,
            contentLength, isChunked, isHttp10, keepAlive, upgradeRequest, false);
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
        var sb = new StringBuilder(64);
        boolean gotCR = false;
        while (true) {
            int c = nextByte();
            if (c == -1) return sb.isEmpty() ? null : sb.toString().trim();
            if (gotCR) {
                if (c == LF) return sb.isEmpty() ? "" : sb.toString();
                gotCR = false;
                sb.append(CR).append((char) c);
            } else {
                if (c == CR) gotCR = true;
                else sb.append((char) c);
            }
        }
    }

    // --- header parser using StringBuilder instead of BufferedBuilder ---

    private Map<String, List<String>> parseHeaders() throws IOException {
        var headers = new LinkedHashMap<String, List<String>>();
        var key = new StringBuilder(32);
        var value = new StringBuilder(128);
        var current = key;
        boolean prevCR = false, startOfLine = true, afterColon = false;
        int headerCount = 0, totalSize = 0;

        while (true) {
            int c = nextByte();
            if (c == -1) break;
            totalSize++;
            if (totalSize > MAX_HEADER_SIZE) throw new IOException("Headers too large");

            if (c == CR) {
                prevCR = true;
            } else if (c == LF && prevCR) {
                if (key.isEmpty() && value.isEmpty()) break;
                if (startOfLine) { addFromSB(headers, key, value); if (++headerCount > MAX_HEADER_COUNT) throw new IOException("Too many headers"); break; }
                prevCR = false; startOfLine = true;
            } else {
                if (startOfLine && (c == ' ' || c == '\t')) {
                    current = value; startOfLine = false;
                } else {
                    if (startOfLine) {
                        if (!key.isEmpty() || !value.isEmpty()) { addFromSB(headers, key, value); if (++headerCount > MAX_HEADER_COUNT) throw new IOException("Too many headers"); }
                        current = key; startOfLine = false; afterColon = false;
                    }
                    if (c == ':' && current == key) {
                        current = value; afterColon = true;
                    } else if (afterColon && (c == ' ' || c == '\t')) {
                        // skip
                    } else {
                        afterColon = false;
                        if (current == key) {
                            // Fast ASCII case normalization: first char upper, rest lower
                            char ch = (char) c;
                            key.append(key.isEmpty()
                                ? (ch >= 'a' && ch <= 'z' ? (char)(ch - 32) : ch)
                                : (ch >= 'A' && ch <= 'Z' ? (char)(ch + 32) : ch));
                        } else {
                            current.append((char) c);
                        }
                    }
                }
            }
        }
        return headers;
    }

    private static void addFromSB(Map<String, List<String>> headers,
                                   StringBuilder k, StringBuilder v) {
        headers.computeIfAbsent(k.toString().trim(), key -> new ArrayList<>(4))
               .add(v.toString().trim());
        k.setLength(0);
        v.setLength(0);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        int hl = haystack.length(), nl = needle.length();
        for (int i = 0; i <= hl - nl; i++)
            if (haystack.regionMatches(true, i, needle, 0, nl)) return true;
        return false;
    }

    record ParsedRequest(
        String method, String path, String queryString, String httpVersion,
        Map<String, List<String>> headers, long contentLength, boolean isChunked,
        boolean isHttp10, boolean keepAlive, boolean isUpgradeRequest,
        boolean isHttp2Preface
    ) {}
}
