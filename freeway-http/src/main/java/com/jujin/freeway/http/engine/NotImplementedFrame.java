package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream;
final class NotImplementedFrame extends BaseFrame { public NotImplementedFrame(FrameHeader h,byte[]b){super(h);} public void writeTo(OutputStream os)throws IOException{header().writeTo(os);} public static NotImplementedFrame parse(byte[]b,FrameHeader h){return new NotImplementedFrame(h,b);} }
