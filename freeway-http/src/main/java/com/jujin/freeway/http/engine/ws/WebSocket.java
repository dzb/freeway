package com.jujin.freeway.http.engine.ws;

import com.jujin.freeway.http.websocket.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * WebSocket frame read loop. Reads frames from the input stream and
 * dispatches them to a {@link WebSocketListener}. Adapted from nanohttpd.
 */
public final class WebSocket {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocket.class);

    private WebSocket() {}

    /**
     * Blocking frame read loop. Drives the listener callbacks until the
     * connection closes or an error occurs.
     */
    public static void readLoop(InputStream in, OutputStream out,
                         WebSocketSessionImpl session,
                         WebSocketListener listener) {
        session.markOpen();
        var textBuf = new StringBuilder();
        var binaryBuf = new ByteArrayOutputStream();
        OpCode fragType = null;

        try {
            while (session.isOpen()) {
                var frame = WebSocketFrame.read(in);
                if (!frame.isMasked()) {
                    session.close(1002, "Client frame must be masked");
                    return;
                }
                switch (frame.opCode()) {
                    case Text, Binary -> {
                        if (fragType != null) {
                            session.close(1002, "Continuation expected");
                            return;
                        }
                        if (frame.isFin()) {
                            if (frame.opCode() == OpCode.Text) {
                                try {
                                    listener.onText(frame.payloadAsString());
                                } catch (Exception e) {
                                    LOG.trace("WebSocket onText error", e);
                                    listener.onError(e);
                                    session.close(1011, "Handler error");
                                    return;
                                }
                            } else {
                                try {
                                    listener.onBinary(frame.payload());
                                } catch (Exception e) {
                                    LOG.trace("WebSocket onBinary error", e);
                                    listener.onError(e);
                                    session.close(1011, "Handler error");
                                    return;
                                }
                            }
                        } else {
                            fragType = frame.opCode();
                            appendData(frame, textBuf, binaryBuf, fragType);
                        }
                    }
                    case Continuation -> {
                        if (fragType == null) {
                            session.close(1002, "Unexpected continuation");
                            return;
                        }
                        appendData(frame, textBuf, binaryBuf, fragType);
                        if (frame.isFin()) {
                            deliverFragmented(fragType, textBuf, binaryBuf, listener);
                            fragType = null;
                            textBuf.setLength(0);
                            binaryBuf.reset();
                        }
                    }
                    case Ping -> writeFrame(out,
                        new WebSocketFrame(OpCode.Pong, true, frame.payload()));
                    case Pong -> { /* no-op */ }
                    case Close -> {
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

    public static void writeFrame(OutputStream out, WebSocketFrame frame)
        throws IOException {
        frame.write(out);
    }

    /** Writes a frame without flushing — caller must flush separately. */
    static void writeFrameNoFlush(OutputStream out, WebSocketFrame frame)
        throws IOException {
        frame.writeWithoutFlush(out);
    }

    // -- fragmentation helpers --

    private static void appendData(WebSocketFrame frame, StringBuilder textBuf,
                                   ByteArrayOutputStream binaryBuf, OpCode fragType) {
        OpCode type = frame.opCode() == OpCode.Continuation ? fragType : frame.opCode();
        if (type == OpCode.Text) {
            textBuf.append(frame.payloadAsString());
        } else {
            try {
                binaryBuf.write(frame.payload());
            } catch (IOException e) { /* ByteArrayOutputStream never throws */ }
        }
    }

    private static void deliverFragmented(OpCode fragType, StringBuilder textBuf,
                                          ByteArrayOutputStream binaryBuf,
                                          WebSocketListener listener) {
        try {
            if (fragType == OpCode.Text) listener.onText(textBuf.toString());
            else listener.onBinary(binaryBuf.toByteArray());
        } catch (Exception e) {
            LOG.trace("WebSocket handler error", e);
            listener.onError(e);
        }
    }
}
