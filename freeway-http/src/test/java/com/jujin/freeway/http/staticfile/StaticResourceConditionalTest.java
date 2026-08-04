package com.jujin.freeway.http.staticfile;

import com.jujin.freeway.http.StubHttpContext;

import com.jujin.freeway.http.staticfile.StaticResourceMount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticResourceConditionalTest {

    @Test
    void returns304WhenIfNoneMatchMatchesEtag(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello");

        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);

        // first request — get the ETag
        StubHttpContext first = new StubHttpContext("GET", "/test.txt");
        assertTrue(mount.serve(first));
        assertEquals(200, first.status());
        String etag = first.responseHeader("ETag");
        assertNotNull(etag);

        // second request — with matching If-None-Match
        StubHttpContext second = new StubHttpContext("GET", "/test.txt");
        second.requestHeader("If-None-Match", etag);
        assertTrue(mount.serve(second));
        assertEquals(304, second.status());
    }

    @Test
    void returns200WhenIfNoneMatchDoesNotMatch(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("data.txt");
        Files.writeString(file, "some data");

        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);

        StubHttpContext ctx = new StubHttpContext("GET", "/data.txt");
        ctx.requestHeader("If-None-Match", "\"sha256-wronghash\"");
        assertTrue(mount.serve(ctx));
        assertEquals(200, ctx.status());
    }

    @Test
    void weakEtagMatches(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("weak.txt");
        Files.writeString(file, "weak test");

        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);

        StubHttpContext first = new StubHttpContext("GET", "/weak.txt");
        mount.serve(first);
        String etag = first.responseHeader("ETag");

        StubHttpContext second = new StubHttpContext("GET", "/weak.txt");
        second.requestHeader("If-None-Match", "W/" + etag);
        assertTrue(mount.serve(second));
        assertEquals(304, second.status());
    }

    @Test
    void wildcardIfNoneMatchReturns304(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("star.txt");
        Files.writeString(file, "star test");

        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);

        StubHttpContext ctx = new StubHttpContext("GET", "/star.txt");
        ctx.requestHeader("If-None-Match", "*");
        assertTrue(mount.serve(ctx));
        assertEquals(304, ctx.status());
    }

    @Test
    void onlyMatchesGetAndHead(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("methods.txt");
        Files.writeString(file, "test");

        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);

        assertTrue(mount.matches("GET", "/methods.txt"));
        assertTrue(mount.matches("HEAD", "/methods.txt"));
        assertFalse(mount.matches("POST", "/methods.txt"));
        assertFalse(mount.matches("PUT", "/methods.txt"));
        assertFalse(mount.matches("DELETE", "/methods.txt"));
    }

    @Test
    void cachesEtagsAcrossRequests(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("cache.txt");
        Files.writeString(file, "cache test");

        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);

        StubHttpContext first = new StubHttpContext("GET", "/cache.txt");
        mount.serve(first);
        String etag1 = first.responseHeader("ETag");

        StubHttpContext second = new StubHttpContext("GET", "/cache.txt");
        mount.serve(second);
        String etag2 = second.responseHeader("ETag");

        assertEquals(etag1, etag2);
    }
}
