package com.jujin.freeway.ioc.internal;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Internal executor management for {@link com.jujin.freeway.ioc.EventBus}.
 */
public final class EventExecutorSupport {

    private final BooleanSupplier requireOpen;
    private volatile Executor asyncExecutor;
    private volatile ExecutorService defaultAsyncExecutor;
    private volatile ExecutorService orderedExecutor;

    public EventExecutorSupport(BooleanSupplier requireOpen) {
        this.requireOpen = Objects.requireNonNull(requireOpen, "requireOpen");
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
                requireOpen.getAsBoolean();
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
                requireOpen.getAsBoolean();
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
