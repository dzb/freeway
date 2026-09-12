package com.jujin.freeway.ioc;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Internal executor management for {@link com.jujin.freeway.ioc.EventBus}.
 */
final class EventExecutorSupport {

    private final Runnable ensureOpen;
    private volatile Executor asyncExecutor;
    private volatile ExecutorService defaultAsyncExecutor;
    private volatile ExecutorService orderedExecutor;

    public EventExecutorSupport(Runnable ensureOpen) {
        this.ensureOpen = Objects.requireNonNull(ensureOpen, "ensureOpen");
    }

    public void setAsyncExecutor(Executor executor) {
        this.asyncExecutor = Objects.requireNonNull(executor, "executor");
    }

    public Executor asyncExecutor() {
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

    public ExecutorService orderedExecutor() {
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

    public void close() {
        synchronized (this) {
            if (defaultAsyncExecutor != null) {
                try {
                    defaultAsyncExecutor.close();
                } catch (RuntimeException ignored) {
                    // best effort during shutdown
                }
                defaultAsyncExecutor = null;
            }
            if (orderedExecutor != null) {
                try {
                    orderedExecutor.close();
                } catch (RuntimeException ignored) {
                    // best effort during shutdown
                }
                orderedExecutor = null;
            }
        }
    }
}
