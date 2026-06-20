package com.jujin.freeway.http.engine;
import java.io.IOException; import java.io.OutputStream; import java.util.List;
final class BinUtils { private BinUtils(){}
public static int readInt(byte[] b,int o,int l){int r=0;for(int i=o+l-1;i>=o;i--)r|=(b[i]&0xff)<<(8*(o+l-1-i));return r;}
public static int readInt(byte[] b,int o){return readInt(b,o,4);}
public static long readLong(byte[] b,int o,int l){long r=0;for(int i=o+l-1;i>=o;i--)r|=(long)(b[i]&0xff)<<(8*(o+l-1-i));return r;}
public static void writeInt(byte[] b,int p,int v,int l){for(int i=0;i<l;i++)b[i+p]=(byte)((v>>(8*(l-1-i)))&0xff);}
public static void writeInt(byte[] b,int p,int v){writeInt(b,p,v,4);}
public static void writeInt(OutputStream os,int v,int l)throws IOException{for(int i=l-1;i>=0;i--)os.write((byte)((v>>(8*i))&0xff));}
public static void writeInt(OutputStream os,int v)throws IOException{writeInt(os,v,4);}
public static byte[] combine(byte[]...b){if(b.length==0)return new byte[0];if(b.length==1)return b[0];int t=0;for(var x:b)t+=x.length;byte[]r=new byte[t];int o=0;for(var x:b){System.arraycopy(x,0,r,o,x.length);o+=x.length;}return r;}
public static byte[] combine(List<byte[]> b){if(b.isEmpty())return new byte[0];if(b.size()==1)return b.getFirst();int t=0;for(var x:b)t+=x.length;byte[]r=new byte[t];int o=0;for(var x:b){System.arraycopy(x,0,r,o,x.length);o+=x.length;}return r;}
public static byte[] combine(List<byte[]> a,List<byte[]> b){int t=0;for(var x:a)t+=x.length;for(var x:b)t+=x.length;if(t==0)return new byte[0];byte[]r=new byte[t];int o=0;for(var x:a){System.arraycopy(x,0,r,o,x.length);o+=x.length;}for(var x:b){System.arraycopy(x,0,r,o,x.length);o+=x.length;}return r;}
}
