package com.jujin.freeway.cloud.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time comparison for the shared secrets the cloud surface checks
 * (the registry token and the mesh handshake token).
 *
 * <p>One helper, two callers: the registry accepted a token with
 * {@code String.equals} while the mesh already used a length-independent
 * comparison — the same rule with two security postures. Both now compare
 * through here.</p>
 */
public final class ConstantTime {

    private ConstantTime() {}

    /** Length-independent comparison, so a secret cannot be probed by timing. */
    public static boolean isEqual(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
