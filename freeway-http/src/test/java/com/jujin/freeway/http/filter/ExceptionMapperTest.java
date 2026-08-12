package com.jujin.freeway.http.filter;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.StubHttpContext;
import com.jujin.freeway.http.filter.ExceptionMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ExceptionMapperTest {

    @Test
    void firstMatchingMapperHandlesException() throws Exception {
        ExceptionMapper mapper = (ctx, ex) -> {
            if (ex instanceof IllegalArgumentException) {
                ctx.send(400, "Bad Request");
                return true;
            }
            return false;
        };

        StubHttpContext ctx = new StubHttpContext();
        boolean handled = mapper.handle(ctx, new IllegalArgumentException("bad"));

        assertTrue(handled);
        assertEquals(400, ctx.status());
    }

    @Test
    void nonMatchingMapperReturnsFalse() throws Exception {
        ExceptionMapper mapper = (ctx, ex) -> {
            if (ex instanceof IllegalArgumentException) {
                return true;
            }
            return false;
        };

        StubHttpContext ctx = new StubHttpContext();
        boolean handled = mapper.handle(ctx, new IllegalStateException("nope"));

        assertFalse(handled);
    }

    @Test
    void mapperChainTriesEachUntilHandled() throws Exception {
        ExceptionMapper first = (ctx, ex) -> false;
        ExceptionMapper second = (ctx, ex) -> {
            ctx.send(500, "caught");
            return true;
        };
        ExceptionMapper third = (ctx, ex) -> {
            fail("third mapper should not be called");
            return false;
        };

        StubHttpContext ctx = new StubHttpContext();
        boolean handled = false;
        for (ExceptionMapper mapper : List.of(first, second, third)) {
            if (mapper.handle(ctx, new RuntimeException("test"))) {
                handled = true;
                break;
            }
        }

        assertTrue(handled);
        assertEquals(500, ctx.status());
    }

    @Test
    void mapperThatThrowsIsSkipped() {
        ExceptionMapper broken = (ctx, ex) -> {
            throw new RuntimeException("mapper exploded");
        };
        ExceptionMapper fallback = (ctx, ex) -> {
            ctx.send(500, "fallback");
            return true;
        };

        StubHttpContext ctx = new StubHttpContext();
        boolean handled = false;
        for (ExceptionMapper mapper : List.of(broken, fallback)) {
            try {
                if (mapper.handle(ctx, new RuntimeException("test"))) {
                    handled = true;
                    break;
                }
            } catch (Exception ignored) {
                // mapper threw, skip to next
            }
        }

        assertTrue(handled);
    }

    @Test
    void noMapperMatchMeansUnhandled() throws Exception {
        ExceptionMapper only = (ctx, ex) -> false;

        StubHttpContext ctx = new StubHttpContext();
        boolean handled = only.handle(ctx, new RuntimeException("test"));

        assertFalse(handled);
    }
}
