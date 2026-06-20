package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream;
final class GoawayFrame extends BaseFrame { public final Http2ErrorCode errorCode; public final int lastSeenStream;
public GoawayFrame(Http2ErrorCode c,int l){super(new FrameHeader(8,FrameType.GOAWAY,FrameFlag.NONE,0));errorCode=c;lastSeenStream=l;}
private GoawayFrame(FrameHeader h,Http2ErrorCode c,int l){super(h);errorCode=c;lastSeenStream=l;}
public void writeTo(OutputStream os)throws IOException{header().writeTo(os);BinUtils.writeInt(os,lastSeenStream);BinUtils.writeInt(os,errorCode.value);}
public static GoawayFrame parse(byte[]b,FrameHeader h)throws IOException{if(h.streamId()!=0)throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);if(b.length<8)throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);return new GoawayFrame(h,Http2ErrorCode.fromValue(BinUtils.readInt(b,4,4)),BinUtils.readInt(b,0));}
}
