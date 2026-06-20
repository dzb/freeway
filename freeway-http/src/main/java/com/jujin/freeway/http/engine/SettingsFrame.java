package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream; import java.util.ArrayList; import java.util.Arrays;
final class SettingsFrame extends BaseFrame { public final ArrayList<SettingParameter> params=new ArrayList<>();
public SettingsFrame(){this(new FrameHeader(0,FrameType.SETTINGS,FrameFlag.FlagSet.of(FrameFlag.ACK),0));} public SettingsFrame(FrameHeader h){super(h);}
public static SettingsFrame parse(byte[]b,FrameHeader h)throws IOException{if(h.streamId()!=0)throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);if(h.flags().contains(FrameFlag.ACK)&&h.length()!=0)throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);if(b.length%6!=0)throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);var f=new SettingsFrame(h);for(int i=0;i<b.length;i+=6){var p=SettingParameter.parse(Arrays.copyOfRange(b,i,i+6));if(p!=null)f.params.add(p);}return f;}
public void writeTo(OutputStream os)throws IOException{int s=params.size()*SettingParameter.PARAMETER_SIZE;new FrameHeader(s,FrameType.SETTINGS,header().flags(),header().streamId()).writeTo(os);for(var p:params)p.writeTo(os);}
}
