package com.jujin.freeway.db;

import java.util.Objects;

/**
 * Keyed entry for {@link DatabaseRegistrations} contributions.
 */
public record DatabaseEntry(String name, Database db) {
    public DatabaseEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(db, "db");
    }
}
