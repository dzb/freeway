package com.jujin.freeway.http.engine;
import com.jujin.freeway.http.engine.http11.Http11Connection;
import com.jujin.freeway.http.engine.http11.HttpParser;
import com.jujin.freeway.http.engine.http20.Http2Connection;
import com.jujin.freeway.http.engine.http20.Http2Stream;
import com.jujin.freeway.http.engine.ws.WebSocket;
import com.jujin.freeway.http.engine.ws.WebSocketSessionImpl;
import com.jujin.freeway.http.engine.ws.WsUtil;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpRequestHandler;
import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.websocket.WebSocketMatch;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-connection handler. Handles plain HTTP, HTTPS with ALPN, WebSocket upgrade,
 * and HTTP/2 (both h2c and h2 over TLS).
 */
public final class HttpSession implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpSession.class);
    private static final byte[] INTERNAL_ERROR_BODY =
        "Internal Server Error".getBytes(StandardCharsets.UTF_8);

    private final Socket rawSocket;
    private final HttpRequestHandler handler;
    private final JsonCodec jsonCodec;
    private final Coercer coercer;
    private final FreewayHttpEngine engine;
    private final int socketBufferSize;
    private final long maxBodySize;

    public HttpSession(Socket socket, HttpRequestHandler handler,
            JsonCodec jsonCodec, Coercer coercer, FreewayHttpEngine engine,
            int socketBufferSize, long maxBodySize) {
        this.rawSocket = socket;
        this.handler = handler;
        this.jsonCodec = jsonCodec;
        this.coercer = coercer;
        this.engine = engine;
        this.socketBufferSize = socketBufferSize;
        this.maxBodySize = maxBodySize;
    }

    @Override
    public void run() {
        Http11Connection connection = null;
        try {
            Socket socket = rawSocket;
            boolean isH2 = false;

            // SSL wrapping + ALPN negotiation
            if (engine.sslContext() != null) {
                var sslSocket = (SSLSocket) engine.sslContext().getSocketFactory()
                    .createSocket(rawSocket, null, false);
                sslSocket.setUseClientMode(false);
                sslSocket.setHandshakeApplicationProtocolSelector(
                    (sslEngine, protocols) ->
                        engine.http2OverSsl() && protocols.contains("h2")
                            ? "h2" : "http/1.1");
                sslSocket.startHandshake();
                isH2 = engine.http2OverSsl() && "h2".equals(sslSocket.getApplicationProtocol());
                socket = sslSocket;
            }

            connection = new Http11Connection(socket, socketBufferSize);
            var in = connection.inputStream();
            var out = connection.outputStream();

            // HTTP/2 over TLS (ALPN negotiated)
            if (isH2) {
                handleHttp2Upgrade(connection, true);
                return;
            }

            // HTTP/1.1 loop — reuse parser + context across requests
            var parser = new HttpParser(in);
            var ctx = new FreewayHttpContext(jsonCodec, coercer);
            ctx.setMaxBodySize(maxBodySize);

            while (!connection.closed) {
                parser.reset(in);
                var req = parser.parse();
                if (req == null) break;

                if (req.isHttp2Preface()) {
                    handleHttp2Upgrade(connection, false);
                    return;
                }

                // Direct Map.get for WS upgrade check — avoids stream
                var upgradeHeader = req.headers().get("upgrade");
                if (req.isUpgradeRequest() && upgradeHeader != null
                        && !upgradeHeader.isEmpty()
                        && "websocket".equalsIgnoreCase(upgradeHeader.getFirst())) {
                    handleWebSocketUpgrade(connection, req);
                    return;
                }

                // Direct Map.get for X-Request-Id
                var reqIdHeader = req.headers().get("x-request-id");
                String correlationId = reqIdHeader != null && !reqIdHeader.isEmpty()
                    ? reqIdHeader.getFirst() : null;
                RequestContext requestContext = HttpContext.createRequestContext(correlationId);

                InputStream bodyStream = parser.bodyStream();

                ctx.reset(req.method(), req.path(), req.queryString(),
                    req.headers(), bodyStream, req.contentLength(), req.isChunked(),
                    out, requestContext, req.isHttp10(), req.keepAlive());
                ctx.headerSet("X-Request-Id", requestContext.correlationId());

                try { handler.handle(ctx); }
                catch (Exception e) {
                    LOG.debug("Handler exception for {} {}", req.method(), req.path(), e);
                    if (!ctx.isResponded()) {
                        try { ctx.status(500).headerSet("Content-Type", "text/plain; charset=utf-8").output(INTERNAL_ERROR_BODY); }
                        catch (IOException ignored) {}
                    }
                }

                ctx.drainUnreadBody();
                if (!ctx.isKeepAlive()) break;
                if (req.isHttp10() && !req.keepAlive()) break;
            }
        } catch (IOException e) {
            LOG.trace("Connection I/O error: {}", e.getMessage());
        } catch (Exception e) {
            LOG.warn("Unexpected session error", e);
        } finally {
            if (connection != null) connection.close();
        }
    }

    // --- HTTP/2 upgrade (h2c: ssl=false, h2: ssl=true) ---

    private void handleHttp2Upgrade(Http11Connection connection, boolean ssl) {
        try {
            var in = connection.inputStream();
            if (!ssl) {
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

            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var h2conn = new Http2Connection(connection.socket(), in,
                connection.outputStream(), executor,
                (stream, streamIn, streamOut, reqHeaders) ->
                    handleHttp2Stream(stream, streamIn, streamOut, reqHeaders));

            if (ssl) {
                if (!h2conn.hasProperPreface(true))
                    throw new IOException("Invalid HTTP/2 TLS preface");
            }
            h2conn.sendMySettings();
            h2conn.handle();
        } catch (IOException e) {
            LOG.trace("HTTP/2 error: {}", e.getMessage());
        } finally {
            connection.close();
        }
    }

    private void handleHttp2Stream(Http2Stream stream, InputStream in,
                                    OutputStream out,
                                    Map<String, List<String>> reqHeaders) {
        try {
            String method = headerValue(reqHeaders, ":method");
            String path = headerValue(reqHeaders, ":path");
            String authority = headerValue(reqHeaders, ":authority");
            if (method == null || path == null)
                throw new IOException("Missing pseudo-headers");

            var headers = new LinkedHashMap<>(reqHeaders);
            headers.remove(":method"); headers.remove(":path");
            headers.remove(":scheme"); headers.remove(":authority");
            if (authority != null) headers.put("Host", List.of(authority));

            var rc = HttpContext.createRequestContext(
                headerValue(reqHeaders, "x-request-id"));
            var ctx = new FreewayHttpContext(jsonCodec, coercer);
            ctx.setMaxBodySize(maxBodySize);
            ctx.reset(method, path, null, headers, in, -1, false, out, rc, false, false);
            ctx.headerSet("X-Request-Id", rc.correlationId());
            handler.handle(ctx);
        } catch (Exception e) {
            LOG.debug("HTTP/2 stream error", e);
        }
    }

    // --- WebSocket upgrade ---

    private void handleWebSocketUpgrade(Http11Connection connection,
                                        HttpParser.ParsedRequest req) {
        try {
            String origin = headerValueReq(req, "Origin");
            WebSocketMatch match = handler.websocket(req.method(), req.path(), origin);
            if (match == null) {
                sendUpgradeError(connection.outputStream(), 403, "Forbidden");
                return;
            }
            String wsKey = headerValueReq(req, "Sec-WebSocket-Key");
            String wsVersion = headerValueReq(req, "Sec-WebSocket-Version");
            if (wsKey == null || !"13".equals(wsVersion)) {
                sendUpgradeError(connection.outputStream(), 400, "Bad Request");
                return;
            }
            String acceptKey;
            try { acceptKey = WsUtil.makeAcceptKey(wsKey); }
            catch (Exception e) {
                sendUpgradeError(connection.outputStream(), 500, "Key generation failed");
                return;
            }

            OutputStream out = connection.outputStream();
            writeLine(out, "HTTP/1.1 101 Switching Protocols");
            writeLine(out, "Upgrade: websocket");
            writeLine(out, "Connection: Upgrade");
            writeLine(out, "Sec-WebSocket-Accept: " + acceptKey);
            String protocol = headerValueReq(req, "Sec-WebSocket-Protocol");
            if (protocol != null)
                writeLine(out, "Sec-WebSocket-Protocol: " + protocol.split(",")[0].trim());
            writeLine(out, "");
            out.flush();

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

    private static String headerValueReq(HttpParser.ParsedRequest req, String name) {
        for (var e : req.headers().entrySet())
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty())
                return e.getValue().getFirst();
        return null;
    }

    private static String headerValue(Map<String, List<String>> h, String n) {
        for (var e : h.entrySet())
            if (e.getKey().equalsIgnoreCase(n) && !e.getValue().isEmpty())
                return e.getValue().getFirst();
        return null;
    }
}
