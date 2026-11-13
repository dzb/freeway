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
import com.jujin.freeway.http.internal.HttpHeaders;
import com.jujin.freeway.http.internal.HttpUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpExchangeTest {
    @Test
    void maxBodySizeMustBePositive() {
        TestExchange ctx = new TestExchange();

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
        TestExchange noContent = new TestExchange();
        noContent.sendText(204, "ignored");
        assertEquals(204, noContent.status());
        assertEquals("", noContent.responseBody());
        assertNull(noContent.responseHeader("Content-Type"));

        TestExchange resetContent = new TestExchange();
        resetContent.sendText(205, "ignored");
        assertEquals(205, resetContent.status());
        assertEquals("", resetContent.responseBody());
        assertNull(resetContent.responseHeader("Content-Type"));

        TestExchange notModified = new TestExchange();
        notModified.setStatus(304).json(Map.of("ok", true));
        assertEquals(304, notModified.status());
        assertEquals("", notModified.responseBody());
        assertNull(notModified.responseHeader("Content-Type"));
    }

    @Test
    void outputStringDefaultsContentTypeWhenAbsent() throws IOException {
        TestExchange ctx = new TestExchange();

        ctx.text("hello");

        assertEquals("hello", ctx.responseBody());
        assertEquals("text/plain; charset=utf-8", ctx.responseHeader("Content-Type"));
    }

    @Test
    void jsonDefaultsContentTypeWhenAbsent() throws IOException {
        TestExchange ctx = new TestExchange();

        ctx.json(Map.of("ok", true));

        assertEquals("application/json; charset=utf-8", ctx.responseHeader("Content-Type"));
    }

    @Test
    void requestHeadersAreExposedAsMap() {
        TestExchange ctx = new TestExchange()
            .requestHeader("X-Test", "a")
            .requestHeader("X-Test", "b");

        // The transport canonicalizes names once (HttpTransport contract),
        // so every engine answers with lowercase keys.
        assertEquals(Map.of("x-test", List.of("a", "b")), ctx.headers());
    }

    @Test
    void addVaryMergesAndDeduplicates() {
        TestExchange ctx = new TestExchange();

        ctx.addVary("Origin");
        ctx.addVary("accept-encoding");
        ctx.addVary("Origin"); // duplicate, case-insensitive

        assertEquals("Origin, accept-encoding", ctx.responseHeader("Vary"));
    }

    @Test
    void addVaryRejectsBlankToken() {
        TestExchange ctx = new TestExchange();

        assertThrows(IllegalArgumentException.class, () -> ctx.addVary(null));
        assertThrows(IllegalArgumentException.class, () -> ctx.addVary(" "));
    }

    @Test
    void headerNamesMustBeRfc7230Tokens() {
        assertThrows(IllegalArgumentException.class,
            () -> HttpHeaders.requireValidName("Bad Name"));
        assertThrows(IllegalArgumentException.class,
            () -> HttpHeaders.requireValidName("Bad:Name"));
        assertThrows(IllegalArgumentException.class,
            () -> HttpHeaders.requireValidName("Bad\u00e9"));
        HttpHeaders.requireValidName("X-Custom-1"); // valid token
    }

    @Test
    void bodyStreamReadsTheRequestBody() throws Exception {
        TestExchange ctx = new TestExchange("POST", "/")
            .requestBody("stream-me".getBytes(StandardCharsets.UTF_8));

        try (InputStream in = ctx.bodyStream()) {
            assertEquals("stream-me",
                new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void httpDateIsRfc1123UtcAndCachedPerSecond() {
        long first = 1_700_000_000_000L;
        long second = first + 1000L;

        String date = HttpHeaders.httpDate(first);
        assertSame(date, HttpHeaders.httpDate(first));
        assertNotSame(date, HttpHeaders.httpDate(second));

        ZonedDateTime parsed =
            ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(ZoneOffset.UTC, parsed.getOffset());
        assertEquals(first, parsed.toInstant().toEpochMilli());
    }

    @Test
    void isMultipartChecksContentTypeWithoutReadingBody() {
        TestExchange multipart = new TestExchange("POST", "/")
            .requestHeader("Content-Type", "multipart/form-data; boundary=x");
        TestExchange json = new TestExchange("POST", "/")
            .requestHeader("Content-Type", "application/json");

        assertTrue(multipart.isMultipart());
        assertFalse(json.isMultipart());
    }

    @Test
    void headerValuesMustBeIso88591Encodable() {
        TestExchange ctx = new TestExchange();
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
            HttpHeaders.requireValidValue("中文值"));

        ctx.setHeader("X-Latin", "caf\u00e9");
        assertEquals("caf\u00e9", ctx.responseHeader("X-Latin"),
            "é (U+00E9) is inside ISO-8859-1 and must be accepted");
        HttpHeaders.requireValidValue("caf\u00e9"); // no throw
    }

    @Test
    void bodyAsJsonAcceptsJsonAndStructuredSuffixMediaTypes() throws IOException {
        TestExchange json = new TestExchange("POST", "/")
            .requestHeader("Content-Type", "application/json")
            .requestBody("{\"name\":\"plain\"}");
        assertEquals(Map.of("name", "plain"), json.bodyAsJson(Map.class));

        TestExchange vendor = new TestExchange("POST", "/")
            .requestHeader("Content-Type", "application/vnd.api+json")
            .requestBody("{\"name\":\"vendor\"}");
        assertEquals(Map.of("name", "vendor"), vendor.bodyAsJson(Map.class),
            "application/*+json structured syntax must be accepted");

        TestExchange patch = new TestExchange("POST", "/")
            .requestHeader("Content-Type", "application/json-patch+json")
            .requestBody("{\"name\":\"patch\"}");
        assertEquals(Map.of("name", "patch"), patch.bodyAsJson(Map.class));

        TestExchange params = new TestExchange("POST", "/")
            .requestHeader("Content-Type", "application/json; charset=utf-8")
            .requestBody("{\"name\":\"params\"}");
        assertEquals(Map.of("name", "params"), params.bodyAsJson(Map.class),
            "parameters after the media type must be ignored");
    }

    @Test
    void bodyAsJsonRejectsNonJsonMediaTypesWithClientError() {
        TestExchange text = new TestExchange("POST", "/")
            .requestHeader("Content-Type", "text/plain")
            .requestBody("{\"name\":\"x\"}");
        assertThrows(UnsupportedMediaTypeException.class,
            () -> text.bodyAsJson(Map.class),
            "text/plain must be rejected with a 4xx-mappable exception, not IllegalStateException");

        TestExchange missing = new TestExchange("POST", "/");
        assertThrows(UnsupportedMediaTypeException.class,
            () -> missing.bodyAsJson(Map.class),
            "a missing Content-Type must be rejected the same way");
    }
}
