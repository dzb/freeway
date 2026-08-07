package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Extension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerCloseTest {

    @Test
    void closeIsIdempotent() {
        Container container = Freeway.create();
        container.close();
        assertDoesNotThrow(container::close,
            "Repeated close() must not re-run shutdown or PreDestroy");
    }

    @Test
    void closedContainerRejectsAllLookups() {
        Container container = Freeway.create(
            binder -> binder.bind(Greeter.class).to(GreeterImpl.class));
        container.close();

        assertThrows(IllegalStateException.class,
            () -> container.get(Greeter.class), "get() after close must fail");
        assertThrows(IllegalStateException.class,
            () -> container.get(Greeter.class, "named"), "named get() after close must fail");
        assertThrows(IllegalStateException.class,
            () -> container.create(GreeterImpl.class), "create() after close must fail");
        assertThrows(IllegalStateException.class,
            () -> container.extension(Greeter.class), "extension() after close must fail");
    }

    @Test
    void preDestroyCanStillLookUpServices() {
        // close() runs @PreDestroy before sealing the container, so cleanup
        // code may resolve other services instead of hitting "Container is closed".
        containerRef = Freeway.create(binder -> {
            binder.bind(OtherService.class).to(OtherServiceImpl.class);
            binder.bind(CleanupService.class).to(CleanupService.class);
        });
        preDestroyAccessed = false;
        containerRef.get(CleanupService.class); // realize so PreDestroy fires on close
        containerRef.close();
        assertTrue(preDestroyAccessed,
            "@PreDestroy must be able to look services up during close");
    }

    @Test
    void threadScopedValueCleanedUpAfterContainerClose() {
        // A THREAD-scope value realized inside an open scope, then the
        // container closes while the scope is still open: scope exit must
        // still run lifecycle cleanup — closing the container must not
        // unregister the value.
        ScopeValue.destroyed = 0;
        Container container = Freeway.create(binder ->
            binder.bind(ScopeValue.class).to(ScopeValue.class).scope(Scope.THREAD));
        Scoping scoping = container.get(Scoping.class);

        scoping.within(() -> {
            container.get(ScopeValue.class); // realize: container-managed
            container.close();               // close while the scope is open
            return null;
        });

        assertEquals(1, ScopeValue.destroyed,
            "scope exit must clean the value up even after container close");
    }

    static class ScopeValue {
        static int destroyed = 0;

        @com.jujin.freeway.ioc.annotation.PreDestroy
        void destroy() {
            destroyed++;
        }
    }

    private static Container containerRef;
    private static boolean preDestroyAccessed;

    interface Greeter {
        String greet();
    }

    interface OtherService {
    }

    static class OtherServiceImpl implements OtherService {
    }

    static class CleanupService {
        @com.jujin.freeway.ioc.annotation.PreDestroy
        void cleanup() {
            containerRef.get(OtherService.class); // must not throw "Container is closed"
            preDestroyAccessed = true;
        }
    }

    static class GreeterImpl implements Greeter {
        @Override
        public String greet() {
            return "hi";
        }
    }
}
