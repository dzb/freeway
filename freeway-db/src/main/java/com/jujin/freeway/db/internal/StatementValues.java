package com.jujin.freeway.db.internal;

/**
 * JDBC bind-value normalization, shared by every parameter site
 * (positional, named, expanded collections, batch rows).
 */
final class StatementValues {

    private StatementValues() {}

    /**
     * The value handed to {@code setObject}. An enum constant binds as its
     * {@link Enum#name()} — the DDL side maps enum properties to VARCHAR
     * ({@code SqlTypeMapping}) and the read side coerces VARCHAR back via
     * {@code Enum.valueOf}, so the name is the only wire form both ends
     * agree on. Passing the raw constant made drivers that reject unknown
     * JAVA_OBJECT values (H2, PostgreSQL) fail the INSERT.
     */
    static Object bindValue(Object value) {
        return value instanceof Enum<?> e ? e.name() : value;
    }
}
