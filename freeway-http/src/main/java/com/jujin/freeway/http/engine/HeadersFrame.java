package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream; import java.util.Arrays;
final class HeadersFrame extends BaseFrame {
private int pad; private boolean excl; private long depStream; private int weight; private byte[] block;
public HeadersFrame(){this(new FrameHeader(0,FrameType.HEADERS,FrameFlag.NONE,0));} public HeadersFrame(FrameHeader h){super(h);}
public byte[] headerBlock(){return block;}
public static HeadersFrame parse(byte[]b,FrameHeader h)throws IOException{if(b==null||h.length()!=b.length)throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);var f=new HeadersFrame(h);int p=0;if(h.flags().contains(FrameFlag.PADDED)){f.pad=BinUtils.readInt(b,p,1);if(f.pad>=h.length())throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);p++;}if(h.flags().contains(FrameFlag.PRIORITY)){int s=BinUtils.readInt(b,p,4);p+=4;f.excl=(s&0x80000000L)!=0;f.depStream=s&0x7FFFFFFFL;if(f.depStream==h.streamId())throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);f.weight=(b[p++]&0xFF)+1;}f.block=Arrays.copyOfRange(b,p,h.length()-f.pad);return f;}
public void writeTo(OutputStream os)throws IOException{byte[]buf=block;FrameHeader.writeTo(os,buf.length,FrameType.HEADERS,FrameFlag.FlagSet.of(FrameFlag.END_HEADERS),header().streamId());os.write(buf);os.flush();}
}
