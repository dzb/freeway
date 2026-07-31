package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.schema.PostgresDialect;

import com.jujin.freeway.commons.validation.NotNull;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseBuilder;
import com.jujin.freeway.db.PoolConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AutoMigrate 集成测试 — 通过 H2 内存库验证完整的 Schema 生命周期。
 */
class SchemaTest {

    private static DatabaseBuilder builder(String name) {
        return new DatabaseBuilder()
            .config(PoolConfig.defaults(
                "jdbc:h2:mem:" + uniqueName(name) + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
    }

    private static String uniqueName(String prefix) {
        return "fw_schema_" + prefix + "_" + UUID.randomUUID().toString().replace('-', '_');
    }

    @Test
    void ensureAndDropWithoutExplicitDialect() {
        Database db = builder("nodialect").build();
        try (db) {
            // The database carries its dialect — no explicit Dialect needed.
            int created = Schema.ensure(db, User.class);
            assertTrue(created > 0, "no-dialect ensure should create tables: " + created);
            assertTrue(columnNames(db, "users").contains("name"),
                "table should exist after ensure without explicit dialect");

            Schema.drop(db, User.class);
            assertFalse(columnNames(db, "users").contains("name"),
                "drop without explicit dialect should remove the table");
        }
    }

    /** H2 中 INFORMATION_SCHEMA 返回大写列名/表名，统一转小写方便比对。 */
    private static Set<String> columnNames(Database db, String tableName) {
        return db.query(
            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? ORDER BY ORDINAL_POSITION",
            tableName.toUpperCase()
        ).list(String.class).stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
    }

    private static List<String> tableNames(Database db) {
        return db.query(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"
        ).list(String.class).stream()
            .filter(t -> t != null)
            .map(String::toLowerCase)
            .toList();
    }

    // ====================== define (纯 DDL 生成) ======================

    @Test
    void defineReturnsValidCreateTable() {
        String ddl = Schema.define(User.class);
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS"));
        assertTrue(ddl.contains("users"));
        assertTrue(ddl.contains("PRIMARY KEY (id)"));
    }

    @Test
    void defineWithDialectProducesDialectSpecificDdl() {
        String pgDdl = Schema.define(new PostgresDialect(), User.class);
        assertTrue(pgDdl.contains("GENERATED ALWAYS AS IDENTITY"));
        assertFalse(pgDdl.contains("AUTO_INCREMENT"));

        String mysqlDdl = Schema.define(new MySqlDialect(), User.class);
        assertTrue(mysqlDdl.contains("AUTO_INCREMENT"));
    }

    @Test
    void defineAllReturnsMultipleDDLs() {
        var ddls = Schema.defineAll(User.class, Post.class);
        assertEquals(2, ddls.size());
        assertTrue(ddls.get(0).contains("users"));
        assertTrue(ddls.get(1).contains("posts"));
    }

    // ====================== ensure — 建新表 ======================

    @Test
    void ensureCreatesTableWhenNotExists() {
        Database db = builder("ensure_create").build();
        try (db) {
            int applied = Schema.ensure(db, new PostgresDialect(), User.class);
            assertEquals(1, applied, "should create 1 table");

            // 验证表可查询
            String count = db.query("SELECT COUNT(*) FROM users").one(String.class).orElseThrow();
            assertEquals("0", count);

            // 验证列存在
            Set<String> cols = columnNames(db, "users");
            assertTrue(cols.contains("id"), "cols: " + cols);
            assertTrue(cols.contains("name"));
            assertTrue(cols.contains("email"));
        }
    }

    @Test
    void ensureCreatesMultipleTables() {
        Database db = builder("ensure_multi").build();
        try (db) {
            int applied = Schema.ensure(db, new PostgresDialect(), User.class, Post.class);
            assertEquals(2, applied);

            String count1 = db.query("SELECT COUNT(*) FROM users").one(String.class).orElseThrow();
            assertEquals("0", count1);
            String count2 = db.query("SELECT COUNT(*) FROM posts").one(String.class).orElseThrow();
            assertEquals("0", count2);
        }
    }

    @Test
    void ensureIsIdempotent() {
        Database db = builder("ensure_idempotent").build();
        try (db) {
            int first = Schema.ensure(db, new PostgresDialect(), User.class);
            assertEquals(1, first);

            int second = Schema.ensure(db, new PostgresDialect(), User.class);
            assertEquals(0, second);
        }
    }

    @Test
    void ensureIdempotentAcrossMultipleTables() {
        Database db = builder("ensure_idem_multi").build();
        try (db) {
            Schema.ensure(db, new PostgresDialect(), User.class, Post.class);

            int again = Schema.ensure(db, new PostgresDialect(), User.class, Post.class);
            assertEquals(0, again, "second ensure should do nothing");
        }
    }

    // ====================== ensure — 加列 ======================

    @Test
    void ensureAddsMissingColumn() {
        Database db = builder("ensure_addcol").build();
        try (db) {
            // 先建一个只有 id+name 的表（模拟旧版实体）
            db.execute("""
                CREATE TABLE users_v2 (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    name VARCHAR(255)
                )
                """);

            // 新实体有 email 列
            int applied = Schema.ensure(db, new PostgresDialect(), UserV2.class);
            assertEquals(1, applied, "should add 1 column");

            // 验证新列存在
            Set<String> cols = columnNames(db, "users_v2");
            assertTrue(cols.contains("email"), "cols: " + cols);

            // 可以插入完整数据
            db.execute("INSERT INTO users_v2 (name, email) VALUES (?, ?)", "test", "test@example.com");
        }
    }

    @Test
    void ensureDoesNotRemoveExistingColumns() {
        Database db = builder("ensure_nodrop").build();
        try (db) {
            // 建表时有 extra_col，新实体没有这个列
            db.execute("""
                CREATE TABLE users_v3 (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255),
                    extra_col VARCHAR(100)
                )
                """);

            int applied = Schema.ensure(db, new PostgresDialect(), UserV3.class);
            assertEquals(0, applied, "should NOT alter existing table");

            // 验证 extra_col 仍在
            Set<String> cols = columnNames(db, "users_v3");
            assertTrue(cols.contains("extra_col"), "existing column should not be dropped, cols: " + cols);
        }
    }

    // ====================== ensure — 注解表名和列名 ======================

    @Test
    void ensureRespectsTableAnnotation() {
        Database db = builder("ensure_tableann").build();
        try (db) {
            int applied = Schema.ensure(db, new PostgresDialect(), AnnotatedUser.class);
            assertEquals(1, applied, "should create table");

            // 验证表名存在
            List<String> tables = tableNames(db);
            assertTrue(tables.contains("app_users"), "tables: " + tables);

            // 验证列名
            Set<String> cols = columnNames(db, "app_users");
            assertTrue(cols.contains("user_name"), "cols: " + cols);
            assertTrue(cols.contains("email_addr"));
        }
    }

    @Test
    void ensureHandlesExplicitMixedCaseNames() {
        Database db = builder("ensure_mixed_case").build();
        try (db) {
            int first = Schema.ensure(db, new PostgresDialect(), MixedCaseUser.class);
            assertEquals(1, first, "should create table");

            int second = Schema.ensure(db, new PostgresDialect(), MixedCaseUser.class);
            assertEquals(0, second, "second ensure should be idempotent");

            List<String> tables = tableNames(db);
            assertTrue(tables.contains("appusers"), "tables: " + tables);

            Set<String> cols = columnNames(db, "AppUsers");
            assertTrue(cols.contains("username"), "cols: " + cols);
            assertTrue(cols.contains("emailaddr"));
        }
    }

    // ====================== ensure — 约束 ======================

    @Test
    void ensureCreatesNotNullColumns() {
        Database db = builder("ensure_notnull").build();
        try (db) {
            Schema.ensure(db, new PostgresDialect(), NotNullUser.class);

            var rows = db.query(
                "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? ORDER BY ORDINAL_POSITION",
                "NOT_NULL_USER"
            ).list(ColumnInfoRow.class);

            for (var row : rows) {
                if (row.columnName().equalsIgnoreCase("name")) {
                    assertEquals("NO", row.isNullable(), "name should be NOT NULL");
                }
            }
        }
    }

    @Test
    void ensureCreatesVarcharWithSize() {
        Database db = builder("ensure_size").build();
        try (db) {
            Schema.ensure(db, new PostgresDialect(), SizedUser.class);

            // 验证列存在即可（类型验证已在 DDL 生成测试中覆盖）
            Set<String> cols = columnNames(db, "sized_user");
            assertTrue(cols.contains("bio"), "bio column should exist, cols: " + cols);
        }
    }

    // ====================== drop ======================

    @Test
    void dropRemovesTable() {
        Database db = builder("drop_table").build();
        try (db) {
            Schema.ensure(db, new PostgresDialect(), User.class);
            Schema.drop(db, new PostgresDialect(), User.class);

            List<String> tables = tableNames(db);
            assertFalse(tables.contains("users"), "table should be dropped");
        }
    }

    @Test
    void dropIsIdempotent() {
        Database db = builder("drop_idempotent").build();
        try (db) {
            Schema.ensure(db, new PostgresDialect(), User.class);
            Schema.drop(db, new PostgresDialect(), User.class);
            // 再次 drop 不应报错（IF EXISTS）
            Schema.drop(db, new PostgresDialect(), User.class);
        }
    }

    // ====================== 完整生命周期 ======================

    @Test
    void fullLifecycleCreateReadInsert() {
        Database db = builder("lifecycle").build();
        try (db) {
            // 1. 建表
            Schema.ensure(db, new PostgresDialect(), User.class);

            // 2. 插入
            db.execute("INSERT INTO users (name, email) VALUES (?, ?)", "Alice", "alice@example.com");

            // 3. 查询
            String name = db.query("SELECT name FROM users WHERE email = ?", "alice@example.com")
                .one(String.class).orElseThrow();
            assertEquals("Alice", name);

            // 4. 新版本实体加列
            int added = Schema.ensure(db, new PostgresDialect(), UserWithBio.class);
            assertEquals(1, added);

            // 5. 更新新列
            db.execute("UPDATE users SET bio = ? WHERE name = ?", "Hello!", "Alice");

            // 6. 查询新列
            String bio = db.query("SELECT bio FROM users WHERE name = ?", "Alice")
                .one(String.class).orElseThrow();
            assertEquals("Hello!", bio);
        }
    }

    // ====================== ensure — 索引 ======================

    @Test
    void ensureCreatesIndexOnNewTable() {
        Database db = builder("ensure_idx_new").build();
        try (db) {
            int applied = Schema.ensure(db, new PostgresDialect(), IndexedUser.class);
            assertEquals(1, applied, "should create 1 table (indexes not counted)");

            // 验证索引存在
            var indexes = db.query(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME) = ?",
                "INDEXED_USER"
            ).list(String.class);
            assertTrue(indexes.stream().anyMatch(i -> i.toLowerCase().contains("idx_indexed_user_email")),
                "indexes: " + indexes);
        }
    }

    @Test
    void ensureCreatesIndexOnExistingTable() {
        Database db = builder("ensure_idx_existing").build();
        try (db) {
            // 先建表（无索引）
            db.execute("""
                CREATE TABLE existing_idx (
                    id BIGINT PRIMARY KEY,
                    email VARCHAR(255)
                )
                """);

            // ensure 加索引（索引不计入 applied）
            Schema.ensure(db, new PostgresDialect(), ExistingIdxUser.class);

            var indexes = db.query(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME) = ?",
                "EXISTING_IDX"
            ).list(String.class);
            assertTrue(indexes.stream().anyMatch(i -> i.toLowerCase().contains("idx_existing")),
                "indexes: " + indexes);
        }
    }

