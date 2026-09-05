package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;


final class SettingParameter {
    public static final int PARAMETER_SIZE = 6;
    public static final SettingParameter DEFAULT_INITIAL_WINDOW_SIZE = new SettingParameter(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, 65535);
    public SettingIdentifier identifier;
    public long value;

    public SettingParameter() {
    }

    public SettingParameter(SettingIdentifier i, long v) {
        identifier = i;
        value = v;
    }

    public static SettingParameter parse(byte[] p) throws IOException {
        if (p.length != 6) throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        var r = new SettingParameter();
        r.identifier = SettingIdentifier.fromValue(BinUtils.readInt(p, 0, 2));
        if (r.identifier == SettingIdentifier.SETTINGS_NONE) return null;
        r.value = BinUtils.readLong(p, 2, 4);
        if (!r.identifier.validateValue(r.value)) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        return r;
    }

    public void writeTo(OutputStream os) throws IOException {
        BinUtils.writeInt(os, identifier.value, 2);
        BinUtils.writeInt(os, (int) value, 4);
    }
}
