package com.jujin.freeway.db.schema;

import com.jujin.freeway.commons.validation.NotNull;
import com.jujin.freeway.commons.validation.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDL 生成单元测试 — 不需要数据库连接。
 */
class SchemaGeneratorTest {

    private final SchemaGenerator gen = new SchemaGenerator(new PostgresDialect());

    // ====================== 基础 Record 映射 ======================

    @Test
    void plainRecordGeneratesTable() {
        String ddl = gen.generate(SimpleUser.class);
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS simple_user"));
        assertTrue(ddl.contains("id"));
        assertTrue(ddl.contains("name"));
        assertTrue(ddl.contains("email"));
    }

    @Test
    void tableNameIsSnakeCaseByDefault() {
        String ddl = gen.generate(UserProfile.class);
        assertTrue(ddl.contains("user_profile"));
    }

    @Test
    void tableNameFromAnnotation() {
        String ddl = gen.generate(NamedUser.class);
        assertTrue(ddl.contains("app_users"));
        assertFalse(ddl.contains("named_user"));
    }

    @Test
    void columnNameIsSnakeCaseByDefault() {
        String ddl = gen.generate(CamelColumns.class);
        assertTrue(ddl.contains("first_name"));
        assertTrue(ddl.contains("last_name"));
        assertTrue(ddl.contains("created_at"));
    }

    @Test
    void columnNameFromAnnotation() {
        String ddl = gen.generate(AnnotatedColumns.class);
        assertTrue(ddl.contains("user_name"));
        assertTrue(ddl.contains("email_addr"));
    }

    // ====================== 类型映射 ======================

    @Test
    void stringMapsToVarchar() {
        String ddl = gen.generate(StringEntity.class);
        assertTrue(ddl.contains("VARCHAR(255)"));
    }

    @Test
    void numericTypesMapCorrectly() {
        String ddl = gen.generate(NumericEntity.class);
        assertTrue(ddl.contains("BIGINT"));
        assertTrue(ddl.contains("INTEGER"));
        assertTrue(ddl.contains("DOUBLE PRECISION"));
        assertTrue(ddl.contains("REAL"));
    }

    @Test
    void booleanMapsToBoolean() {
        String ddl = gen.generate(BoolEntity.class);
        assertTrue(ddl.contains("BOOLEAN"));
    }

    @Test
    void decimalScaleDefaultsToTwo() {
        String ddl = gen.generate(PrecisionDecimalEntity.class);
        assertTrue(ddl.contains("DECIMAL(10,2)"),
            "an unset @Column.scale must fall back to the documented default 2: " + ddl);
    }

    @Test
    void temporalTypesMapCorrectly() {
        String ddl = gen.generate(TemporalEntity.class);
        assertTrue(ddl.contains("DATE"));
        assertTrue(ddl.contains("TIMESTAMP"));
    }

    @Test
    void uuidMapsToUUID() {
        String ddl = gen.generate(UuidEntity.class);
        assertTrue(ddl.contains("UUID"));
    }

    @Test
    void decimalMapsToDecimal() {
        String ddl = gen.generate(DecimalEntity.class);
        assertTrue(ddl.contains("DECIMAL(30,2)"));
    }

    @Test
    void columnTypeOverride() {
        String ddl = gen.generate(TypeOverride.class);
        assertTrue(ddl.contains("TEXT"));
    }

    // ====================== 约束 ======================

    @Test
    void primaryKeyFromAnnotation() {
        String ddl = gen.generate(SimpleUser.class);
        assertTrue(ddl.contains("PRIMARY KEY (id)"));
    }

    @Test
    void compositePrimaryKey() {
        String ddl = gen.generate(CompositeKey.class);
        assertTrue(ddl.contains("PRIMARY KEY (key1, key2)"));
    }

    @Test
    void notNullFromAnnotation() {
        String ddl = gen.generate(NotNullEntity.class);
        assertTrue(ddl.contains("name VARCHAR(255) NOT NULL"));
    }

    @Test
    void primitiveTypesAreNotNull() {
        String ddl = gen.generate(PrimitiveNotNull.class);
        // int/long 原始类型自动 NOT NULL
        String[] lines = ddl.split("\n");
        boolean intNotNull = false, longNotNull = false;
        for (String line : lines) {
            if (line.contains("int_val") && line.contains("NOT NULL")) intNotNull = true;
            if (line.contains("long_val") && line.contains("NOT NULL")) longNotNull = true;
        }
        assertTrue(intNotNull, "primitive int should be NOT NULL");
        assertTrue(longNotNull, "primitive long should be NOT NULL");
    }

    @Test
    void generatedColumnHasIdentityClause() {
        String ddl = gen.generate(SimpleUser.class);
        assertTrue(ddl.contains("GENERATED ALWAYS AS IDENTITY"));
    }

