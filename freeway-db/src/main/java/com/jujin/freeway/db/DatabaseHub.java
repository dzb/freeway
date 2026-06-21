package com.jujin.freeway.db;

import java.util.Map;

/**
 * Registry for multiple named {@link Database} instances.
 *
 * <p>Contributions are made via {@code binder.contribute(DatabaseNamed.class)} in IoC mode,
 * or via {@link DatabaseHubImpl} directly in standalone mode.
 */
public interface DatabaseHub {

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
