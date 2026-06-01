package com.jujin.freeway.db;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 RowMapper 在多线程并发访问下的线程安全性。
 * 核心关注点：共享 RowMapper 实例同时处理多个 ResultSet 时不会产生列索引错乱。
 */
class RowMapperConcurrencyTest {

    /** Record 映射的并发安全 */
    @Test
    void concurrentRecordMapping() throws Exception {
        String dbName = "freeway_conc_record_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .url("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("")
            .build();

        try (db) {
            db.sql("create table items (id bigint primary key, label varchar(32) not null)").execute();
            db.sql("insert into items values (1, 'alpha'), (2, 'beta'), (3, 'gamma')").execute();

            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<List<Item>>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() ->
                    db.sql("select id, label from items order by id").list(Item.class)
                ));
            }

            for (Future<List<Item>> future : futures) {
                List<Item> items = future.get();
                assertEquals(3, items.size());
                assertEquals(new Item(1L, "alpha"), items.get(0));
                assertEquals(new Item(2L, "beta"), items.get(1));
                assertEquals(new Item(3L, "gamma"), items.get(2));
            }

            executor.shutdown();
        }
    }

    /** Bean 映射的并发安全 */
    @Test
    void concurrentBeanMapping() throws Exception {
        String dbName = "freeway_conc_bean_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .url("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("")
            .build();

        try (db) {
            db.sql("create table users (user_id bigint primary key, full_name varchar(64) not null)").execute();
            db.sql("insert into users values (10, 'Alice'), (20, 'Bob'), (30, 'Charlie')").execute();

            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<List<UserBean>>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() ->
                    db.sql("select user_id, full_name from users order by user_id").list(UserBean.class)
                ));
            }

            for (Future<List<UserBean>> future : futures) {
                List<UserBean> users = future.get();
                assertEquals(3, users.size());
                assertEquals(10L, users.get(0).getUserId());
                assertEquals("Alice", users.get(0).getFullName());
                assertEquals(20L, users.get(1).getUserId());
                assertEquals("Bob", users.get(1).getFullName());
                assertEquals(30L, users.get(2).getUserId());
                assertEquals("Charlie", users.get(2).getFullName());
            }

            executor.shutdown();
        }
    }

    /** 简单类型映射的并发安全（createSimple 路径） */
    @Test
    void concurrentSimpleMapping() throws Exception {
        String dbName = "freeway_conc_simple_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .url("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("")
            .build();

        try (db) {
            db.sql("create table vals (v varchar(16) not null)").execute();
            db.sql("insert into vals values ('a'), ('b'), ('c')").execute();

            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<List<String>>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() ->
                    db.sql("select v from vals order by v").list(String.class)
                ));
            }

            for (Future<List<String>> future : futures) {
                assertEquals(List.of("a", "b", "c"), future.get());
            }

            executor.shutdown();
        }
    }

    /** 自定义 RowMapper 的并发安全 */
    @Test
    void concurrentCustomMapper() throws Exception {
        String dbName = "freeway_conc_custom_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .url("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("")
            .rowMapper(Score.class, (rs, rowNum) -> new Score(rs.getLong("id"), rs.getInt("score") * 2))
            .build();

        try (db) {
            db.sql("create table scores (id bigint primary key, score int not null)").execute();
            db.sql("insert into scores values (1, 50), (2, 75)").execute();

            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<List<Score>>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() ->
                    db.sql("select id, score from scores order by id").list(Score.class)
                ));
            }

            for (Future<List<Score>> future : futures) {
                List<Score> scores = future.get();
                assertEquals(2, scores.size());
                assertEquals(100, scores.get(0).value());
                assertEquals(150, scores.get(1).value());
            }

            executor.shutdown();
        }
    }

    /** 多类型混合并发 + 多次迭代 */
    @Test
    void mixedConcurrentWorkload() throws Exception {
        String dbName = "freeway_conc_mixed_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .url("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .username("sa")
            .password("")
            .build();

        try (db) {
            db.sql("create table t1 (id bigint primary key, name varchar(16))").execute();
            db.sql("insert into t1 values (1, 'one'), (2, 'two'), (3, 'three')").execute();
            db.sql("create table t2 (total bigint not null)").execute();
            db.sql("insert into t2 values (100), (200), (300)").execute();

            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                final int idx = t;
                futures.add(executor.submit(() -> {
                    // 每次迭代：交替查询 Record + 简单类型
                    for (int i = 0; i < 20; i++) {
                        List<T1Row> rows = db.sql("select id, name from t1 order by id")
                            .list(T1Row.class);
                        if (rows.size() != 3) return false;
                        if (rows.get(0).id != 1L || !"one".equals(rows.get(0).name)) return false;

                        Long sum = db.sql("select sum(total) from t2").one(Long.class).orElseThrow();
                        if (sum != 600L) return false;
                    }
                    return true;
                }));
            }

            for (Future<Boolean> future : futures) {
                assertEquals(true, future.get());
            }

            executor.shutdown();
        }
    }

    public record Item(long id, String label) {
    }

    public record Score(long id, int value) {
    }

    public record T1Row(long id, String name) {
    }

    /** Bean 风格（非 record），用于触发 createBean 路径 */
    public static class UserBean {
        private long userId;
        private String fullName;

        public long getUserId() { return userId; }
        public void setUserId(long userId) { this.userId = userId; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
    }
}
