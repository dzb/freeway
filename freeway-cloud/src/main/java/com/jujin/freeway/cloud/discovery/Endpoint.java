package com.jujin.freeway.cloud.discovery;

import java.net.URI;
import java.util.Objects;

/**
 * Structured network locator: scheme + host + port + basePath, convertible to
 * a {@link URI}. Covers DNS names, K8s Service FQDNs, mesh sidecars and other
 * non-IP locators — no bare string concatenation.
 *
 * @param scheme   e.g. {@code http} / {@code https}
 * @param host     hostname or IP
 * @param port     TCP port
 * @param basePath path prefix (empty string when none; no trailing slash)
 */
public record Endpoint(String scheme, String host, int port, String basePath) {

    public Endpoint {
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("scheme must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        basePath = (basePath == null || basePath.isBlank()) ? "" : basePath;
    }

    public static Endpoint of(String scheme, String host, int port) {
        return new Endpoint(scheme, host, port, "");
    }

    public static Endpoint of(String scheme, String host, int port, String basePath) {
        return new Endpoint(scheme, host, port, basePath);
    }

    /** Base URI including the basePath. */
    public URI uri() {
        return URI.create(scheme + "://" + host + ":" + port + basePath);
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
