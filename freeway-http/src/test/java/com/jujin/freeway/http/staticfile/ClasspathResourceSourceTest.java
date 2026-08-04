package com.jujin.freeway.http.staticfile;

import com.jujin.freeway.http.StubHttpContext;

import com.jujin.freeway.http.staticfile.StaticResourceMount;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathResourceSourceTest {

    @Test
    void servesFileFromClasspath() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/assets", "static");

        StubHttpContext ctx = new StubHttpContext("GET", "/assets/test.txt");
        assertTrue(mount.serve(ctx));
        assertEquals(200, ctx.status());
        assertTrue(ctx.responseBody().contains("classpath test content"));
    }

    @Test
    void returns404ForMissingClasspathResource() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/assets", "static");

        StubHttpContext ctx = new StubHttpContext("GET", "/assets/nonexistent.txt");
        assertTrue(mount.serve(ctx));
        assertEquals(404, ctx.status());
    }

    @Test
    void fallthroughReturnsFalseForMissingResource() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/assets", "static")
            .fallthrough(true);

        StubHttpContext ctx = new StubHttpContext("GET", "/assets/missing.txt");
        assertFalse(mount.serve(ctx));
    }

    @Test
    void mountsUnderRootPath() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/", "static");

        StubHttpContext ctx = new StubHttpContext("GET", "/test.txt");
        assertTrue(mount.serve(ctx));
        assertEquals(200, ctx.status());
    }

    @Test
    void setsCorrectContentType() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/", "static");

        StubHttpContext ctx = new StubHttpContext("GET", "/test.txt");
        mount.serve(ctx);
        assertEquals("text/plain; charset=utf-8", ctx.responseHeader("Content-Type"));
    }

    @Test
    void setsNosniffHeader() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/", "static");

        StubHttpContext ctx = new StubHttpContext("GET", "/test.txt");
        mount.serve(ctx);
        assertEquals("nosniff", ctx.responseHeader("X-Content-Type-Options"));
    }
}
