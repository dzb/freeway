package com.jujin.freeway.ioc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.PreDestroy;
import org.junit.jupiter.api.Test;

/**
 * Contribution instances are constructed outside ServiceRuntime's target cache.
 * They must still use the one container shutdown drain: @PreDestroy can resolve,
 * failures surface, and an instance shared with a binding is closed once.
 */
class ContributionShutdownTest {

    interface Ability {}

    static final class Dependency {}

    static final class Tracked implements Ability, AutoCloseable {
        static int preDestroyed;
        static int closed;
        @Inject Dependency dependency;

        @PreDestroy
        void destroy() {
            assertNotNull(dependency, "contribution @PreDestroy must run while the container can resolve");
            preDestroyed++;
        }

        @Override
        public void close() {
            closed++;
        }
    }

    static final class Shared implements Ability, AutoCloseable {
        static int preDestroyed;
        static int closed;
        @PreDestroy void destroy() { preDestroyed++; }
        @Override public void close() { closed++; }
    }

    static final class Failing implements Ability, AutoCloseable {
        @Override public void close() { throw new IllegalStateException("boom"); }
    }

    @Test
    void contributionRunsPreDestroyAndCloseOnce() {
        Tracked.preDestroyed = 0;
        Tracked.closed = 0;
        try (Container container = Freeway.create(
                binder -> {
                    binder.bind(Dependency.class).to(Dependency.class);
                    binder.contribute(Ability.class).addDeferred(Tracked.class);
                })) {
            assertEquals(1, container.extension(Ability.class).all().size());
        }
        assertEquals(1, Tracked.preDestroyed);
        assertEquals(1, Tracked.closed);
    }

    @Test
    void contributionSharedWithBindingIsStillClosedOnce() {
        Shared.preDestroyed = 0;
        Shared.closed = 0;
        Shared shared = new Shared();
        try (Container container = Freeway.create(
                binder -> {
                    binder.bind(Shared.class).to(c -> shared);
                    binder.contribute(Ability.class).addDeferred("shared", c -> shared);
                })) {
            // Realize the concrete binding too: both the service cache and the
            // contribution list now hold the same identity.
            assertEquals(shared, container.get(Shared.class));
            assertEquals(1, container.extension(Ability.class).all().size());
        }
        assertEquals(1, Shared.preDestroyed, "the identity drain must not run @PreDestroy twice");
        assertEquals(1, Shared.closed, "the identity drain must not close twice");
    }

    @Test
    void contributionCloseFailureIsReportedByContainerClose() {
        Container container = Freeway.create(
            binder -> binder.contribute(Ability.class).addDeferred(Failing.class));
        assertThrows(RuntimeException.class, container::close,
            "a failing contribution close must not be silently swallowed");
    }
}
