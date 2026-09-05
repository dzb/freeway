package com.jujin.freeway.db;

import com.jujin.freeway.db.internal.DatabaseHubImpl;
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
public interface DatabaseHub {

    /**
     * Creates a hub over the given named databases (standalone mode).
     */
    static DatabaseHub of(Map<String, Database> databases) {
        return new DatabaseHubImpl(databases);
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
     * Returns an unmodifiable view of all registered databases.
     */
    Map<String, Database> all();
}
