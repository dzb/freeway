package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream;
final class PriorityFrame extends BaseFrame { public int streamDep; public int weight; public boolean excl;
public PriorityFrame(FrameHeader h){super(h);} public void writeTo(OutputStream os)throws IOException{header().writeTo(os);}
public static PriorityFrame parse(byte[]b,FrameHeader h)throws IOException{if(b.length!=5)throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);var f=new PriorityFrame(h);int t=BinUtils.readInt(b,0,4);f.excl=(t&0x80000000)!=0;f.streamDep=t&0x7FFFFFFF;if(f.streamDep==h.streamId())throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);f.weight=(b[4]&0xFF)+1;return f;}
}
