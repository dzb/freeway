package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream;
final class FrameHeader {
private final int len; private final FrameType type; private final FrameFlag.FlagSet flags; private final int streamId;
public FrameHeader(int l,FrameType t,FrameFlag.FlagSet f,int s){len=l;type=t;flags=f;streamId=s;}
public int length(){return len;} public FrameType type(){return type;} public FrameFlag.FlagSet flags(){return flags;} public int streamId(){return streamId;}
public static FrameHeader parse(byte[] b){return new FrameHeader(BinUtils.readInt(b,0,3),FrameType.fromValue(b[3]&0xFF),FrameFlag.parse(b[4],FrameType.fromValue(b[3]&0xFF)),BinUtils.readInt(b,5)&0x7FFFFFFF);}
public void writeTo(OutputStream os)throws IOException{BinUtils.writeInt(os,len,3);os.write(type.value&0xFF);os.write(flags.value());BinUtils.writeInt(os,streamId);}
public static void writeTo(OutputStream os,int l,FrameType t,FrameFlag.FlagSet f,int s)throws IOException{BinUtils.writeInt(os,l,3);os.write(t.value&0xFF);os.write(f.value());BinUtils.writeInt(os,s);}
public byte[] encode(){byte[]b=new byte[9];BinUtils.writeInt(b,0,len,3);b[3]=(byte)(type.value&0xFF);b[4]=flags.value();BinUtils.writeInt(b,5,streamId);return b;}
public static byte[] encode(int l,FrameType t,FrameFlag.FlagSet f,int s){byte[]b=new byte[9];BinUtils.writeInt(b,0,l,3);b[3]=(byte)(t.value&0xFF);b[4]=f.value();BinUtils.writeInt(b,5,s);return b;}
}
