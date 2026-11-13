package com.jujin.freeway.http;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Response policy of the shared {@link HttpExchangeSupport}: status/header
 * validation, {@code Date}, {@code Content-Length}, gzip decision,
 * {@code Vary}, the commit guard and the exchange snapshot. Assertions
 * read the {@link ResponseHead} the support object commits to a
 * {@link TestTransport} — the transport records, it never decides.
 */
class HttpExchangeSupportTest {

    private static TestExchange exchange(String method, String path) {
        return new TestExchange(method, path);
    }

    @Test
    void outputCommitsHeadAndBodyToTheTransport() throws Exception {
        var ctx = exchange("GET", "/");

        ctx.sendText(200, "hello");

        assertEquals(200, ctx.responseHead().status(), "head must carry the status");
        assertEquals(1, ctx.transport().bodies().size(),
            "one response, one body write");
        assertEquals("hello", ctx.responseBody());
        assertTrue(ctx.isCommitted());
    }

    @Test
    void setPathVarsReplacesTheWholeMatch() {
        // The name says "set"; a merge would let a caller that reuses an
        // exchange keep the previous match's variables — and a filter
        // reading a tenant id would read last request's value.
        var ctx = exchange("GET", "/");
        ctx.setPathVars(Map.of("tenant", "acme", "id", "42"));
        ctx.setPathVars(Map.of("id", "43"));

        assertEquals(Map.of("id", "43"), ctx.pathVars(),
            "a new match replaces the old one; nothing may linger");
    }

    @Test
    void requestHeaderNamesAreCanonicalLowercaseWhateverTheEngine() {
        // HTTP/2 keeps the first letter of an inbound field name upper-case
        // while HTTP/1.1 lowercases it, so a map consumer such as cloud's
        // PropagationFilter saw `Traceparent` on h2 and `traceparent` on h1 —
        // and the W3C trace was silently dropped on h2. The transport
        // canonicalizes names once, at this boundary.
        var ctx = exchange("GET", "/");
        ctx.requestHeader("Traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        ctx.requestHeader("X-Custom", "abc");

        assertTrue(ctx.headers().containsKey("traceparent"),
            "a map lookup by the lowercase name must work: " + ctx.headers().keySet());
        assertFalse(ctx.headers().containsKey("Traceparent"),
            "keys are lowercase, not merely case-insensitive");
        assertEquals("abc", ctx.header("X-Custom").orElse(null),
            "the single-name accessor stays case-insensitive");
    }

    @Test
    void setHeaderIsVisibleBeforeCommitAndLandsInTheHead() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.setHeader("X-Custom", "abc");

        assertEquals("abc", ctx.responseHeader("X-Custom"),
            "setHeader must write the response headers immediately");

        ctx.sendText(200, "ok");
        assertEquals("abc", headValue(ctx, "X-Custom"),
            "the committed head must carry the response headers");
    }

    @Test
    void responseIncludesDateHeader() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.sendText(200, "ok");

