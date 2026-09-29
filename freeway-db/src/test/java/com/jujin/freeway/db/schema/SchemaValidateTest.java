package com.jujin.freeway.db.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.PoolConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link Schema#validate} mirrors {@link Schema#ensure} without applying:
 * empty where ensure converges, drift lines naming the entity wherever the
 * database lags behind it.
 */
class SchemaValidateTest {

    @Table("vdoc_users")
    public record VDocUser(
        @Id @Generated Long id,
        String name
    ) {}

    @Table("vdoc_users")
    public record VDocUserV2(
        @Id @Generated Long id,
        String name,
        String nickname
    ) {}

    @Table("vdoc_indexed")
    public record VDocIndexed(
        @Id @Generated Long id,
        @Index String email
    ) {}

    @Table("vdoc_indexed")
    public record VDocIndexedPlain(
        @Id @Generated Long id,
        String email
    ) {}

    @Test
    void emptyDatabaseReportsMissingTableNamingEntity() {
        try (Database db = tempDb("freeway_validate_empty")) {
            List<String> drift = Schema.validate(db, VDocUser.class);

            assertEquals(1, drift.size(), drift.toString());
            assertTrue(drift.get(0).contains("vdoc_users"), drift.toString());
            assertTrue(drift.get(0).contains("VDocUser"), drift.toString());
        }
    }

    @Test
    void convergedDatabaseIsClean() {
        try (Database db = tempDb("freeway_validate_clean")) {
            Schema.ensure(db, VDocUser.class);

            assertTrue(Schema.validate(db, VDocUser.class).isEmpty());
        }
    }

    @Test
    void addedColumnIsReportedNamingTableAndColumn() {
        try (Database db = tempDb("freeway_validate_column")) {
            Schema.ensure(db, VDocUser.class);

            List<String> drift = Schema.validate(db, VDocUserV2.class);

            assertEquals(1, drift.size(), drift.toString());
            assertTrue(drift.get(0).contains("vdoc_users"), drift.toString());
            assertTrue(drift.get(0).contains("nickname"), drift.toString());
            assertTrue(drift.get(0).contains("VDocUserV2"), drift.toString());
        }
    }

    @Test
    void missingIndexIsReported() {
        // Plain H2 (no MODE=PostgreSQL): the Postgres-dialect index probe
        // (pg_indexes) cannot run on H2, and unknown state is never drift —
        // so this branch needs the H2 dialect's INFORMATION_SCHEMA probe.
        try (Database db = tempH2Db("freeway_validate_index")) {
            Schema.ensure(db, VDocIndexedPlain.class);

            List<String> drift = Schema.validate(db, VDocIndexed.class);

            assertEquals(1, drift.size(), drift.toString());
            assertTrue(drift.get(0).contains("vdoc_indexed"), drift.toString());
        }
    }

    @Test
    void noEntitiesMeansNoDrift() {
        try (Database db = tempDb("freeway_validate_none")) {
            assertTrue(Schema.validate(db).isEmpty());
        }
    }

    private static Database tempDb(String name) {
        String dbName = name + "_" + UUID.randomUUID().toString().replace('-', '_');
        return Database.create(PoolConfig.defaults(
            "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        ));
    }

    private static Database tempH2Db(String name) {
        String dbName = name + "_" + UUID.randomUUID().toString().replace('-', '_');
        return Database.create(PoolConfig.defaults(
            "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1",
            "sa",
            ""
        ));
    }
}