    @Test
    void sizeAnnotationControlsVarcharLength() {
        String ddl = gen.generate(SizeConstrained.class);
        assertTrue(ddl.contains("VARCHAR(50)"));
    }

    // ====================== @Transient ======================

    @Test
    void transientFieldsAreExcluded() {
        String ddl = gen.generate(WithTransient.class);
        assertTrue(ddl.contains("kept"));
        assertFalse(ddl.contains("ignored"));
        assertFalse(ddl.contains("computed"));
    }

    // ====================== Bean 映射 ======================

    @Test
    void beanClassGeneratesTable() {
        String ddl = gen.generate(BeanEntity.class);
        assertTrue(ddl.contains("bean_entity"));
        assertTrue(ddl.contains("name"));
        assertTrue(ddl.contains("age"));
    }

    @Test
    void beanWithSettersAndAnnotations() {
        String ddl = gen.generate(AnnotatedBean.class);
        assertTrue(ddl.contains("PRIMARY KEY (pk_id)"));
    }

    // ====================== 枚举 ======================

    @Test
    void enumMapsToVarchar() {
        String ddl = gen.generate(EnumEntity.class);
        assertTrue(ddl.contains("VARCHAR(32)"));
    }

    // ====================== defineAll ======================

    @Test
    void defineAllGeneratesMultipleTables() {
        var ddls = gen.generateAll(SimpleUser.class, UserProfile.class);
        assertEquals(2, ddls.size());
        assertTrue(ddls.get(0).contains("simple_user"));
        assertTrue(ddls.get(1).contains("user_profile"));
    }

    // ====================== 精度控制 ======================

    @Test
    void columnLengthControlsVarchar() {
        String ddl = gen.generate(VarcharLength.class);
        assertTrue(ddl.contains("VARCHAR(100)"));
    }

    @Test
    void columnPrecisionAndScaleControlsDecimal() {
        String ddl = gen.generate(DecimalPrecision.class);
        assertTrue(ddl.contains("DECIMAL(10,4)"));
    }

    @Test
    void columnLengthFallbackForDecimal() {
        String ddl = gen.generate(DecimalLengthFallback.class);
        // length=20 在无 precision 时作为 DECIMAL 精度回退
        assertTrue(ddl.contains("DECIMAL(20,"));
    }

    @Test
    void columnPrecisionTakesPriorityOverLength() {
        String ddl = gen.generate(DecimalBoth.class);
        // precision=12 优先于 length=20
        assertTrue(ddl.contains("DECIMAL(12,3)"));
    }

    @Test
    void sizeAnnotationControlsVarchar() {
        String ddl = gen.generate(SizeConstrained.class);
        assertTrue(ddl.contains("VARCHAR(50)"));
    }

    @Test
    void columnLengthOverridesSizeAnnotation() {
        String ddl = gen.generate(LengthOverridesSize.class);
        // @Column(length=10) 优先于 @Size(max=200)
        assertTrue(ddl.contains("VARCHAR(10)"));
    }

    @Test
    void explicitTypeOverridesLength() {
        String ddl = gen.generate(TypeOverride.class);
        assertTrue(ddl.contains("TEXT"));
        assertFalse(ddl.contains("VARCHAR"));
    }

    // ====================== 索引 ======================

    @Test
    void singleIndex() {
        var indexes = gen.generateIndexes(SingleIndexEntity.class);
        assertEquals(1, indexes.size());
        assertTrue(indexes.get(0).contains("CREATE INDEX IF NOT EXISTS"));
        assertTrue(indexes.get(0).contains("idx_single_index_entity_email"));
    }

    @Test
    void namedIndex() {
        var indexes = gen.generateIndexes(NamedIndexEntity.class);
        assertEquals(1, indexes.size());
        assertTrue(indexes.get(0).contains("idx_email"));
    }

    @Test
    void uniqueIndex() {
        var indexes = gen.generateIndexes(UniqueIndexEntity.class);
        assertEquals(1, indexes.size());
        assertTrue(indexes.get(0).contains("CREATE UNIQUE INDEX IF NOT EXISTS"));
    }

    @Test
    void compositeIndex() {
        var indexes = gen.generateIndexes(CompositeIndexEntity.class);
        assertEquals(1, indexes.size());
        String ddl = indexes.get(0);
        assertTrue(ddl.contains("idx_lookup"), "should contain idx_lookup: " + ddl);
        assertTrue(ddl.contains("user_id"), "should contain user_id: " + ddl);
        assertTrue(ddl.contains("created_at"), "should contain created_at: " + ddl);
    }

    @Test
    void multipleSeparateIndexes() {
        var indexes = gen.generateIndexes(MultiIndexEntity.class);
        assertEquals(2, indexes.size());
    }