        assertNotNull(headValue(ctx, "Date"),
            "responses must carry an RFC 7231 Date header");
    }

    @Test
    void appSetDateHeaderIsNotOverridden() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.setHeader("Date", "custom-date");
        ctx.sendText(200, "ok");

        assertEquals("custom-date", headValue(ctx, "Date"),
            "an application-provided Date must win");
    }

    @Test
    void setHeaderRejectsCRLFInName() {
        var ctx = exchange("GET", "/");
        assertThrows(IllegalArgumentException.class, () ->
            ctx.setHeader("X-Test\r\nInjected", "yes"),
                "Header name with CRLF must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
            ctx.setHeader("X:Test", "yes"),
                "Header name with colon must be rejected");
    }

    @Test
    void sseOpensOnTheTransport() throws Exception {
        var ctx = exchange("GET", "/");

        try (var emitter = ctx.sse()) {
            assertNotNull(emitter);
        }

        assertNotNull(ctx.transport().sse(), "sse() must open the emitter via the transport");
        assertEquals(MediaTypes.EVENT_STREAM_UTF8,
            ctx.responseHeader("Content-Type"),
            "SSE Content-Type must be set on the exchange");
        assertNull(ctx.responseHeader("Content-Length"),
            "SSE must not advertise a Content-Length");
        assertNull(ctx.responseHeader("Connection"),
            "Connection is the transport's decision (HTTP/1.1 closes, HTTP/2 drops it)");
    }

    @Test
    void noBodyStatusStillCommitsTheResponse() throws Exception {
        var ctx = exchange("POST", "/");
        ctx.setStatus(204);
        ctx.body("should-be-ignored".getBytes(StandardCharsets.UTF_8));

        assertEquals(204, ctx.responseHead().status());
        assertEquals(1, ctx.transport().bodies().size(),
            "one response, one body write");
        assertFalse(ctx.allowsBody(), "204 must not allow a body");
        assertEquals("", ctx.responseBody());
    }

    @Test
    void bodylessStatusDropsHandlerSetContentLength() throws Exception {
        // RFC 9110 §8.6: a 204 must not carry Content-Length even when the
        // handler set one — the header must not leak next to a suppressed body.
        var ctx = exchange("GET", "/");
        ctx.setStatus(204).setHeader("Content-Length", "999");
        ctx.body(new byte[0]);

        assertFalse(ctx.responseHead().hasHeader("Content-Length"),
            "204 must not carry Content-Length: " + ctx.responseHead().headers());
    }

    @Test
    void notModifiedStatusDropsHandlerSetContentLength() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.setStatus(304).setHeader("Content-Length", "999");
        ctx.body(new byte[0]);

        assertFalse(ctx.responseHead().hasHeader("Content-Length"),
            "304 must not carry Content-Length");
    }

    @Test
    void bodyAllowedResponseCarriesTheCommittedContentLength() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.body("hello".getBytes(StandardCharsets.UTF_8));

        assertEquals("5", headValue(ctx, "Content-Length"),
            "Content-Length is framework-authoritative: the bytes committed");
        assertEquals("hello", ctx.responseBody());
    }

    @Test
    void queryParamsAreDeeplyUnmodifiable() {
        var ctx = exchange("GET", "/path?a=1&a=2");

        Map<String, List<String>> params = ctx.queryParams();
        assertThrows(UnsupportedOperationException.class, () ->
            params.put("b", List.of("3")));
        assertThrows(UnsupportedOperationException.class, () ->
            params.get("a").add("3"));
    }

    @Test
    void repeatedOutputIsIgnoredAfterResponse() throws Exception {
        var ctx = exchange("GET", "/");

        ctx.sendText(200, "first");
        ctx.sendText(200, "second");

        assertEquals(1, ctx.transport().bodies().size(),
            "a second output after the response must be a no-op");
        assertEquals("first", ctx.responseBody());
    }

    @Test
    void responseHeadersAreCaseInsensitive() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.setHeader("Content-Length", "1");
        ctx.setHeader("content-length", "2");

        assertEquals("2", ctx.responseHeader("Content-Length"));
        assertEquals("2", ctx.responseHeader("content-length"));

        ctx.body(new byte[0]);
        assertEquals(1, ctx.responseHead().headers().stream()
            .filter(h -> h.name().equalsIgnoreCase("Content-Length")).count(),
            "case-insensitive replacement must not duplicate the header");
    }

    @Test
    void addHeaderPreservesMultipleValues() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.addHeader("Set-Cookie", "a=1");
        ctx.addHeader("Set-Cookie", "b=2");
        ctx.body(new byte[0]);

        assertEquals(List.of("a=1", "b=2"),
            ctx.responseHead().headers("Set-Cookie"));
    }

    @Test
    void setHeaderReplacesAllValuesForName() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.addHeader("X-Multi", "1");
        ctx.addHeader("X-Multi", "2");
        ctx.setHeader("X-Multi", "3");
        ctx.body(new byte[0]);

        assertEquals(List.of("3"), ctx.responseHead().headers("X-Multi"));
    }

    @Test
    void rejectsOutOfRangeStatusCodes() {
        var ctx = exchange("GET", "/");
        assertThrows(IllegalArgumentException.class, () -> ctx.setStatus(0),
            "setStatus(0) must be rejected — it would emit an invalid status line");
        assertThrows(IllegalArgumentException.class, () -> ctx.setStatus(99),
            "setStatus(99) must be rejected — below the 100 minimum");
        assertThrows(IllegalArgumentException.class, () -> ctx.setStatus(600),
            "setStatus(600) must be rejected — above the 599 maximum");
        assertThrows(IllegalArgumentException.class, () -> ctx.setStatus(999999),
            "setStatus(999999) must be rejected");
        assertThrows(IllegalArgumentException.class, () -> ctx.setStatus(-1),
            "negative status codes must be rejected");
    }

    @Test
    void acceptsBoundaryStatusCodes() {
        var ctx = exchange("GET", "/");
        ctx.setStatus(100);
        assertEquals(100, ctx.status(), "100 is the lowest legal status code");
        ctx.setStatus(599);
        assertEquals(599, ctx.status(), "599 is the highest legal status code");
    }

    @Test
    void setHeaderRejectsNonIso88591Value() {
        var ctx = exchange("GET", "/");
        assertThrows(IllegalArgumentException.class, () ->
            ctx.setHeader("X-Filename", "报告.pdf"),
            "a UTF-8 value outside ISO-8859-1 must be rejected, not silently "
                + "mangled to '?' by the HTTP/1.1 writer");
        ctx.setHeader("X-Latin", "caf\u00e9");
        assertEquals("caf\u00e9", ctx.responseHeader("X-Latin"),
            "é (U+00E9) is inside ISO-8859-1 and must be accepted");
    }

    @Test
    void noEntityStatusesAreNotCompressed() throws Exception {
        var ctx = exchange("GET", "/")
            .requestHeader("accept-encoding", "gzip");
        ctx.setStatus(204).setHeader("Content-Type", "text/plain");
        ctx.body(new byte[512]);

        assertFalse(ctx.responseHead().hasHeader("Content-Encoding"),
            "a bodyless status must never be compressed");
    }

    @Test
    void headRequestKeepsContentLengthButSuppressesBodyBytes() throws Exception {
        var ctx = exchange("HEAD", "/");
        ctx.sendText(200, "hello");

        assertEquals("5", headValue(ctx, "Content-Length"),
            "HEAD reports the same Content-Length as the equivalent GET");
        assertEquals("", ctx.responseBody(),
            "HEAD must not commit body bytes");
    }

    @Test
    void newSupportStartsWithEmptyPerRequestState() {
        // The per-request lifecycle replaces the old keep-alive reset: a
        // fresh support object has no principal, no attributes, no path
        // variables, no response headers, and a generated correlation id.
        var ctx = exchange("GET", "/one");
        ctx.setPrincipal("alice");
        ctx.setAttribute("cart", List.of("a", "b"));
        ctx.setPathVars(Map.of("tenant", "acme"));
        ctx.setHeader("X-Left", "over");

        var next = exchange("GET", "/two");

        assertNull(next.principal());
        assertNull(next.attribute("cart"));
        assertTrue(next.attributes().isEmpty());
        assertEquals(Map.of(), next.pathVars());
        assertNull(next.responseHeader("X-Left"));
        assertEquals(200, next.status());
        assertNotNull(next.correlationId());
        assertFalse(next.correlationId().isBlank());
    }

    @Test
    void outputStreamWithoutLengthRemovesContentLength() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.body(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), -1);

        assertNull(ctx.responseHeader("Content-Length"),
            "an unknown length must not advertise one");
        assertEquals("hello", ctx.responseBody());
    }

    @Test
    void outputStreamWithLengthSetsContentLength() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.body(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 5);

        assertEquals("5", ctx.responseHeader("Content-Length"));
        assertEquals("hello", ctx.responseBody());
    }

    @Test
    void handlerTransferEncodingIsNotForwarded() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.setHeader("Transfer-Encoding", "chunked");
        ctx.sendText(200, "ok");

        assertFalse(ctx.responseHead().hasHeader("Transfer-Encoding"),
            "framing is the transport's fact; the head must not carry a "
                + "handler-supplied Transfer-Encoding");
        assertEquals("ok", ctx.responseBody());
    }

    @Test
    void statusChangeAfterCommitIsIgnored() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.sendText(200, "first");

        ctx.setStatus(500);
        ctx.sendText(201, "second");

        assertEquals(200, ctx.status(),
            "a committed response must not start reporting a different status");
        assertEquals("first", ctx.responseBody());
    }

    @Test
    void fileOpenFailureLeavesTheExchangeRetryable() throws Exception {
        var ctx = exchange("GET", "/");
        ctx.setHeader("X-Request-Id", "trace-1");
        var missing = java.nio.file.Path.of(
            "/definitely/missing/freeway-audit-" + System.nanoTime());

        assertThrows(java.io.IOException.class, () -> ctx.file(missing, 0, 10));

        assertFalse(ctx.isCommitted(),
            "the transport never committed, so the error handler may still write a 500");
        assertEquals(200, ctx.status(), "the prepared status must be rolled back");
        assertNull(ctx.responseHeader("Date"), "prepared headers must be rolled back");
        assertNull(ctx.responseHeader("Content-Length"));
        assertEquals("trace-1", ctx.responseHeader("X-Request-Id"),
            "the exchange's tracing id survives the rollback");

        ctx.sendText(200, "recovered");
        assertEquals("recovered", ctx.responseBody());
        assertTrue(ctx.isCommitted());
    }

    private static String headValue(TestExchange ctx, String name) {
        ResponseHead head = ctx.responseHead();
        return head == null ? null : head.header(name);
    }
}
