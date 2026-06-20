package com.jujin.freeway.http.engine;
enum FrameType { DATA(0x0),HEADERS(0x1),PRIORITY(0x2),RST_STREAM(0x3),SETTINGS(0x4),PUSH_PROMISE(0x5),PING(0x6),GOAWAY(0x7),WINDOW_UPDATE(0x8),CONTINUATION(0x9),NOT_IMPLEMENTED(0xA);
public final byte value; private static final FrameType[] V=values();
FrameType(int v){value=(byte)v;} public static FrameType fromValue(int v){if(v<0||v>0x9)return NOT_IMPLEMENTED;return V[v];} }
