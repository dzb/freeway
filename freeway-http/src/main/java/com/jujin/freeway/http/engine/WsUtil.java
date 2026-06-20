package com.jujin.freeway.http.engine;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

final class WsUtil {

    private WsUtil() {}

    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    static String makeAcceptKey(String key) throws NoSuchAlgorithmException {
        var md = MessageDigest.getInstance("SHA-1");
        String text = key + MAGIC;
        md.update(text.getBytes(), 0, text.length());
        return Base64.getEncoder().encodeToString(md.digest());
    }
}
