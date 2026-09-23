package com.jujin.freeway.db;

import com.jujin.freeway.db.internal.DatabaseRegistryImpl;
import java.util.Map;

/**
 * Registry for multiple named {@link Database} instances.
 *
 * <p>Contributions are made via {@code binder.contribute(NamedDatabase.class)} in IoC mode,
 * or via {@link #of(Map)} in standalone mode.
 *
 * <p>Multi-database work is <b>not</b> XA / two-phase committed: each
 * {@link Database} manages its own connections and transactions
 * independently. Do not mix writes across multiple databases inside a
 * single-database transaction — work on the other databases commits on its
 * own and is not rolled back with the transaction.
 */
public interface DatabaseRegistry {

    /**
     * Creates a registry over the given named databases (standalone mode).
     */
    static DatabaseRegistry of(Map<String, Database> databases) {
        return new DatabaseRegistryImpl(databases);
    }

    /**
     * Returns the database registered under the given name.
     *
     * @param name the database name
     * @return the database, or null if not found
     */
    Database get(String name);

    /**
     * Returns the primary (default) database.
     */
    Database primary();

    /**
     * Returns the registered databases as they were when this registry was
     *  assembled — an immutable snapshot, not a live view: later contributions
     *  do not appear.
     */
    Map<String, Database> all();
}
