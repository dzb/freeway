package com.jujin.freeway.commons.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

public final class Digests {

    private Digests() {}

    /**
     * Per-thread {@link MessageDigest} instance. {@code MessageDigest} is not
     * thread-safe and creating one is relatively expensive, so reuse one per
     * thread instead of allocating on every digest call.
     */
    private static final ThreadLocal<MessageDigest> SHA_256 =
        ThreadLocal.withInitial(() -> {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        });

    /** Returns the SHA-256 digest of {@code content} as a hex string. */
    public static String sha256Hex(byte[] content) {
        return HexFormat.of().formatHex(digest(content));
    }

    /** Returns the SHA-256 digest of {@code content} as a Base64-URL string (no padding). */
    public static String sha256Base64(byte[] content) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(content));
    }

    private static byte[] digest(byte[] content) {
        MessageDigest md = SHA_256.get();
        md.reset();
        return md.digest(content);
    }
}
