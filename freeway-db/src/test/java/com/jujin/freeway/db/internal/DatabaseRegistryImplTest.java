package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.BatchQuery;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.ExecuteResult;
import com.jujin.freeway.db.IsolationLevel;
import com.jujin.freeway.db.NamedDatabase;
import com.jujin.freeway.db.Query;
import com.jujin.freeway.db.Transactional;
import com.jujin.freeway.db.dialect.Dialect;
import com.jujin.freeway.db.dialect.PostgresDialect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRegistryImplTest {

    @Test
    void duplicateNameFailsFast() {
        NamedDatabase first = new NamedDatabase("primary", new StubDb());
        NamedDatabase second = new NamedDatabase("primary", new StubDb());

        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> new DatabaseRegistryImpl(List.of(first, second))
        );
        assertTrue(ex.getMessage().contains("primary"),
            "message must name the duplicated database: " + ex.getMessage());
    }

    @Test
    void distinctNamesAreAllRegistered() {
        StubDb db = new StubDb();
        DatabaseRegistryImpl registry =
            new DatabaseRegistryImpl(List.of(new NamedDatabase("primary", db),
                new NamedDatabase("audit", db)));

        assertEquals(2, registry.all().size());
        assertEquals(db, registry.primary());
        assertEquals(db, registry.get("audit"));
    }

    /** Value object standing in for a Database — the registry only stores routes. */
    private static final class StubDb implements Database {

        @Override
        public Dialect dialect() {
            return new PostgresDialect();
        }

        @Override
        public Query query(String sql, Object... params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecuteResult execute(String sql, Object... params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchQuery batch(String sql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void transaction(Transactional work) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void transaction(IsolationLevel isolation, Transactional work) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean ping() {
            return false;
        }

        @Override
        public DatabaseStats stats() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }
}
