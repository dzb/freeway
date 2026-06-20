package com.jujin.freeway.http.engine;
import java.io.ByteArrayOutputStream; import java.io.IOException; import java.io.OutputStream;
abstract class BaseFrame { private FrameHeader header; public BaseFrame(FrameHeader h){header=h;} public FrameHeader header(){return header;} public void setHeader(FrameHeader h){header=h;} public abstract void writeTo(OutputStream os)throws IOException; public byte[] encode(){var b=new ByteArrayOutputStream();try{writeTo(b);}catch(IOException ignored){}return b.toByteArray();} }
