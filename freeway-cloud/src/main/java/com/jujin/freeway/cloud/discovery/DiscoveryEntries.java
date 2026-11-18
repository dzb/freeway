package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The config grammar shared by the config-driven discovery backends:
 * comma-separated {@code serviceId=scheme://host:port[/basePath]} entries under
 * one key.
 *
 * <p>One parser, two backends: what a static map and a DNS map accept cannot
 * drift apart, and a difference between them is a policy passed in by the
 * caller (whether an IP literal is a literal endpoint or a configuration
 * mistake), never a second copy of the validation. Both fail startup with the
 * key name and the 1-based entry index.</p>
 */
final class DiscoveryEntries {

    /** IPv4 dotted quad; an IPv6 host is recognised by its colons. */
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /**
     * One parsed entry: the logical service, its structured endpoint, the raw
     * address as written (for error messages) and the 0-based list position.
     */
    record Entry(String serviceId, ServiceEndpoint endpoint, String address, int index) {}

    private DiscoveryEntries() {}

    /** Parses the raw comma list under {@code key}; empty/blank input fails. */
    static List<Entry> parse(String key, List<String> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                key + " must contain at least one serviceId=scheme://host:port[/basePath] entry");
        }

        List<Entry> parsed = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i).trim();
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw invalidEntry(key, i, "expected serviceId=scheme://host:port[/basePath]");
            }
            String serviceId = entry.substring(0, separator).trim();
            String address = entry.substring(separator + 1).trim();
            if (serviceId.isBlank() || serviceId.contains(",")) {
                throw invalidEntry(key, i, "serviceId must not be blank or contain a comma");
            }

            URI uri;
            try {
                uri = URI.create(address);
            } catch (IllegalArgumentException e) {
                throw invalidEntry(key, i, "invalid endpoint URI '" + address + "': " + e.getMessage());
            }
            if (uri.getScheme() == null || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalidEntry(key, i,
                    "endpoint must be an absolute URI with scheme, host, explicit port "
                        + "and no userinfo, query or fragment: '" + address + "'");
            }
            ServiceEndpoint endpoint = ServiceEndpoint.of(
                uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());
            parsed.add(new Entry(serviceId, endpoint, address, i));
        }
        return parsed;
    }

    /** Resolves the raw list through the symbol chain and parses it. */
    static List<Entry> parse(SymbolSource symbols, String key) {
        return parse(key, symbols.resolve(SymbolSpec.list(key, List.of())));
    }

    /**
     * Whether the host is an address literal rather than a name. Only obvious
     * forms are recognised — this is a configuration check, never a lookup, so
     * a name that happens to resolve locally is still a name.
     */
    static boolean isIpLiteral(String host) {
        String literal = host;
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        return literal.indexOf(':') >= 0 || IPV4.matcher(literal).matches();
    }

    /** The shared failure shape: the key, the 1-based entry number, the detail. */
    static IllegalArgumentException invalidEntry(String key, int index, String detail) {
        return new IllegalArgumentException(key + " entry " + (index + 1) + ": " + detail);
    }
}
