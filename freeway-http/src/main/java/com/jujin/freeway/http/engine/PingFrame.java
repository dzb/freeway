package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream;
final class PingFrame extends BaseFrame { public final byte[] body;
public PingFrame(FrameHeader h,byte[]b){super(h);body=b;} public PingFrame(){super(new FrameHeader(8,FrameType.PING,FrameFlag.NONE,0));body=new byte[8];} public PingFrame(PingFrame a){super(new FrameHeader(8,FrameType.PING,FrameFlag.FlagSet.of(FrameFlag.ACK),0));body=a.body;}
public void writeTo(OutputStream os)throws IOException{header().writeTo(os);}
public static PingFrame parse(byte[]b,FrameHeader h)throws IOException{if(h.streamId()!=0)throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);if(b.length!=8)throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);return new PingFrame(h,b);}
}
