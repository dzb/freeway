package com.jujin.freeway.http.engine.http2.hpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jujin.freeway.http.internal.HttpUtils;
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
    private boolean seenRegularHeader;

    public void add(Http2HeaderField f) throws IOException {
        if (f.isPseudoHeader() && !PSEUDO.contains(f.name)) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (PROHIBITED.contains(f.name)) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if ("te".equals(f.name) && !"trailers".equals(f.value)) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (!f.isPseudoHeader()) seenRegularHeader = true;
        if (f.isPseudoHeader() && seenRegularHeader) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (f.isPseudoHeader()) {
            if (pseudo.put(f.name, f) != null)
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
        fields.add(f);
    }

    public void validate() throws IOException {
        var method = pseudo.get(":method");
        if (method == null || method.value == null || method.value.isBlank())
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        boolean connect = "CONNECT".equals(method.value);
        boolean extended = pseudo.containsKey(":protocol");
        if (!connect && extended)
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);

        if (connect && !extended) {
            // Plain CONNECT: :authority is required; :scheme/:path must be
            // absent (RFC 7540 §8.3).
            if (pseudo.containsKey(":scheme") || pseudo.containsKey(":path"))
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        } else {
            // Non-CONNECT (and extended CONNECT, RFC 8441): :path and
            // :scheme are required and :path must be a valid origin-form
            // path (or "*" for OPTIONS) — mirroring HTTP/1.1 request-target
            // rules that the HTTP/1.1 parser already enforces.
            var path = pseudo.get(":path");
            if (path == null || path.value == null || path.value.isBlank()
                    || invalidPath(method.value, path.value))
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            var scheme = pseudo.get(":scheme");
            if (scheme == null || scheme.value == null || scheme.value.isBlank())
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }

        // RFC 7540 §8.1.2.3: :authority is required for CONNECT and optional
        // for other requests; when present it must satisfy the same character
        // rules as an HTTP/1.1 Host header (no @, whitespace, /, \, CTL).
        var authority = pseudo.get(":authority");
        if (authority != null && invalidAuthority(authority.value))
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        if (connect && (authority == null || authority.value == null
                || authority.value.isBlank()))
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
    }

    /** Rejects :path values that do not match HTTP/1.1 origin-form rules:
     *  must start with "/" ("*" only for OPTIONS), no authority-form
     *  ("//host/...") or absolute-form ("scheme://..."), no whitespace or
     *  control characters. */
    private static boolean invalidPath(String method, String value) {
        if ("OPTIONS".equals(method) && "*".equals(value)) return false;
        if (!value.startsWith("/")) return true;
        if (value.startsWith("//") || value.contains("://")) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c == 0x7F) return true;
        }
        return false;
    }

    /** Mirrors the HTTP/1.1 Host rules (HttpSession.invalidHostHeader):
     *  rejects @, whitespace, /, \ and control characters. */
    private static boolean invalidAuthority(String value) {
        return HttpUtils.invalidHostValue(value);
    }

    public List<Http2HeaderField> fields() {
        return fields;
    }

    public int size() {
        return fields.size();
    }
}
