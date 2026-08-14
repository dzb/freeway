package com.jujin.freeway.http.route;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Single-template matching ({@link PathPattern} instance API) plus the shared
 * static helpers. The instance matcher is a public capability for callers
 * that test one path against one pattern outside the route table.
 */
class PathPatternTest {

    @Test
    void literalAndParameterMatching() {
        PathPattern pattern = new PathPattern("/users/:id/orders/{orderId}");
        assertEquals(Map.of("id", "42", "orderId", "7"),
            pattern.match("/users/42/orders/7"));
        assertEquals(Map.of("id", "42", "orderId", "7x"),
            pattern.match("/users/42/orders/7x"),
            "an unconstrained parameter accepts any segment");
        assertEquals(Map.of("id", "42x", "orderId", "7"),
            pattern.match("/users/42x/orders/7"),
            "an unconstrained parameter accepts any segment");
        assertNull(pattern.match("/users/42/orders"), "missing segment must not match");
        assertNull(pattern.match("/users/42/orders/7/extra"), "extra segment must not match");
        assertNull(pattern.match("/users/42/orderz/7"), "literal mismatch must not match");
    }

    @Test
    void regexConstraintMatching() {
        PathPattern pattern = new PathPattern("/files/{name:[a-z]+}");
        assertEquals(Map.of("name", "readme"), pattern.match("/files/readme"));
        assertNull(pattern.match("/files/Readme"), "regex constraint must be enforced");
        assertNull(pattern.match("/files/readme.txt"));
    }

    @Test
    void terminalWildcardMatching() {
        PathPattern pattern = new PathPattern("/assets/{path:.*}");
        assertEquals(Map.of("path", "css/site.css"),
            pattern.match("/assets/css/site.css"));
        assertNull(pattern.match("/other/css/site.css"));
    }

    @Test
    void overlongSegmentNeverMatches() {
        PathPattern pattern = new PathPattern("/x/{p:[a-z]+}");
        assertNull(pattern.match("/x/" + "a".repeat(PathPattern.MAX_SEGMENT_LENGTH + 1)),
            "an overlong segment must be rejected before regex matching (ReDoS guard)");
    }

    @Test
    void malformedTemplatesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new PathPattern("/a/{}/b"),
            "empty parameter name must fail at construction");
        assertThrows(IllegalArgumentException.class, () -> new PathPattern("/a/{x:(unclosed}/b"),
            "invalid regex must fail at construction");
        assertThrows(IllegalArgumentException.class,
            () -> new PathPattern("/a/{x:" + "r".repeat(100) + "}"),
            "overlong regex constraint must fail at construction");
    }
}
