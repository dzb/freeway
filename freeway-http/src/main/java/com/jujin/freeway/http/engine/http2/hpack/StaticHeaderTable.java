package com.jujin.freeway.http.engine.http2.hpack;

import java.util.HashMap;
import java.util.Map;

import com.jujin.freeway.http.engine.http2.util.Http2HeaderField;

public final class StaticHeaderTable {
    private static final Http2HeaderField[] TABLE = new Http2HeaderField[62];
    private static final Map<String, Integer> NAME_INDEX = new HashMap<>(128);
    private static final Map<String, byte[]> STATUS_INDEX = new HashMap<>(8);

    static {
        add(1, ":authority", null);
        add(2, ":method", "GET");
        add(3, ":method", "POST");
        add(4, ":path", "/");
        add(5, ":path", "/index.html");
        add(6, ":scheme", "http");
        add(7, ":scheme", "https");
        add(8, ":status", "200");
        add(9, ":status", "204");
        add(10, ":status", "206");
        add(11, ":status", "304");
        add(12, ":status", "400");
        add(13, ":status", "404");
        add(14, ":status", "500");
        add(15, "accept-charset", null);
        add(16, "accept-encoding", "gzip, deflate");
        add(17, "accept-language", null);
        add(18, "accept-ranges", null);
        add(19, "accept", null);
        add(20, "access-control-allow-origin", null);
        add(21, "age", null);
        add(22, "allow", null);
        add(23, "authorization", null);
        add(24, "cache-control", null);
        add(25, "content-disposition", null);
        add(26, "content-encoding", null);
        add(27, "content-language", null);
        add(28, "content-length", null);
        add(29, "content-location", null);
        add(30, "content-range", null);
        add(31, "content-type", null);
        add(32, "cookie", null);
        add(33, "date", null);
        add(34, "etag", null);
        add(35, "expect", null);
        add(36, "expires", null);
        add(37, "from", null);
        add(38, "host", null);
        add(39, "if-match", null);
        add(40, "if-modified-since", null);
        add(41, "if-none-match", null);
        add(42, "if-range", null);
        add(43, "if-unmodified-since", null);
        add(44, "last-modified", null);
        add(45, "link", null);
        add(46, "location", null);
        add(47, "max-forwards", null);
        add(48, "proxy-authenticate", null);
        add(49, "proxy-authorization", null);
        add(50, "range", null);
        add(51, "referer", null);
        add(52, "refresh", null);
        add(53, "retry-after", null);
        add(54, "server", null);
        add(55, "set-cookie", null);
        add(56, "strict-transport-security", null);
        add(57, "transfer-encoding", null);
        add(58, "user-agent", null);
        add(59, "vary", null);
        add(60, "via", null);
        add(61, "www-authenticate", null);
        STATUS_INDEX.put("200", idx(8));
        STATUS_INDEX.put("204", idx(9));
        STATUS_INDEX.put("206", idx(10));
        STATUS_INDEX.put("304", idx(11));
        STATUS_INDEX.put("400", idx(12));
        STATUS_INDEX.put("404", idx(13));
        STATUS_INDEX.put("500", idx(14));
    }

    private static void add(int index, String name, String value) {
        TABLE[index] = new Http2HeaderField(name, value);
        NAME_INDEX.put(name, index);
    }

    public static Http2HeaderField get(int index) {
        return index >= 1 && index < TABLE.length ? TABLE[index] : null;
    }

    public static Integer nameIndex(String name) {
        return NAME_INDEX.get(name);
    }

    public static byte[] statusIndex(String status) {
        return STATUS_INDEX.get(status);
    }

    private static byte[] idx(int index) {
        return new byte[]{(byte) (0x80 | index)};
    }
}
