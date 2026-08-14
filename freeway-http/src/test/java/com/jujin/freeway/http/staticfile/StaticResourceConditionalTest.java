package com.jujin.freeway.http.staticfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jujin.freeway.http.StubHttpContext;
import com.jujin.freeway.http.staticfile.StaticResourceMount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StaticResourceConditionalTest {

    @Test
    void returns304WhenIfNoneMatchMatchesEtag(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello");

        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);

        // first request — get the ETag
        StubHttpContext first = new StubHttpContext("GET", "/test.txt");
        assertTrue(mount.serve(first, first));
        assertEquals(200, first.status());
        String etag = first.responseHeader("ETag");
        assertNotNull(etag);

        // second request — with matching If-None-Match
        StubHttpContext second = new StubHttpContext("GET", "/test.txt");
        second.requestHeader("If-None-Match", etag);
        assertTrue(mount.serve(second, second));
        assertEquals(304, second.status());
    }

    @Test
    void returns200WhenIfNoneMatchDoesNotMatch(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("data.txt");
        Files.writeString(file, "some data");

        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);

        StubHttpContext ctx = new StubHttpContext("GET", "/data.txt");
        ctx.requestHeader("If-None-Match", "\"sha256-wronghash\"");
        assertTrue(mount.serve(ctx, ctx));
        assertEquals(200, ctx.status());
    }

    @Test
    void weakEtagMatches(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("weak.txt");
        Files.writeString(file, "weak test");

        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);

        StubHttpContext first = new StubHttpContext("GET", "/weak.txt");
        mount.serve(first, first);
        String etag = first.responseHeader("ETag");

        StubHttpContext second = new StubHttpContext("GET", "/weak.txt");
        second.requestHeader("If-None-Match", "W/" + etag);
        assertTrue(mount.serve(second, second));
        assertEquals(304, second.status());
    }

    @Test
    void wildcardIfNoneMatchReturns304(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("star.txt");
        Files.writeString(file, "star test");

        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);

        StubHttpContext ctx = new StubHttpContext("GET", "/star.txt");
        ctx.requestHeader("If-None-Match", "*");
        assertTrue(mount.serve(ctx, ctx));
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
        mount.serve(first, first);
        String etag1 = first.responseHeader("ETag");

        StubHttpContext second = new StubHttpContext("GET", "/cache.txt");
        mount.serve(second, second);
        String etag2 = second.responseHeader("ETag");

        assertEquals(etag1, etag2);
    }

    @Test
    void ifModifiedSinceUsesHttpSecondPrecision(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("precision.txt");
        Files.writeString(file, "hello");
        StaticResourceMount mount = StaticResourceMount.directory("/", tempDir);
        StubHttpContext first = new StubHttpContext("GET", "/precision.txt");
        mount.serve(first, first);
        StubHttpContext second = new StubHttpContext("GET", "/precision.txt");
        second.requestHeader("If-Modified-Since", first.responseHeader("Last-Modified"));
        mount.serve(second, second);
        assertEquals(304, second.status());
    }

    @Test
    void ifRangeWithEchoedLastModifiedAllowsRange(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("data.txt");
        Files.writeString(file, "0123456789");
        // Give the file an mtime with a sub-second component, then verify the
        // filesystem preserved it — otherwise the comparison is second-aligned
        // anyway and the regression cannot be reproduced.
        long subSecond = (System.currentTimeMillis() / 1000) * 1000 + 321;
        Files.setLastModifiedTime(file, FileTime.fromMillis(subSecond));
        assumeTrue(Files.getLastModifiedTime(file).toMillis() % 1000 != 0,
            "filesystem must preserve sub-second mtime for this test");

        StaticResourceMount mount = StaticResourceMount.directory("/files", tempDir);

        StubHttpContext first = new StubHttpContext("GET", "/files/data.txt");
        assertTrue(mount.serve(first, first));
        String lastModified = first.responseHeader("Last-Modified");
        assertNotNull(lastModified);

        // A client echoing the Last-Modified value in If-Range must still get
        // its range served (206), not a full 200.
        StubHttpContext range = new StubHttpContext("GET", "/files/data.txt");
        range.requestHeader("If-Range", lastModified);
        range.requestHeader("Range", "bytes=0-3");
        assertTrue(mount.serve(range, range));
        assertEquals(206, range.status(),
            "If-Range with the echoed Last-Modified date must allow the range");
        assertEquals("0123", range.responseBody());

        // The ETag form of If-Range must be unaffected.
        StubHttpContext etag = new StubHttpContext("GET", "/files/data.txt");
        etag.requestHeader("If-Range", first.responseHeader("ETag"));
        etag.requestHeader("Range", "bytes=4-5");
        assertTrue(mount.serve(etag, etag));
        assertEquals(206, etag.status());
        assertEquals("45", etag.responseBody());
    }
}
