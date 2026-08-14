package com.jujin.freeway.ioc;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.PreDestroy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void preDestroyCanPublishEventsDuringContainerClose() {
        // Regression: the container-managed EventBus used to be closed in an
        // unspecified order relative to other services — a @PreDestroy that
        // published events hit a closed bus, threw IllegalStateException, and
        // failed the whole shutdown. The bus is now closed only after every
        // lifecycle callback has run.
        var received = new CopyOnWriteArrayList<String>();
        containerRef = Freeway.create(binder ->
            binder.bind(EventPublishingCleanup.class).to(EventPublishingCleanup.class));
        containerRef.get(EventBus.class).subscribe(String.class, received::add);
        containerRef.get(EventPublishingCleanup.class); // realize so PreDestroy fires on close

        assertDoesNotThrow(containerRef::close,
            "close() must not fail when @PreDestroy publishes events");
        assertEquals(List.of("cleanup-event"), received,
            "the event published from @PreDestroy must be delivered while the bus is still open");
    }

    @Test
    void closeCallbackCanPublishEventsDuringContainerClose() {
        // The AutoCloseable drain runs after the @PreDestroy drain, and the
        // EventBus is one of those closeables — before the fix it could be
        // closed before another service's close() ran, so a close() that
        // published events threw IllegalStateException and failed shutdown.
        var received = new CopyOnWriteArrayList<String>();
        containerRef = Freeway.create(binder ->
            binder.bind(ClosingPublisher.class).to(ClosingPublisher.class));
        containerRef.get(EventBus.class).subscribe(String.class, received::add);
        containerRef.get(ClosingPublisher.class); // realize so close() fires on container close

        assertDoesNotThrow(containerRef::close,
            "close() must not fail when a service close() callback publishes events");
        assertEquals(List.of("closing-event"), received,
            "the event published from a close() callback must be delivered while the bus is still open");
    }

    @Test
    void servicesRealizedDuringDrainReceiveLifecycle() {
        // Regression: Shutdown snapshot the target cache ONCE before the
        // @PreDestroy phase, so a service realized inside a callback (the
        // documented drain pattern) was orphaned — its @PreDestroy/AutoCloseable
        // never ran and the instance leaked.
        drainRealized = false;
        drainClosed = false;
        containerRef = Freeway.create(binder -> {
            binder.bind(DrainService.class).to(DrainService.class);      // stays lazy
            binder.bind(DrainTrigger.class).to(DrainTrigger.class);
        });
        containerRef.get(DrainTrigger.class); // realize the trigger only
        containerRef.close();
        assertTrue(drainRealized,
            "a service realized inside @PreDestroy must receive its own @PreDestroy on close");
        assertTrue(drainClosed,
            "a service realized inside @PreDestroy must be closed on container close");
    }

    @Test
    void failingPreDestroyDoesNotBlockOtherServices() {
        // A throwing @PreDestroy must not prevent other services from
        // receiving their own @PreDestroy; the failure accumulates into the
        // close() exception.
        preDestroyFailureObserved = false;
        containerRef = Freeway.create(binder -> {
            binder.bind(FailingCleanup.class).to(FailingCleanup.class);
            binder.bind(ObservingCleanup.class).to(ObservingCleanup.class);
        });
        containerRef.get(FailingCleanup.class);
        containerRef.get(ObservingCleanup.class);

        RuntimeException ex = assertThrows(RuntimeException.class, containerRef::close);
        assertTrue(preDestroyFailureObserved,
            "the other service's @PreDestroy must still run after a failure");
        assertTrue(ex.getMessage().contains("Unable to invoke @PreDestroy"),
            "the failure must surface via the close() exception, got: " + ex.getMessage());
    }

    @Test
    void errorFromPreDestroyDoesNotAbortShutdown() {
        // Regression: drainPhase caught only Exception, so an Error
        // (AssertionError, ...) from one @PreDestroy escaped close() before
        // closed=true was set and the caches were cleared — a retried close()
        // re-ran @PreDestroy on already-destroyed singletons. The Error must
        // accumulate into the close() exception while the drain completes.
        preDestroyFailureObserved = false;
        containerRef = Freeway.create(binder -> {
            binder.bind(ErrorCleanup.class).to(ErrorCleanup.class);
            binder.bind(ObservingCleanup.class).to(ObservingCleanup.class);
        });
        containerRef.get(ErrorCleanup.class);
        containerRef.get(ObservingCleanup.class);

        RuntimeException ex = assertThrows(RuntimeException.class, containerRef::close,
            "close() must complete and report the failure, not be aborted by the Error");
        assertTrue(preDestroyFailureObserved,
            "the other service's @PreDestroy must still run after an Error");
        assertTrue(ex.getMessage().contains("Unable to invoke @PreDestroy"),
            "the Error must surface via the close() exception, got: " + ex.getMessage());

        // The container must now be sealed — a retried close is a no-op and
        // does NOT re-run @PreDestroy.
        assertDoesNotThrow(containerRef::close, "close() after an Error must still be idempotent");
        assertThrows(IllegalStateException.class, () -> containerRef.get(ObservingCleanup.class),
            "the container must be sealed even though a callback threw an Error");
    }

    @Test
    void errorFromAutoCloseableDoesNotAbortShutdown() {
        // Same contract for the AutoCloseable branch of the drain.
        preDestroyFailureObserved = false;
        autoCloseErrorObserved = false;
        containerRef = Freeway.create(binder -> {
            binder.bind(ErrorCloseable.class).to(ErrorCloseable.class);
            binder.bind(ObservingCleanup.class).to(ObservingCleanup.class);
        });
        containerRef.get(ErrorCloseable.class);
        containerRef.get(ObservingCleanup.class);

        RuntimeException ex = assertThrows(RuntimeException.class, containerRef::close);
        assertTrue(preDestroyFailureObserved,
            "the other service's @PreDestroy must still run after an AutoCloseable Error");
        assertTrue(autoCloseErrorObserved,
            "the failing closeable must still be attempted");
        assertTrue(ex.getMessage().contains("Unable to close container-managed resource"),
            "got: " + ex.getMessage());
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

        @PreDestroy
        void destroy() {
            destroyed++;
        }
    }

    @Test
    void concurrentCloseDoesNotOrphanSlowRealize() throws Exception {
        // Regression: close() drains lifecycle callbacks WITHOUT holding
        // REALIZE_LOCK (deliberately — user callbacks may join threads that
        // realize services), then clears the caches and seals. A realize()
        // that passed its first closed check and is blocked in a slow
        // constructor could insert a fresh singleton into targetCache AFTER
        // the drain's last snapshot — an instance that never receives
        // @PreDestroy. close() must re-drain under the lock after sealing so
        // such a target is cleaned up in place.
        raceDestroyed = 0;
        raceEntered = new CountDownLatch(1);
        raceRelease = new CountDownLatch(1);
        Container container = Freeway.create(binder ->
            binder.bind(SlowRaceService.class).to(SlowRaceService.class));

        AtomicReference<Throwable> realizeError = new AtomicReference<>();
        Thread realizing = new Thread(() -> {
            try {
                container.get(SlowRaceService.class);
            } catch (Throwable t) {
                realizeError.set(t);
            }
        });
        realizing.start();
        assertTrue(raceEntered.await(5, TimeUnit.SECONDS),
            "realize must enter the slow constructor");

        Thread closer = new Thread(container::close);
        closer.start();
        // Give close() time to finish its drain (nothing to drain yet) and
        // block on REALIZE_LOCK, so the constructor returns strictly after
        // the drain's last snapshot — the race window.
        Thread.sleep(200);
        raceRelease.countDown();

        closer.join(10_000);
        realizing.join(10_000);
        assertFalse(closer.isAlive(), "close() must complete");
        assertFalse(realizing.isAlive(), "the racing realize must complete");
        assertNull(realizeError.get(),
            "the racing realize must succeed (it started before close), got: " + realizeError.get());

        assertEquals(1, raceDestroyed,
            "a singleton realized during close must receive exactly one @PreDestroy — no orphan");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> container.get(SlowRaceService.class));
        assertTrue(ex.getMessage().contains("Container is closed"),
            "get() after close must report the sealed container, got: " + ex.getMessage());
    }

    static class SlowRaceService {
        SlowRaceService() {
            raceEntered.countDown();
            try {
                raceRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        @PreDestroy
        void destroy() {
            raceDestroyed++;
        }
    }

    private static Container containerRef;
    private static boolean preDestroyAccessed;
    private static boolean drainRealized;
    private static boolean drainClosed;
    private static boolean preDestroyFailureObserved;
    private static boolean autoCloseErrorObserved;
    private static CountDownLatch raceEntered;
    private static CountDownLatch raceRelease;
    private static int raceDestroyed;

    interface Greeter {
        String greet();
    }

    interface OtherService {
    }

    static class OtherServiceImpl implements OtherService {
    }

    static class CleanupService {
        @PreDestroy
        void cleanup() {
            containerRef.get(OtherService.class); // must not throw "Container is closed"
            preDestroyAccessed = true;
        }
    }

    static class EventPublishingCleanup {
        @Inject
        EventBus bus;

        @PreDestroy
        void cleanup() {
            bus.publish("cleanup-event");
        }
    }

    static class ClosingPublisher implements AutoCloseable {
        @Inject
        EventBus bus;

        @Override
        public void close() {
            bus.publish("closing-event");
        }
    }

    static class DrainTrigger {
        @PreDestroy
        void cleanup() {
            containerRef.get(DrainService.class); // realizes a NEW service during drain
        }
    }

    static class DrainService implements AutoCloseable {
        @PreDestroy
        void cleanup() {
            drainRealized = true;
        }

        @Override
        public void close() {
            drainClosed = true;
        }
    }

    static class FailingCleanup {
        @PreDestroy
        void cleanup() {
            throw new IllegalStateException("cleanup failed");
        }
    }

    static class ErrorCleanup {
        @PreDestroy
        void cleanup() {
            throw new AssertionError("cleanup blew up");
        }
    }

    static class ErrorCloseable implements AutoCloseable {
        @Override
        public void close() {
            autoCloseErrorObserved = true;
            throw new AssertionError("close blew up");
        }
    }

    static class ObservingCleanup {
        @PreDestroy
        void cleanup() {
            preDestroyFailureObserved = true;
        }
    }

    static class GreeterImpl implements Greeter {
        @Override
        public String greet() {
            return "hi";
        }
    }
}
