package com.jujin.freeway.bench.db;

import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.db.schema.SchemaEntity;

/** Schema entity registration for benchmark tables. */
public final class BenchSchema {

    private BenchSchema() {}

    /** Returns the schema entity group for all benchmark tables. */
    public static SchemaEntity all() {
        return SchemaEntity.of("bench", BenchmarkRun.class, BenchmarkResult.class);
    }
}
