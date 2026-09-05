package com.jujin.freeway.http.engine.ws;

enum OpCode {
    Continuation(0), Text(1), Binary(2), Close(8), Ping(9), Pong(10);

    private final byte code;

    OpCode(int code) { this.code = (byte) code; }

    byte value() { return code; }

    boolean isControlFrame() { return this == Close || this == Ping || this == Pong; }

    static OpCode find(byte value) {
        for (var op : values()) {
            if (op.code == value) return op;
        }
        return null;
    }
}
