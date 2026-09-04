package com.jujin.freeway.http.engine.ws;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.jujin.freeway.http.ExchangeMetaImpl;
import com.jujin.freeway.http.internal.HttpUtils;
import com.jujin.freeway.http.websocket.WebSocketSession;

public final class WebSocketSessionImpl implements WebSocketSession {

    /** Messages larger than this are sent fragmented, so a single frame
     *  never exceeds the receive-side cap (WebSocketFrame.MAX_FRAME_SIZE)
     *  and peers with per-frame limits accept the message. */
    private static final int MAX_FRAME_PAYLOAD = 16 * 1024 * 1024;

    private final String method;
    private final String path;
    private final String rawQuery;
    private final Map<String, List<String>> headers;
    private final InputStream in;
    private final OutputStream out;
    private final Map<String, String> pathVariables;

    private final ExchangeMetaImpl exchangeMeta;
    private volatile boolean open = true;
    private volatile int closeCode = 1006;
    private volatile String closeReason = "";
    private final Object writeLock = new Object();

    // lazy
    private Map<String, List<String>> queryParams;

    public WebSocketSessionImpl(String method, String path, String rawQuery,
                         Map<String, List<String>> headers,
                         InputStream in, OutputStream out,
                         Map<String, String> pathVariables,
                         String correlationId) {
        this.method = method;
        this.path = path;
        this.rawQuery = rawQuery;
        this.headers = headers;
        this.in = in;
        this.out = out;
        this.pathVariables = pathVariables != null ? Map.copyOf(pathVariables) : Map.of();
        this.exchangeMeta = new ExchangeMetaImpl(correlationId);
    }

    @Override public String correlationId() { return exchangeMeta.correlationId(); }
    @Override public Instant startTime() { return exchangeMeta.startTime(); }
    @Override public Object principal() { return exchangeMeta.principal(); }
    @Override public void setPrincipal(Object principal) {
        exchangeMeta.setPrincipal(principal);
    }

    @Override
    public Object attribute(String key) {
        return exchangeMeta.attribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        exchangeMeta.setAttribute(key, value);
    }

    @Override
    public Map<String, Object> attributes() {
        return exchangeMeta.attributes();
    }

    @Override public String method() { return method; }
    @Override public String path() { return path; }
    @Override
    public Optional<String> pathVar(String name) {
        return Optional.ofNullable(pathVariables.get(name));
    }
    @Override public Map<String, String> pathVars() { return pathVariables; }
    @Override public boolean isOpen() { return open; }

    @Override
    public Optional<String> queryParam(String name) {
        return ensureQueryParams().getOrDefault(name, List.of()).stream()
            .findFirst();
    }

    @Override
    public List<String> queryParams(String name) {
        return List.copyOf(ensureQueryParams().getOrDefault(name, List.of()));
    }

