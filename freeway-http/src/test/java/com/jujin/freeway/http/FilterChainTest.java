package com.jujin.freeway.http;

import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.RouteHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FilterChainTest {

    @Test
    void filtersExecuteInOrder() throws Exception {
        List<String> order = new ArrayList<>();
        HttpFilter first = (ctx, next) -> {
            order.add("first");
            next.handle(ctx);
        };
        HttpFilter second = (ctx, next) -> {
            order.add("second");
            next.handle(ctx);
        };
        RouteHandler handler = ctx -> order.add("handler");

        // build chain manually: second wraps first wraps handler
        RouteHandler chain = ctx -> second.doFilter(ctx,
            next -> first.doFilter(next, handler));

        StubHttpContext ctx = new StubHttpContext();
        chain.handle(ctx);

        assertEquals(List.of("second", "first", "handler"), order);
    }

    @Test
    void filterCanShortCircuit() throws Exception {
        HttpFilter blocker = (ctx, next) -> {
            ctx.status(403).output("blocked".getBytes());
            // does not call next.handle(ctx)
        };
        AtomicInteger handlerCalled = new AtomicInteger(0);
        RouteHandler handler = ctx -> handlerCalled.incrementAndGet();

        RouteHandler chain = ctx -> blocker.doFilter(ctx, handler);
        StubHttpContext ctx = new StubHttpContext();
        chain.handle(ctx);

        assertEquals(403, ctx.statusCode());
        assertEquals(0, handlerCalled.get());
    }

    @Test
    void exceptionFromInnerFilterPropagatesThroughOuterFilter() {
        AtomicInteger innerAfterCalled = new AtomicInteger(0);
        HttpFilter inner = (ctx, next) -> {
            innerAfterCalled.incrementAndGet();
            throw new IllegalStateException("inner failure");
        };
        AtomicInteger outerFinally = new AtomicInteger(0);
        HttpFilter outer = (ctx, next) -> {
            try {
                next.handle(ctx);
            } finally {
                outerFinally.incrementAndGet();
            }
        };
        RouteHandler handler = ctx -> fail("handler should not be reached");

        RouteHandler chain = ctx -> outer.doFilter(ctx,
            next -> inner.doFilter(next, handler));
        StubHttpContext ctx = new StubHttpContext();
        assertThrows(IllegalStateException.class, () -> chain.handle(ctx));
        assertEquals(1, innerAfterCalled.get());
        assertEquals(1, outerFinally.get());
    }

    @Test
    void exceptionFromHandlerPropagatesThroughFilters() {
        AtomicInteger filterFinally = new AtomicInteger(0);
        HttpFilter filter = (ctx, next) -> {
            try {
                next.handle(ctx);
            } finally {
                filterFinally.incrementAndGet();
            }
        };
        RouteHandler handler = ctx -> {
            throw new IllegalArgumentException("handler error");
        };

        RouteHandler chain = ctx -> filter.doFilter(ctx, handler);
        StubHttpContext ctx = new StubHttpContext();
        assertThrows(IllegalArgumentException.class, () -> chain.handle(ctx));
        assertEquals(1, filterFinally.get());
    }

    @Test
    void emptyFilterListPassesThroughToHandler() throws Exception {
        AtomicInteger called = new AtomicInteger(0);
        RouteHandler handler = ctx -> called.incrementAndGet();

        RouteHandler chain = handler; // no filters
        StubHttpContext ctx = new StubHttpContext();
        chain.handle(ctx);

        assertEquals(1, called.get());
    }
}
