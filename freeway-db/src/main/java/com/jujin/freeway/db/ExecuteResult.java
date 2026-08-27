package com.jujin.freeway.db;

/**
 * Return value for {@link Database#execute(String, Object...)}.
 * Carries both the affected row count and the generated key (if any).
 *
 * <pre>{@code
 * long id = db.execute("INSERT INTO users (name) VALUES (?)", "john").longKey();
 * int rows = db.execute("UPDATE users SET status = ? WHERE id = ?", 1, id).rows();
 * }</pre>
 */
public record ExecuteResult(int rows, Object generatedKey) {

    /** Whether a generated key was returned. */
    public boolean hasGeneratedKey() {
        return generatedKey != null;
    }

    /** Convenience: the generated key as {@code long}, or 0 if no key was generated. */
    public long longKey() {
        if (generatedKey == null) return 0L;
        if (generatedKey instanceof Number n) return n.longValue();
        throw new IllegalStateException("Generated key is not numeric: " + generatedKey);
    }

    @Override
    public String toString() {
        if (generatedKey == null) return "ExecuteResult[rows=" + rows + "]";
        return "ExecuteResult[rows=" + rows + ", key=" + generatedKey + "]";
    }
}