    @Override
    public Map<String, List<String>> queryParams() {
        Map<String, List<String>> m = ensureQueryParams();
        Map<String, List<String>> copy = new LinkedHashMap<>();
        m.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public Optional<String> header(String name) {
        return Optional.ofNullable(HttpUtils.headerValue(headers, name));
    }

    @Override
    public List<String> headers(String name) {
        return HttpUtils.headerValues(headers, name);
    }

    @Override
    public Map<String, List<String>> headers() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    private void checkOpen() throws IOException {
        if (!open) throw new IOException("WebSocket is closed");
    }

    @Override
    public void sendText(String text) throws IOException {
        // length() × 4 would overflow for > 1GB strings; the division form
        // is mathematically equivalent and overflow-free.
        if (text.length() <= MAX_FRAME_PAYLOAD / 4) { // cheap upper bound
            writeFrame(new WebSocketFrame(OpCode.Text, true, text));
            return;
        }
        writeFragmented(OpCode.Text, text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        if (data.length <= MAX_FRAME_PAYLOAD) {
            writeFrame(new WebSocketFrame(OpCode.Binary, true, data));
            return;
        }
        writeFragmented(OpCode.Binary, data);
    }

    /** Sends a message larger than one frame as a fragmented message
     *  (RFC 6455 §5.4): the first frame carries the opcode with FIN=0, the
     *  rest are CONTINUATION, the last with FIN=1. The whole sequence is
     *  written under the write lock so no other frame (e.g. a ping) can
     *  interleave; text is split at UTF-8 code-point boundaries so no
     *  fragment ends mid-character. */
    private void writeFragmented(OpCode opCode, byte[] data) throws IOException {
        synchronized (writeLock) {
            checkOpen();
            int offset = 0;
            boolean first = true;
            while (offset < data.length) {
                int end = Math.min(offset + MAX_FRAME_PAYLOAD, data.length);
                if (end < data.length) {
                    end = codePointBoundary(data, end);
                }
                WebSocket.writeFrame(out, new WebSocketFrame(
                    first ? opCode : OpCode.Continuation, end == data.length,
                    Arrays.copyOfRange(data, offset, end)));
                first = false;
                offset = end;
            }
        }
    }

    /** Backs up to the start of the UTF-8 code point containing {@code pos}
     *  (bytes 0x80-0xBF are continuation bytes of a multi-byte code point). */
    private static int codePointBoundary(byte[] data, int pos) {
        while (pos > 0 && (data[pos] & 0xC0) == 0x80) {
            pos--;
        }
        return pos;
    }

    @Override
    public void ping(byte[] data) throws IOException {
        writeFrame(new WebSocketFrame(OpCode.Ping, true, data));
    }

    @Override
    public void flush() throws IOException {
        synchronized (writeLock) {
            out.flush();
        }
    }

    @Override
    public void sendTextBatch(List<String> texts) throws IOException {
        synchronized (writeLock) {
            checkOpen();
            for (String text : texts) {
                WebSocket.writeFrameNoFlush(out, new WebSocketFrame(OpCode.Text, true, text));
            }
            out.flush();
        }
    }

    @Override
    public void close(int code, String reason) throws IOException {
        synchronized (writeLock) {
            if (!open) return;
            var closePayload = buildClosePayload(code, reason);
            closeCode = code;
            closeReason = reason == null ? "" : reason;
            open = false;
            WebSocket.writeFrame(out,
                new WebSocketFrame(OpCode.Close, true, closePayload));
        }
        // Wake the blocking read loop so a server-initiated close does not
        // leave the session thread parked until the peer responds.
        try {
            in.close();
        } catch (IOException ignored) {}
    }

    // --- package-private ---

    /** Synchronized frame write shared with the read loop's automatic pong/close replies. */
    void writeFrame(WebSocketFrame frame) throws IOException {
        synchronized (writeLock) {
            checkOpen();
            WebSocket.writeFrame(out, frame);
        }
    }

    /** Synchronized batched frame write without per-frame flush. */
    void writeFrameNoFlush(WebSocketFrame frame) throws IOException {
        synchronized (writeLock) {
            checkOpen();
            WebSocket.writeFrameNoFlush(out, frame);
        }
    }

    void markOpen() { open = true; }

    void markClosed(CloseCode code, String reason) {
        markClosed(code.value(), reason);
    }
    void markClosed(int code, String reason) {
        closeCode = code;
        closeReason = reason == null ? "" : reason;
        open = false;
    }
    int closeCode() { return closeCode; }
    String closeReason() { return closeReason; }

    // --- internal ---

    private Map<String, List<String>> ensureQueryParams() {
        if (queryParams == null) {
            queryParams = HttpUtils.parseQueryParams(rawQuery);
        }
        return queryParams;
    }

    private static byte[] buildClosePayload(int code, String reason) {
        if (!WebSocketFrame.isValidWireCloseCode(code)) {
            throw new IllegalArgumentException("Invalid WebSocket close code: " + code);
        }
        byte[] reasonBytes = reason.getBytes(WebSocketFrame.TEXT_CHARSET);
        if (reasonBytes.length > 123) {
            throw new IllegalArgumentException("WebSocket close reason exceeds 123 bytes");
        }
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (code >> 8 & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        return payload;
    }

}
