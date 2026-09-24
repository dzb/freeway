package com.jujin.freeway.ioc.event;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal executor management for {@link com.jujin.freeway.ioc.event.EventBus}.
 */
final class EventExecutorSupport {

    private static final Logger LOG = LoggerFactory.getLogger(EventExecutorSupport.class);

    /** Grace period for in-flight dispatch before shutdown is forced. */
    private static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 5_000;

    private final Runnable ensureOpen;
    private final long shutdownTimeoutMs;
    private volatile Executor asyncExecutor;
    private volatile ExecutorService defaultAsyncExecutor;
    private volatile ExecutorService orderedExecutor;

    EventExecutorSupport(Runnable ensureOpen) {
        this(ensureOpen, DEFAULT_SHUTDOWN_TIMEOUT_MS);
    }

    /** Tests shrink the timeout to keep close() fast. */
    EventExecutorSupport(Runnable ensureOpen, long shutdownTimeoutMs) {
        this.ensureOpen = Objects.requireNonNull(ensureOpen, "ensureOpen");
        this.shutdownTimeoutMs = shutdownTimeoutMs;
    }

    /**
     * Installs a custom async executor. The bus never closes a caller-supplied
     * executor — its lifecycle belongs to the installer; only the bus-created
     * defaults (virtual-thread async / ordered) are shut down on
     * {@link com.jujin.freeway.ioc.event.EventBus#close()}.
     */
    void setAsyncExecutor(Executor executor) {
        this.asyncExecutor = Objects.requireNonNull(executor, "executor");
    }

    Executor asyncExecutor() {
        Executor e = asyncExecutor;
        if (e != null) {
            return e;
        }
        ExecutorService d = defaultAsyncExecutor;
        if (d != null) {
            return d;
        }
        synchronized (this) {
            d = defaultAsyncExecutor;
            if (d == null) {
                ensureOpen.run();
                d = defaultAsyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
            }
            return d;
        }
    }

    ExecutorService orderedExecutor() {
        ExecutorService e = orderedExecutor;
        if (e != null) {
            return e;
        }
        synchronized (this) {
            e = orderedExecutor;
            if (e == null) {
                ensureOpen.run();
                e = orderedExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().factory()
                );
            }
            return e;
        }
    }

    /**
     * Shuts down the bus-created executors with a bounded wait. Never the
     * unbounded {@code ExecutorService.close()} — a hung subscriber must not
     * hang the whole container's shutdown (EventBus is closed last by
     * {@code internal.Shutdown}, after every {@code @PreDestroy}).
     * Caller-supplied executors are not touched (see setAsyncExecutor).
     *
     * <p>Only the detach runs under the monitor: the (up-to-timeout) wait
     * happens outside, so a concurrent {@link #asyncExecutor()} /
     * {@link #orderedExecutor()} never queues behind a shutdown — it fails
     * fast through {@code ensureOpen} (the bus is already closed by then).
     * Safe because {@code EventBus.close()} sets the closed flag before
     * calling in, and creation re-checks that flag under this lock.</p>
     */
    void close() {
        ExecutorService async;
        ExecutorService ordered;
        synchronized (this) {
            async = defaultAsyncExecutor;
            ordered = orderedExecutor;
            defaultAsyncExecutor = null;
            orderedExecutor = null;
        }
        closeBounded(async, "async");
        closeBounded(ordered, "ordered");
    }

    private void closeBounded(ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }
        try {
            executor.shutdown();
            if (!executor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                LOG.warn(
                    "EventBus {} executor still busy after {} ms — forcing shutdown;"
                        + " a blocked subscriber is holding dispatch",
                    name, shutdownTimeoutMs);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // best effort during shutdown
        }
    }
}
