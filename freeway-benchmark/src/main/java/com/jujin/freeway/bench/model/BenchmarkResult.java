package com.jujin.freeway.bench.model;

import com.jujin.freeway.db.schema.Column;
import com.jujin.freeway.db.schema.Generated;
import com.jujin.freeway.db.schema.Id;
import com.jujin.freeway.db.schema.Table;

/** An individual benchmark measurement within a run session. */
@Table("bench_results")
public record BenchmarkResult(
    @Id @Generated long id,
    @Column("run_id") long runId,
    @Column("benchmark") String benchmark,
    @Column("mode") String mode,
    @Column("score") double score,
    @Column("score_error") double scoreError,
    @Column("unit") String unit,
    @Column("p50_us") long p50us,
    @Column("p95_us") long p95us,
    @Column("p99_us") long p99us,
    @Column("errors") int errors
) {
    public static BenchmarkResult of(long runId, String benchmark, String mode,
                                      double score, double scoreError, String unit,
                                      long p50us, long p95us, long p99us, int errors) {
        return new BenchmarkResult(0, runId, benchmark, mode, score, scoreError,
            unit, p50us, p95us, p99us, errors);
    }
}
