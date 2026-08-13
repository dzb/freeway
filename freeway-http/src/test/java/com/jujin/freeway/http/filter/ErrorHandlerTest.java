package com.jujin.freeway.http.filter;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.StubHttpContext;
import com.jujin.freeway.http.body.UnsupportedMediaTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ErrorHandlerTest {

    @Test
    void firstMatchingHandlerHandlesException() throws Exception {
        ErrorHandler handler = (ctx, ex) -> {
            if (ex instanceof IllegalArgumentException) {
                ctx.send(400, "Bad Request");
                return true;
            }
            return false;
        };

        StubHttpContext ctx = new StubHttpContext();
        boolean handled = handler.handle(ctx, new IllegalArgumentException("bad"));

        assertTrue(handled);
        assertEquals(400, ctx.status());
    }

    @Test
    void nonMatchingHandlerReturnsFalse() throws Exception {
        ErrorHandler handler = (ctx, ex) -> {
            if (ex instanceof IllegalArgumentException) {
                return true;
            }
            return false;
        };

        StubHttpContext ctx = new StubHttpContext();
        boolean handled = handler.handle(ctx, new IllegalStateException("nope"));

        assertFalse(handled);
    }

    @Test
    void handlerChainTriesEachUntilHandled() throws Exception {
        ErrorHandler first = (ctx, ex) -> false;
        ErrorHandler second = (ctx, ex) -> {
            ctx.send(500, "caught");
            return true;
        };
        ErrorHandler third = (ctx, ex) -> {
            fail("third handler should not be called");
            return false;
        };

        StubHttpContext ctx = new StubHttpContext();
        boolean handled = false;
        for (ErrorHandler handler : List.of(first, second, third)) {
            if (handler.handle(ctx, new RuntimeException("test"))) {
                handled = true;
                break;
            }
        }

        assertTrue(handled);
        assertEquals(500, ctx.status());
    }

    @Test
    void handlerThatThrowsIsSkipped() {
        ErrorHandler broken = (ctx, ex) -> {
            throw new RuntimeException("handler exploded");
        };
        ErrorHandler fallback = (ctx, ex) -> {
            ctx.send(500, "fallback");
            return true;
        };

        StubHttpContext ctx = new StubHttpContext();
        boolean handled = false;
        for (ErrorHandler handler : List.of(broken, fallback)) {
            try {
                if (handler.handle(ctx, new RuntimeException("test"))) {
                    handled = true;
                    break;
                }
            } catch (Exception ignored) {
                // handler threw, skip to next
            }
        }

        assertTrue(handled);
    }

    @Test
    void noHandlerMatchMeansUnhandled() throws Exception {
        ErrorHandler only = (ctx, ex) -> false;

        StubHttpContext ctx = new StubHttpContext();
        boolean handled = only.handle(ctx, new RuntimeException("test"));

        assertFalse(handled);
    }

    @Test
    void defaultHandlerMapsUnsupportedMediaTypeTo415() throws Exception {
        StubHttpContext ctx = new StubHttpContext();

        boolean handled = ErrorHandlers.defaultHandler().handle(
            ctx, new UnsupportedMediaTypeException(
                "Expected application/json Content-Type"));

        assertTrue(handled);
        assertEquals(415, ctx.status());
    }
}
