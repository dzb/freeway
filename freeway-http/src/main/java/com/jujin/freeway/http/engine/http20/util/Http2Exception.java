package com.jujin.freeway.http.engine.http20.util;

import java.io.IOException;

public final class Http2Exception extends IOException {
    private final Http2ErrorCode code;

    public Http2Exception(Http2ErrorCode c) {
        this(c, "");
    }

    public Http2Exception(Http2ErrorCode c, String m) {
        super(m);
        this.code = c;
    }

    public Http2ErrorCode errorCode() {
        return code;
    }
}
