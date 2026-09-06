package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.http.engine.http2.Http2Connection;
import com.jujin.freeway.http.engine.http2.Http2ResponseWriter;
import com.jujin.freeway.http.engine.http2.Http2Stream;
import com.jujin.freeway.http.engine.http2.SettingsFrame;
import com.jujin.freeway.http.engine.http2.Http2ErrorCode;

/**
 * HTTP/2 connection handling: preface verification, connection/stream
 * wiring, and per-stream dispatch.
 */
final class Http2Session {

    private static final Logger LOG = LoggerFactory.getLogger(Http2Session.class);
    private static final Set<String> H2_FORBIDDEN_UPGRADE_HEADERS = Set.of(
        "connection", "keep-alive", "proxy-connection",
        "transfer-encoding", "upgrade", "http2-settings", "host");

    private final SessionContext ctx;
    private ExecutorService h2Executor;

    Http2Session(SessionContext ctx) {
        this.ctx = ctx;
    }

    void handle(HttpConnection connection, boolean ssl, Http1xParser parser,
                SettingsFrame upgradeSettings,
                Map<String, List<String>> upgradeStreamHeaders) {
        Http2Connection h2conn = null;
        try {
            InputStream in = ssl
                ? connection.inputStream()
                : parser.bodyStream(-1);
            if (upgradeSettings != null) {
                byte[] preface = new byte[Http2Connection.PREFACE.length()];
                int off = 0;
                while (off < preface.length) {
                    int n = in.read(preface, off, preface.length - off);
                    if (n < 0) throw new IOException("EOF reading HTTP/2 preface");
                    off += n;
                }
                if (!Http2Connection.PREFACE.equals(
                        new String(preface, StandardCharsets.ISO_8859_1)))
                    throw new IOException("Invalid HTTP/2 preface");
            } else if (!ssl) {
                byte[] preface = new byte[Http2Connection.PARTIAL_PREFACE.length()];
                int off = 0;
                while (off < preface.length) {
                    int n = in.read(preface, off, preface.length - off);
                    if (n < 0) throw new IOException("EOF reading HTTP/2 preface");
                    off += n;
                }
                if (!Http2Connection.PARTIAL_PREFACE.equals(
                        new String(preface, StandardCharsets.ISO_8859_1)))
                    throw new IOException("Invalid HTTP/2 preface");
            }

            this.h2Executor = Executors.newVirtualThreadPerTaskExecutor();
            ctx.metrics().h2Connections().increment();
            h2conn = new Http2Connection(connection.socket(), in,
                    connection.outputStream(), h2Executor,
                (stream, streamIn, streamOut, reqHeaders) ->
                    handleHttp2Stream(stream, streamIn, streamOut, reqHeaders,
                        ssl ? connection.getSSLSession() : null,
                        connection.socket()),
                HttpSession.timeoutMillis(ctx.config().readTimeout()),
                ctx.config().h2ResetBurstLimit(), ctx.config().h2ResetWindow());
            final Http2Connection goAwayTarget = h2conn;
            connection.setPreCloseHook(() -> {
                try {
                    goAwayTarget.sendGoAway(Http2ErrorCode.NO_ERROR);
                } catch (IOException ignored) {
                }
            });

            if (upgradeSettings != null) {
                h2conn.applyUpgradeSettings(upgradeSettings);
            }

            if (ssl) {
                if (!h2conn.hasProperPreface(true))
                    throw new IOException("Invalid HTTP/2 TLS preface");
            }
            h2conn.sendMySettings();
            if (upgradeStreamHeaders != null) {
                h2conn.prepopulateUpgradeStream(upgradeStreamHeaders);
            }
            h2conn.handle();
        } catch (IOException e) {
            LOG.trace("HTTP/2 error: {}", e.getMessage());
        } finally {
            if (h2conn != null) h2conn.close();
            if (h2Executor != null) {
                h2Executor.close();
                h2Executor = null;
            }
            connection.close();
        }
    }

    void handleH2cUpgrade(HttpConnection connection, Http1xParser.ParsedRequest req,
                          Http1xParser parser, SettingsFrame settings) {
        try {
            OutputStream out = connection.outputStream();
            HttpSession.writeLine(out, "HTTP/1.1 101 Switching Protocols");
            HttpSession.writeLine(out, "Connection: Upgrade");
            HttpSession.writeLine(out, "Upgrade: h2c");
            HttpSession.writeLine(out, "");
            out.flush();

            handle(connection, false, parser, settings,
                upgradeStreamRequestHeaders(req));
        } catch (IOException e) {
            LOG.trace("h2c upgrade error: {}", e.getMessage());
            connection.close();
        }
    }

    private static Map<String, List<String>> upgradeStreamRequestHeaders(
            Http1xParser.ParsedRequest req) {
        var headers = new LinkedHashMap<String, List<String>>();
        headers.put(":method", List.of(req.method()));
        String path = req.path();
        if (req.queryString() != null && !req.queryString().isEmpty()) {
            path += "?" + req.queryString();
        }
        headers.put(":path", List.of(path));
        headers.put(":scheme", List.of("http"));
        headers.put(":authority", List.of(HttpSession.headerValue(req.headers(), "host")));
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

    private void handleHttp2Stream(Http2Stream stream, InputStream in,
                                    OutputStream out,
                                    Map<String, List<String>> reqHeaders,
                                    SSLSession sslSession, Socket socket) {
        try {
            String method = HttpSession.headerValue(reqHeaders, ":method");
            String fullPath = HttpSession.headerValue(reqHeaders, ":path");
            String authority = HttpSession.headerValue(reqHeaders, ":authority");
            if (method == null || fullPath == null)
                throw new IOException("Missing pseudo-headers");

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

            String correlationId = HttpSession.headerValue(reqHeaders, "x-request-id");
            var context = new HttpContextImpl(ctx.jsonCodec(), ctx.coercer(), correlationId);
            context.setMaxBodySize(ctx.config().maxBodySize());
            context.setCompression(ctx.config().compression());
            context.setSecure(sslSession != null);
            context.setSslSession(sslSession);
            context.setRemoteAddress(HttpSession.remoteAddress(socket));
            context.reset(method, path, rawQuery, headers, in, -1, false,
                out, correlationId, false, false);
            context.setWriter(new Http2ResponseWriter(stream));
            // Echo for tracing only — a hostile value (e.g. CR/LF inside an
            // HPACK-encoded header) must never poison the response head; the
            // stream-level catch below would otherwise reset it.
            HttpSession.echoRequestId(context);
            ctx.executeRequest(context);
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
}
