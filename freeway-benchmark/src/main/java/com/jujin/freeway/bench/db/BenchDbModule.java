package com.jujin.freeway.bench.db;

import com.jujin.freeway.db.DbModule;
import com.jujin.freeway.db.schema.SchemaEntity;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module2;

/**
 * Configures SQLite database for benchmark result persistence.
 *
 * <p>Sets JDBC URL to {@code jdbc:sqlite:bench.db} (created in the working directory).
 * Registers the benchmark schema entities for auto-table-creation at startup.
 */
public final class BenchDbModule implements Module2 {

    @Override
    public void bind(Binder binder) {
        // Install DbModule with SQLite config
        binder.install(new DbModule());

        // Register schema entities for auto-creation
        binder.contribute(SchemaEntity.class)
            .add(BenchSchema.all());
    }
}
