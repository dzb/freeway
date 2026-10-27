package com.jujin.freeway.cloud.registry;

import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The registry fleet this backend talks to, as one value with named knobs —
 * the same shape-for-a-seven-position-constructor reasoning as every adapter
 * wiring record.
 *
 * <p>The node list is the backend: writes fan out to every node, reads union
 * their answers. That is this backend's availability model, chosen instead of
 * a replication protocol — a registry holds rebuildable state (every instance
 * re-registers), so N independent nodes with a client that talks to all of
 * them survive any one node dying without the nodes knowing about each other.</p>
 */
public record RegistryNodes(List<URI> addresses, String token, Duration timeout) {

    private static final SymbolSpec<String> NODES =
        SymbolSpec.of(ConfigKeys.NODES, String.class, "");
    private static final SymbolSpec<String> TOKEN =
        SymbolSpec.of(ConfigKeys.TOKEN, String.class, "");
    private static final SymbolSpec<Long> TIMEOUT_MS =
        SymbolSpec.of(ConfigKeys.TIMEOUT_MS, Long.class, ConfigKeys.TIMEOUT_MS_DEFAULT);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(ConfigKeys.TIMEOUT_MS_DEFAULT);

    public RegistryNodes {
        addresses = List.copyOf(addresses);
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException(
                ConfigKeys.NODES + " is empty — the backend has no registry to talk to");
        }
        token = token == null ? "" : token;
        timeout =
            (timeout == null || timeout.isZero() || timeout.isNegative())
                ? DEFAULT_TIMEOUT
                : timeout;
    }

    /** Resolves and parses the configured fleet; blank or unparseable fails here. */
    public static RegistryNodes from(SymbolSource symbols) {
        String raw = symbols.resolve(NODES).trim();
        if (raw.isEmpty()) {
            throw new IllegalStateException(
                ConfigKeys.NODES
                    + " is blank — the backend is bound but has no registry node to talk to;"
                    + " list at least one node (host:port or http(s)://host:port)");
        }
        List<URI> addresses = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            URI uri = URI.create(trimmed.contains("://") ? trimmed : "http://" + trimmed);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new IllegalStateException(
                    ConfigKeys.NODES + " entry must be http(s): " + trimmed);
            }
            addresses.add(uri);
        }
        if (addresses.isEmpty()) {
            throw new IllegalStateException(ConfigKeys.NODES + " is blank — no node was parsed");
        }
        return new RegistryNodes(addresses, symbols.resolve(TOKEN),
            Duration.ofMillis(symbols.resolve(TIMEOUT_MS)));
    }
}