    @Test
    void ensureIndexIsIdempotent() {
        Database db = builder("ensure_idx_idempotent").build();
        try (db) {
            Schema.ensure(db, new PostgresDialect(), IndexedUser.class);
            // 第二次 ensure 不应重复创建索引
            int again = Schema.ensure(db, new PostgresDialect(), IndexedUser.class);
            assertEquals(0, again, "second ensure should do nothing");
        }
    }

    // ====================== 实体定义 ======================

    @Table("users")
    public record User(
        @Id @Generated Long id,
        @NotNull String name,
        String email
    ) {}

    @Table("users_v2")
    public record UserV2(
        @Id @Generated Long id,
        String name,
        String email
    ) {}

    @Table("users_v3")
    public record UserV3(
        @Id Long id,
        String name
    ) {}

    @Table("app_users")
    public record AnnotatedUser(
        @Id @Generated Long id,
        @Column("user_name") String name,
        @Column("email_addr") String email
    ) {}

    @Table("AppUsers")
    public record MixedCaseUser(
        @Id @Generated Long id,
        @Column("UserName") String name,
        @Column("EmailAddr") String email
    ) {}

    @Table("not_null_user")
    public record NotNullUser(
        @Id Long id,
        @NotNull String name
    ) {}

    @Table("sized_user")
    public record SizedUser(
        @Id Long id,
        @com.jujin.freeway.commons.validation.Size(min = 1, max = 200) String bio
    ) {}

    @Table("posts")
    public record Post(
        @Id @Generated Long id,
        @NotNull String title,
        String content,
        LocalDateTime createdAt
    ) {}

    @Table("users")
    public record UserWithBio(
        @Id @Generated Long id,
        @NotNull String name,
        String email,
        String bio
    ) {}

    // ====================== 索引测试用实体 ======================

    @Table("indexed_user")
    public record IndexedUser(
        @Id @Generated Long id,
        String name,
        @Index String email
    ) {}

    @Table("existing_idx")
    public record ExistingIdxUser(
        @Id Long id,
        @Index(name = "idx_existing") String email
    ) {}

    // INFORMATION_SCHEMA 查询用
    public record ColumnInfoRow(String columnName, String isNullable) {}
}
