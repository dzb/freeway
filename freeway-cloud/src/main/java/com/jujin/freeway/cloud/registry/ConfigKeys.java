package com.jujin.freeway.cloud.registry;

/**
 * The native fleet backend's own vocabulary, fenced to its namespace the same
 * way every feature module's is: a key outside {@link #PREFIX} fails the bind
 * instead of sitting outside the unknown-key check. The deployment's own
 * registry keys ({@code freeway.cloud.registry.service-id} and friends) belong
 * to {@code CloudModule.ConfigKeys} — this namespace is only where the nodes
 * live.
 */
public final class ConfigKeys {

    private ConfigKeys() {}

    public static final String PREFIX = "freeway.cloud.registry-node.";

    /**
     * Comma-separated registry node addresses, each {@code host:port} (spoken as
     * {@code http://}) or a full {@code http(s)://host:port} URL. Every listed
     * node receives writes (register/renew/unregister fan out to all) and serves
     * reads (discovery unions the answers) — any one node staying alive keeps the
     * fleet working, which is this backend's whole high-availability story.
     */
    public static final String NODES = "freeway.cloud.registry-node.nodes";

    /**
     * Shared secret presented as the {@code X-Registry-Token} header; the nodes
     * set the same value as {@code freeway.cloud.registry.node-token}. Blank =
     * no header, matching an open (development) node.
     */
    public static final String TOKEN = "freeway.cloud.registry-node.token";

    /** Connect and request timeout for one node exchange, in milliseconds. */
    public static final String TIMEOUT_MS = "freeway.cloud.registry-node.timeout-ms";
    public static final long TIMEOUT_MS_DEFAULT = 3000;
}
