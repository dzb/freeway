package com.jujin.freeway.http.staticfile;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.TestExchange;
import com.jujin.freeway.http.staticfile.StaticResourceMount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathResourceSourceTest {

    @Test
    void servesFileFromClasspath() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/assets", "static");

        TestExchange ctx = new TestExchange("GET", "/assets/test.txt");
        assertTrue(mount.serve(ctx, ctx));
        assertEquals(200, ctx.status());
        assertTrue(ctx.responseBody().contains("classpath test content"));
    }

    @Test
    void returns404ForMissingClasspathResource() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/assets", "static");

        TestExchange ctx = new TestExchange("GET", "/assets/nonexistent.txt");
        assertTrue(mount.serve(ctx, ctx));
        assertEquals(404, ctx.status());
    }

    @Test
    void fallthroughReturnsFalseForMissingResource() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/assets", "static")
            .withFallthrough(true);

        TestExchange ctx = new TestExchange("GET", "/assets/missing.txt");
        assertFalse(mount.serve(ctx, ctx));
    }

    @Test
    void mountsUnderRootPath() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/", "static");

        TestExchange ctx = new TestExchange("GET", "/test.txt");
        assertTrue(mount.serve(ctx, ctx));
        assertEquals(200, ctx.status());
    }

    @Test
    void setsCorrectContentType() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/", "static");

        TestExchange ctx = new TestExchange("GET", "/test.txt");
        mount.serve(ctx, ctx);
        assertEquals("text/plain; charset=utf-8", ctx.responseHeader("Content-Type"));
    }

    @Test
    void setsNosniffHeader() throws Exception {
        StaticResourceMount mount = StaticResourceMount.classpath("/", "static");

        TestExchange ctx = new TestExchange("GET", "/test.txt");
        mount.serve(ctx, ctx);
        assertEquals("nosniff", ctx.responseHeader("X-Content-Type-Options"));
    }
}
