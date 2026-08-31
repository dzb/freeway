package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.metrics.Metrics;
import java.util.concurrent.atomic.LongAdder;

/**
 * Internal counters for {@link com.jujin.freeway.ioc.CallBus}.
 */
public final class CallStats {

    private final Metrics.Counter cCalled;
    private final Metrics.Counter cServed;
    private final Metrics.Counter cFailed;
    private final Metrics.Counter cDead;
    private final LongAdder called = new LongAdder();
    private final LongAdder served = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder dead = new LongAdder();

    public CallStats(Metrics metrics) {
        this.cCalled = metrics.counter("callbus.called");
        this.cServed = metrics.counter("callbus.served");
        this.cFailed = metrics.counter("callbus.failed");
        this.cDead = metrics.counter("callbus.dead");
    }

    public void called() {
        called.increment();
        cCalled.increment();
    }

    public void served() {
        served.increment();
        cServed.increment();
    }

    public void failed() {
        failed.increment();
        cFailed.increment();
    }

    public void dead() {
        dead.increment();
        cDead.increment();
    }

    public long calledCount() {
        return called.sum();
    }

    public long servedCount() {
        return served.sum();
    }

    public long failedCount() {
        return failed.sum();
    }

    public long deadCount() {
        return dead.sum();
    }
}
