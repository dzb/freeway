package com.jujin.freeway.db;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jujin.freeway.commons.coercion.CoercerImpl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowTest {

    private static DatabaseBuilder builder(String name) {
        return new DatabaseBuilder()
            .config(PoolConfig.defaults(
                "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    // ===================== basic types =====================

    @Test
    void mapsAllBasicTypes() {
        var db = builder(uniqueDb("basic")).build();
        try (db) {
            db.execute("CREATE TABLE t (s VARCHAR(16), i INT, l BIGINT, d DOUBLE, b BOOLEAN, dec DECIMAL(10,2))");
            db.execute("INSERT INTO t VALUES ('hello', 42, 9999, 3.14, true, 123.45)");

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            assertEquals("hello", r.string("s"));
            assertEquals(42, r.integer("i"));
            assertEquals(9999L, r.get("l", Long.class));
            assertEquals(3.14, r.get("d", Double.class));
            assertTrue(r.booleanValue("b"));
            assertEquals(new BigDecimal("123.45"), r.decimal("dec"));
        }
    }

    @Test
    void nullableColumnsReturnNull() {
        var db = builder(uniqueDb("nullable")).build();
        try (db) {
            db.execute("CREATE TABLE t (id INT, name VARCHAR(16))");
            db.execute("INSERT INTO t VALUES (1, null)");

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            assertEquals(1, r.integer("id"));
            assertNull(r.string("name"));
            assertNull(r.integer("name")); // missing column returns null
        }
    }

    @Test
    void columnAccessIsCaseInsensitive() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", "alice");
        Row row = new Row(values, new CoercerImpl());

        assertEquals("alice", row.string("name"));
        assertEquals("alice", row.string("NAME"));
        assertEquals("alice", row.string("Name"));
        assertNull(row.string("missing"));
    }

    // ===================== date / time types =====================

    @Test
    void mapsTemporalTypes() {
        var db = builder(uniqueDb("temporal")).build();
        try (db) {
            db.execute("CREATE TABLE t (d DATE, dt TIMESTAMP, t TIME)");
            db.execute("INSERT INTO t VALUES ('2024-06-15', '2024-06-15 14:30:00', '14:30:00')");

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            assertEquals(LocalDate.of(2024, 6, 15), r.date("d"));
            assertEquals(LocalDateTime.of(2024, 6, 15, 14, 30, 0), r.dateTime("dt"));
            assertEquals(LocalTime.of(14, 30, 0), r.time("t"));
        }
    }

    @Test
    void mapsInstant() {
        var db = builder(uniqueDb("instant")).build();
        try (db) {
            db.execute("CREATE TABLE t (ts TIMESTAMP)");
            db.execute("INSERT INTO t VALUES ('2024-06-15 14:30:00')");

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            Instant inst = r.instant("ts");
            assertNotNull(inst);
        }
    }

    // ===================== uuid =====================

    @Test
    void mapsUuid() {
        var db = builder(uniqueDb("uuid")).build();
        try (db) {
            db.execute("CREATE TABLE t (id UUID)");
            UUID id = UUID.randomUUID();
            db.execute("INSERT INTO t VALUES (?)", id);

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            assertEquals(id, r.uuid("id"));
        }
    }

    // ===================== bigint (BigInteger) =====================

    @Test
    void mapsBigInteger() {
        var db = builder(uniqueDb("bigint")).build();
        try (db) {
            db.execute("CREATE TABLE t (val NUMERIC(38))");
            db.execute("INSERT INTO t VALUES (12345678901234567890)");

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            BigInteger bi = r.bigInt("val");
            assertNotNull(bi);
            assertEquals("12345678901234567890", bi.toString());
        }
    }

    // ===================== blob =====================

    @Test
    void mapsBlobToBytes() {
        var db = builder(uniqueDb("blob")).build();
        try (db) {
            db.execute("CREATE TABLE t (id INT, data BYTEA)");
            byte[] input = "binary data here".getBytes(StandardCharsets.UTF_8);
            db.execute("INSERT INTO t VALUES (1, ?)", (Object) input);

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            byte[] output = r.bytes("data");
            assertNotNull(output);
            assertArrayEquals(input, output);
        }
    }

    @Test
    void mapsBlobEmpty() {
        var db = builder(uniqueDb("blob_empty")).build();
        try (db) {
            db.execute("CREATE TABLE t (data BYTEA)");
            db.execute("INSERT INTO t VALUES (?)", (Object) new byte[0]);

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            byte[] output = r.bytes("data");
            assertNotNull(output);
            assertEquals(0, output.length);
        }
    }

    @Test
    void mapsBlobNull() {
        var db = builder(uniqueDb("blob_null")).build();
        try (db) {
            db.execute("CREATE TABLE t (id INT, data BYTEA)");
            db.execute("INSERT INTO t VALUES (1, null)");

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            assertNull(r.bytes("data"));
        }
    }

    // ===================== columns / raw =====================

    @Test
    void columnsReturnsAllColumnNames() {
        var db = builder(uniqueDb("cols")).build();
        try (db) {
            db.execute("CREATE TABLE t (a int, b varchar(8), c boolean)");
            db.execute("INSERT INTO t VALUES (1, 'x', true)");

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            Set<String> cols = r.columns();
            assertTrue(cols.contains("a"));
            assertTrue(cols.contains("b"));
            assertTrue(cols.contains("c"));
            assertEquals(3, cols.size());
        }
    }

    @Test
    void rawReturnsObject() {
        var db = builder(uniqueDb("raw")).build();
        try (db) {
            db.execute("CREATE TABLE t (val int)");
            db.execute("INSERT INTO t VALUES (42)");

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            assertEquals(42, r.raw("val"));
        }
    }

    @Test
    void columnAliasUsesLabel() {
        var db = builder(uniqueDb("alias")).build();
        try (db) {
            db.execute("CREATE TABLE t (original_name varchar(8))");
            db.execute("INSERT INTO t VALUES ('test')");

            Row r = db.query("SELECT original_name AS alias FROM t").one(Row.class).orElseThrow();

            assertEquals("test", r.string("alias"));
            assertNull(r.string("original_name"));
        }
    }

    // ===================== list =====================

    @Test
    void mapsMultipleRows() {
        var db = builder(uniqueDb("multi")).build();
        try (db) {
            db.execute("CREATE TABLE t (id int, label varchar(16))");
            db.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')");

            List<Row> rows = db.query("SELECT * from t order by id").list(Row.class);

            assertEquals(3, rows.size());
            assertEquals("a", rows.get(0).string("label"));
            assertEquals("b", rows.get(1).string("label"));
            assertEquals("c", rows.get(2).string("label"));
        }
    }

    @Test
    void emptyResult() {
        var db = builder(uniqueDb("empty")).build();
        try (db) {
            db.execute("CREATE TABLE t (id int)");

            List<Row> rows = db.query("SELECT * from t").list(Row.class);

            assertTrue(rows.isEmpty());
        }
    }

    @Test
    void oneReturnsEmptyOptional() {
        var db = builder(uniqueDb("none")).build();
        try (db) {
            db.execute("CREATE TABLE t (id int)");

            var result = db.query("SELECT * from t where id = 999").one(Row.class);

            assertTrue(result.isEmpty());
        }
    }

    // ===================== generic getter =====================

    @Test
    void genericGetterCoerces() {
        var db = builder(uniqueDb("generic")).build();
        try (db) {
            db.execute("CREATE TABLE t (id int)");
            db.execute("INSERT INTO t VALUES (100)");

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            assertEquals("100", r.get("id", String.class));
            assertEquals(Long.valueOf(100), r.get("id", Long.class));
            assertEquals(Double.valueOf(100.0), r.get("id", Double.class));
        }
    }

    // ===================== toString =====================

    @Test
    void toStringContainsValues() {
        var db = builder(uniqueDb("tostring")).build();
        try (db) {
            db.execute("CREATE TABLE t (id int, name varchar(8))");
            db.execute("INSERT INTO t VALUES (1, 'x')");

            Row r = db.query("SELECT * from t").one(Row.class).orElseThrow();

            String s = r.toString();
            assertTrue(s.contains("id"));
            assertTrue(s.contains("name"));
        }
    }

    // ===================== helper =====================

    private static String uniqueDb(String prefix) {
        return "freeway_row_" + prefix + "_" + UUID.randomUUID().toString().replace('-', '_');
    }
}
