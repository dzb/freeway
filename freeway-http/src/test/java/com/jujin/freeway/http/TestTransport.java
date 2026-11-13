package com.jujin.freeway.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
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
    private boolean committed;
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
    public InputStream framedBodyStream() {
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

    @Override
    public boolean isCommitted() {
        return committed;
    }

    // -- HttpTransport: response side --

    @Override
    public void writeResponse(ResponsePlan plan) throws IOException {
        ResponseHead head = plan.head();
        if (!head.sendsBodyBytes()) {
            // HEAD / 204 / 205 / 304 must not read the body source just to
            // record it, but an owned channel is still released.
            if (plan.body() instanceof ResponseBody.Channel channel) {
                channel.channel().close();
            }
            heads.add(head);
            bodies.add(new byte[0]);
            committed = true;
            return;
        }
        byte[] data = switch (plan.body()) {
            case ResponseBody.Empty ignored -> new byte[0];
            case ResponseBody.Bytes bytes -> bytes.data().clone();
            case ResponseBody.Stream stream -> stream.stream().readAllBytes();
            case ResponseBody.File file -> readFile(file);
            case ResponseBody.Channel channel -> readChannel(channel);
        };
        if (plan.gzip()) {
            data = Compression.gzip(data);
            head = head.withHeader("Content-Length", Integer.toString(data.length));
        } else if (head.sendsBodyBytes() && !head.hasHeader("Content-Length")) {
            head = head.withHeader("Content-Length", Integer.toString(data.length));
        }
        heads.add(head);
        bodies.add(head.sendsBodyBytes() ? data : new byte[0]);
        committed = true;
    }

    private static byte[] readFile(ResponseBody.File file) throws IOException {
        try (FileChannel channel = FileChannel.open(file.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return readRange(channel, file.offset(), file.length());
        }
    }

    private static byte[] readChannel(ResponseBody.Channel body) throws IOException {
        try {
            return readRange(body.channel(), body.offset(), body.length());
        } finally {
            body.channel().close();
        }
    }

    private static byte[] readRange(FileChannel channel, long offset, long length)
            throws IOException {
        long remaining = length >= 0 ? length : channel.size() - offset;
        channel.position(offset);
        return java.nio.channels.Channels.newInputStream(channel)
            .readNBytes(Math.toIntExact(remaining));
    }

    @Override
    public SseEmitter openSse(ResponseHead head) throws IOException {
        heads.add(head);
        sse = new SseEmitter(new ByteArrayOutputStream());
        committed = true;
        return sse;
    }

    // -- assertions read the recording --

    /** The most recent recorded response head, or null. */
    public ResponseHead head() {
        return heads.isEmpty() ? null : heads.getLast();
    }

    /** Every response head recorded on this exchange, in order. */
    public List<ResponseHead> heads() {
        return List.copyOf(heads);
    }

    /** The most recent response body as recorded, or null. */
    public byte[] bodyBytes() {
        return bodies.isEmpty() ? null : bodies.getLast();
    }

    /** Every response body recorded on this exchange, in order. */
    public List<byte[]> bodies() {
        return List.copyOf(bodies);
    }

    /** The emitter opened by {@code sse()}, or null. */
    public SseEmitter sse() {
        return sse;
    }
}
