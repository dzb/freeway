package com.jujin.freeway.http.engine;
import java.util.Base64;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.HttpRequestHandler;
import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.engine.http11.Http11Connection;
import com.jujin.freeway.http.engine.http11.HttpParser;
import com.jujin.freeway.http.engine.http2.Http2Connection;
import com.jujin.freeway.http.engine.http2.Http2ResponseWriter;
import com.jujin.freeway.http.engine.http2.Http2Stream;
import com.jujin.freeway.http.engine.http2.frame.FrameFlag;
import com.jujin.freeway.http.engine.http2.frame.FrameHeader;
import com.jujin.freeway.http.engine.http2.frame.FrameType;
import com.jujin.freeway.http.engine.http2.frame.SettingsFrame;
import com.jujin.freeway.http.engine.ws.WebSocket;
import com.jujin.freeway.http.engine.ws.WebSocketSessionImpl;
import com.jujin.freeway.http.engine.ws.WebSocketUtil;
import com.jujin.freeway.http.websocket.WebSocketMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jdk.net.ExtendedSocketOptions;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLSession;

/**
 * Per-connection handler. Handles plain HTTP, HTTPS with ALPN, WebSocket upgrade,
 * and HTTP/2 (both h2c and h2 over TLS).
 */
final class HttpSession implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpSession.class);
    private static final byte[] INTERNAL_ERROR_BODY =
        "Internal Server Error".getBytes(StandardCharsets.UTF_8);
    /** TCP keepalive probe tuning (seconds), for dead-peer detection while an
     *  HTTP/2 stream is open. The application read timeout cannot cover this:
     *  active streams must never be torn down by it (see updateReadTimeout),
     *  so a peer whose TCP stack died would otherwise hold the connection —
     *  and its fd — forever.
     *
     *  Values chosen deliberately:
     *  - IDLE 30s matches the default readTimeout (HttpServerConfig
     *    DEFAULT_READ_TIMEOUT): "30s of silence" is the single inactivity
     *    yardstick across both layers. Idle connections are already reaped
     *    by readTimeout before keepalive ever fires, so probes only ever
     *    matter for connections with open streams; and a slow-but-live
     *    client is never harmed, because the kernel resets the probe timer
     *    on any traffic and ACKed probes keep the connection alive.
     *  - INTERVAL 15s x COUNT 3 gives a ~75s worst case to declare a peer
     *    dead (30s idle + 3 probes at 15s intervals). The retries absorb
     *    transient packet loss or brief partitions; only a peer whose TCP
     *    stack is really gone is reclaimed, roughly 100x faster than the
     *    kernel default (~2 hours). */
    private static final int KEEPALIVE_IDLE_SECONDS = 30;
    private static final int KEEPALIVE_INTERVAL_SECONDS = 15;
    private static final int KEEPALIVE_PROBE_COUNT = 3;
    /** Connection-specific HTTP/1.1 headers that must not cross into HTTP/2
     *  (RFC 7540 §8.1.2.2); Host becomes :authority. */
    private static final Set<String> H2_FORBIDDEN_UPGRADE_HEADERS = Set.of(
        "connection", "keep-alive", "proxy-connection",
        "transfer-encoding", "upgrade", "http2-settings", "host");

    private final Socket rawSocket;
    private final HttpRequestHandler handler;
    private final JsonCodec jsonCodec;
    private final Coercer coercer;
    private final FreewayHttpEngine engine;
    private final HttpServerConfig config;
    private final ConnectionRegistry registry;
    private final Metrics metrics;
    private final Metrics.Timer requestTimer;
    private ExecutorService h2Executor;

    public HttpSession(Socket socket, HttpRequestHandler handler,
            JsonCodec jsonCodec, Coercer coercer, FreewayHttpEngine engine,
            HttpServerConfig config, ConnectionRegistry registry) {
        this.rawSocket = socket;
        this.handler = handler;
        this.jsonCodec = jsonCodec;
        this.coercer = coercer;
        this.engine = engine;
        this.config = config;
        this.registry = registry;
        this.metrics = engine.metrics();
        this.requestTimer = this.metrics.timer("freeway.http.requests.duration");
    }

    @Override
    public void run() {
        Http11Connection connection = null;
        try {
            Socket socket = rawSocket;
            boolean isH2 = false;

            // Socket-level tuning: Nagle off for small-response latency,
            // keep-alive probe for dead-peer detection, and a read timeout so
            // idle/slow connections cannot hold a thread forever.
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            configureKeepAliveProbe(socket);
            socket.setSoTimeout(timeoutMillis(config.readTimeout()));
            if (config.receiveBufferSize() > 0) {
                socket.setReceiveBufferSize(config.receiveBufferSize());
            }
            if (config.sendBufferSize() > 0) {
                socket.setSendBufferSize(config.sendBufferSize());
            }

            // SSL wrapping + ALPN negotiation
            if (engine.sslContext() != null) {
                var sslSocket = (SSLSocket) engine.sslContext().getSocketFactory()
                    .createSocket(rawSocket, null, false);
                sslSocket.setUseClientMode(false);
                sslSocket.setSoTimeout(timeoutMillis(config.readTimeout()));
                configureKeepAliveProbe(sslSocket);
                if (engine.sslParameters() != null) {
                    sslSocket.setSSLParameters(engine.sslParameters());
                }
                sslSocket.setHandshakeApplicationProtocolSelector(
                    (sslEngine, protocols) ->
                        engine.http2OverSsl() && protocols.contains("h2")
                            ? "h2" : "http/1.1");
                sslSocket.startHandshake();
                isH2 = engine.http2OverSsl() && "h2".equals(sslSocket.getApplicationProtocol());
                socket = sslSocket;
            }

            connection = new Http11Connection(
                socket, config.socketBufferSize(),
                timeoutMillis(config.writeTimeout()));
            registry.register(connection);
            var in = connection.inputStream();
            var out = connection.outputStream();

            // HTTP/2 over TLS (ALPN negotiated)
            if (isH2) {
                handleHttp2Upgrade(connection, true, null, null, null);
                return;
            }

            // HTTP/1.1 loop — reuse parser + context across requests
            var parser = new HttpParser(in);
            var ctx = new HttpContextDefault(jsonCodec, coercer);
            ctx.setMaxBodySize(config.maxBodySize());
            ctx.setCompression(config.compression());
            Http11Connection conn = connection;
            if (engine.sslContext() == null
                    && conn.socket().getChannel() != null) {
                ctx.setFileSender((channel, offset, length) -> {
                    metrics.counter("freeway.http.sendfile.transfers").increment();
                    conn.transferFile(channel, offset, length);
                });
            }
            ctx.setSecure(connection.getSSLSession() != null);
            ctx.setSslSession(connection.getSSLSession());

            while (!connection.closed) {
                parser.reset(in);
                var req = parser.parse();
                if (req == null) break;

                if (req.isHttp2Preface()) {
                    handleHttp2Upgrade(connection, false, parser, null, null);
                    return;
                }

                // RFC 7230 §5.4: HTTP/1.1 requests must carry exactly one
                // Host header; missing/duplicate/empty values are 400.
                if (!req.isHttp10() && invalidHostHeader(req.headers())) {
                    sendUpgradeError(connection.outputStream(), 400, "Bad Request");
                    break;
                }

                if (isH2cUpgradeRequest(req)) {
                    SettingsFrame h2cSettings = tryPrepareH2cUpgrade(req);
                    if (h2cSettings != null) {
                        metrics.counter("freeway.http.requests.total").increment();
                        handleH2cUpgrade(connection, req, parser, h2cSettings);
                        return;
                    }
                    // RFC 7540 §3.2: a server that does not accept the h2c
                    // upgrade answers as though the Upgrade header were
                    // absent — process the request as ordinary HTTP/1.1 so
                    // clients that attempt an upgrade for every request
                    // (e.g. the JDK HttpClient) still get a normal response.
                }

                // Direct Map.get for WS upgrade check — avoids stream
                var upgradeHeader = req.headers().get("upgrade");
                if (req.isUpgradeRequest() && upgradeHeader != null
                        && !upgradeHeader.isEmpty()
                        && "websocket".equalsIgnoreCase(upgradeHeader.getFirst())) {
                    metrics.counter("freeway.http.requests.total").increment();
                    handleWebSocketUpgrade(connection, req);
                    return;
                }

                // Direct Map.get for X-Request-Id
                var reqIdHeader = req.headers().get("x-request-id");
                String correlationId = reqIdHeader != null && !reqIdHeader.isEmpty()
                    ? reqIdHeader.getFirst() : null;
                RequestContext requestContext = HttpContext.createRequestContext(correlationId);

                // RFC 7230 §3.3.3: a request with neither Content-Length nor
                // Transfer-Encoding has a zero-length body. Reading to EOF here
                // would block on a keep-alive socket and consume pipelined data.
                long bodyLength = req.isChunked() ? -1L : Math.max(0L, req.contentLength());
                // Only hand the parser's buffered bytes to the body stream when
                // there actually is a body. Otherwise pipelined bytes for the
                // next request would be stranded in the body prefix and lost.
                InputStream bodyStream = req.isChunked() || req.contentLength() > 0
                    ? parser.bodyStream(bodyLength)
                    : in;
                ctx.reset(req.method(), req.path(), req.queryString(),
                    req.headers(), bodyStream, bodyLength, req.isChunked(),
                    out, requestContext, req.isHttp10(), req.keepAlive());
                ctx.setHeader("X-Request-Id", requestContext.correlationId());

                // RFC 7231 §5.1.1: acknowledge Expect: 100-continue before
                // the handler reads the body so clients send it promptly.
                if ((req.isChunked() || bodyLength > 0)
                        && expects100Continue(req)) {
                    writeLine(out, "HTTP/1.1 100 Continue");
                    writeLine(out, "");
                    out.flush();
                }

                registry.requestsInFlight.incrementAndGet();
                long startNanos = System.nanoTime();
                try { handler.handle(ctx); }
                catch (Exception e) {
                    LOG.debug("Handler exception for {} {}", req.method(), req.path(), e);
                    if (!ctx.isResponded()) {
                        try { ctx.status(500).setHeader("Content-Type", "text/plain; charset=utf-8").output(INTERNAL_ERROR_BODY); }
                        catch (IOException ignored) {}
                    }
                } finally {
                    registry.requestsInFlight.decrementAndGet();
                    requestTimer.record(System.nanoTime() - startNanos);
                }

                metrics.counter("freeway.http.requests.total").increment();
                int status = ctx.status();
                if (status >= 500) {
                    metrics.counter("freeway.http.responses.5xx").increment();
                } else if (status >= 400) {
                    metrics.counter("freeway.http.responses.4xx").increment();
                }

                boolean bodyDrained = ctx.drainUnreadBody();
                if (req.isChunked() && bodyDrained) {
                    parser.reclaimChunkedPrefix();
                }
                ctx.syncKeepAliveFromResponse();
                if (registry.isStopping()) break;
                if (!ctx.isKeepAlive()) break;
                if (req.isHttp10() && !req.keepAlive()) break;
            }
        } catch (IOException e) {
            LOG.trace("Connection I/O error: {}", e.getMessage());
        } catch (Exception e) {
            LOG.warn("Unexpected session error", e);
        } finally {
            if (connection != null) {
                registry.unregister(connection);
                connection.close();
            } else if (!rawSocket.isClosed()) {
                // SSL handshake or connection setup failed before the
                // Http11Connection was created — the raw socket would
                // otherwise leak an fd per failed handshake.
                try {
                    rawSocket.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Applies per-socket TCP keepalive probe tuning so dead peers are
     * reclaimed quickly. Best-effort: unsupported platforms (e.g. older
     * Windows without TCP_KEEPIDLE) keep the default kernel behavior.
     */
    static void configureKeepAliveProbe(Socket socket) {
        try {
            socket.setOption(ExtendedSocketOptions.TCP_KEEPIDLE,
                KEEPALIVE_IDLE_SECONDS);
            socket.setOption(ExtendedSocketOptions.TCP_KEEPINTERVAL,
                KEEPALIVE_INTERVAL_SECONDS);
            socket.setOption(ExtendedSocketOptions.TCP_KEEPCOUNT,
                KEEPALIVE_PROBE_COUNT);
        } catch (UnsupportedOperationException | IOException e) {
            LOG.debug("TCP keepalive probe tuning unavailable: {}",
                e.getMessage());
        }
    }

    // --- HTTP/2 upgrade (h2c: ssl=false, h2: ssl=true) ---

    private void handleHttp2Upgrade(
        Http11Connection connection,
        boolean ssl,
        HttpParser parser,
        SettingsFrame upgradeSettings,
        Map<String, List<String>> upgradeStreamHeaders
    ) {
        Http2Connection h2conn = null;
        try {
            // For h2c the request-line parser has already bulk-read the rest
            // of the magic preface into its own buffer — read the remaining
            // "\r\nSM\r\n\r\n" from there so the bytes are not lost.
            InputStream in = ssl
                ? connection.inputStream()
                : parser.bodyStream(-1); // all buffered bytes — the preface continues the request line
            if (upgradeSettings != null) {
                // The h2c upgrade client still sends the standard 24-byte
                // connection preface after the 101 response; the SETTINGS
                // payload was already carried in HTTP2-Settings.
                byte[] preface = new byte[Http2Connection.PREFACE.length()];
                int off = 0;
                while (off < preface.length) {
                    int n = in.read(preface, off, preface.length - off);
                    if (n < 0) throw new IOException("EOF reading HTTP/2 preface");
                    off += n;
                }
                if (!Http2Connection.PREFACE.equals(new String(preface)))
                    throw new IOException("Invalid HTTP/2 preface");
            } else if (!ssl) {
                byte[] preface = new byte[Http2Connection.PARTIAL_PREFACE.length()];
                int off = 0;
                while (off < preface.length) {
                    int n = in.read(preface, off, preface.length - off);
                    if (n < 0) throw new IOException("EOF reading HTTP/2 preface");
                    off += n;
                }
                if (!Http2Connection.PARTIAL_PREFACE.equals(new String(preface)))
                    throw new IOException("Invalid HTTP/2 preface");
            }

            this.h2Executor = Executors.newVirtualThreadPerTaskExecutor();
            metrics.counter("freeway.http.h2.connections").increment();
            h2conn = new Http2Connection(connection.socket(), in,
                    connection.outputStream(), h2Executor,
                (stream, streamIn, streamOut, reqHeaders) ->
                    handleHttp2Stream(
                        stream,
                        streamIn,
                        streamOut,
                        reqHeaders,
                        ssl ? connection.getSSLSession() : null
                    ),
                timeoutMillis(config.readTimeout()));

            if (upgradeSettings != null) {
                h2conn.applyUpgradeSettings(upgradeSettings);
            }

            if (ssl) {
                if (!h2conn.hasProperPreface(true))
                    throw new IOException("Invalid HTTP/2 TLS preface");
            }
            // Server connection preface (RFC 7540 §3.5) — the first SETTINGS
            // frame sent below is the preface; the PRI magic belongs to the
            // client only and must never be echoed back.
            h2conn.sendMySettings();
            if (upgradeStreamHeaders != null) {
                h2conn.prepopulateUpgradeStream(upgradeStreamHeaders);
            }
            h2conn.handle();
        } catch (IOException e) {
            LOG.trace("HTTP/2 error: {}", e.getMessage());
        } finally {
            // Close the HTTP/2 layer first: it marks the connection closed,
            // closes every stream (waking handlers blocked on request body
            // DATA via dataIn.close()) and interrupts their threads, so the
            // executor close below cannot wait forever. Only then close the
            // buffered streams — a handler may still be writing its response
            // when the peer disconnected mid-request, and it must observe a
            // closed connection (IOException) instead of writing into an
            // already-closed SessionBufferedOutputStream, which would NPE on
            // a null buf.
            if (h2conn != null) h2conn.close();
            if (h2Executor != null) {
                h2Executor.close();
                h2Executor = null;
            }
            connection.close();
        }
    }

    // --- HTTP/1.1 Upgrade: h2c (RFC 7540 §3.2) ---

    private static boolean isH2cUpgradeRequest(HttpParser.ParsedRequest req) {
        String connection = headerValue(req.headers(), "connection");
        if (connection == null || !containsToken(connection, "upgrade")) {
            return false;
        }
        String upgrade = headerValue(req.headers(), "upgrade");
        return upgrade != null && containsToken(upgrade, "h2c");
    }

    private static boolean expects100Continue(HttpParser.ParsedRequest req) {
        String expect = headerValue(req.headers(), "expect");
        return expect != null && containsToken(expect, "100-continue");
    }

    private static boolean invalidHostHeader(Map<String, List<String>> headers) {
        List<String> values = headers.get("host");
        if (values == null || values.size() != 1) {
            return true;
        }
        String host = values.getFirst();
        if (host == null || host.isBlank()) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == ',' || c == ' ' || c == '\t' || c == '/' || c == '\\' || c == '@') {
                return true;
            }
        }
        return false;
    }

    private static int timeoutMillis(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return 0; // disabled
        }
        long millis = timeout.toMillis();
        return (int) Math.min(Math.max(millis, 1), Integer.MAX_VALUE);
    }

    /**
     * Validates an h2c upgrade request (RFC 7540 §3.2) and decodes its
     * HTTP2-Settings payload. Returns null when the upgrade must be declined
     * — the caller then processes the request as ordinary HTTP/1.1.
     */
    private static SettingsFrame tryPrepareH2cUpgrade(
            HttpParser.ParsedRequest req) {
        if (req.isHttp10() || req.isChunked() || req.contentLength() > 0) {
            return null;
        }
        String host = headerValue(req.headers(), "host");
        if (host == null || host.isBlank()) return null;
        String connection = headerValue(req.headers(), "connection");
        if (connection == null
                || !containsToken(connection, "http2-settings")) {
            return null;
        }
        var settingsValues = req.headers().get("http2-settings");
        if (settingsValues == null || settingsValues.size() != 1) {
            return null;
        }
        byte[] settingsPayload;
        try {
            settingsPayload = decodeBase64Url(settingsValues.getFirst());
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            return SettingsFrame.parse(settingsPayload,
                new FrameHeader(settingsPayload.length,
                    FrameType.SETTINGS, FrameFlag.NONE, 0));
        } catch (IOException e) {
            return null;
        }
    }

    private void handleH2cUpgrade(Http11Connection connection,
                                  HttpParser.ParsedRequest req,
                                  HttpParser parser,
                                  SettingsFrame settings) {
        try {
            OutputStream out = connection.outputStream();
            writeLine(out, "HTTP/1.1 101 Switching Protocols");
            writeLine(out, "Connection: Upgrade");
            writeLine(out, "Upgrade: h2c");
            writeLine(out, "");
            out.flush();

            handleHttp2Upgrade(connection, false, parser, settings,
                upgradeStreamRequestHeaders(req));
        } catch (IOException e) {
            LOG.trace("h2c upgrade error: {}", e.getMessage());
            connection.close();
        }
    }

    /** Converts the HTTP/1.1 upgrade request into stream 1's HTTP/2 header
     *  block (RFC 7540 §3.2). The request is complete, so pseudo-headers are
     *  derived from the request line and Host; connection-specific headers
     *  are dropped. */
    private static Map<String, List<String>> upgradeStreamRequestHeaders(
            HttpParser.ParsedRequest req) {
        var headers = new LinkedHashMap<String, List<String>>();
        headers.put(":method", List.of(req.method()));
        String path = req.path();
        if (req.queryString() != null && !req.queryString().isEmpty()) {
            path += "?" + req.queryString();
        }
        headers.put(":path", List.of(path));
        headers.put(":scheme", List.of("http"));
        headers.put(":authority", List.of(headerValue(req.headers(), "host")));
        for (var entry : req.headers().entrySet()) {
            if (H2_FORBIDDEN_UPGRADE_HEADERS.contains(
                    entry.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            headers.put(entry.getKey().toLowerCase(Locale.ROOT),
                List.copyOf(entry.getValue()));
        }
        return headers;
    }

    private static byte[] decodeBase64Url(String value) {
        String v = value.trim();
        int pad = (4 - v.length() % 4) % 4;
        if (pad > 0) {
            v += "=".repeat(pad);
        }
        return Base64.getUrlDecoder().decode(v);
    }

    private static boolean containsToken(String headerValue, String token) {
        for (String part : headerValue.split(",")) {
            if (token.equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }

    private void handleHttp2Stream(Http2Stream stream, InputStream in,
                                    OutputStream out,
                                    Map<String, List<String>> reqHeaders,
                                    SSLSession sslSession) {
        try {
            String method = headerValue(reqHeaders, ":method");
            String fullPath = headerValue(reqHeaders, ":path");
            String authority = headerValue(reqHeaders, ":authority");
            if (method == null || fullPath == null)
                throw new IOException("Missing pseudo-headers");

            // Split query string from :path (HTTP/2 includes it)
            String path = fullPath;
            String rawQuery = null;
            int q = fullPath.indexOf('?');
            if (q >= 0) {
                path = fullPath.substring(0, q);
                rawQuery = fullPath.substring(q + 1);
            }

            var headers = new LinkedHashMap<>(reqHeaders);
            headers.remove(":method"); headers.remove(":path");
            headers.remove(":scheme"); headers.remove(":authority");
            if (authority != null) headers.put("Host", List.of(authority));

            var rc = HttpContext.createRequestContext(
                headerValue(reqHeaders, "x-request-id"));
            var ctx = new HttpContextDefault(jsonCodec, coercer);
            ctx.setMaxBodySize(config.maxBodySize());
            ctx.setCompression(config.compression());
            ctx.setSecure(sslSession != null);
            ctx.setSslSession(sslSession);
            ctx.reset(method, path, rawQuery, headers, in, -1, false, out, rc, false, false);
            ctx.setWriter(new Http2ResponseWriter(stream));
            ctx.setHeader("X-Request-Id", rc.correlationId());
            registry.requestsInFlight.incrementAndGet();
            metrics.counter("freeway.http.requests.total").increment();
            long startNanos = System.nanoTime();
            try {
                handler.handle(ctx);
            } finally {
                registry.requestsInFlight.decrementAndGet();
                requestTimer.record(System.nanoTime() - startNanos);
            }
            int status = ctx.status();
            if (status >= 500) {
                metrics.counter("freeway.http.responses.5xx").increment();
            } else if (status >= 400) {
                metrics.counter("freeway.http.responses.4xx").increment();
            }
            stream.close();
        } catch (Exception e) {
            LOG.debug("HTTP/2 stream error", e);
            try {
                stream.sendReset();
            } catch (Exception ignored) {
            }
            stream.close();
        }
    }

    // --- WebSocket upgrade ---

    private void handleWebSocketUpgrade(Http11Connection connection,
                                        HttpParser.ParsedRequest req) {
        try {
            String origin = headerValue(req.headers(), "Origin");
            WebSocketMatch match = handler.websocket(req.method(), req.path(), origin);
            if (match == null) {
                sendUpgradeError(connection.outputStream(), 403, "Forbidden");
                return;
            }
            String wsKey = headerValue(req.headers(), "Sec-WebSocket-Key");
            String wsVersion = headerValue(req.headers(), "Sec-WebSocket-Version");
            if (wsKey == null || !"13".equals(wsVersion)) {
                sendUpgradeError(connection.outputStream(), 400, "Bad Request");
                return;
            }
            // RFC 6455 §4.2.1: key must be base64 of 16-byte nonce
            try {
                if (Base64.getDecoder().decode(wsKey).length != 16) {
                    sendUpgradeError(connection.outputStream(), 400, "Invalid Sec-WebSocket-Key");
                    return;
                }
            } catch (IllegalArgumentException e) {
                sendUpgradeError(connection.outputStream(), 400, "Invalid Sec-WebSocket-Key");
                return;
            }
            String acceptKey;
            try { acceptKey = WebSocketUtil.makeAcceptKey(wsKey); }
            catch (Exception e) {
                sendUpgradeError(connection.outputStream(), 500, "Key generation failed");
                return;
            }

            OutputStream out = connection.outputStream();
            writeLine(out, "HTTP/1.1 101 Switching Protocols");
            writeLine(out, "Upgrade: websocket");
            writeLine(out, "Connection: Upgrade");
            writeLine(out, "Sec-WebSocket-Accept: " + acceptKey);
            String protocolHeader =
                headerValue(req.headers(), "Sec-WebSocket-Protocol");
            if (protocolHeader != null
                    && !match.endpoint().subprotocols().isEmpty()) {
                for (String candidate : protocolHeader.split(",")) {
                    String candidateProtocol = candidate.trim();
                    if (match.endpoint().subprotocols().contains(candidateProtocol)) {
                        writeLine(out, "Sec-WebSocket-Protocol: " + candidateProtocol);
                        break;
                    }
                }
            }
            writeLine(out, "");
            out.flush();
            metrics.counter("freeway.http.websocket.connections").increment();

            var wsSession = new WebSocketSessionImpl(req.method(), req.path(),
                req.queryString(), req.headers(), connection.inputStream(),
                connection.outputStream(), match.pathVariables());
            var listener = match.endpoint().open(wsSession);
            listener.onOpen(wsSession);
            WebSocket.readLoop(connection.inputStream(), connection.outputStream(),
                wsSession, listener);
        } catch (Exception e) {
            LOG.trace("WebSocket upgrade error: {}", e.getMessage());
        } finally {
            connection.close();
        }
    }

    // --- helpers ---

    private static void sendUpgradeError(OutputStream out, int code, String msg)
        throws IOException {
        writeLine(out, "HTTP/1.1 " + code + " " + msg);
        writeLine(out, "Content-Length: 0");
        writeLine(out, "Connection: close");
        writeLine(out, "");
        out.flush();
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.ISO_8859_1));
        out.write('\r'); out.write('\n');
    }

    private static String headerValue(Map<String, List<String>> h, String n) {
        for (var e : h.entrySet())
            if (e.getKey().equalsIgnoreCase(n) && !e.getValue().isEmpty())
                return e.getValue().getFirst();
        return null;
    }
}
