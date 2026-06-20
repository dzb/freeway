package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream;
final class ResetStreamFrame extends BaseFrame { public final Http2ErrorCode errorCode;
public ResetStreamFrame(Http2ErrorCode c,int sid){super(new FrameHeader(4,FrameType.RST_STREAM,FrameFlag.NONE,sid));errorCode=c;}
private ResetStreamFrame(FrameHeader h,Http2ErrorCode c){super(h);errorCode=c;}
public void writeTo(OutputStream os)throws IOException{header().writeTo(os);BinUtils.writeInt(os,errorCode.value,4);os.flush();}
public static ResetStreamFrame parse(byte[]b,FrameHeader h)throws IOException{if(b.length!=4)throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);return new ResetStreamFrame(h,Http2ErrorCode.fromValue(BinUtils.readInt(b,0)));}
}
