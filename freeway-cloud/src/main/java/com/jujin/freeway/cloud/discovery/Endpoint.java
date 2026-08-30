package com.jujin.freeway.cloud.discovery;

import java.net.URI;

/**
 * Structured network locator: scheme + host + port + basePath, convertible to
 * a {@link URI}. Covers DNS names, K8s Service FQDNs, mesh sidecars and other
 * non-IP locators — no bare string concatenation.
 *
 * <p>The tuple is validated at construction: a locator that cannot render as
 * a valid URI fails here with a clear message, never later on a request
 * thread. The base path is normalized to start with {@code /}, carry no
 * trailing slash, or be empty when absent.
 *
 * @param scheme   e.g. {@code http} / {@code https}
 * @param host     hostname, IP or IPv6 literal
 * @param port     TCP port
 * @param basePath path prefix (empty string when none)
 */
public record Endpoint(String scheme, String host, int port, String basePath) {

    public Endpoint(String scheme, String host, int port, String basePath) {
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("scheme must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.basePath = normalizeBasePath(basePath);
        try {
            uri();
        } catch (RuntimeException unrenderable) {
            throw new IllegalArgumentException(
                "endpoint does not render as a valid URI: "
                    + scheme + "://" + host + ":" + port + this.basePath, unrenderable);
        }
    }

    /** Blank → {@code ""}; a missing leading slash is added; trailing slashes are stripped. */
    private static String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "";
        }
        String path = basePath.startsWith("/") ? basePath : "/" + basePath;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public static Endpoint of(String scheme, String host, int port) {
        return new Endpoint(scheme, host, port, "");
    }

    public static Endpoint of(String scheme, String host, int port, String basePath) {
        return new Endpoint(scheme, host, port, basePath);
    }

    /**
     * Base URI including the basePath. IPv6 literal hosts are bracketed per
     * RFC 3986 ({@code http://[::1]:8080}) — unbracketed, the JDK
     * {@code HttpClient} rejects the URI outright; a host already handed over
     * in brackets is tolerated.
     */
    public URI uri() {
        String literal = host;
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        String hostPart = literal.indexOf(':') >= 0 ? "[" + literal + "]" : literal;
        return URI.create(scheme + "://" + hostPart + ":" + port + basePath);
    }

    /** New endpoint with the same port/path but a different host (instance identity is preserved). */
    public Endpoint withHost(String newHost) {
        return new Endpoint(scheme, newHost, port, basePath);
    }

    /** New endpoint with the same host/path but a different port. */
    public Endpoint withPort(int newPort) {
        return new Endpoint(scheme, host, newPort, basePath);
    }

    @Override
    public String toString() {
        return uri().toString();
    }
}
