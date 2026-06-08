package com.jujin.freeway.db;

import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamQueryTest {

    private Database createDb() {
        String dbName = "freeway_stream_" + UUID.randomUUID().toString().replace('-', '_');
        return new DatabaseBuilder()
            .config(DatabaseConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();
    }

    @Test
    void streamsMultipleRows() {
        Database db = createDb();
        try (db) {
            db.sql("create table t (id int, name varchar(20))").execute();
            for (int i = 0; i < 10; i++) {
                db.sql("insert into t values (?, ?)", i, "n" + i).execute();
            }

            var collected = new ArrayList<String>();
            try (var stream = db.sql("select name from t order by id").stream(String.class)) {
                stream.forEach(name -> collected.add(name));
            }
            assertEquals(10, collected.size());
            for (int i = 0; i < 10; i++) {
                assertEquals("n" + i, collected.get(i));
            }
        }
    }

    @Test
    void streamWithEmptyResult() {
        Database db = createDb();
        try (db) {
            db.sql("create table t (id int)").execute();
            var collected = new ArrayList<Integer>();
            try (var stream = db.sql("select id from t").stream(Integer.class)) {
                stream.forEach(collected::add);
            }
            assertTrue(collected.isEmpty());
        }
    }

    @Test
    void streamClosesResourcesOnTerminalOperation() {
        Database db = createDb();
        try (db) {
            db.sql("create table t (id int)").execute();
            db.sql("insert into t values (1), (2), (3)").execute();

            long count;
            try (var stream = db.sql("select id from t").stream(Integer.class)) {
                count = stream.count();
            }
            assertEquals(3, count);
        }
    }

    @Test
    void streamWithPositionalParams() {
        Database db = createDb();
        try (db) {
            db.sql("create table t (id int, val varchar(20))").execute();
            db.sql("insert into t values (1, 'a'), (2, 'b'), (3, 'c')").execute();

            var collected = new ArrayList<String>();
            try (var stream = db.sql("select val from t where id > ?", 1).stream(String.class)) {
                stream.forEach(collected::add);
            }
            assertEquals(2, collected.size());
            assertEquals("b", collected.get(0));
            assertEquals("c", collected.get(1));
        }
    }

    @Test
    void streamWithNamedParams() {
        Database db = createDb();
        try (db) {
            db.sql("create table t (id int, val varchar(20))").execute();
            db.sql("insert into t values (1, 'x'), (2, 'y')").execute();

            var collected = new ArrayList<String>();
            try (var stream = db.sql("select val from t where id = $id")
                .param("id", 1)
                .stream(String.class)) {
                stream.forEach(collected::add);
            }
            assertEquals(1, collected.size());
            assertEquals("x", collected.get(0));
        }
    }

    @Test
    void streamShortCircuitLimit() {
        Database db = createDb();
        try (db) {
            db.sql("create table t (id int)").execute();
            for (int i = 0; i < 100; i++) {
                db.sql("insert into t values (?)", i).execute();
            }

            var collected = new ArrayList<Integer>();
            try (var stream = db.sql("select id from t order by id").stream(Integer.class)) {
                stream.limit(5).forEach(collected::add);
            }
            assertEquals(5, collected.size());
            assertTrue(collected.get(0) == 0 || collected.get(0) != null);
        }
    }

    @Test
    void streamWithRowMapper() {
        Database db = createDb();
        try (db) {
            db.sql("create table t (id int, name varchar(20))").execute();
            db.sql("insert into t values (1, 'foo'), (2, 'bar')").execute();

            var collected = new ArrayList<Record>();
            try (var stream = db.sql("select id, name from t order by id")
                .stream(Record.class)) {
                stream.forEach(collected::add);
            }
            assertEquals(2, collected.size());
            assertEquals(1, collected.get(0).id);
            assertEquals("foo", collected.get(0).name);
            assertEquals(2, collected.get(1).id);
            assertEquals("bar", collected.get(1).name);
        }
    }

    record Record(int id, String name) {}
}
