package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.scoped.Defer;
import com.jujin.freeway.ioc.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A value scope opened inside a {@code Defer} action scope attaches to it:
 * the bean is resolvable inside the {@code Scoping.within} block, lives until
 * the action scope closes, and is not resolvable outside the access window.
 */
class ScopingDeferAttachmentTest {

    private static final AtomicInteger destroyed = new AtomicInteger();

    static final class ScopedValue {
        @PreDestroy
        void destroy() {
            destroyed.incrementAndGet();
        }
    }

    @Test
    void threadBeanAttachesToTheEnclosingActionScope() {
        destroyed.set(0);
        Container container = Freeway.create(binder ->
            binder.bind(ScopedValue.class).to(ScopedValue.class).scope(Scope.THREAD));
        Scoping scoping = container.get(Scoping.class);

        Defer.within(() -> {
            scoping.within(() -> {
                assertNotNull(container.get(ScopedValue.class));
                return null;
            });
            assertEquals(0, destroyed.get(),
                "cleanup waits for the action scope it attached to");
            assertThrows(IllegalStateException.class,
                () -> container.get(ScopedValue.class),
                "outside the value access window a THREAD bean is not resolvable");
        });

        assertEquals(1, destroyed.get(), "the action scope close destroys the bean");
        container.close();
    }
}
