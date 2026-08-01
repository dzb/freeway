package com.jujin.freeway.db;

import java.util.Objects;

/**
 * A named {@link Database} registration, used to contribute
 * databases to the {@link DatabaseHub}.
 *
 * @param name the logical database name
 * @param db   the database instance
 */
public record NamedDatabase(String name, Database db) {
    public NamedDatabase {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(db, "db");
    }
}
