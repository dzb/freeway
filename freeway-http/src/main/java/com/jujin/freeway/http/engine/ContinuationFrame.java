package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream;
final class ContinuationFrame extends BaseFrame { private final byte[] body;
public ContinuationFrame(FrameHeader h,byte[]b){super(h);body=b;} public byte[] headerBlock(){return body;}
public void writeTo(OutputStream os)throws IOException{header().writeTo(os);}
public static ContinuationFrame parse(byte[]b,FrameHeader h){return new ContinuationFrame(h,b);}
}
