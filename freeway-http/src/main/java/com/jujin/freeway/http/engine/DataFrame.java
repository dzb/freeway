package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream; import java.util.Arrays;
final class DataFrame extends BaseFrame { public final byte[] body;
public DataFrame(FrameHeader h,byte[]b){super(h);body=b;} public void writeTo(OutputStream os)throws IOException{os.write(body);}
public static DataFrame parse(byte[]b,FrameHeader h)throws IOException{int o=0,p=0;if(h.flags().contains(FrameFlag.PADDED)){p=(b[o++]&0xFF)+1;if(p>b.length)throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);}if(p>0)return new DataFrame(h,Arrays.copyOfRange(b,o,b.length-p));return new DataFrame(h,b);}
}
