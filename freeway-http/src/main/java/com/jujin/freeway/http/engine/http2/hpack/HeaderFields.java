package com.jujin.freeway.http.engine.http2.hpack;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;
import com.jujin.freeway.http.engine.http2.util.Http2HeaderField;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HeaderFields {
    private static final Set<String> PROHIBITED = Set.of(
        "connection", "transfer-encoding", "keep-alive",
        "proxy-connection", "upgrade");
    private static final Set<String> REQUIRED = Set.of(":path", ":method", ":scheme", ":authority");
    private static final Set<String> PSEUDO = Set.of(":authority", ":method", ":path", ":scheme");
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
            if (pseudo.put(f.name, f) != null && REQUIRED.contains(f.name))
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
        fields.add(f);
    }

    public void validate() throws IOException {
        for (String n : REQUIRED) {
            var h = pseudo.get(n);
            if (h == null || h.value == null || h.value.isBlank())
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    public List<Http2HeaderField> fields() {
        return fields;
    }

    public int size() {
        return fields.size();
    }
}