    @Test
    void mysqlIndexOmitsIfNotExists() {
        String ddl = new IndexDef("idx_email", List.of("email"), false)
            .toSql(new MySqlDialect(), "users");
        assertEquals("CREATE INDEX idx_email ON users (email)", ddl);
    }

    // ====================== upsert 子句 ======================

    @Test
    void postgresUpsertUsesOnConflict() {
        String clause = new PostgresDialect().upsertClause(List.of("id"), List.of("name", "email"));
        assertEquals(" ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email", clause);
    }

    @Test
    void mysqlUpsertUsesOnDuplicateKey() {
        String clause = new MySqlDialect().upsertClause(List.of("id"), List.of("name", "email"));
        assertEquals(" ON DUPLICATE KEY UPDATE name = VALUES(name), email = VALUES(email)", clause);
    }

    @Test
    void sqliteUpsertUsesOnConflict() {
        String clause = new SqliteDialect().upsertClause(List.of("id"), List.of("name"));
        assertEquals(" ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name", clause);
    }

    // ====================== supportsReturning ======================

    @Test
    void postgresSupportsReturning() {
        assertTrue(new PostgresDialect().supportsReturning());
    }

    @Test
    void mysqlDoesNotSupportReturning() {
        assertFalse(new MySqlDialect().supportsReturning());
    }

    @Test
    void sqliteSupportsReturning() {
        assertTrue(new SqliteDialect().supportsReturning());
    }

    // ====================== truncate ======================

    @Test
    void postgresTruncateIncludesRestartIdentity() {
        String sql = new PostgresDialect().truncateTable("users");
        assertEquals("TRUNCATE TABLE users RESTART IDENTITY", sql);
    }

    @Test
    void mysqlTruncateIsSimple() {
        String sql = new MySqlDialect().truncateTable("users");
        assertEquals("TRUNCATE TABLE users", sql);
    }

    @Test
    void sqliteTruncateUsesDeleteFrom() {
        String sql = new SqliteDialect().truncateTable("users");
        assertEquals("DELETE FROM users", sql);
    }

    @Test
    void truncateQuotesReservedWordTable() {
        String sql = new PostgresDialect().truncateTable("user");
        assertEquals("TRUNCATE TABLE \"user\" RESTART IDENTITY", sql);
    }

    // ====================== H2Dialect ======================

    @Test
    void h2TruncateResetsIdentity() {
        String sql = new H2Dialect().truncateTable("users");
        assertEquals("TRUNCATE TABLE users RESTART IDENTITY", sql);
    }

    @Test
    void h2BinaryTypeIsBinaryVarying() {
        assertEquals("BINARY VARYING", new H2Dialect().defaultBinaryType());
    }

    @Test
    void h2SupportsReturning() {
        assertTrue(new H2Dialect().supportsReturning());
    }

    @Test
    void h2QuotesReservedWord() {
        assertEquals("\"user\"", new H2Dialect().quoteName("user"));
    }

    // ====================== forUpdateClause ======================

    @Test
    void forUpdateClauseDefault() {
        assertEquals("FOR UPDATE", new PostgresDialect().forUpdateClause());
        assertEquals("FOR UPDATE", new MySqlDialect().forUpdateClause());
        assertEquals("", new SqliteDialect().forUpdateClause());
        assertEquals("FOR UPDATE", new H2Dialect().forUpdateClause());
    }

    // ====================== dialectId ======================

    @Test
    void dialectIds() {
        assertEquals("postgresql", new PostgresDialect().dialectId());
        assertEquals("mysql", new MySqlDialect().dialectId());
        assertEquals("sqlite", new SqliteDialect().dialectId());
        assertEquals("h2", new H2Dialect().dialectId());
    }

    // ====================== generatedTypeOverride ======================

    @Test
    void defaultGeneratedTypeOverrideReturnsInput() {
        assertEquals("BIGINT", new PostgresDialect().generatedTypeOverride("BIGINT", Long.class));
        assertEquals("INTEGER", new MySqlDialect().generatedTypeOverride("INTEGER", Integer.class));
    }

    @Test
    void sqliteGeneratedTypeOverrideForcesInteger() {
        var d = new SqliteDialect();
        assertEquals("INTEGER", d.generatedTypeOverride("BIGINT", Long.class));
        assertEquals("INTEGER", d.generatedTypeOverride("BIGINT", long.class));
        assertEquals("INTEGER", d.generatedTypeOverride("INTEGER", Integer.class));
        assertEquals("INTEGER", d.generatedTypeOverride("BIGINT", int.class));
    }

    @Test
    void sqliteGeneratedTypeOverrideRejectsNonIntegral() {
        var d = new SqliteDialect();
        assertThrows(IllegalArgumentException.class, () ->
            d.generatedTypeOverride("TEXT", String.class));
    }

