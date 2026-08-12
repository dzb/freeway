package com.jujin.freeway.http.engine.http2.frame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;

public final class SettingsFrame extends BaseFrame {
    public final ArrayList<SettingParameter> params = new ArrayList<>();

    public SettingsFrame() {
        this(new FrameHeader(0, FrameType.SETTINGS, FrameFlag.FlagSet.of(FrameFlag.ACK), 0));
    }

    public SettingsFrame(FrameHeader header) {
        super(header);
    }

    public static SettingsFrame parse(byte[] body, FrameHeader header) throws IOException {
        if (header.streamId() != 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (header.flags().contains(FrameFlag.ACK) && header.length() != 0)
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        if (body.length % 6 != 0) throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        var frame = new SettingsFrame(header);
        for (int i = 0; i < body.length; i += 6) {
            var param = SettingParameter.parse(Arrays.copyOfRange(body, i, i + 6));
            if (param != null) frame.params.add(param);
        }
        return frame;
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        int size = params.size() * SettingParameter.PARAMETER_SIZE;
        new FrameHeader(size, FrameType.SETTINGS, header().flags(), header().streamId()).writeTo(outputStream);
        for (var param : params) param.writeTo(outputStream);
    }

    public byte[] encode() {
        var bos = new ByteArrayOutputStream();
        try { writeTo(bos); } catch (IOException ignored) {}
        return bos.toByteArray();
    }
}
