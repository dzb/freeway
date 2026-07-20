package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * H2 native SQL dialect (non-PostgreSQL mode).
 *
 * <p>Extends {@link PostgresDialect} and overrides only the aspects that differ
 * in H2's native mode: binary type ({@code BINARY VARYING}), index introspection
 * (queries {@code INFORMATION_SCHEMA.INDEXES}), and H2-specific reserved words.
 *
 * <p>For H2 in PostgreSQL compatibility mode ({@code MODE=PostgreSQL}), use
 * {@link PostgresDialect} directly.
 */
public final class H2Dialect extends PostgresDialect {

    private static final Set<String> RESERVED = buildReserved(
        "dual", "rownum", "sysdate", "systimestamp", "array", "identity", "if",
        "cached", "memory", "generated", "always", "checkpoint", "shutdown",
        "analyze", "backup", "call", "compress", "script", "merge", "explain"
    );

    @Override
    public String dialectId() {
        return "h2";
    }

    @Override
    public String defaultBinaryType() {
        return "BINARY VARYING";
    }

    @Override
    public Set<String> existingIndexes(Database db, String tableName) {
        return querySet(db,
            "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME) = ? AND UPPER(TABLE_SCHEMA) = UPPER(?)",
            tableName.toUpperCase(Locale.ROOT), effectiveSchema());
    }

    @Override
    public Set<String> reservedWords() {
        return RESERVED;
    }

    private static Set<String> buildReserved(String... h2Specific) {
        Set<String> words = new HashSet<>(Dialect.COMMON_RESERVED);
        words.addAll(Set.of(h2Specific));
        return Set.copyOf(words);
    }
}
