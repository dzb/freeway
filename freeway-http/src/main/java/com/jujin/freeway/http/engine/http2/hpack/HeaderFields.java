package com.jujin.freeway.http.engine.http2.hpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jujin.freeway.http.engine.http2.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.Http2Exception;
import com.jujin.freeway.http.engine.http2.Http2HeaderField;

public final class HeaderFields {
    private static final Set<String> PROHIBITED = Set.of(
        "connection", "transfer-encoding", "keep-alive",
        "proxy-connection", "upgrade");
    private static final Set<String> PSEUDO = Set.of(":authority", ":method", ":path", ":scheme", ":protocol");
    private final List<Http2HeaderField> fields = new ArrayList<>();
    private final Map<String, Http2HeaderField> pseudo = new HashMap<>(8);
    private boolean hasNon;

    public void add(Http2HeaderField f) throws IOException {
        if (f.isPseudoHeader() && !PSEUDO.contains(f.name)) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (PROHIBITED.contains(f.name)) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if ("te".equals(f.name) && !"trailers".equals(f.value)) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (!f.isPseudoHeader()) hasNon = true;
        if (f.isPseudoHeader() && hasNon) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (f.isPseudoHeader()) {
            if (pseudo.put(f.name, f) != null)
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
        fields.add(f);
    }

    public void validate() throws IOException {
        var method = pseudo.get(":method");
        var authority = pseudo.get(":authority");
        if (method == null || method.value == null || method.value.isBlank()
                || authority == null || authority.value == null || authority.value.isBlank())
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        boolean connect = "CONNECT".equals(method.value);
        boolean extended = pseudo.containsKey(":protocol");
        if (connect && !extended) {
            if (pseudo.containsKey(":scheme") || pseudo.containsKey(":path"))
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        } else {
            for (String n : Set.of(":path", ":scheme")) {
                var h = pseudo.get(n);
                if (h == null || h.value == null || h.value.isBlank())
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            }
        }
        if (!connect && extended)
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
    }

    public List<Http2HeaderField> fields() {
        return fields;
    }

    public int size() {
        return fields.size();
    }
}
