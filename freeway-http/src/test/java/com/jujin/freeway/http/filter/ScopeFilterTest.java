package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.ScopeBoundary;
import com.jujin.freeway.ioc.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The request boundary: one scope for the whole dispatch, the same
 * THREAD-scoped instance throughout, and cleanup on both normal and checked
 * exception paths.
 */
class ScopeFilterTest {

    private static final AtomicInteger destroyed = new AtomicInteger();

    static final class ScopedValue {
        @PreDestroy
        void destroy() {
            destroyed.incrementAndGet();
        }
    }

    private static Container container() {
        return Freeway.create(binder ->
            binder.bind(ScopedValue.class).to(ScopedValue.class).scope(Scope.THREAD));
    }

    @Test
    void opensOneBoundaryForTheWholeRequestAndClosesIt() throws Exception {
        destroyed.set(0);
        Container container = container();
        ScopeFilter filter = new ScopeFilter(container.get(ScopeBoundary.class));
        ScopedValue[] seen = new ScopedValue[2];

        filter.doFilter(null, ctx -> {
            seen[0] = container.get(ScopedValue.class);
            seen[1] = container.get(ScopedValue.class);
        });

        assertSame(seen[0], seen[1], "one instance per request boundary");
        assertEquals(1, destroyed.get(), "cleanup runs when the boundary closes");
        assertThrows(IllegalStateException.class, () -> container.get(ScopedValue.class),
            "the scope is closed after the request");
        assertEquals(ScopeFilter.ORDER, filter.order());
        container.close();
    }

    @Test
    void checkedExceptionPropagatesUnchangedAfterCleanup() {
        destroyed.set(0);
        Container container = container();
        ScopeFilter filter = new ScopeFilter(container.get(ScopeBoundary.class));

        IOException failure = assertThrows(IOException.class, () -> filter.doFilter(
            null,
            ctx -> {
                container.get(ScopedValue.class);
                throw new IOException("boom");
            }));

        assertEquals("boom", failure.getMessage(), "the checked exception keeps its type and message");
        assertEquals(1, destroyed.get(), "cleanup still runs on the checked-exception path");
        assertThrows(IllegalStateException.class, () -> container.get(ScopedValue.class));
        container.close();
    }
}
