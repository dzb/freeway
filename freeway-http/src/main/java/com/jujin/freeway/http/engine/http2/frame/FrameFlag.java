package com.jujin.freeway.http.engine.http2.frame;

import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;

public enum FrameFlag {
    END_STREAM((byte) 0x1), ACK((byte) 0x1), END_HEADERS((byte) 0x4), PADDED((byte) 0x8), PRIORITY((byte) 0x20);
    public static final FlagSet NONE = new FlagSet(0, false);
    private static final byte MASK = 0x1 | 0x4 | 0x8 | 0x20;
    private final byte value;

    FrameFlag(byte v) {
        this.value = v;
    }

    public static FlagSet parse(byte v, FrameType t) {
        if (v == 0) return NONE;
        return new FlagSet(v & MASK, t == FrameType.SETTINGS || t == FrameType.PING);
    }

    public static void validate(byte v, FrameType t) throws Http2Exception {
        int raw = v & 0xFF;
        if (t == FrameType.CONTINUATION && (raw & ~0x04) != 0)
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                "CONTINUATION has illegal flags");
    }

    public static final class FlagSet {
        private final int value;
        private final boolean ack;

        FlagSet(int v, boolean a) {
            value = v;
            ack = a;
        }

        public static FlagSet of(FrameFlag... f) {
            int v = 0;
            boolean a = false;
            for (var x : f) {
                v |= x.value;
                if (x == ACK) a = true;
            }
            return new FlagSet(v, a);
        }

        public byte value() {
            return (byte) value;
        }

        public boolean contains(FrameFlag f) {
            return (value & f.value) == f.value;
        }


        public String toString() {
            var sb = new StringBuilder("[");
            int t = value;
            if ((t & 1) == 1) {
                sb.append(ack ? "ACK" : "END_STREAM");
                t ^= 1;
            }
            for (var f : FrameFlag.values()) {
                if ((t & f.value) == f.value) {
                    if (!sb.isEmpty()) sb.append(",");
                    sb.append(f);
                }
            }
            sb.append("]");
            return sb.toString();
        }
    }
}
