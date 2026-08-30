package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.discovery.Endpoint;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Endpoint URI rendering, including IPv6 literal hosts. */
class EndpointTest {

    @Test
    void ipv6HostIsBracketedInTheUri() {
        Endpoint endpoint = Endpoint.of("http", "::1", 8080);
        assertEquals("http://[::1]:8080", endpoint.uri().toString(),
            "RFC 3986 requires brackets around IPv6 literals");
        // The JDK HttpClient transport must accept the rendered endpoint URI.
        assertDoesNotThrow(() -> HttpRequest.newBuilder(endpoint.uri()).GET().build());
    }

    @Test
    void preBracketedIpv6HostIsNotDoubleBracketed() {
        assertEquals("http://[::1]:8080", Endpoint.of("http", "[::1]", 8080).uri().toString());
    }

    @Test
    void regularHostIsUnchanged() {
        assertEquals("http://127.0.0.1:8080",
            Endpoint.of("http", "127.0.0.1", 8080).uri().toString());
        assertEquals("https://svc.internal:8443/base",
            Endpoint.of("https", "svc.internal", 8443, "/base").uri().toString());
    }

    @Test
    void basePathIsNormalized() {
        assertEquals("http://h:8080", Endpoint.of("http", "h", 8080, "").uri().toString());
        assertEquals("http://h:8080/base",
            Endpoint.of("http", "h", 8080, "base").uri().toString(),
            "a missing leading slash is added");
        assertEquals("http://h:8080/base",
            Endpoint.of("http", "h", 8080, "/base/").uri().toString(),
            "trailing slashes are stripped");
    }

    @Test
    void malformedEndpointsFailAtConstruction() {
        // URI validity is a construction-time property, never a per-request surprise.
        assertThrows(IllegalArgumentException.class,
            () -> Endpoint.of("http", "host with space", 8080));
        assertThrows(IllegalArgumentException.class,
            () -> Endpoint.of("http", "127.0.0.1", 8080, "/bad path"));
        assertThrows(IllegalArgumentException.class,
            () -> Endpoint.of("http", "127.0.0.1", 99999));
    }
}
