package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.http.engine.ws.WebSocket;
import com.jujin.freeway.http.engine.ws.WebSocketSessionImpl;
import com.jujin.freeway.http.engine.ws.WebSocketUtil;
import com.jujin.freeway.http.websocket.WebSocketMatch;

/**
 * WebSocket upgrade handshake and read loop, reached from the HTTP/1.1 loop.
 */
final class WebSocketUpgrade {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketUpgrade.class);

    private final SessionContext ctx;

    WebSocketUpgrade(SessionContext ctx) {
        this.ctx = ctx;
    }

    void handle(HttpConnection connection, Http1xParser parser,
                Http1xParser.ParsedRequest req) {
        try {
            String origin = HttpSession.headerValue(req.headers(), "Origin");
            WebSocketMatch match = ctx.handler().websocket(req.method(), req.path(), origin);
            if (match == null) {
                HttpSession.sendUpgradeError(connection.outputStream(), 403, "Forbidden");
                return;
            }
            String wsKey = HttpSession.headerValue(req.headers(), "Sec-WebSocket-Key");
            String wsVersion = HttpSession.headerValue(req.headers(), "Sec-WebSocket-Version");
            if (wsKey == null || !"13".equals(wsVersion)) {
                HttpSession.sendUpgradeError(connection.outputStream(), 400, "Bad Request");
                return;
            }
            try {
                if (Base64.getDecoder().decode(wsKey).length != 16) {
                    HttpSession.sendUpgradeError(connection.outputStream(), 400,
                        "Invalid Sec-WebSocket-Key");
                    return;
                }
            } catch (IllegalArgumentException e) {
                HttpSession.sendUpgradeError(connection.outputStream(), 400,
                    "Invalid Sec-WebSocket-Key");
                return;
            }
            String acceptKey;
            try {
                acceptKey = WebSocketUtil.makeAcceptKey(wsKey);
            } catch (Exception e) {
                HttpSession.sendUpgradeError(connection.outputStream(), 500,
                    "Key generation failed");
                return;
            }

            OutputStream out = connection.outputStream();
            HttpSession.writeLine(out, "HTTP/1.1 101 Switching Protocols");
            HttpSession.writeLine(out, "Upgrade: websocket");
            HttpSession.writeLine(out, "Connection: Upgrade");
            HttpSession.writeLine(out, "Sec-WebSocket-Accept: " + acceptKey);
            String protocolHeader =
                HttpSession.headerValue(req.headers(), "Sec-WebSocket-Protocol");
            if (protocolHeader != null
                    && !match.endpoint().subprotocols().isEmpty()) {
                for (String candidate : protocolHeader.split(",")) {
                    String candidateProtocol = candidate.trim();
                    if (match.endpoint().subprotocols().contains(candidateProtocol)) {
                        HttpSession.writeLine(out,
                            "Sec-WebSocket-Protocol: " + candidateProtocol);
                        break;
                    }
                }
            }
            HttpSession.writeLine(out, "");
            out.flush();
            ctx.metrics().websocketConnections().increment();

            InputStream websocketInput = parser.upgradeStream();
            var wsSession = new WebSocketSessionImpl(req.method(), req.path(),
                req.queryString(), req.headers(), websocketInput,
                connection.outputStream(), match.pathVariables(),
                HttpSession.headerValue(req.headers(), "x-request-id"));
            var listener = match.endpoint().open(wsSession);
            listener.onOpen(wsSession);
            WebSocket.readLoop(websocketInput, connection.outputStream(),
                wsSession, listener);
        } catch (Exception e) {
            LOG.trace("WebSocket upgrade error: {}", e.getMessage());
        } finally {
            connection.close();
        }
    }
}
