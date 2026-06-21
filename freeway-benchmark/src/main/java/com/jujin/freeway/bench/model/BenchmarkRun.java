package com.jujin.freeway.bench.model;

import com.jujin.freeway.db.schema.Column;
import com.jujin.freeway.db.schema.Generated;
import com.jujin.freeway.db.schema.Id;
import com.jujin.freeway.db.schema.Table;
import java.time.Instant;

/** A single benchmark run session. */
@Table("bench_runs")
public record BenchmarkRun(
    @Id @Generated long id,
    @Column("engine") String engine,
    @Column("scenario") String scenario,
    @Column("concurrency") int concurrency,
    @Column("requests") int requests,
    @Column("warmup") int warmup,
    @Column("runs") int runs,
    @Column("commit_sha") String commitSha,
    @Column("jdk_info") String jdkInfo,
    @Column("os_info") String osInfo,
    @Column("cpu_info") String cpuInfo,
    @Column("created_at") Instant createdAt
) {
    public static BenchmarkRun create(String engine, String scenario,
                                       int concurrency, int requests,
                                       int warmup, int runs) {
        return new BenchmarkRun(0, engine, scenario, concurrency, requests,
            warmup, runs, "", jvm(), os(), cpu(), Instant.now());
    }

    private static String jvm() {
        return System.getProperty("java.vm.name") + " "
            + System.getProperty("java.vm.version") + " "
            + System.getProperty("java.vm.vendor");
    }

    private static String os() {
        return System.getProperty("os.name") + " "
            + System.getProperty("os.version") + " "
            + System.getProperty("os.arch");
    }

    private static String cpu() {
        return "available=" + Runtime.getRuntime().availableProcessors();
    }
}
