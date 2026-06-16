package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.SqlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 数据库 Schema 工具 — 实体类自动建表 / 迁移。
 *
 * <h3>快速开始</h3>
 * <pre>{@code
 * // 只生成 DDL，不执行
 * String ddl = Schema.define(User.class);
 *
 * // AutoMigrate：自动建表 + 添加缺失列（不删列、不改列）
 * Schema.ensure(db, User.class, Post.class);
 *
 * // 删表
 * Schema.drop(db, User.class);
 * }</pre>
 *
 * <h3>AutoMigrate 策略</h3>
 * <ul>
 *   <li>表不存在 → {@code CREATE TABLE IF NOT EXISTS} + {@code CREATE INDEX IF NOT EXISTS}</li>
 *   <li>表已存在、列缺失 → {@code ALTER TABLE ADD COLUMN}</li>
 *   <li>表已存在、索引缺失 → {@code CREATE INDEX IF NOT EXISTS}</li>
 *   <li>绝不删除已有列/索引、不修改已有列类型</li>
 * </ul>
 *
 * <h3>注解支持</h3>
 * <ul>
 *   <li>{@link Table @Table} — 表名覆盖</li>
 *   <li>{@link Column @Column} — 列名、类型、可空性覆盖</li>
 *   <li>{@link Id @Id} — 主键</li>
 *   <li>{@link Generated @Generated} — 自增列</li>
 *   <li>{@link Transient @Transient} — 排除字段</li>
 *   <li>{@link Index @Index} — 索引（支持复合索引和唯一索引）</li>
 * </ul>
 * 并自动识别 commons 中的验证注解（{@code @NotNull}, {@code @NotBlank}, {@code @Size}）。
 */
public final class Schema {
    private static final Logger LOG = LoggerFactory.getLogger(Schema.class);

    private Schema() {
    }

    /**
     * 为实体类生成 CREATE TABLE DDL 字符串，不执行。
     */
    public static String define(Class<?> entityType) {
        return new SchemaGenerator(new PostgresDialect()).generate(entityType);
    }

    /**
     * 为多个实体类生成 CREATE TABLE DDL 列表，不执行。
     */
    public static List<String> defineAll(Class<?>... entityTypes) {
        return new SchemaGenerator(new PostgresDialect()).generateAll(entityTypes);
    }

    /**
     * AutoMigrate — 确保实体类对应的表结构和列存在。
     *
     * @param db          数据库连接
     * @param entityTypes 实体类列表
     * @return 实际执行的 DDL 语句数
     * @throws SqlException 执行失败时抛出
     */
    public static int ensure(Database db, Class<?>... entityTypes) {
        return ensure(db, new PostgresDialect(), entityTypes);
    }

    /**
     * AutoMigrate — 使用指定方言。
     */
    public static int ensure(Database db, Dialect dialect, Class<?>... entityTypes) {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(dialect, "dialect");
        if (entityTypes == null || entityTypes.length == 0) {
            return 0;
        }

        SchemaGenerator gen = new SchemaGenerator(dialect);
        int executed = 0;

        Set<String> existingTables = dialect.existingTables(db);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Existing tables in schema: {}", existingTables);
        }

        for (Class<?> type : entityTypes) {
            TableDef table = gen.define(type);
            String tableName = table.name();

            if (!existingTables.contains(tableName)) {
                String ddl = dialect.createTable(table);
                LOG.info("Creating table: {}", tableName);
                db.execute(ddl);
                executed++;
                continue;
            }

            // 表已存在 — 检查缺失列
            Set<String> existingCols = dialect.existingColumns(db, tableName);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Existing columns for {}: {}", tableName, existingCols);
            }

            for (ColumnDef col : table.columns()) {
                if (!existingCols.contains(col.name())) {
                    String alter = dialect.addColumn(tableName, col);
                    LOG.info("Adding column: {}.{}", tableName, col.name());
                    db.execute(alter);
                    executed++;
                }
            }
        }

        // 索引：CREATE INDEX IF NOT EXISTS 天生幂等，直接全量执行
        for (Class<?> type : entityTypes) {
            TableDef table = gen.define(type);
            for (String indexDdl : dialect.createIndexes(table)) {
                LOG.info("Ensuring index on {}", table.name());
                db.execute(indexDdl);
            }
        }

        if (executed > 0) {
            LOG.info("AutoMigrate applied {} change(s)", executed);
        }
        return executed;
    }

    /**
     * 删除实体类对应的表。
     */
    public static void drop(Database db, Class<?>... entityTypes) {
        drop(db, new PostgresDialect(), entityTypes);
    }

    /**
     * 删除实体类对应的表，使用指定方言。
     */
    public static void drop(Database db, Dialect dialect, Class<?>... entityTypes) {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(dialect, "dialect");
        if (entityTypes == null || entityTypes.length == 0) {
            return;
        }
        SchemaGenerator gen = new SchemaGenerator(dialect);
        for (Class<?> type : entityTypes) {
            TableDef table = gen.define(type);
            LOG.info("Dropping table: {}", table.name());
            db.execute(dialect.dropTable(table.name()));
        }
    }

}
