package com.jujin.freeway.db;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.internal.RowMapperResolver;
import com.jujin.freeway.db.schema.Column;

import static org.junit.jupiter.api.Assertions.*;

class RowMapperTest {

    // ====================== Record 映射 ======================

    @Test
    void recordMappingWithExactColumnNames() {
        String dbName = uniqueDb("record_exact");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, name varchar(16) not null)");
            db.execute("insert into t values (1, 'hello')");

            ExactRecord result = db.query("select id, name from t").one(ExactRecord.class).orElseThrow();
            assertEquals(1L, result.id);
            assertEquals("hello", result.name);
        }
    }

    @Test
    void recordMappingWithSnakeCaseColumns() {
        String dbName = uniqueDb("record_snake");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (user_id bigint primary key, full_name varchar(16) not null)");
            db.execute("insert into t values (1, 'Alice')");

            SnakeRecord result = db.query("select user_id, full_name from t")
                .one(SnakeRecord.class).orElseThrow();
            assertEquals(1L, result.userId);
            assertEquals("Alice", result.fullName);
        }
    }

    @Test
    void recordMappingCaseInsensitive() {
        String dbName = uniqueDb("record_case");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (\"MY_ID\" bigint primary key, \"LABEL\" varchar(16) not null)");
            db.execute("insert into t values (42, 'test')");

            CaseRecord result = db.query("select \"MY_ID\", \"LABEL\" from t")
                .one(CaseRecord.class).orElseThrow();
            assertEquals(42L, result.myId);
            assertEquals("test", result.label);
        }
    }

    @Test
    void recordMappingPartialColumnsUsesDefaults() {
        String dbName = uniqueDb("record_partial");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, label varchar(16), amount bigint)");
            db.execute("insert into t (id, label) values (1, 'partial')");

            AllTypesRecord result = db.query("select id, label from t")
                .one(AllTypesRecord.class).orElseThrow();
            assertEquals(1L, result.id());
            assertEquals("partial", result.label());
            assertEquals(0L, result.amount()); // 默认值
        }
    }

    // ====================== Bean 映射 ======================

    @Test
    void beanMappingWithExactColumnNames() {
        String dbName = uniqueDb("bean_exact");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, name varchar(16) not null)");
            db.execute("insert into t values (1, 'hello')");

            BeanTarget result = db.query("select id, name from t").one(BeanTarget.class).orElseThrow();
            assertEquals(1L, result.id);
            assertEquals("hello", result.name);
        }
    }

    @Test
    void beanMappingWithSnakeCaseColumns() {
        String dbName = uniqueDb("bean_snake");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (user_id bigint primary key, full_name varchar(16) not null)");
            db.execute("insert into t values (1, 'Bob')");

            BeanSnake result = db.query("select user_id, full_name from t")
                .one(BeanSnake.class).orElseThrow();
            assertEquals(1L, result.getUserId());
            assertEquals("Bob", result.getFullName());
        }
    }

    @Test
    void beanMappingWithoutSettersUsesFields() {
        String dbName = uniqueDb("bean_fields");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, name varchar(16) not null)");
            db.execute("insert into t values (7, 'field')");

            FieldBeanTarget result = db.query("select id, name from t")
                .one(FieldBeanTarget.class).orElseThrow();
            assertEquals(7L, result.getId());
            assertEquals("field", result.getName());
        }
    }

    @Test
    void beanMappingSkipsMissingColumns() {
        String dbName = uniqueDb("bean_missing");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint primary key, name varchar(16))");
            db.execute("insert into t (id) values (1)");

            BeanWithBoth result = db.query("select id from t").one(BeanWithBoth.class).orElseThrow();
            assertEquals(1L, result.getId());
            assertNull(result.getName());
        }
    }

    // ====================== 简单类型映射 ======================

    @Test
    void simpleTypesStringAndInteger() {
        String dbName = uniqueDb("simple_str_int");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (s varchar(16), i int)");
            db.execute("insert into t values ('abc', 42)");

            assertEquals("abc", db.query("select s from t").one(String.class).orElseThrow());
            assertEquals(42, db.query("select i from t").one(Integer.class).orElseThrow());
            assertEquals(42, db.query("select i from t").one(int.class).orElseThrow());
        }
    }

    @Test
    void simpleTypesLongAndDouble() {
        String dbName = uniqueDb("simple_long_dbl");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (l bigint, d double)");
            db.execute("insert into t values (9999999999, 3.14)");

            assertEquals(9999999999L, db.query("select l from t").one(Long.class).orElseThrow());
            assertEquals(3.14, db.query("select d from t").one(Double.class).orElseThrow(), 1e-9);
        }
    }

    @Test
    void simpleTypesBigDecimalAndBigInteger() {
        String dbName = uniqueDb("simple_big");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (bd decimal(30,10), bi decimal(30))");
            db.execute("insert into t values (1234567890.123456789, 9876543210987654321)");

            assertEquals(0, new BigDecimal("1234567890.123456789").compareTo(
                db.query("select bd from t").one(BigDecimal.class).orElseThrow()));
            assertEquals(new BigInteger("9876543210987654321"),
                db.query("select bi from t").one(BigInteger.class).orElseThrow());
        }
    }

    @Test
    void simpleTypesTemporal() {
        String dbName = uniqueDb("simple_temporal");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (d date, ts timestamp, t2 time)");
            db.execute("insert into t values (DATE '2024-06-15', TIMESTAMP '2024-06-15 10:30:00', TIME '14:45:00')");

            assertEquals(LocalDate.of(2024, 6, 15),
                db.query("select d from t").one(LocalDate.class).orElseThrow());
            assertEquals(LocalDateTime.of(2024, 6, 15, 10, 30, 0),
                db.query("select ts from t").one(LocalDateTime.class).orElseThrow());
            assertEquals(LocalTime.of(14, 45, 0),
                db.query("select t2 from t").one(LocalTime.class).orElseThrow());
        }
    }

    @Test
    void simpleTypesLocalDateTimeAndUuid() {
        String dbName = uniqueDb("simple_ldt_uuid");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (ts timestamp, uid uuid)");
            db.execute("insert into t values (TIMESTAMP '2025-01-01 00:00:00', '550e8400-e29b-41d4-a716-446655440000')");

            assertEquals(LocalDateTime.of(2025, 1, 1, 0, 0, 0),
                db.query("select ts from t").one(LocalDateTime.class).orElseThrow());
            assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                db.query("select uid from t").one(UUID.class).orElseThrow());
        }
    }

    @Test
    void simpleTypesBoolean() {
        String dbName = uniqueDb("simple_bool");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (v boolean)");
            db.execute("insert into t values (true), (false)");

            List<Boolean> results = db.query("select v from t order by v desc").list(Boolean.class);
            assertEquals(List.of(true, false), results);
        }
    }

    // ====================== NULL 值和原始类型 ======================

    @Test
    void nullableSimpleTypeReturnsEmpty() {
        String dbName = uniqueDb("nullable");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (v varchar(16))");
            db.execute("insert into t values (null)");

            Optional<String> result = db.query("select v from t").one(String.class);
            assertTrue(result.isEmpty());
        }
    }

    @Test
    void primitiveIntGetsDefaultZeroOnNull() {
        String dbName = uniqueDb("prim_null_int");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (v int)");
            db.execute("insert into t values (null)");

            // 注意：one() 返回 Optional，Null 值在映射层被转为 0，所以 Optional 非空
            int result = db.query("select v from t").one(int.class).orElseThrow();
            assertEquals(0, result);
        }
    }

    @Test
    void emptyResultList() {
        String dbName = uniqueDb("empty_list");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (id bigint)");

            List<Long> result = db.query("select id from t where id < 0").list(Long.class);
            assertTrue(result.isEmpty());
        }
    }

    // ====================== 自定义 RowMapper ======================

    @Test
    void customMapperOverridesBuiltin() {
        String dbName = uniqueDb("custom");
        Database db = builder(dbName)
            .rowMapper(ExactRecord.class, (rs, rowNum) ->
                new ExactRecord(rs.getLong("id") * 100, "custom-" + rs.getString("name")))
            .build();
        try (db) {
            db.execute("create table t (id bigint primary key, name varchar(16) not null)");
            db.execute("insert into t values (1, 'hello')");

            ExactRecord result = db.query("select id, name from t").one(ExactRecord.class).orElseThrow();
            assertEquals(100L, result.id);
            assertEquals("custom-hello", result.name);
        }
    }

    @Test
    void customMapperWithList() {
        String dbName = uniqueDb("custom_list");
        Database db = builder(dbName)
            .rowMapper(TransformResult.class, (rs, rowNum) ->
                new TransformResult(rs.getLong("id"), rs.getString("val").toUpperCase()))
            .build();
        try (db) {
            db.execute("create table t (id bigint primary key, val varchar(16) not null)");
            db.execute("insert into t values (1, 'abc'), (2, 'def')");

            List<TransformResult> results = db.query("select id, val from t order by id")
                .list(TransformResult.class);
            assertEquals(2, results.size());
            assertEquals(new TransformResult(1L, "ABC"), results.get(0));
            assertEquals(new TransformResult(2L, "DEF"), results.get(1));
        }
    }

    @Test
    void manualAndContributedRowMappersAreMerged() {
        RowMapper<ExactRecord> contributedExact = (rs, rowNum) ->
            new ExactRecord(rs.getLong("id"), "contributed");
        RowMapper<TransformResult> manualTransform = (rs, rowNum) ->
            new TransformResult(rs.getLong("id"), "manual");
        RowMapper<ExactRecord> manualExact = (rs, rowNum) ->
            new ExactRecord(rs.getLong("id") * 10, "manual");

        RowMapperResolver resolver = new RowMapperResolver(
            new CoercerDefault(),
            Map.of(
                TransformResult.class, manualTransform,
                ExactRecord.class, manualExact
            ),
            Map.of(
                ExactRecord.class, contributedExact
            )
        );

        assertSame(manualTransform, resolver.resolve(TransformResult.class));
        assertSame(manualExact, resolver.resolve(ExactRecord.class));
    }

    @Test
    void resolverRejectsInterfaceTypesWithClearMessage() {
        RowMapperResolver resolver = new RowMapperResolver(new CoercerDefault(), Map.of(), Map.of());

        SqlException ex = assertThrows(SqlException.class, () -> resolver.resolve(Marker.class));

        assertTrue(ex.getMessage().contains("interface"));
        assertTrue(ex.getMessage().contains(Marker.class.getName()));
    }

    @Test
    void resolverRejectsAbstractTypesWithClearMessage() {
        RowMapperResolver resolver = new RowMapperResolver(new CoercerDefault(), Map.of(), Map.of());

        SqlException ex = assertThrows(SqlException.class, () -> resolver.resolve(AbstractTarget.class));

        assertTrue(ex.getMessage().contains("abstract class"));
        assertTrue(ex.getMessage().contains(AbstractTarget.class.getName()));
    }

    // ====================== 错误路径 ======================

    @Test
    void throwsOnUnknownType() {
        String dbName = uniqueDb("err_unknown");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (v int)");
            db.execute("insert into t values (1)");

            assertThrows(SqlException.class,
                () -> db.query("select v from t").one(Object.class));
        }
    }

    // ====================== @Column 注解映射 ======================

    @Test
    void columnAnnotationOverridesRecordMapping() {
        String dbName = uniqueDb("col_record");
        Database db = builder(dbName).build();
        try (db) {
            // 列名使用 snake_case，与属性名不匹配但 @Column 指定了映射
            db.execute("create table t (user_id bigint primary key, display_name varchar(32) not null)");
            db.execute("insert into t values (1, 'Alice')");

            ColumnRecord result = db.query("select user_id, display_name from t")
                .one(ColumnRecord.class).orElseThrow();
            assertEquals(1L, result.id());
            assertEquals("Alice", result.name());
        }
    }

    @Test
    void columnAnnotationOverridesBeanMapping() {
        String dbName = uniqueDb("col_bean");
        Database db = builder(dbName).build();
        try (db) {
            db.execute("create table t (order_id bigint primary key, order_status varchar(16) not null)");
            db.execute("insert into t values (99, 'SHIPPED')");

            ColumnBean result = db.query("select order_id, order_status from t")
                .one(ColumnBean.class).orElseThrow();
            assertEquals(99L, result.getId());
            assertEquals("SHIPPED", result.getStatus());
        }
    }

    @Test
    void columnAnnotationWithPartialOverrides() {
        String dbName = uniqueDb("col_partial");
        Database db = builder(dbName).build();
        try (db) {
            // display_name 通过 @Column 映射到 name，score 没有注解直接用驼峰→snake
            db.execute("create table t (display_name varchar(32) not null, score int)");
            db.execute("insert into t values ('test', 42)");

            MixedColumnRecord result = db.query("select display_name, score from t")
                .one(MixedColumnRecord.class).orElseThrow();
            assertEquals("test", result.name());
            assertEquals(42, result.score());
        }
    }

    @Test
    void columnAnnotationFallsThroughToNameMatch() {
        String dbName = uniqueDb("col_fallback");
        Database db = builder(dbName).build();
        try (db) {
            // @Column 指定了不存在的列名，但属性名可以匹配
            db.execute("create table t (code varchar(8) not null)");
            db.execute("insert into t values ('OK')");

            ColumnFallbackRecord result = db.query("select code from t")
                .one(ColumnFallbackRecord.class).orElseThrow();
            // @Column("status_code") 不存在，但属性名 code 匹配
            assertEquals("OK", result.code());
        }
    }

    // ====================== 辅助方法 ======================

    private static DatabaseBuilder builder(String dbName) {
        return new DatabaseBuilder()
            .config(PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    private static String uniqueDb(String prefix) {
        return "freeway_mapping_" + prefix + "_" + UUID.randomUUID().toString().replace('-', '_');
    }

    // ====================== 测试用数据类型 ======================

    public record ExactRecord(long id, String name) {
    }

    public record SnakeRecord(long userId, String fullName) {
    }

    public record CaseRecord(long myId, String label) {
    }

    public record AllTypesRecord(long id, String label, long amount) {
    }

    public record TransformResult(long id, String val) {
    }

    // Bean 类

    public static class BeanTarget {
        private long id;
        private String name;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class BeanSnake {
        private long userId;
        private String fullName;

        public long getUserId() { return userId; }
        public void setUserId(long userId) { this.userId = userId; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
    }

    public static class FieldBeanTarget {
        private long id;
        private String name;

        public long getId() { return id; }
        public String getName() { return name; }
    }

    public static class BeanWithBoth {
        private long id;
        private String name;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    interface Marker {
    }

    abstract static class AbstractTarget {
    }

    // ====================== @Column 测试类型 ======================

    public record ColumnRecord(
        @Column("user_id") long id,
        @Column("display_name") String name
    ) {}

    public static class ColumnBean {
        @Column("order_id")
        private long id;
        @Column("order_status")
        private String status;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public record MixedColumnRecord(
        @Column("display_name") String name,
        int score
    ) {}

    public record ColumnFallbackRecord(
        @Column("status_code") String code
    ) {}
}
