package com.jujin.freeway.http.engine.http20.frame;
import com.jujin.freeway.http.engine.http20.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http20.util.Http2Exception;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

public final class DataFrame extends BaseFrame {
    public final byte[] body;

    public DataFrame(FrameHeader header, byte[] body) { super(header); this.body = body; }

    public static BaseFrame parse(byte[] body, FrameHeader header) throws IOException {
        int index = 0;
        int padding = 0;
        if (header.flags().contains(FrameFlag.PADDED)) {
            padding = (body[index] & 0xFF) + 1;
            if (padding > body.length) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
        return padding > 0
            ? new DataFrame(header, Arrays.copyOfRange(body, index, body.length - padding))
            : new DataFrame(header, body);
    }

    public void writeTo(OutputStream outputStream) throws IOException { outputStream.write(body); }
}
