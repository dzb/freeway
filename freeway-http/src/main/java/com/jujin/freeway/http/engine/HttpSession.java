package com.jujin.freeway.http.engine;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import javax.net.ssl.SSLSocket;

import jdk.net.ExtendedSocketOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.ExchangeHandler;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.internal.HttpUtils;

/**
 * Per-connection entry point. Tunes the socket, wraps TLS/ALPN, then hands
 * the connection to the HTTP/1.1, HTTP/2, or WebSocket protocol handler.
 */
final class HttpSession implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpSession.class);
    private static final int KEEPALIVE_IDLE_SECONDS = 30;
    private static final int KEEPALIVE_INTERVAL_SECONDS = 15;
    private static final int KEEPALIVE_PROBE_COUNT = 3;
    private static final int SESSION_BUFFER_SIZE = 1024;

    private final Socket rawSocket;
    private final SessionContext context;
    private final Semaphore connectionPermits;

    HttpSession(Socket socket, ExchangeHandler handler,
            JsonCodec jsonCodec, Coercer coercer, FreewayHttpEngine engine,
            HttpServerConfig config, ConnectionRegistry registry) {
        this(socket, handler, jsonCodec, coercer, engine, config, registry, null);
    }

    HttpSession(Socket socket, ExchangeHandler handler,
            JsonCodec jsonCodec, Coercer coercer, FreewayHttpEngine engine,
            HttpServerConfig config, ConnectionRegistry registry,
            Semaphore connectionPermits) {
        this.rawSocket = socket;
        this.context = new SessionContext(handler, jsonCodec, coercer, engine,
            config, registry, new HttpMetrics(engine.metrics()));
        this.connectionPermits = connectionPermits;
    }

    @Override
    public void run() {
        HttpConnection connection = null;
        try {
            Socket socket = rawSocket;
            boolean isH2 = false;

            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            configureKeepAliveProbe(socket);
            socket.setSoTimeout(timeoutMillis(context.config().readTimeout()));
            if (context.config().receiveBufferSize() > 0) {
                socket.setReceiveBufferSize(context.config().receiveBufferSize());
            }
            if (context.config().sendBufferSize() > 0) {
                socket.setSendBufferSize(context.config().sendBufferSize());
            }

            if (context.engine().sslContext() != null) {
                var sslSocket = (SSLSocket) context.engine().sslContext()
                    .getSocketFactory().createSocket(rawSocket, null, false);
                sslSocket.setUseClientMode(false);
                sslSocket.setSoTimeout(timeoutMillis(context.config().readTimeout()));
                configureKeepAliveProbe(sslSocket);
                if (context.engine().sslParameters() != null) {
                    sslSocket.setSSLParameters(context.engine().sslParameters());
                }
                sslSocket.setHandshakeApplicationProtocolSelector(
                    (sslEngine, protocols) ->
                        context.engine().http2OverSsl() && protocols.contains("h2")
                            ? "h2" : "http/1.1");
                sslSocket.startHandshake();
                isH2 = context.engine().http2OverSsl()
                    && "h2".equals(sslSocket.getApplicationProtocol());
                socket = sslSocket;
            }

            connection = new HttpConnection(
                socket, SESSION_BUFFER_SIZE,
                timeoutMillis(context.config().writeTimeout()));
            context.registry().register(connection);

            if (isH2) {
                new Http2Session(context).handle(connection, true, null, null, null);
            } else {
                new Http1xSession(context).handle(connection);
            }
        } catch (SocketTimeoutException e) {
            LOG.trace("Connection idle timeout: {}", e.getMessage());
        } catch (EOFException e) {
            LOG.trace("Connection closed by peer: {}", e.getMessage());
        } catch (IOException e) {
            LOG.debug("Connection setup failed: {}", e.getMessage());
        } catch (Exception e) {
            LOG.warn("Unexpected session error", e);
        } finally {
            if (connection != null) {
                context.registry().unregister(connection);
                connection.close();
            } else if (!rawSocket.isClosed()) {
                try {
                    rawSocket.close();
                } catch (IOException ignored) {}
            }
            if (connectionPermits != null) connectionPermits.release();
        }
    }

    // -- shared connection/protocol helpers --

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

    static int timeoutMillis(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return 0; // disabled
        }
        long millis = timeout.toMillis();
        return (int) Math.min(Math.max(millis, 1), Integer.MAX_VALUE);
    }

    static String remoteAddress(Socket socket) {
        var address = socket.getInetAddress();
        return address != null ? address.getHostAddress() : "";
    }

    static String headerValue(Map<String, List<String>> headers, String name) {
        return HttpUtils.headerValue(headers, name);
    }

    static boolean containsToken(String headerValue, String token) {
        for (String part : headerValue.split(",")) {
            if (token.equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }

    static byte[] decodeBase64Url(String value) {
        String v = value.trim();
        int pad = (4 - v.length() % 4) % 4;
        if (pad > 0) {
            v += "=".repeat(pad);
        }
        return Base64.getUrlDecoder().decode(v);
    }

    static void sendUpgradeError(OutputStream out, int code, String msg)
            throws IOException {
        writeLine(out, "HTTP/1.1 " + code + " " + msg);
        writeLine(out, "Content-Length: 0");
        writeLine(out, "Connection: close");
        writeLine(out, "");
        out.flush();
    }

    static void writeLine(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.ISO_8859_1));
        out.write('\r');
        out.write('\n');
    }
}
