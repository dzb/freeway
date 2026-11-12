package com.jujin.freeway.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSession;

import com.jujin.freeway.http.sse.SseEmitter;

/**
 * An {@link HttpTransport} that records what the support object commits:
 * every {@link ResponseHead} and every body handed to the transport. Request
 * bytes are configurable; responses are captured instead of written.
 *
 * <p>The recording is deliberately dumb — assertions read the real
 * {@link ResponseHead} and body rather than a re-implementation of response
 * policy, so a policy test fails when the shared support object changes.</p>
 */
public final class TestTransport implements HttpTransport {

    private final String method;
    private final String path;
    private final Map<String, List<String>> queryParams;
    private final Map<String, List<String>> requestHeaders =
        new LinkedHashMap<>();
    private InputStream requestBody = InputStream.nullInputStream();

    private final List<ResponseHead> heads = new ArrayList<>();
    private final List<byte[]> bodies = new ArrayList<>();
    private SseEmitter sse;

    public TestTransport(String method, String path,
            Map<String, List<String>> queryParams) {
        this.method = method;
        this.path = path;
        this.queryParams = new LinkedHashMap<>(queryParams);
    }

    // -- request configuration (before the exchange runs) --

    public TestTransport addRequestHeader(String name, String value) {
        requestHeaders.computeIfAbsent(name, k -> new ArrayList<>(2)).add(value);
        return this;
    }

    public TestTransport addQueryParam(String name, String value) {
        queryParams.computeIfAbsent(name, k -> new ArrayList<>(2)).add(value);
        return this;
    }

    public TestTransport requestBody(byte[] body) {
        this.requestBody = new java.io.ByteArrayInputStream(body);
        return this;
    }

    public TestTransport requestBody(String body) {
        return requestBody(body.getBytes(StandardCharsets.UTF_8));
    }

    public TestTransport requestBody(InputStream body) {
        this.requestBody = body;
        return this;
    }

    // -- HttpTransport: request side --

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Map<String, List<String>> queryParams() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        queryParams.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }

    @Override
    public Map<String, List<String>> headers() {
        // Same canonicalization the built-in transport performs: HTTP/2 field
        // names arrive with arbitrary case, and ctx.headers() must not tell
        // which engine answered.
        Map<String, List<String>> out = new LinkedHashMap<>();
        requestHeaders.forEach((name, values) -> out
            .computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>(2))
            .addAll(values));
        out.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(out);
    }

    @Override
    public InputStream bodyStream() {
        return requestBody;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public SSLSession sslSession() {
        return null;
    }

    @Override
    public String remoteAddress() {
        return "";
    }

    // -- HttpTransport: response side --

    @Override
    public void writeResponse(ResponseHead head, byte[] body) {
        heads.add(head);
        // What is committed is what goes on the wire: HEAD and the bodyless
        // statuses carry the head only.
        bodies.add(head.sendsBodyBytes() ? body : new byte[0]);
    }

    @Override
    public void writeResponse(ResponseHead head, InputStream body, boolean gzip)
            throws IOException {
        heads.add(head);
        if (!head.sendsBodyBytes()) {
            bodies.add(new byte[0]);
            return;
        }
        byte[] data = body.readAllBytes();
        bodies.add(gzip ? Compression.gzip(data) : data);
    }

    @Override
    public void writeResponseFile(ResponseHead head, FileChannel channel,
            long offset, long length, boolean gzip) throws IOException {
        heads.add(head);
        if (!head.sendsBodyBytes()) {
            // HEAD / 204 must not read the file: the size is already in the
            // head the support object set.
            bodies.add(new byte[0]);
            return;
        }
        channel.position(offset);
        byte[] data = new byte[(int) Math.min(length, Integer.MAX_VALUE)];
        int read = 0;
        while (read < data.length) {
            int n = channel.read(java.nio.ByteBuffer.wrap(data, read,
                data.length - read));
            if (n < 0) break;
            read += n;
        }
        byte[] actual = read == data.length ? data
            : java.util.Arrays.copyOf(data, read);
        bodies.add(gzip ? Compression.gzip(actual) : actual);
    }

    @Override
    public SseEmitter openSse(ResponseHead head) throws IOException {
        heads.add(head);
        sse = new SseEmitter(new ByteArrayOutputStream());
        return sse;
    }

    // -- assertions read the recording --

    /** The most recent committed response head, or null. */
    public ResponseHead head() {
        return heads.isEmpty() ? null : heads.getLast();
    }

    /** Every response head committed on this exchange, in order. */
    public List<ResponseHead> heads() {
        return List.copyOf(heads);
    }

    /** The most recent response body as committed, or null. */
    public byte[] bodyBytes() {
        return bodies.isEmpty() ? null : bodies.getLast();
    }

    /** Every response body committed on this exchange, in order. */
    public List<byte[]> bodies() {
        return List.copyOf(bodies);
    }

    /** The emitter opened by {@code sse()}, or null. */
    public SseEmitter sse() {
        return sse;
    }
}
