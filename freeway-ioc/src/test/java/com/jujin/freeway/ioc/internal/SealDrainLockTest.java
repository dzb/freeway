package com.jujin.freeway.ioc.internal;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.annotation.PreDestroy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The realize lock must not be held while user lifecycle callbacks run.
 *
 * <p>{@link ContainerImpl#close()} drains in two passes — a main one, and a
 * final one for targets realized during it. The final pass ran inside {@code
 * synchronized (realizeLock)}, the very lock whose own documentation says it is
 * "deliberately NOT held across the container's lifecycle drain", because user
 * callbacks may join threads that themselves resolve services. A resolver that
 * passed {@code requireOpen()} before the closed flag went up then blocked on
 * the lock while the draining thread waited for it: nothing times either out.
 */
class SealDrainLockTest {

    @Test
    void theFinalDrainDoesNotHoldTheRealizeLock() throws Exception {
        Container container = Freeway.create(binder -> { });
        Object runtime = field(container, "serviceRuntime");
        Object lock = field(runtime, "realizeLock");

        Map<ServiceKey, Object> targets = new LinkedHashMap<>();
        Shutdown shutdown = new Shutdown(targets);
        AtomicBoolean heldDuringPreDestroy = new AtomicBoolean();
        AtomicBoolean heldDuringClose = new AtomicBoolean();

        targets.put(new ServiceKey(LocksProbe.class, "probe"),
            new LocksProbe(
                () -> heldDuringPreDestroy.set(Thread.holdsLock(lock)),
                () -> heldDuringClose.set(Thread.holdsLock(lock))));

        RuntimeException failure = seal((ServiceRuntime) runtime, container, shutdown);

        assertNull(failure, "the drain itself must succeed");
        assertFalse(heldDuringPreDestroy.get(),
            "@PreDestroy ran while the drain held the realize lock — a callback "
                + "that resolves a service deadlocks against its own lock, and "
                + "one that joins such a thread hangs");
        assertFalse(heldDuringClose.get(),
            "AutoCloseable.close() carries the same requirement as @PreDestroy");
    }

    /**
     * A target realized by a drain callback is closed before the container is
     * done with it, and that close callback may join a worker which resolves.
     *
     * <p>A contract test, not a regression guard: the realization happens inside
     * the {@code @PreDestroy} phase of the <em>main</em> drain, and the same
     * drain's close phase closes it, so this stays green whether or not
     * {@code ServiceRuntime.seal} runs a final pass of its own. The guards for
     * that pass live in {@code ContainerCloseTest}.
     */
    @Test
    void aTargetRealizedByADrainCallbackJoiningAWorkerDoesNotHang() {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean joined = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();

        try (Container container = Freeway.create(binder -> {
            binder.bind(RealizingOnDestroy.class);
            binder.bind(JoiningOnDestroy.class);
            binder.bind(Consumer.class).to(Service.class);
        })) {
            container.get(RealizingOnDestroy.class);

            Thread worker = new Thread(() -> {
                try {
                    container.get(Consumer.class).use();
                } catch (RuntimeException refused) {
                    // Refused because the container is closing: that is the
                    // contract. Blocking instead would be the defect.
                } finally {
                    finished.countDown();
                }
            }, "seal-drain-worker");

            RealizingOnDestroy.CALLBACK = () ->
                container.get(JoiningOnDestroy.class);   // realized during the drain
            JoiningOnDestroy.CLOSE = () -> {
                closed.set(true);
                worker.start();
                try {
                    joined.set(finished.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            };

            container.close();
        }

        assertTrue(closed.get(),
            "a target realized during the drain never got its close() — the final "
                + "pass is what reaches it");
        assertTrue(joined.get(),
            "a final-pass callback joined a worker that never resolved — the "
                + "drain must not be holding the lock it needs");
    }

    /**
     * Resolving during the <em>main</em> drain is the documented "look up
     * services during close" contract, and that pass never held the lock.
     */
    @Test
    void aCallbackResolvingAServiceIsNotRefused() {
        AtomicBoolean reached = new AtomicBoolean();
        try (Container container = Freeway.create(binder -> {
            binder.bind(ResolvingOnDestroy.class);
            binder.bind(Consumer.class).to(Service.class);
        })) {
            container.get(ResolvingOnDestroy.class);

            ResolvingOnDestroy.CALLBACK = () -> {
                container.get(Consumer.class).use();
                reached.set(true);
            };

            container.close();
        }
        assertTrue(reached.get(),
            "resolving during the main drain is the documented 'look up "
                + "services during close' contract and must keep working");
    }

    /** Drives {@code seal} the way {@code close()} does — flag, drain, caches —
     *  without an application shutdown around it. */
    private static RuntimeException seal(ServiceRuntime runtime, Container container, Shutdown shutdown)
        throws Exception {
        Field closed = ContainerImpl.class.getDeclaredField("closed");
        closed.setAccessible(true);
        return runtime.seal(
            () -> {
                try {
                    closed.set(container, Boolean.TRUE);
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException(ex);
                }
            },
            () -> shutdown.drainRemaining(null));
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    interface Consumer {
        void use();
    }

    public static final class Service implements Consumer {
        @Override
        public void use() {
            // Nothing to do — resolving it is the point.
        }
    }

    /** Reports whether the lock is held while each of its callbacks runs. */
    static final class LocksProbe implements AutoCloseable {
        private final Runnable preDestroy;
        private final Runnable close;

        LocksProbe(Runnable preDestroy, Runnable close) {
            this.preDestroy = preDestroy;
            this.close = close;
        }

        @PreDestroy
        void destroy() {
            preDestroy.run();
        }

        @Override
        public void close() {
            close.run();
        }
    }

    public static final class RealizingOnDestroy {
        static Runnable CALLBACK = () -> { };

        @PreDestroy
        void destroy() {
            CALLBACK.run();
        }
    }

    public static final class ResolvingOnDestroy {
        static Runnable CALLBACK = () -> { };

        @PreDestroy
        void destroy() {
            CALLBACK.run();
        }
    }

    public static final class JoiningOnDestroy implements AutoCloseable {
        static Runnable CLOSE = () -> { };

        @Override
        public void close() {
            CLOSE.run();
        }
    }
}
