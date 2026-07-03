package com.jujin.freeway.http.engine.ws;

public enum CloseCode {
    NormalClosure(1000),
    GoingAway(1001),
    ProtocolError(1002),
    UnsupportedData(1003),
    NoStatusRcvd(1005),
    AbnormalClosure(1006),
    InvalidFramePayloadData(1007),
    PolicyViolation(1008),
    MessageTooBig(1009),
    MandatoryExt(1010),
    InternalServerError(1011),
    TLSHandshake(1015);

    private final int code;

    CloseCode(int code) { this.code = code; }

    int value() { return code; }

    /** RFC 6455 §7.4.1: 1005/1006/1015 must never appear on the wire. */
    boolean isReserved() {
        return this == NoStatusRcvd || this == AbnormalClosure || this == TLSHandshake;
    }

    static CloseCode find(int value) {
        for (var c : values()) {
            if (c.code == value) return c;
        }
        return null;
    }
}
