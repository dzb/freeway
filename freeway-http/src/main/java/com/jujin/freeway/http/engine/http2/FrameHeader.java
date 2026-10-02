package com.jujin.freeway.http.engine.http2;

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

    /** SETTINGS_MAX_FRAME_SIZE initial value (RFC 9113 §6.5.2): the largest
     *  frame payload a peer accepts until it advertises a bigger one. Frames
     *  larger than the peer's limit are a connection error at its end. */
    public static final int DEFAULT_MAX_FRAME_SIZE = 16_384;

    /** SETTINGS_MAX_FRAME_SIZE upper bound (RFC 9113 §6.5.2, 2^24-1): a larger
     *  advertised value is a protocol error, so both the settings validation
     *  and the send path clamp to it. */
    public static final int MAX_FRAME_SIZE = 16_777_215;

    private final int len;
    private final FrameType type;
    private final FrameFlag.FlagSet flags;
    private final int streamId;

    /**
     * @param length payload length; must fit the wire's 3-byte field. A length
     *        that does not is not clamped — the three-byte write would silently
     *        truncate (or wrap), and the peer would read a frame whose header
     *        disagrees with the bytes after it. Every caller computes the length
     *        from the payload it is about to write, so a violation is a bug worth
     *        naming at the point it is written.
     */
    public FrameHeader(int length, FrameType type, FrameFlag.FlagSet flags, int streamId) {
        requireLength(length, type);
        this.len = length;
        this.type = type;
        this.flags = flags;
        this.streamId = streamId;
    }

    /** Parses a frame header from a 9-byte array. */
    public static FrameHeader parse(byte[] buffer) {
        int length = Bytes.readInt(buffer, 0, 3);
        FrameType type = FrameType.fromValue(buffer[3] & 0xFF);
        FrameFlag.FlagSet flags = FrameFlag.parse(buffer[4], type);
        int streamId = Bytes.readInt(buffer, 5) & 0x7FFFFFFF;
        return new FrameHeader(length, type, flags, streamId);
    }

    /**
     * Writes a frame header to the output stream.
     *
     * <p>The 3-byte length write is unchecked by construction — it shifts the
     * value out a byte at a time, so anything wider is truncated or wrapped with
     * no signal. Both static entry points therefore run the same range check as
     * the constructor; leaving them out would mean the invariant is enforced on
     * one path and not the other, which is the arrangement this class exists to
     * avoid.
     */
    public static void writeTo(OutputStream outputStream, int length, FrameType type, FrameFlag.FlagSet flags, int streamId) throws IOException {
        requireLength(length, type);
        Bytes.writeInt(outputStream, length, 3);
        outputStream.write(type.value & 0xFF);
        outputStream.write(flags.value());
        Bytes.writeInt(outputStream, streamId);
    }

    /** Encodes a frame header as a 9-byte array. Checked as {@link #writeTo} is. */
    public static byte[] encode(int length, FrameType type, FrameFlag.FlagSet flags, int streamId) {
        requireLength(length, type);
        byte[] buffer = new byte[9];
        Bytes.writeInt(buffer, 0, length, 3);
        buffer[3] = (byte) (type.value & 0xFF);
        buffer[4] = flags.value();
        Bytes.writeInt(buffer, 5, streamId);
        return buffer;
    }

    private static void requireLength(int length, FrameType type) {
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw new IllegalArgumentException(
                "Frame length " + length + " does not fit the 3-byte field"
                    + " (0.." + MAX_FRAME_SIZE + ") for " + type);
        }
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
        Bytes.writeInt(outputStream, len, 3);
        outputStream.write(type.value & 0xFF);
        outputStream.write(flags.value());
        Bytes.writeInt(outputStream, streamId);
    }

}
