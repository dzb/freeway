package com.jujin.freeway.bench.event;

import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.bench.model.BenchmarkResult;

/** EventBus events published during benchmark lifecycle. */
public sealed interface BenchEvent {

    /** Published when a benchmark run starts. */
    record RunStarted(BenchmarkRun run) implements BenchEvent {}

    /** Published when a single result is collected. */
    record ResultCollected(BenchmarkResult result) implements BenchEvent {}

    /** Published when a benchmark run completes. */
    record RunCompleted(long runId) implements BenchEvent {}
}
