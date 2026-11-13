package com.jujin.freeway.http.staticfile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.time.Duration;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jujin.freeway.http.TestExchange;
import com.jujin.freeway.http.staticfile.StaticResourceMount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StaticResourceMountTest {
    @TempDir
    Path tempDir;

    @Test
    void durationCacheMaxAgeRendersWholeSeconds() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("cache"));
        Files.writeString(root.resolve("asset.txt"), "asset");

        StaticResourceMount mount = StaticResourceMount.directory("/static", root)
            .withCacheMaxAge(Duration.ofHours(2));
        TestExchange ctx = new TestExchange("GET", "/static/asset.txt");
        mount.serve(ctx, ctx);

        assertEquals("public, max-age=7200", ctx.responseHeader("Cache-Control"));
    }

    @Test
    void directoryMountRejectsSymlinkTraversalOutsideRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        createSymlinkOrSkip(root.resolve("outside"), outside);

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);
        TestExchange ctx = new TestExchange("GET", "/static/outside/secret.txt");

        mount.serve(ctx, ctx);

        assertEquals(404, ctx.status());
        assertEquals("{\"error\":\"Not Found\",\"code\":\"not_found\"}", ctx.responseBody());
    }

    @Test
    void directoryMountAllowsSymlinkThatResolvesInsideRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Path assets = Files.createDirectory(root.resolve("assets"));
        Files.writeString(assets.resolve("app.js"), "console.log('ok');");
        createSymlinkOrSkip(root.resolve("linked"), assets);

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);
        TestExchange ctx = new TestExchange("GET", "/static/linked/app.js");

        mount.serve(ctx, ctx);

        assertEquals(200, ctx.status());
        assertTrue(ctx.responseBody().contains("console.log"));
    }

    @Test
    void malformedEncodedPathReturnsNotFound() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Files.writeString(root.resolve("index.html"), "ok");

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);
        TestExchange ctx = new TestExchange("GET", "/static/%zz");

        mount.serve(ctx, ctx);

        assertEquals(404, ctx.status());
        assertEquals("{\"error\":\"Not Found\",\"code\":\"not_found\"}", ctx.responseBody());
    }

    @Test
    void encodedBackslashPathReturnsNotFound() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Files.writeString(root.resolve("secret.txt"), "secret");

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);
        TestExchange ctx = new TestExchange("GET", "/static/%5csecret.txt");

        mount.serve(ctx, ctx);

        assertEquals(404, ctx.status());
        assertEquals("{\"error\":\"Not Found\",\"code\":\"not_found\"}", ctx.responseBody());
    }

    @Test
    void byteRangeServesPartialContent() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("range"));
        Files.writeString(root.resolve("data.txt"), "0123456789");
        StaticResourceMount mount = StaticResourceMount.directory("/files", root);

        TestExchange prefix = new TestExchange("GET", "/files/data.txt");
        prefix.requestHeader("Range", "bytes=0-3");
        mount.serve(prefix, prefix);
        assertEquals(206, prefix.status());
        assertEquals("bytes 0-3/10", prefix.responseHeader("Content-Range"));
        assertEquals("0123", prefix.responseBody());

        TestExchange openEnd = new TestExchange("GET", "/files/data.txt");
        openEnd.requestHeader("Range", "bytes=5-");
        mount.serve(openEnd, openEnd);
        assertEquals(206, openEnd.status());
        assertEquals("56789", openEnd.responseBody());

        TestExchange middle = new TestExchange("GET", "/files/data.txt");
        middle.requestHeader("Range", "bytes=2-8");
        mount.serve(middle, middle);
        assertEquals("2345678", middle.responseBody());

        TestExchange suffix = new TestExchange("GET", "/files/data.txt");
        suffix.requestHeader("Range", "bytes=-4");
        mount.serve(suffix, suffix);
        assertEquals("6789", suffix.responseBody());

        TestExchange head = new TestExchange("HEAD", "/files/data.txt");
        head.requestHeader("Range", "bytes=0-3");
        mount.serve(head, head);
        assertEquals(206, head.status());
        assertEquals("bytes 0-3/10", head.responseHeader("Content-Range"));
        assertEquals("4", head.responseHeader("Content-Length"));

        TestExchange unsatisfiable = new TestExchange("GET", "/files/data.txt");
        unsatisfiable.requestHeader("Range", "bytes=20-");
        mount.serve(unsatisfiable, unsatisfiable);
        assertEquals(416, unsatisfiable.status());
        assertEquals("bytes */10", unsatisfiable.responseHeader("Content-Range"));

        TestExchange full = new TestExchange("GET", "/files/data.txt");
        mount.serve(full, full);
        assertEquals(200, full.status());
        assertEquals("0123456789", full.responseBody());
    }

    @Test
    void classpathMountRejectsOversizedResource() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(
            new OversizedResourceClassLoader("assets/app.js", 51L * 1024 * 1024)
        );
        try {
            StaticResourceMount mount = StaticResourceMount.classpath("/static", "assets");
            TestExchange ctx = new TestExchange("GET", "/static/app.js");

            IOException ex = assertThrows(IOException.class, () -> mount.serve(ctx, ctx));

            assertTrue(ex.getMessage().contains("too large"));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void directoryMountServesFileLargerThanMemoryCap(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Path big = root.resolve("big.bin");
        // Sparse 60MB file — larger than the 50MB in-memory cap, but
        // streamable; meta()/resolve() must not reject it by size.
        try (var raf = new RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(60L * 1024 * 1024);
        }
        long expected = Files.size(big);
        assertTrue(expected > 50L * 1024 * 1024, "test file must exceed the memory cap");

        StaticResourceMount mount = StaticResourceMount.directory("/files", root);

        // HEAD reports the real size without reading the file and without a
        // size error (previously meta() threw IOException → 500).
        TestExchange head = new TestExchange("HEAD", "/files/big.bin");
        assertTrue(mount.serve(head, head));
        assertEquals(200, head.status());
        assertEquals(String.valueOf(expected), head.responseHeader("Content-Length"),
            "an oversized file must be streamed by metadata without a size error");

        // GET must still be served (200), not 500.
        TestExchange get = new TestExchange("GET", "/files/big.bin");
        assertTrue(mount.serve(get, get));
        assertEquals(200, get.status());
        assertEquals(expected, get.responseBodyBytes().length);
    }

    @Test
    void headOmitsContentLengthWhenClasspathSizeUnknown() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(
            new UnknownSizeClassLoader("assets/app.js"));
        try {
            StaticResourceMount mount = StaticResourceMount.classpath("/static", "assets");
            TestExchange ctx = new TestExchange("HEAD", "/static/app.js");

            assertTrue(mount.serve(ctx, ctx));
            assertEquals(200, ctx.status());
            assertNull(ctx.responseHeader("Content-Length"),
                "an unknown resource size must not be reported as 0");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void directoryRequestsResolveSubdirectoryIndexHtml() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/index.html"), "sub index content");

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);

        TestExchange withSlash = new TestExchange("GET", "/static/sub/");
        assertTrue(mount.serve(withSlash, withSlash));
        assertEquals(200, withSlash.status());
        assertEquals("sub index content", withSlash.responseBody(),
            "a trailing-slash directory request must serve sub/index.html");

        TestExchange withoutSlash = new TestExchange("GET", "/static/sub");
        assertTrue(mount.serve(withoutSlash, withoutSlash));
        assertEquals(200, withoutSlash.status());
        assertEquals("sub index content", withoutSlash.responseBody(),
            "a directory request without a trailing slash must also serve "
                + "sub/index.html");
    }

    @Test
    void missingDirectoryIndexReturnsNotFound() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Files.createDirectories(root.resolve("empty"));

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);
        TestExchange ctx = new TestExchange("GET", "/static/empty/");

        assertTrue(mount.serve(ctx, ctx));
        assertEquals(404, ctx.status());
        assertEquals("{\"error\":\"Not Found\",\"code\":\"not_found\"}", ctx.responseBody());
    }

    @Test
    void trailingSlashOnAFileStillReturnsNotFound() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Files.writeString(root.resolve("data.txt"), "data");

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);
        TestExchange ctx = new TestExchange("GET", "/static/data.txt/");

        assertTrue(mount.serve(ctx, ctx));
        assertEquals(404, ctx.status(),
            "a trailing slash on a file must not be served as a directory index");
    }

    private static void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "Symbolic links are not available: " + e.getMessage());
        }
    }

    private static final class OversizedResourceClassLoader extends ClassLoader {
        private final String resourceName;
        private final long contentLength;

        private OversizedResourceClassLoader(String resourceName, long contentLength) {
            this.resourceName = resourceName;
            this.contentLength = contentLength;
        }

        @Override
        @SuppressWarnings("deprecation")
        public URL getResource(String name) {
            if (!resourceName.equals(name)) {
                return null;
            }
            try {
                return new URL(null, "memory:/" + name, new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) {
                        return new URLConnection(url) {
                            @Override
                            public void connect() {
                            }

                            @Override
                            public long getContentLengthLong() {
                                return contentLength;
                            }

                            @Override
                            public InputStream getInputStream() {
                                throw new AssertionError("oversized resource should fail before reading");
                            }
                        };
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }


    @Test
    void spaFallbackServesIndexForExtensionlessPath() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("spa"));
        Files.writeString(root.resolve("index.html"), "<html>shell</html>");
        StaticResourceMount mount = StaticResourceMount.directory("/", root)
            .withSpaFallback(true);

        assertTrue(mount.fallthrough(), "SPA fallback needs the route-chain hop");
        assertTrue(mount.spaFallback());
        assertTrue(mount.spaFallbackEligible("/chat"));
        assertTrue(mount.spaFallbackEligible("/chat/42"));
        assertFalse(mount.spaFallbackEligible("/missing.js"));
        assertFalse(mount.spaFallbackEligible("/"));
        assertTrue(mount.hasSpaIndex());

        TestExchange ctx = new TestExchange("GET", "/chat");
        assertTrue(mount.serveSpaFallback(ctx, ctx));
        assertEquals(200, ctx.status());
        assertTrue(ctx.responseBody().contains("shell"));
        assertTrue(ctx.responseHeader("Content-Type").startsWith("text/html"));
    }

    @Test
    void spaFallbackReusesTheConditionalPipeline() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("spa-conditional"));
        Files.writeString(root.resolve("index.html"), "<html>shell</html>");
        StaticResourceMount mount = StaticResourceMount.directory("/", root)
            .withSpaFallback(true);

        TestExchange first = new TestExchange("GET", "/chat");
        assertTrue(mount.serveSpaFallback(first, first));
        String etag = first.responseHeader("ETag");
        assertTrue(etag != null && !etag.isBlank(), "index must carry the shared ETag");

        TestExchange second = new TestExchange("GET", "/chat");
        second.requestHeader("If-None-Match", etag);
        assertTrue(mount.serveSpaFallback(second, second));
        assertEquals(304, second.status(),
            "the SPA hop must reuse conditional handling, not bypass it");
    }

    @Test
    void spaFallbackRequiresIndexAndRejectsAssetPaths() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("spa-no-index"));
        StaticResourceMount missing = StaticResourceMount.directory("/", root)
            .withSpaFallback(true);
        assertFalse(missing.hasSpaIndex());
        TestExchange chat = new TestExchange("GET", "/chat");
        assertFalse(missing.serveSpaFallback(chat, chat),
            "no index means the fallback cannot handle the path");

        Files.writeString(root.resolve("index.html"), "<html>shell</html>");
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("assets/app.js"), "console.log('app');");
        StaticResourceMount mount = StaticResourceMount.directory("/", root)
            .withSpaFallback(true);

        TestExchange asset = new TestExchange("GET", "/missing.js");
        assertFalse(mount.serveSpaFallback(asset, asset),
            "a dotted last segment is an asset request, not the SPA shell");
        assertFalse(mount.spaFallbackEligible("/assets/app.js"));
        assertTrue(mount.hasSpaIndex());
    }

    @Test
    void spaFallbackModeTransitionsStayCanonical() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("spa-modes"));
        StaticResourceMount base = StaticResourceMount.directory("/", root);
        assertFalse(base.fallthrough());
        assertFalse(base.spaFallback());

        StaticResourceMount spa = base.withSpaFallback(true);
        assertTrue(spa.fallthrough());
        assertTrue(spa.spaFallback());
        assertFalse(spa.withFallthrough(false).spaFallback(),
            "disabling fallthrough must also clear the terminal SPA hop");
        assertFalse(spa.withFallthrough(false).fallthrough());
        assertFalse(base.withFallthrough(true).spaFallback());
        assertFalse(base.withSpaFallback(false).fallthrough());
        assertTrue(spa.withSpaFallback(false).fallthrough());
        assertFalse(spa.withSpaFallback(false).spaFallback());
    }

    private static final class UnknownSizeClassLoader extends ClassLoader {
        private final String resourceName;

        private UnknownSizeClassLoader(String resourceName) {
            this.resourceName = resourceName;
        }

        @Override
        @SuppressWarnings("deprecation")
        public URL getResource(String name) {
            if (!resourceName.equals(name)) {
                return null;
            }
            try {
                return new URL(null, "memory:/" + name, new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) {
                        return new URLConnection(url) {
                            @Override
                            public void connect() {
                            }

                            @Override
                            public long getContentLengthLong() {
                                return -1;
                            }

                            @Override
                            public InputStream getInputStream() {
                                return new ByteArrayInputStream(
                                    "content".getBytes(StandardCharsets.UTF_8));
                            }
                        };
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
