package com.jujin.freeway.cloud.discovery;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ServiceEndpoint URI rendering, including IPv6 literal hosts. */
class ServiceEndpointTest {

    @Test
    void ipv6HostIsBracketedInTheUri() {
        ServiceEndpoint endpoint = ServiceEndpoint.of("http", "::1", 8080);
        assertEquals("http://[::1]:8080", endpoint.uri().toString(),
            "RFC 3986 requires brackets around IPv6 literals");
        // The JDK HttpClient transport must accept the rendered endpoint URI.
        assertDoesNotThrow(() -> HttpRequest.newBuilder(endpoint.uri()).GET().build());
    }

    @Test
    void preBracketedIpv6HostIsNotDoubleBracketed() {
        assertEquals("http://[::1]:8080", ServiceEndpoint.of("http", "[::1]", 8080).uri().toString());
    }

    @Test
    void regularHostIsUnchanged() {
        assertEquals("http://127.0.0.1:8080",
            ServiceEndpoint.of("http", "127.0.0.1", 8080).uri().toString());
        assertEquals("https://svc.internal:8443/base",
            ServiceEndpoint.of("https", "svc.internal", 8443, "/base").uri().toString());
    }

    @Test
    void basePathIsNormalized() {
        assertEquals("http://h:8080", ServiceEndpoint.of("http", "h", 8080, "").uri().toString());
        assertEquals("http://h:8080/base",
            ServiceEndpoint.of("http", "h", 8080, "base").uri().toString(),
            "a missing leading slash is added");
        assertEquals("http://h:8080/base",
            ServiceEndpoint.of("http", "h", 8080, "/base/").uri().toString(),
            "trailing slashes are stripped");
    }

    @Test
    void malformedEndpointsFailAtConstruction() {
        // URI validity is a construction-time property, never a per-request surprise.
        assertThrows(IllegalArgumentException.class,
            () -> ServiceEndpoint.of("http", "host with space", 8080));
        assertThrows(IllegalArgumentException.class,
            () -> ServiceEndpoint.of("http", "127.0.0.1", 8080, "/bad path"));
        assertThrows(IllegalArgumentException.class,
            () -> ServiceEndpoint.of("http", "127.0.0.1", 99999));
    }
}
