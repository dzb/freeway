package com.jujin.freeway.cloud.events;

import com.jujin.freeway.cloud.CloudConfigKeys;

import java.net.URI;

/**
 * A parsed mesh peer address ({@code host:port}), rendered as
 * {@code scheme://host:port/cloud/events}.
 *
 * <p>Parsing accepts IPv6 literals in brackets ({@code [::1]:7001}) or bare
 * ({@code fe80::1} — multiple colons imply no port component); the host is
 * bracketed in the rendered URI per RFC 3986. A host without a port renders
 * on the scheme's default port (80).
 *
 * <p>Equality is host + port — the endpoint identity used to deduplicate
 * peers — and avoids the bracket ambiguity of {@code URI.getHost()}, which
 * differs for IPv6 literals across JDK versions.
 */
record PeerAddress(String host, int port) {

    /** {@code host:port} → {@link PeerAddress}; {@code host} defaults port 80. */
    static PeerAddress parse(String peer) {
        if (peer == null || peer.isBlank()) {
            throw new IllegalArgumentException("peer must not be blank");
        }
        String host;
        int port = 80;
        if (peer.startsWith("[")) {
            int close = peer.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("unclosed IPv6 literal: " + peer);
            }
            host = peer.substring(1, close);
            if (close + 1 < peer.length()) {
                if (peer.charAt(close + 1) != ':') {
                    throw new IllegalArgumentException("expected :port after ']': " + peer);
                }
                port = parsePort(peer.substring(close + 2));
            }
        } else {
            int colon = peer.indexOf(':');
            if (colon >= 0 && colon == peer.lastIndexOf(':')) {
                host = peer.substring(0, colon);
                port = parsePort(peer.substring(colon + 1));
            } else {
                host = peer; // plain hostname or bare IPv6 literal
            }
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("peer host must not be blank: " + peer);
        }
        return new PeerAddress(host, port);
    }

    private static int parsePort(String raw) {
        int port;
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("peer port is not a number: '" + raw + "'");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("peer port out of range: " + port);
        }
        return port;
    }

    /** {@code scheme://host:port/cloud/events}, bracketing an IPv6 host. */
    URI toUri(String scheme) {
        String hostPart = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return URI.create(scheme + "://" + hostPart + ":" + port + CloudConfigKeys.EVENTS_PATH_DEFAULT);
    }

    @Override
    public String toString() {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }
}
