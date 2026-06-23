package com.jujin.freeway.db;

/**
 * Functional interface for a unit of work executed inside a database transaction.
 *
 * <p>Used with {@link Database#transaction(Transactional)}:
 * <pre>{@code
 * db.transaction(() -> {
 *     db.execute("UPDATE ...");
 *     db.execute("INSERT ...");
 * });
 * }</pre>
 */
@FunctionalInterface
public interface Transactional {
    void run() throws Exception;
}
