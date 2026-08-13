package com.jujin.freeway.http.engine;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.http.ErrorResponses;
import com.jujin.freeway.http.engine.http2.frame.FrameFlag;
import com.jujin.freeway.http.engine.http2.frame.FrameHeader;
import com.jujin.freeway.http.engine.http2.frame.FrameType;
import com.jujin.freeway.http.engine.http2.frame.SettingsFrame;

/**
 * HTTP/1.1 connection loop: keep-alive parsing, request dispatch, and the
 * h2c / WebSocket upgrade hand-off.
 */
final class Http1xSession {

    private static final Logger LOG = LoggerFactory.getLogger(Http1xSession.class);
    private final SessionContext ctx;

    Http1xSession(SessionContext ctx) {
        this.ctx = ctx;
    }

    void handle(HttpConnection connection) {
        try {
            var in = connection.inputStream();
            var out = connection.outputStream();
            var parser = new Http1xParser(in);
            var context = new HttpContextDefault(ctx.jsonCodec(), ctx.coercer());
            context.setMaxBodySize(ctx.config().maxBodySize());
            context.setCompression(ctx.config().compression());
            if (ctx.engine().sslContext() == null
                    && connection.socket().getChannel() != null) {
                context.setFileSender((channel, offset, length) -> {
                    ctx.metrics().sendfileTransfers().increment();
                    connection.transferFile(channel, offset, length);
                });
            }
            context.setSecure(connection.getSSLSession() != null);
            context.setSslSession(connection.getSSLSession());
            context.setRemoteAddress(HttpSession.remoteAddress(connection.socket()));

            while (!connection.closed) {
                parser.reset(in);
                var req = parser.parse();
                if (req == null) break;

                if (req.isHttp2Preface()) {
                    new Http2Session(ctx).handle(connection, false, parser, null, null);
                    return;
                }

                if (!req.isHttp10() && invalidHostHeader(req.headers())) {
                    HttpSession.sendUpgradeError(connection.outputStream(), 400, "Bad Request");
                    break;
                }

                String expectHeader = HttpSession.headerValue(req.headers(), "expect");
                if (expectHeader != null && !expects100Continue(req)) {
                    HttpSession.sendUpgradeError(connection.outputStream(), 417,
                        "Expectation Failed");
                    break;
                }

                if (isH2cUpgradeRequest(req)) {
                    SettingsFrame h2cSettings = tryPrepareH2cUpgrade(req);
                    if (h2cSettings != null) {
                        ctx.metrics().requestsTotal().increment();
                        new Http2Session(ctx).handleH2cUpgrade(connection, req, parser, h2cSettings);
                        return;
                    }
                }

                var upgradeHeader = req.headers().get("upgrade");
                if (req.isUpgradeRequest() && upgradeHeader != null
                        && !upgradeHeader.isEmpty()
                        && "websocket".equalsIgnoreCase(upgradeHeader.getFirst())) {
                    ctx.metrics().requestsTotal().increment();
                    new WebSocketUpgrade(ctx).handle(connection, parser, req);
                    return;
                }

                var reqIdHeader = req.headers().get("x-request-id");
                String correlationId = reqIdHeader != null && !reqIdHeader.isEmpty()
                    ? reqIdHeader.getFirst() : null;
                long bodyLength = req.isChunked() ? -1L : Math.max(0L, req.contentLength());
                InputStream bodyStream = req.isChunked() || req.contentLength() > 0
                    ? parser.bodyStream(bodyLength)
                    : in;
                context.reset(req.method(), req.path(), req.queryString(),
                    req.headers(), bodyStream, bodyLength, req.isChunked(),
                    out, correlationId, req.isHttp10(), req.keepAlive());
                context.setHeader("X-Request-Id", context.correlationId());
                if ((req.isChunked() || bodyLength > 0) && expects100Continue(req)) {
                    HttpSession.writeLine(out, "HTTP/1.1 100 Continue");
                    HttpSession.writeLine(out, "");
                    out.flush();
                }

                try { ctx.executeRequest(context); }
                catch (Exception e) {
                    LOG.debug("Handler exception for {} {}", req.method(), req.path(), e);
                    if (!context.isResponded()) {
                        try {
                            ErrorResponses.internalError(context);
                        } catch (IOException ignored) {}
                    }
                }

                boolean bodyDrained = context.drainUnreadBody();
                if (req.isChunked() && bodyDrained) {
                    parser.reclaimChunkedPrefix();
                }
                if (!bodyDrained) break;
                context.syncKeepAliveFromResponse();
                if (ctx.registry().isStopping()) break;
                if (!context.isKeepAlive()) break;
                if (req.isHttp10() && !req.keepAlive()) break;
            }
        } catch (SocketTimeoutException e) {
            LOG.trace("Connection idle timeout: {}", e.getMessage());
        } catch (EOFException e) {
            LOG.trace("Connection closed by peer: {}", e.getMessage());
        } catch (IOException e) {
            LOG.debug("Malformed request, replying 400: {}", e.getMessage());
            if (!connection.closed) {
                try {
                    HttpSession.sendUpgradeError(connection.outputStream(), 400, "Bad Request");
                } catch (IOException ignored) {
                }
            }
        } catch (Exception e) {
            LOG.warn("Unexpected session error", e);
        }
    }

    private static boolean isH2cUpgradeRequest(Http1xParser.ParsedRequest req) {
        String connection = HttpSession.headerValue(req.headers(), "connection");
        if (connection == null || !HttpSession.containsToken(connection, "upgrade")) {
            return false;
        }
        String upgrade = HttpSession.headerValue(req.headers(), "upgrade");
        return upgrade != null && HttpSession.containsToken(upgrade, "h2c");
    }

    private static boolean expects100Continue(Http1xParser.ParsedRequest req) {
        String expect = HttpSession.headerValue(req.headers(), "expect");
        return expect != null && HttpSession.containsToken(expect, "100-continue");
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

    private static SettingsFrame tryPrepareH2cUpgrade(Http1xParser.ParsedRequest req) {
        if (req.isHttp10() || req.isChunked() || req.contentLength() > 0) {
            return null;
        }
        String host = HttpSession.headerValue(req.headers(), "host");
        if (host == null || host.isBlank()) return null;
        String connection = HttpSession.headerValue(req.headers(), "connection");
        if (connection == null
                || !HttpSession.containsToken(connection, "http2-settings")) {
            return null;
        }
        var settingsValues = req.headers().get("http2-settings");
        if (settingsValues == null || settingsValues.size() != 1) {
            return null;
        }
        byte[] settingsPayload;
        try {
            settingsPayload = HttpSession.decodeBase64Url(settingsValues.getFirst());
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
}
