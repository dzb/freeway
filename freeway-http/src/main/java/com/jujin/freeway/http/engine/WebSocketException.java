package com.jujin.freeway.http.engine;

import java.io.IOException;

final class WebSocketException extends IOException {

    private final CloseCode code;
    private final String reason;

    WebSocketException(CloseCode code, String reason) {
        super(code + ": " + reason);
        this.code = code;
        this.reason = reason;
    }

    WebSocketException(CloseCode code, String reason, Throwable cause) {
        super(code + ": " + reason, cause);
        this.code = code;
        this.reason = reason;
    }

    CloseCode closeCode() { return code; }
    String closeReason() { return reason; }
}
