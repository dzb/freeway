package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.websocket.WebSocketListener;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket frame read loop. Reads frames from the input stream and
 * dispatches them to a {@link WebSocketListener}. Adapted from nanohttpd.
 */
final class WebSocket {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocket.class);

    private WebSocket() {}

    /**
     * Blocking frame read loop. Drives the listener callbacks until the
     * connection closes or an error occurs.
     */
    static void readLoop(InputStream in, OutputStream out,
                         WebSocketSessionImpl session,
                         WebSocketListener listener) {
        session.markOpen();
        try {
            while (session.isOpen()) {
                var frame = WebSocketFrame.read(in);
                switch (frame.opCode()) {
                    case Text -> {
                        try {
                            listener.onText(frame.payloadAsString());
                        } catch (Exception e) {
                            LOG.trace("WebSocket onText error", e);
                            listener.onError(e);
                            session.close(1011, "Handler error");
                            return;
                        }
                    }
                    case Binary -> {
                        try {
                            listener.onBinary(frame.payload());
                        } catch (Exception e) {
                            LOG.trace("WebSocket onBinary error", e);
                            listener.onError(e);
                            session.close(1011, "Handler error");
                            return;
                        }
                    }
                    case Ping -> writeFrame(out,
                        new WebSocketFrame(OpCode.Pong, true, frame.payload()));
                    case Pong -> { /* no-op */ }
                    case Close -> {
                        // Echo close frame back per RFC 6455
                        writeFrame(out, new WebSocketFrame(OpCode.Close, true,
                            frame.payload()));
                        String reason = frame.closeReason() != null
                            ? frame.closeReason() : "";
                        int code = frame.closeCode() != null
                            ? frame.closeCode().value() : 1000;
                        session.markClosed(code, reason);
                        try {
                            listener.onClose(code, reason, true);
                        } catch (Exception ignored) {}
                        return;
                    }
                }
            }
        } catch (EOFException e) {
            LOG.trace("WebSocket EOF: {}", e.getMessage());
            session.markClosed(CloseCode.AbnormalClosure, e.getMessage());
            try { listener.onError(e); } catch (Exception ignored) {}
        } catch (WebSocketException e) {
            LOG.trace("WebSocket protocol error: {}", e.getMessage());
            session.markClosed(e.closeCode(), e.closeReason());
            try { listener.onError(e); } catch (Exception ignored) {}
        } catch (IOException e) {
            LOG.trace("WebSocket I/O error: {}", e.getMessage());
            session.markClosed(CloseCode.AbnormalClosure, e.getMessage());
            try { listener.onError(e); } catch (Exception ignored) {}
        } finally {
            if (session.isOpen()) {
                session.markClosed(CloseCode.InternalServerError,
                    "Handler terminated without closing");
            }
        }
    }

    static void writeFrame(OutputStream out, WebSocketFrame frame)
        throws IOException {
        frame.write(out);
    }
}
