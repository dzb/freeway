package com.jujin.freeway.http.engine.http20.frame;
import com.jujin.freeway.http.engine.http20.util.BinUtils;

import java.io.IOException;
import java.io.OutputStream;

/**
 * HTTP/2 frame header (9 bytes)
 * <pre>
 * +---------------+---------------+---------------+
 * |   Length (3)  |   Type (1)    |   Flags (1)   |
 * +---------------+---------------+---------------+
 * |R|                 Stream Identifier (4)       |
 * +------------------------------------------------+
 * </pre>
 */
public final class FrameHeader {
    private final int len;
    private final FrameType type;
    private final FrameFlag.FlagSet flags;
    private final int streamId;

    public FrameHeader(int length, FrameType type, FrameFlag.FlagSet flags, int streamId) {
        this.len = length;
        this.type = type;
        this.flags = flags;
        this.streamId = streamId;
    }

    /** Parses a frame header from a 9-byte array. */
    public static FrameHeader parse(byte[] buffer) {
        int length = BinUtils.readInt(buffer, 0, 3);
        FrameType type = FrameType.fromValue(buffer[3] & 0xFF);
        FrameFlag.FlagSet flags = FrameFlag.parse(buffer[4], type);
        int streamId = BinUtils.readInt(buffer, 5) & 0x7FFFFFFF;
        return new FrameHeader(length, type, flags, streamId);
    }

    /** Writes a frame header to the output stream. */
    public static void writeTo(OutputStream outputStream, int length, FrameType type, FrameFlag.FlagSet flags, int streamId) throws IOException {
        BinUtils.writeInt(outputStream, length, 3);
        outputStream.write(type.value & 0xFF);
        outputStream.write(flags.value());
        BinUtils.writeInt(outputStream, streamId);
    }

    /** Encodes a frame header as a 9-byte array. */
    public static byte[] encode(int length, FrameType type, FrameFlag.FlagSet flags, int streamId) {
        byte[] buffer = new byte[9];
        BinUtils.writeInt(buffer, 0, length, 3);
        buffer[3] = (byte) (type.value & 0xFF);
        buffer[4] = flags.value();
        BinUtils.writeInt(buffer, 5, streamId);
        return buffer;
    }

    /** Returns the payload length. */
    public int length() {
        return len;
    }

    /** Returns the frame type. */
    public FrameType type() {
        return type;
    }

    /** Returns the flags. */
    public FrameFlag.FlagSet flags() {
        return flags;
    }

    /** Returns the stream ID (high bit is reserved; effective width is 31 bits). */
    public int streamId() {
        return streamId;
    }

    /** Writes this header to the output stream. */
    public void writeTo(OutputStream outputStream) throws IOException {
        BinUtils.writeInt(outputStream, len, 3);
        outputStream.write(type.value & 0xFF);
        outputStream.write(flags.value());
        BinUtils.writeInt(outputStream, streamId);
    }

    /** Encodes this header as a 9-byte array. */
    public byte[] encode() {
        byte[] buffer = new byte[9];
        BinUtils.writeInt(buffer, 0, len, 3);
        buffer[3] = (byte) (type.value & 0xFF);
        buffer[4] = flags.value();
        BinUtils.writeInt(buffer, 5, streamId);
        return buffer;
    }
}
