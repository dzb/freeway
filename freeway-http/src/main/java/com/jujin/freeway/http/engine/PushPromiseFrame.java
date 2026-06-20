package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream;
final class PushPromiseFrame extends BaseFrame { public PushPromiseFrame(FrameHeader h){super(h);} public void writeTo(OutputStream os)throws IOException{header().writeTo(os);} public static PushPromiseFrame parse(byte[]b,FrameHeader h){return new PushPromiseFrame(h);} }
