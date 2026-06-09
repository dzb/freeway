package com.jujin.freeway.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StaticResourceMountTest {
    @TempDir
    Path tempDir;

    @Test
    void directoryMountRejectsSymlinkTraversalOutsideRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        createSymlinkOrSkip(root.resolve("outside"), outside);

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);
        StubHttpContext ctx = new StubHttpContext("GET", "/static/outside/secret.txt");

        mount.serve(ctx);

        assertEquals(404, ctx.statusCode());
        assertEquals("Not Found", ctx.responseBody());
    }

    @Test
    void directoryMountAllowsSymlinkThatResolvesInsideRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Path assets = Files.createDirectory(root.resolve("assets"));
        Files.writeString(assets.resolve("app.js"), "console.log('ok');");
        createSymlinkOrSkip(root.resolve("linked"), assets);

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);
        StubHttpContext ctx = new StubHttpContext("GET", "/static/linked/app.js");

        mount.serve(ctx);

        assertEquals(200, ctx.statusCode());
        assertTrue(ctx.responseBody().contains("console.log"));
    }

    @Test
    void malformedEncodedPathReturnsNotFound() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Files.writeString(root.resolve("index.html"), "ok");

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);
        StubHttpContext ctx = new StubHttpContext("GET", "/static/%zz");

        mount.serve(ctx);

        assertEquals(404, ctx.statusCode());
        assertEquals("Not Found", ctx.responseBody());
    }

    @Test
    void encodedBackslashPathReturnsNotFound() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("public"));
        Files.writeString(root.resolve("secret.txt"), "secret");

        StaticResourceMount mount = StaticResourceMount.directory("/static", root);
        StubHttpContext ctx = new StubHttpContext("GET", "/static/%5csecret.txt");

        mount.serve(ctx);

        assertEquals(404, ctx.statusCode());
        assertEquals("Not Found", ctx.responseBody());
    }

    @Test
    void classpathMountRejectsOversizedResource() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(
            new OversizedResourceClassLoader("assets/app.js", 51L * 1024 * 1024)
        );
        try {
            StaticResourceMount mount = StaticResourceMount.classpath("/static", "assets");
            StubHttpContext ctx = new StubHttpContext("GET", "/static/app.js");

            IOException ex = assertThrows(IOException.class, () -> mount.serve(ctx));

            assertTrue(ex.getMessage().contains("too large"));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
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
}
