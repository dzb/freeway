package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream;
final class WindowUpdateFrame extends BaseFrame { private int inc;
public WindowUpdateFrame(FrameHeader h){super(h);} public WindowUpdateFrame(int sid,int i){super(new FrameHeader(4,FrameType.WINDOW_UPDATE,FrameFlag.NONE,sid));inc=i;}
public int increment(){return inc;}
public static WindowUpdateFrame parse(byte[]b,FrameHeader h)throws IOException{if(b.length!=4)throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);var f=new WindowUpdateFrame(h);f.inc=BinUtils.readInt(b,0);if(f.inc==0)throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);return f;}
public void writeTo(OutputStream os)throws IOException{header().writeTo(os);BinUtils.writeInt(os,inc);}
}
