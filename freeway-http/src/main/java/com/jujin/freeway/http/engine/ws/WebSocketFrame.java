package com.jujin.freeway.http.engine.ws;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * RFC 6455 WebSocket frame with binary serialization/deserialization.
 * Supports text, binary, control (close/ping/pong), and fragmented frames.
 */
public final class WebSocketFrame {

    static final Charset TEXT_CHARSET = StandardCharsets.UTF_8;
    static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

    private final OpCode opCode;
    private final boolean fin;
    private byte[] maskingKey;
    private byte[] payload;
    private String payloadString;

    // --- constructors ---

    public WebSocketFrame(OpCode opCode, boolean fin) {
        this.opCode = opCode;
        this.fin = fin;
    }

    public WebSocketFrame(OpCode opCode, boolean fin, byte[] payload) {
        this(opCode, fin);
        this.payload = payload;
    }

    public WebSocketFrame(OpCode opCode, boolean fin, String textPayload) {
        this(opCode, fin);
        setTextPayload(textPayload);
    }

    // --- accessors ---

    OpCode opCode() { return opCode; }
    boolean isFin() { return fin; }
    boolean isMasked() { return maskingKey != null; }
    byte[] payload() { return payload; }

    String payloadAsString() {
        if (payloadString == null) {
            payloadString = new String(payload, TEXT_CHARSET);
        }
        return payloadString;
    }

    // --- close frame info (populated when opCode == Close) ---

    private CloseCode closeCode;
    private String closeReason;

    CloseCode closeCode() { return closeCode; }
    String closeReason() { return closeReason; }

    // --- read from wire ---

    static WebSocketFrame read(InputStream in) throws IOException {
        int head = checkedRead(in.read());
        boolean fin = (head & 0x80) != 0;
        OpCode opCode = OpCode.find((byte) (head & 0x0F));

        if ((head & 0x70) != 0) {
            throw new WebSocketException(CloseCode.ProtocolError,
                "Reserved bits must be 0");
        }
        if (opCode == null) {
            throw new WebSocketException(CloseCode.ProtocolError,
                "Unknown opcode " + (head & 0x0F));
        }
        if (opCode.isControlFrame() && !fin) {
            throw new WebSocketException(CloseCode.ProtocolError,
                "Fragmented control frame");
        }

        var frame = new WebSocketFrame(opCode, fin);
        frame.readPayloadInfo(in);
        frame.readPayload(in);

        if (opCode == OpCode.Close) {
            frame.parseCloseFrame();
        }
        return frame;
    }

    // --- write to wire ---

