package com.jujin.freeway.http.engine.http11;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.jujin.freeway.http.HttpContext;

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
    // Bytes handed to a chunked body stream that the body did not consume
    // (typically a pipelined next request). Reclaimed before the next parse.
    private ByteArrayInputStream chunkedPrefix;

    public HttpParser(InputStream in) { this.in = in; }

    /** Reuse this parser for a new request on the same connection. */
    public void reset(InputStream newIn) {
        this.in = newIn;
        this.chunkedPrefix = null;
        // Preserve bytes buffered past the previous request's header boundary
        // (a pipelined next request); otherwise they would be lost.
        if (pos > 0 && pos < end) {
            System.arraycopy(buf, pos, buf, 0, end - pos);
            end -= pos;
        } else if (pos >= end) {
            end = 0;
        }
        // pos == 0 && end > 0 → buffer already compacted; keep it
        pos = 0;
        reqLineBuf.setLength(0);
        headerKeyBuf.setLength(0);
        headerValBuf.setLength(0);
    }

    public ParsedRequest parse() throws IOException {
        // Preserve unread bytes from a previous pipelined request. reset()
        // may already have compacted the buffer (pos == 0 with end > 0).
        if (pos < end) {
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, end - pos);
                end -= pos;
            }
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
            // Keys are already lowercased by parseHeaders (RFC 7230 §3.2) —
            // toLowerCase here would allocate a new String per header.
            switch (entry.getKey()) {
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
                            String[] tokens = v.split(",", -1);
                            for (int i = 0; i < tokens.length; i++) {
                                String token = tokens[i];
                                token = token.trim();
                                if ("chunked".equalsIgnoreCase(token)) {
                                    if (isChunked || i != tokens.length - 1)
                                        throw new IOException("Invalid Transfer-Encoding order");
                                    isChunked = true;
                                } else if (!token.isEmpty()) {
                                    throw new IOException("Unsupported Transfer-Encoding: " + token);
                                } else {
                                    throw new IOException("Invalid empty Transfer-Encoding");
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

    /** Fills the reusable buffer; returns false on EOF. */
    private boolean fill() throws IOException {
        pos = 0;
        end = in.read(buf, 0, buf.length);
        return end > 0;
    }

    // --- request line parser ---

    private String readRequestLine() throws IOException {
        return readLine(reqLineBuf, MAX_REQUEST_LINE_SIZE, true)
            ? reqLineBuf.toString()
            : null;
    }

    // --- header parser ---

    private Map<String, List<String>> parseHeaders() throws IOException {
        var headers = new LinkedHashMap<String, List<String>>();
        int headerCount = 0, totalSize = 0;
        boolean haveLine = readLine(headerKeyBuf, MAX_HEADER_SIZE, false);
        while (haveLine) {
            if (headerKeyBuf.isEmpty()) break; // blank line terminates headers
            totalSize += headerKeyBuf.length() + 2;
            if (totalSize > MAX_HEADER_SIZE) throw new IOException("Headers too large");

            int colon = -1;
            for (int i = 0; i < headerKeyBuf.length(); i++) {
                if (headerKeyBuf.charAt(i) == ':') { colon = i; break; }
            }
            // RFC 7230 §3.2: a header field must be `name ":" value` — a line
            // without a colon is malformed, not an empty-valued header.
            if (colon < 0) {
                throw new IOException("Malformed header line (missing colon)");
            }
            String rawKey = headerKeyBuf.substring(0, colon);
            if (rawKey.isEmpty() || !rawKey.equals(rawKey.trim())
                    || !HttpContext.isToken(rawKey)) {
                throw new IOException("Invalid header name");
            }
            String key = rawKey;
            key = key.toLowerCase(Locale.ROOT);

            headerValBuf.setLength(0);
            if (colon >= 0) {
                int vStart = colon + 1;
                while (vStart < headerKeyBuf.length()
                        && (headerKeyBuf.charAt(vStart) == ' '
                            || headerKeyBuf.charAt(vStart) == '\t')) {
                    vStart++;
                }
                headerValBuf.append(headerKeyBuf, vStart, headerKeyBuf.length());
            }

            // RFC 7230 §3.2.4 obs-fold: continuation lines start with SP/HT.
            boolean headersEnded = false;
            while (!headersEnded) {
                boolean haveFold = readLine(headerKeyBuf, MAX_HEADER_SIZE, false);
                if (!haveFold || headerKeyBuf.isEmpty()) {
                    // clean EOF at a line boundary, or the terminating blank
                    // line — the block is complete.
                    headersEnded = true;
                    break;
                }
                if (headerKeyBuf.charAt(0) == ' ' || headerKeyBuf.charAt(0) == '\t') {
                    totalSize += headerKeyBuf.length() + 2;
                    if (totalSize > MAX_HEADER_SIZE) {
                        throw new IOException("Headers too large");
                    }
                    headerValBuf.append(' ').append(headerKeyBuf.toString().trim());
                    continue;
                }
                // Regular next header line — keep it for the outer loop.
                break;
            }

            // Strip trailing OWS per RFC 7230 §3.2.6 — clients may append
            // spaces/tabs before CRLF (e.g. "Content-Length: 4 ").
            int vEnd = headerValBuf.length();
            while (vEnd > 0
                    && (headerValBuf.charAt(vEnd - 1) == ' '
                        || headerValBuf.charAt(vEnd - 1) == '\t')) {
                vEnd--;
            }
            String value = vEnd == headerValBuf.length()
                ? headerValBuf.toString()
                : headerValBuf.substring(0, vEnd);
            headers.computeIfAbsent(key, k -> new ArrayList<>(4)).add(value);
            if (++headerCount > MAX_HEADER_COUNT) {
                throw new IOException("Too many headers");
            }
            if (headersEnded) break;
        }
        return headers;
    }

    /**
     * Reads one CRLF-terminated line into {@code out} (CRLF excluded) by
     * scanning the bulk buffer, refilling only at chunk boundaries. Returns
     * false on a clean EOF before any byte of the line; throws on EOF mid-line
     * or when {@code maxLen} is exceeded.
     */
    private boolean readLine(StringBuilder out, int maxLen, boolean requestLine)
            throws IOException {
        out.setLength(0);
        boolean crPending = false;
        while (true) {
            if (pos >= end && !fill()) {
                if (out.isEmpty() && !crPending) return false;
                throw new IOException(requestLine
                    ? "EOF while reading HTTP request line"
                    : "EOF while reading HTTP headers");
            }
            if (crPending) {
                if (buf[pos] == (byte) '\n') {
                    pos++;
                    return true;
                }
                out.append(CR);
                if (out.length() > maxLen) throw lineTooLong(requestLine, maxLen);
                crPending = false;
            }
            int i = pos;
            while (i < end && buf[i] != (byte) '\n') i++;
            if (i < end) {
                int contentEnd = i;
                if (contentEnd > pos && buf[contentEnd - 1] == (byte) '\r') {
                    contentEnd--;
                }
                appendRange(out, pos, contentEnd, maxLen, requestLine);
                pos = i + 1;
                return true;
            }
            int appendEnd = end;
            if (buf[end - 1] == (byte) '\r') {
                crPending = true;
                appendEnd--;
            }
            appendRange(out, pos, appendEnd, maxLen, requestLine);
            pos = end;
        }
    }

    private void appendRange(StringBuilder out, int from, int to,
                             int maxLen, boolean requestLine) throws IOException {
        int len = to - from;
        if (out.length() + len > maxLen) throw lineTooLong(requestLine, maxLen);
        for (int j = from; j < to; j++) {
            out.append((char) (buf[j] & 0xFF));
        }
    }

    private static IOException lineTooLong(boolean requestLine, int maxLen) {
        return new IOException(requestLine
            ? "Request line too long (max " + maxLen + " chars)"
            : "Headers too large");
    }

    /**
     * Returns an {@code InputStream} for reading the request body: any bytes
     * already buffered past the header boundary, followed by the remaining
     * raw socket input.
     *
     * <p>When {@code bodyLength} is known (Content-Length, not chunked) only
     * that many buffered bytes are handed to the body — bytes belonging to a
     * pipelined next request stay in the parser buffer and are parsed next.
     * Call this once after {@link #parse()}.</p>
     */
    public InputStream bodyStream(long bodyLength) {
        if (pos >= end) return in;
        int available = end - pos;
        if (bodyLength < 0) {
            // Chunked bodies have an unknown wire length, so all buffered
            // bytes must go to the body parser. Keep a reference so any
            // bytes left after the terminal chunk (pipelined requests) can
            // be put back into this parser's buffer.
            chunkedPrefix = new ByteArrayInputStream(buf, pos, available);
            pos = end;
            return new SequenceInputStream(chunkedPrefix, in);
        }
        int prefixLen = bodyLength >= 0 ? (int) Math.min(available, bodyLength) : available;
        var prefix = new ByteArrayInputStream(buf, pos, prefixLen);
        pos += prefixLen;
        if (pos < end) {
            // Body is shorter than what was buffered: the rest belongs to a
            // pipelined next request — keep it for the next parse().
            return prefix;
        }
        return new SequenceInputStream(prefix, in);
    }

    /** Returns unread parser bytes followed by the underlying stream. */
    public InputStream upgradeStream() {
        if (pos >= end) return in;
        var prefix = new ByteArrayInputStream(buf, pos, end - pos);
        pos = end;
        return new SequenceInputStream(prefix, in);
    }

    /**
     * Returns bytes that were buffered past the end of a chunked body back
     * into the parser's reusable buffer so the next pipelined request is not
     * lost. Must be called only after the chunked body has been fully drained.
     */
    public void reclaimChunkedPrefix() {
        if (chunkedPrefix == null) return;
        int remaining = chunkedPrefix.available();
        if (remaining > 0) {
            int read = chunkedPrefix.read(buf, 0, Math.min(remaining, buf.length));
            if (read > 0) {
                end = read;
                pos = 0;
            }
        }
        chunkedPrefix = null;
    }

    public record ParsedRequest(
        String method, String path, String queryString, String httpVersion,
        Map<String, List<String>> headers, long contentLength, boolean isChunked,
        boolean isHttp10, boolean keepAlive, boolean isUpgradeRequest,
        boolean isHttp2Preface
    ) {}
}
