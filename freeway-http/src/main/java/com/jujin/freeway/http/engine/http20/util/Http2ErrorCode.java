package com.jujin.freeway.http.engine.http20.util;

public enum Http2ErrorCode {
    NO_ERROR(0x0),
    PROTOCOL_ERROR(0x1),
    INTERNAL_ERROR(0x2),
    FLOW_CONTROL_ERROR(0x3),
    SETTINGS_TIMEOUT(0x4),
    STREAM_CLOSED(0x5),
    FRAME_SIZE_ERROR(0x6),
    REFUSED_STREAM(0x7),
    CANCEL(0x8),
    COMPRESSION_ERROR(0x9),
    CONNECT_ERROR(0xa),
    ENHANCE_YOUR_CALM(0xb),
    INADEQUATE_SECURITY(0xc),
    HTTP_1_1_REQUIRED(0xd);

    public final int value;

    Http2ErrorCode(int v) {
        value = v;
    }

    public static Http2ErrorCode fromValue(int v) {
        for (var e : values()) if (e.value == v) return e;
        return INTERNAL_ERROR; // RFC 7540 §7: unknown error codes treated as INTERNAL_ERROR
    }
}
