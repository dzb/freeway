package com.jujin.freeway.cloud;

/**
 * The two namespaces cloud modules spell. Kept separate from the bundle so a
 * feature module can declare its vocabulary without depending on
 * {@link CloudModule}.
 */
public final class CloudKeys {
    private CloudKeys() {}

    public static final String PREFIX = "freeway.cloud";
    public static final String APP_PREFIX = "freeway.app";
}
