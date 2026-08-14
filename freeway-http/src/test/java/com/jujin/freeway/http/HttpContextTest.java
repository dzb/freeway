package com.jujin.freeway.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.body.UnsupportedMediaTypeException;
import com.jujin.freeway.http.internal.HttpUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpContextTest {
    @Test
    void maxBodySizeMustBePositive() {
        StubHttpContext ctx = new StubHttpContext();

        assertThrows(IllegalArgumentException.class, () -> ctx.setMaxBodySize(0));
        assertThrows(IllegalArgumentException.class, () -> ctx.setMaxBodySize(-1));
    }

    @Test
    void malformedQueryEncodingFallsBackToRawText() {
        var params = HttpUtils.parseQueryParams("bad=%zz&ok=a%20b");

        assertEquals("%zz", params.get("bad").getFirst());
        assertEquals("a b", params.get("ok").getFirst());
    }

    @Test
    void noContentStatusesSuppressResponseBody() throws IOException {
        StubHttpContext noContent = new StubHttpContext();
        noContent.send(204, "ignored");
        assertEquals(204, noContent.status());
        assertEquals("", noContent.responseBody());
        assertNull(noContent.responseHeader("Content-Type"));

        StubHttpContext resetContent = new StubHttpContext();
        resetContent.send(205, "ignored");
        assertEquals(205, resetContent.status());
        assertEquals("", resetContent.responseBody());
        assertNull(resetContent.responseHeader("Content-Type"));

        StubHttpContext notModified = new StubHttpContext();
        notModified.setStatus(304).outputJson(Map.of("ok", true));
        assertEquals(304, notModified.status());
        assertEquals("", notModified.responseBody());
        assertNull(notModified.responseHeader("Content-Type"));
    }

    @Test
    void outputStringDefaultsContentTypeWhenAbsent() throws IOException {
        StubHttpContext ctx = new StubHttpContext();

        ctx.output("hello");

        assertEquals("hello", ctx.responseBody());
        assertEquals("text/plain; charset=utf-8", ctx.responseHeader("Content-Type"));
    }

    @Test
    void outputJsonDefaultsContentTypeWhenAbsent() throws IOException {
        StubHttpContext ctx = new StubHttpContext();

        ctx.outputJson(Map.of("ok", true));

        assertEquals("application/json; charset=utf-8", ctx.responseHeader("Content-Type"));
    }

    @Test
    void requestHeadersAreExposedAsMap() {
        StubHttpContext ctx = new StubHttpContext()
            .requestHeader("X-Test", "a")
            .requestHeader("X-Test", "b");

        assertEquals(Map.of("X-Test", List.of("a", "b")), ctx.headers());
    }

    @Test
    void addVaryMergesAndDeduplicates() {
        StubHttpContext ctx = new StubHttpContext();

        ctx.addVary("Origin");
        ctx.addVary("accept-encoding");
        ctx.addVary("Origin"); // duplicate, case-insensitive

        assertEquals("Origin, accept-encoding", ctx.responseHeader("Vary"));
    }

    @Test
    void addVaryRejectsBlankToken() {
        StubHttpContext ctx = new StubHttpContext();

        assertThrows(IllegalArgumentException.class, () -> ctx.addVary(null));
        assertThrows(IllegalArgumentException.class, () -> ctx.addVary(" "));
    }

    @Test
    void headerNamesMustBeRfc7230Tokens() {
        assertThrows(IllegalArgumentException.class,
            () -> AbstractHttpContext.validateHeaderName("Bad Name"));
        assertThrows(IllegalArgumentException.class,
            () -> AbstractHttpContext.validateHeaderName("Bad:Name"));
        assertThrows(IllegalArgumentException.class,
            () -> AbstractHttpContext.validateHeaderName("Bad\u00e9"));
        AbstractHttpContext.validateHeaderName("X-Custom-1"); // valid token
    }

    @Test
    void deprecatedReadBodyLimitedBridgesToReadBody() throws Exception {
        StubHttpContext ctx = new StubHttpContext();
        ctx.requestBody("payload");

        byte[] limited = ctx.readBodyLimited(
            new java.io.ByteArrayInputStream("payload".getBytes()));

        org.junit.jupiter.api.Assertions.assertEquals(
            "payload", new String(limited, StandardCharsets.UTF_8),
            "the deprecated bridge must delegate to readBody with identical semantics");
    }

    @Test
    void bodyStreamDefaultsToBufferedBody() throws Exception {
        StubHttpContext ctx = new StubHttpContext();
        ctx.output("stream-me".getBytes(StandardCharsets.UTF_8));

        try (InputStream in = ctx.bodyStream()) {
            assertEquals("stream-me",
                new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void httpDateIsRfc1123UtcAndCachedPerSecond() {
        long first = 1_700_000_000_000L;
        long second = first + 1000L;

        String date = HttpUtils.httpDate(first);
        assertSame(date, HttpUtils.httpDate(first));
        assertNotSame(date, HttpUtils.httpDate(second));

        ZonedDateTime parsed =
            ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(ZoneOffset.UTC, parsed.getOffset());
        assertEquals(first, parsed.toInstant().toEpochMilli());
    }

    @Test
    void isMultipartChecksContentTypeWithoutReadingBody() {
        StubHttpContext multipart = new StubHttpContext("POST", "/")
            .requestHeader("Content-Type", "multipart/form-data; boundary=x");
        StubHttpContext json = new StubHttpContext("POST", "/")
            .requestHeader("Content-Type", "application/json");

        assertTrue(multipart.isMultipart());
        assertFalse(json.isMultipart());
    }

    @Test
    void exchangeMetaResetClearsPerRequestState() {
        ExchangeMetaDefault meta = new ExchangeMetaDefault("client-id");
        meta.setPrincipal("user");
        meta.setAttribute("k", "v");

        Instant before = Instant.now();
        meta.reset();
        Instant after = Instant.now();

        assertNull(meta.principal());
        assertTrue(meta.attributes().isEmpty());
        assertNotEquals("client-id", meta.correlationId());
        assertFalse(meta.startTime().isBefore(before));
        assertFalse(meta.startTime().isAfter(after));
    }

    @Test
    void headerValuesMustBeIso88591Encodable() {
        StubHttpContext ctx = new StubHttpContext();
        assertThrows(IllegalArgumentException.class, () ->
            ctx.setHeader("X-Filename", "文件名.txt"),
            "UTF-8 characters outside ISO-8859-1 must be rejected — the "
                + "HTTP/1.1 writer serializes header values as ISO-8859-1 and "
                + "would silently replace them with '?'");
        assertThrows(IllegalArgumentException.class, () ->
            ctx.setHeader("X-Emoji", "ok \uD83D\uDE00"),
            "surrogate-pair (emoji) values are not ISO-8859-1 encodable and "
                + "must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
            AbstractHttpContext.validateHeaderValue("中文值"));

        ctx.setHeader("X-Latin", "caf\u00e9");
        assertEquals("caf\u00e9", ctx.responseHeader("X-Latin"),
            "é (U+00E9) is inside ISO-8859-1 and must be accepted");
        AbstractHttpContext.validateHeaderValue("caf\u00e9"); // no throw
    }

    @Test
    void bodyAsJsonAcceptsJsonAndStructuredSuffixMediaTypes() throws IOException {
        StubHttpContext json = new StubHttpContext("POST", "/")
            .requestHeader("Content-Type", "application/json")
            .requestBody("{\"name\":\"plain\"}");
        assertEquals(Map.of("name", "plain"), json.bodyAsJson(Map.class));

        StubHttpContext vendor = new StubHttpContext("POST", "/")
            .requestHeader("Content-Type", "application/vnd.api+json")
            .requestBody("{\"name\":\"vendor\"}");
        assertEquals(Map.of("name", "vendor"), vendor.bodyAsJson(Map.class),
            "application/*+json structured syntax must be accepted");

        StubHttpContext patch = new StubHttpContext("POST", "/")
            .requestHeader("Content-Type", "application/json-patch+json")
            .requestBody("{\"name\":\"patch\"}");
        assertEquals(Map.of("name", "patch"), patch.bodyAsJson(Map.class));

        StubHttpContext params = new StubHttpContext("POST", "/")
            .requestHeader("Content-Type", "application/json; charset=utf-8")
            .requestBody("{\"name\":\"params\"}");
        assertEquals(Map.of("name", "params"), params.bodyAsJson(Map.class),
            "parameters after the media type must be ignored");
    }

    @Test
    void bodyAsJsonRejectsNonJsonMediaTypesWithClientError() {
        StubHttpContext text = new StubHttpContext("POST", "/")
            .requestHeader("Content-Type", "text/plain")
            .requestBody("{\"name\":\"x\"}");
        assertThrows(UnsupportedMediaTypeException.class,
            () -> text.bodyAsJson(Map.class),
            "text/plain must be rejected with a 4xx-mappable exception, not IllegalStateException");

        StubHttpContext missing = new StubHttpContext("POST", "/");
        assertThrows(UnsupportedMediaTypeException.class,
            () -> missing.bodyAsJson(Map.class),
            "a missing Content-Type must be rejected the same way");
    }
}