    static String decodeUtf8(byte[] data, int offset, int length) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data, offset, length)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new WebSocketException(CloseCode.InvalidFramePayloadData, "Invalid UTF-8");
        }
    }

    void write(OutputStream out) throws IOException {
        if (opCode.isControlFrame() && payload != null && payload.length > 125) {
            throw new WebSocketException(CloseCode.ProtocolError,
                "Control frame payload must not exceed 125 bytes");
        }
        int header = fin ? 0x80 : 0;
        header |= opCode.value() & 0x0F;
        out.write(header);

        int len = payload != null ? payload.length : 0;
        boolean masked = maskingKey != null && maskingKey.length == 4;

        if (len <= 125) {
            out.write(masked ? 0x80 | (byte) len : (byte) len);
        } else if (len <= 0xFFFF) {
            out.write(masked ? 0xFE : 126);
            out.write(len >>> 8);
            out.write(len);
        } else {
            out.write(masked ? 0xFF : 127);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(len >>> 24);
            out.write(len >>> 16);
            out.write(len >>> 8);
            out.write(len);
        }

        if (masked) {
            out.write(maskingKey);
            for (int i = 0; i < len; i++) {
                out.write(payload[i] ^ maskingKey[i % 4]);
            }
        } else {
            out.write(payload);
        }
        out.flush();
    }

    // --- internal ---

    private void setTextPayload(String text) {
        this.payload = text.getBytes(TEXT_CHARSET);
        this.payloadString = text;
    }

    private void readPayloadInfo(InputStream in) throws IOException {
        byte b = (byte) checkedRead(in.read());
        boolean masked = (b & 0x80) != 0;
        int payloadLength = b & 0x7F;

        if (payloadLength == 126) {
            payloadLength = (checkedRead(in.read()) << 8 | checkedRead(in.read())) & 0xFFFF;
            if (payloadLength < 126) {
                throw new WebSocketException(CloseCode.ProtocolError,
                    "Not using minimal length encoding");
            }
        } else if (payloadLength == 127) {
            long longLen = (long) checkedRead(in.read()) << 56
                | (long) checkedRead(in.read()) << 48
                | (long) checkedRead(in.read()) << 40
                | (long) checkedRead(in.read()) << 32
                | checkedRead(in.read()) << 24
                | checkedRead(in.read()) << 16
                | checkedRead(in.read()) << 8
                | checkedRead(in.read());
            if (longLen < 65536) {
                throw new WebSocketException(CloseCode.ProtocolError,
                    "Not using minimal length encoding");
            }
            if (longLen < 0 || longLen > MAX_FRAME_SIZE) {
                throw new WebSocketException(CloseCode.MessageTooBig,
                    "Max frame length exceeded");
            }
            payloadLength = (int) longLen;
        }
        if (payloadLength > MAX_FRAME_SIZE) {
            throw new WebSocketException(CloseCode.MessageTooBig,
                "Max frame length exceeded: " + payloadLength);
        }

        if (opCode.isControlFrame() && payloadLength > 125) {
            throw new WebSocketException(CloseCode.ProtocolError,
                "Control frame payload > 125 bytes");
        }
        if (opCode == OpCode.Close && payloadLength == 1) {
            throw new WebSocketException(CloseCode.ProtocolError,
                "Close frame with payload len 1");
        }

        if (masked) {
            maskingKey = new byte[4];
            int read = 0;
            while (read < 4) {
                read += checkedRead(in.read(maskingKey, read, 4 - read));
            }
        }
        this.payload = new byte[payloadLength];
    }

    /** Writes frame without flushing — for high-frequency sends where SessionBufferedOutputStream batching is preferred. */
    void writeWithoutFlush(OutputStream out) throws IOException {
        int header = fin ? 0x80 : 0;
        header |= opCode.value() & 0x0F;
        out.write(header);

        int len = payload != null ? payload.length : 0;
        boolean masked = maskingKey != null && maskingKey.length == 4;

        if (len <= 125) {
            out.write(masked ? 0x80 | (byte) len : (byte) len);
        } else if (len <= 0xFFFF) {
            out.write(masked ? 0xFE : 126);
            out.write(len >>> 8);
            out.write(len);
        } else {
            out.write(masked ? 0xFF : 127);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(len >>> 24);
            out.write(len >>> 16);
            out.write(len >>> 8);
            out.write(len);
        }

        if (masked) {
            out.write(maskingKey);
            for (int i = 0; i < len; i++) {
                out.write(payload[i] ^ maskingKey[i % 4]);
            }
        } else {
            out.write(payload);
        }
    }

    private void readPayload(InputStream in) throws IOException {
        int read = 0;
        int len = payload.length;
        while (read < len) {
            read += checkedRead(in.read(payload, read, len - read));
        }
        if (maskingKey != null) {
            for (int i = 0; i < len; i++) {
                payload[i] ^= maskingKey[i % 4];
            }
        }
        // Validate UTF-8 only for complete text messages. Fragmented text is
        // reassembled by the read loop and decoded there — a multi-byte
        // character may legitimately span fragment boundaries.
        if (opCode == OpCode.Text && fin) {
            payloadString = decodeUtf8(payload, 0, payload.length);
        }
    }

    private void parseCloseFrame() throws IOException {
        if (payload.length >= 2) {
            int codeVal = (payload[0] & 0xFF) << 8 | (payload[1] & 0xFF);
            this.closeCode = CloseCode.find(codeVal);
            // RFC 6455 §7.4.1: 1005/1006/1015 must not appear on wire
            if (this.closeCode == null || this.closeCode.isReserved()) {
                this.closeCode = CloseCode.ProtocolError;
            }
            this.closeReason = payload.length > 2
                    ? decodeUtf8(payload, 2, payload.length - 2) : "";
        }
    }

    private static int checkedRead(int read) throws IOException {
        if (read < 0) throw new EOFException();
        return read;
    }
}
