package com.jujin.freeway.http.engine.ws;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class WebSocketUtil {

    private WebSocketUtil() {}

    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static String makeAcceptKey(String key) throws NoSuchAlgorithmException {
        var md = MessageDigest.getInstance("SHA-1");
        // Explicit UTF-8: getBytes() follows the platform default, and its
        // byte count can differ from length() for non-ASCII input — update
        // must cover exactly the encoded bytes, not the char count.
        byte[] text = (key + MAGIC).getBytes(StandardCharsets.UTF_8);
        md.update(text, 0, text.length);
        return Base64.getEncoder().encodeToString(md.digest());
    }
}
