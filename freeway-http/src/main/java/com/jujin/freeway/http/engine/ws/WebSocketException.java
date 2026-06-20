package com.jujin.freeway.http.engine.ws;

import java.io.IOException;

public final class WebSocketException extends IOException {

    private final CloseCode code;
    private final String reason;

    public WebSocketException(CloseCode code, String reason) {
        super(code + ": " + reason);
        this.code = code;
        this.reason = reason;
    }

    public WebSocketException(CloseCode code, String reason, Throwable cause) {
        super(code + ": " + reason, cause);
        this.code = code;
        this.reason = reason;
    }

    CloseCode closeCode() { return code; }
    String closeReason() { return reason; }
}