    @Test
    void sqliteAddColumnDoesNotDuplicateKeyword() {
        String ddl = new SqliteDialect().addColumn(
            "users",
            new ColumnDef("email", "TEXT", true, false, false)
        );
        assertEquals("ALTER TABLE users ADD COLUMN email TEXT", ddl);
    }

    @Test
    void sqliteGeneratedPrimaryKeyUsesInteger() {
        String ddl = new SchemaGenerator(new SqliteDialect()).generate(
            SqliteGeneratedUser.class
        );
        assertTrue(ddl.contains("id INTEGER PRIMARY KEY AUTOINCREMENT"));
        assertFalse(ddl.contains("BIGINT"));
    }

    @Test
    void noIndexesForEntityWithoutAnnotations() {
        var indexes = gen.generateIndexes(SimpleUser.class);
        assertTrue(indexes.isEmpty());
    }

    // ====================== 错误路径 ======================

    @Test
    void emptyTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> gen.generate(NoProperties.class));
    }

    // ====================== 实体类定义 ======================

    public record SimpleUser(
        @Id @Generated Long id,
        String name,
        String email
    ) {}

    public record UserProfile(Long id, String bio, String avatarUrl) {}

    @Table("app_users")
    public record NamedUser(@Id Long id, String name) {}

    public record CamelColumns(String firstName, String lastName, LocalDateTime createdAt) {}

    public record AnnotatedColumns(
        @Id Long id,
        @Column("user_name") String name,
        @Column("email_addr") String email
    ) {}

    public record StringEntity(@Id Long id, String value) {}

    public record NumericEntity(
        @Id long id,
        int count,
        double amount,
        float rate
    ) {}

    public record BoolEntity(@Id Long id, boolean active) {}

    public record TemporalEntity(@Id Long id, LocalDate date, LocalDateTime timestamp) {}

    public record UuidEntity(@Id Long id, UUID uid) {}

    public record DecimalEntity(@Id Long id, BigDecimal price) {}

    public record TypeOverride(
        @Id Long id,
        @Column(type = "TEXT") String content
    ) {}

    public record CompositeKey(@Id String key1, @Id String key2, String value) {}

    public record NotNullEntity(@Id Long id, @NotNull String name) {}

    public record PrimitiveNotNull(@Id long id, int intVal, long longVal, String optStr) {}

    public record SizeConstrained(
        @Id Long id,
        @Size(min = 1, max = 50) String shortName
    ) {}

    public record PrecisionDecimalEntity(
        @Id Long id,
        @Column(precision = 10) java.math.BigDecimal amount
    ) {}

    public record WithTransient(
        @Id Long id,
        String kept,
        @Transient String ignored,
        @Transient int computed
    ) {}

    @Table("bean_entity")
    public static class BeanEntity {
        @Id private Long id;
        private String name;
        private int age;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
    }

    @Table("annotated_bean")
    public static class AnnotatedBean {
        @Id
        @Column("pk_id")
        private Long id;
        private String label;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    public enum Status { ACTIVE, INACTIVE }
    public record EnumEntity(@Id Long id, Status status) {}

    // 没有可映射属性的类（只有 transient 字段）
    public record NoProperties(@Transient String x) {}

    // ====================== 精度控制测试用实体 ======================

    public record VarcharLength(
        @Id Long id,
        @Column(length = 100) String title
    ) {}

    public record DecimalPrecision(
        @Id Long id,
        @Column(precision = 10, scale = 4) BigDecimal rate
    ) {}

    public record DecimalLengthFallback(
        @Id Long id,
        @Column(length = 20) BigDecimal amount
    ) {}

    public record DecimalBoth(
        @Id Long id,
        @Column(precision = 12, scale = 3, length = 20) BigDecimal value
    ) {}

    public record LengthOverridesSize(
        @Id Long id,
        @Column(length = 10)
        @Size(max = 200) String code
    ) {}

    // ====================== 索引测试用实体 ======================

    @Table("single_index_entity")
    public record SingleIndexEntity(
        @Id Long id,
        @Index String email
    ) {}

    @Table("named_index_entity")
    public record NamedIndexEntity(
        @Id Long id,
        @Index(name = "idx_email") String email
    ) {}

    @Table("unique_index_entity")
    public record UniqueIndexEntity(
        @Id Long id,
        @Index(unique = true) String username
    ) {}

    @Table("composite_index_entity")
    public record CompositeIndexEntity(
        @Id Long id,
        @Index(name = "idx_lookup") String userId,
        @Index(name = "idx_lookup") LocalDateTime createdAt,
        String status
    ) {}

    @Table("multi_index_entity")
    public record MultiIndexEntity(
        @Id Long id,
        @Index String email,
        @Index String username
    ) {}

    public record SqliteGeneratedUser(
        @Id @Generated Long id,
        String name
    ) {}
}
