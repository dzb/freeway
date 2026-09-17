package com.jujin.freeway.db;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Enum parameters must bind in the wire form both ends of the module agree
 * on: the DDL maps enum properties to VARCHAR (SqlTypeMapping) and the read
 * side coerces via {@code Enum.valueOf}. Regression: the bind sites passed
 * the raw constant to {@code setObject}, so H2/PostgreSQL failed the insert
 * with a JAVA_OBJECT→VARCHAR conversion error — enums were half-supported
 * (readable, unwritable) with zero round-trip coverage.
 */
class EnumParameterTest {

    enum Status { ACTIVE, ARCHIVED }

    private static Database db() {
        return new DatabaseBuilder()
            .config(PoolConfig.defaults(
                "jdbc:h2:mem:enum_" + UUID.randomUUID().toString().replace('-', '_')
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", ""))
            .build();
    }

    @Test
    void enumRoundTripsThroughPositionalAndNamedParameters() {
        try (Database db = db()) {
            db.execute("create table t_status (id bigint primary key, status varchar(32))");

            db.execute("insert into t_status (id, status) values (?, ?)",
                1L, Status.ACTIVE);
            assertEquals(Status.ACTIVE,
                db.query("select status from t_status where id = ?", 1L)
                    .one(Status.class).orElseThrow());

            db.query("insert into t_status (id, status) values (:id, :s)")
                .param("id", 2L)
                .param("s", Status.ARCHIVED)
                .execute();
            assertEquals(Status.ARCHIVED,
                db.query("select status from t_status where id = :id")
                    .param("id", 2L)
                    .one(Status.class).orElseThrow());

            assertEquals(2,
                db.query("select status from t_status where status in (:s)")
                    .param("s", List.of(Status.ACTIVE, Status.ARCHIVED))
                    .list(Status.class).size());

            assertEquals(1, db.execute(
                "update t_status set status = ? where status = ?",
                Status.ARCHIVED, Status.ACTIVE).rows());
        }
    }

    @Test
    void enumRoundTripsThroughBatchRows() {
        try (Database db = db()) {
            db.execute("create table t_status (id bigint primary key, status varchar(32))");
            db.batch("insert into t_status (id, status) values (?, ?)")
                .rows(new Object[] { 1L, Status.ACTIVE },
                       new Object[] { 2L, Status.ARCHIVED })
                .execute();
            assertEquals(2, db.query("select id from t_status").list(Long.class).size());
            assertEquals(Status.ARCHIVED,
                db.query("select status from t_status where id = 2")
                    .one(Status.class).orElseThrow());

            db.batch("update t_status set status = :s where id = :id")
                .named(List.of(Map.of("id", 1L, "s", Status.ACTIVE)))
                .execute();
            assertEquals(Status.ACTIVE,
                db.query("select status from t_status where id = 1")
                    .one(Status.class).orElseThrow());
        }
    }
}
