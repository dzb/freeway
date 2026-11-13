package com.jujin.freeway.db.dialect;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.Orm;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.Sql;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.schema.Id;
import com.jujin.freeway.db.schema.Table;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Live H2 checks for the capabilities the dialect declares. These are
 * deliberately H2-mode specific: H2's compatibility modes change the parser
 * surface, not H2's own introspection/DDL/type semantics.
 */
class H2DialectLiveTest {

    @Table("h2_live")
    record Row(@Id Long id, String name) {}

    @Table("h2_live")
    record IdOnly(@Id Long id) {}

    @Test
    void mergeUpsertWorksInNativeAndPostgresModes() {
        for (H2Mode mode : List.of(H2Mode.NATIVE, H2Mode.POSTGRESQL, H2Mode.MYSQL)) {
            H2Dialect dialect = new H2Dialect(mode);
            try (Database db = h2(mode)) {
                db.execute("CREATE TABLE h2_live (id BIGINT PRIMARY KEY, name VARCHAR(32))");
                Orm orm = Orm.of(db);
                orm.save(new Row(1L, "first"));
                orm.save(new Row(1L, "second"));
                assertEquals("second", db.query("SELECT name FROM h2_live WHERE id = 1")
                    .one(String.class).orElse(null), mode.name());
            }
        }
    }

    @Test
    void postgresModeRejectsPreparedConflictSuffixes() {
        H2Dialect dialect = new H2Dialect(H2Mode.POSTGRESQL);
        try (Database db = h2(H2Mode.POSTGRESQL)) {
            db.execute("CREATE TABLE h2_live (id BIGINT PRIMARY KEY, name VARCHAR(32))");
            // H2 accepts ON CONFLICT only with literal VALUES; Freeway's
            // parameterized statements are rejected by H2, so the dialect
            // must not advertise this surface.
            assertThrows(SqlException.class, () -> Sql.insert("h2_live")
                .value("id", 1L).value("name", "a")
                .onConflict("id").doNothing().toSql(dialect));
            assertThrows(SqlException.class, () -> Sql.insert("h2_live")
                .value("id", 2L).value("name", "b")
                .onConflict("id").doUpdateSet("name").toSql(dialect));
        }
    }

    @Test
    void mysqlModeUsesOnDuplicateKey() {
        H2Dialect dialect = new H2Dialect(H2Mode.MYSQL);
        try (Database db = h2(H2Mode.MYSQL)) {
            db.execute("CREATE TABLE h2_live (id BIGINT PRIMARY KEY, name VARCHAR(32))");
            db.execute(Sql.insert("h2_live")
                .value("id", 1L).value("name", "a")
                .onConflict("id").doUpdateSet("name"));
            db.execute(Sql.insert("h2_live")
                .value("id", 1L).value("name", "b")
                .onConflict("id").doUpdateSet("name"));
            assertEquals("b", db.query("SELECT name FROM h2_live WHERE id = 1")
                .one(String.class).orElse(null));
        }
    }

    @Test
    void nativeModeRejectsConflictSuffix() {
        try (Database db = h2(H2Mode.NATIVE)) {
            assertThrows(SqlException.class, () -> Sql.insert("h2_live")
                .value("id", 1L).value("name", "a")
                .onConflict("id").doUpdateSet("name").toSql(db.dialect()));
        }
    }

    @Test
    void standardPaginationWorksInEveryMode() {
        for (H2Mode mode : List.of(H2Mode.NATIVE, H2Mode.POSTGRESQL, H2Mode.MYSQL,
                H2Mode.MARIADB, H2Mode.ORACLE, H2Mode.MSSQLSERVER, H2Mode.DB2,
                H2Mode.DERBY, H2Mode.HSQLDB, H2Mode.STRICT, H2Mode.LEGACY)) {
            H2Dialect dialect = new H2Dialect(mode);
            try (Database db = h2(mode)) {
                db.execute("CREATE TABLE h2_live (id BIGINT PRIMARY KEY)");
                db.execute("INSERT INTO h2_live VALUES (1)");
                db.execute("INSERT INTO h2_live VALUES (2)");
                db.execute("INSERT INTO h2_live VALUES (3)");
                assertEquals(List.of(2L), db.query(
                    "SELECT id FROM h2_live ORDER BY id " + dialect.limitOffsetClause(1, 1))
                    .list(Long.class), mode.name());
                assertEquals(List.of(2L, 3L), db.query(
                    "SELECT id FROM h2_live ORDER BY id " + dialect.offsetOnlyClause(1))
                    .list(Long.class), mode.name());
                assertEquals(List.of(2L), db.query(
                    Sql.select("id").from("h2_live").orderBy("id").limit(1).offset(1)
                        .toSql(dialect)).list(Long.class), mode.name());
                assertEquals(List.of(1L), db.query(
                    Sql.select("id").from("h2_live").orderBy("id").limit(1)
                        .toSql(dialect)).list(Long.class), mode.name());
                assertEquals(List.of(2L), Orm.of(db)
                    .findAll(IdOnly.class, new Orm.FindOptions("id ASC", 1, 1))
                    .stream().map(IdOnly::id).toList(), mode.name());
            }
        }
    }

    private static Database h2(H2Mode mode) {
        String name = "h2live_" + UUID.randomUUID().toString().replace('-', '_');
        String url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1"
            + (mode == H2Mode.NATIVE ? "" : ";MODE=" + mode.urlName());
        return Database.create(Database.Wiring.defaults(PoolConfig.defaults(url, "sa", ""))
            .withDialect(new H2Dialect(mode)));
    }
}
