package com.jujin.freeway.db;
import com.jujin.freeway.commons.coercion.CoercerDefault;

import com.jujin.freeway.db.schema.Column;
import com.jujin.freeway.db.dialect.Dialect;
import com.jujin.freeway.db.schema.Generated;
import com.jujin.freeway.db.schema.Id;
import com.jujin.freeway.db.dialect.PostgresDialect;
import com.jujin.freeway.db.schema.Schema;
import com.jujin.freeway.db.schema.Table;
import com.jujin.freeway.db.schema.Transient;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrmTest {

    // ==================== entity types ====================

    @Table
    record Post(@Id @Generated Long id, @Column String title, @Column String body) {
        Post(String title, String body) { this(null, title, body); }
    }

    @Table("comments")
    static class Comment {
        @Id @Generated Long id;
        @Column String text;
        @Column("post_id") Long postId;

        Comment() {}
        Comment(String text, Long postId) { this.text = text; this.postId = postId; }
    }

    @Table("app_user")
    static class User {
        @Id String username;
        @Column String email;
        @Transient String ignoredField;

        User() {}
        User(String username, String email) { this.username = username; this.email = email; }
    }

    @Table("id_only")
    static class IdOnly {
        @Id String id;

        IdOnly() {}
        IdOnly(String id) { this.id = id; }
    }

    @Table("gen_only")
    static class GeneratedOnly {
        @Id @Generated Long id;

        GeneratedOnly() {}
        GeneratedOnly(Long id) { this.id = id; }
    }

    /** Primitive (non-boxed) generated id — reads back 0L, never null. */
    @Table("primitive_id")
    static class PrimitiveId {
        @Id @Generated long id;
        @Column String name;

        PrimitiveId() {}
        PrimitiveId(String name) { this.name = name; }
    }

    /** Records calls to offsetOnlyClause to verify Orm delegates no-LIMIT OFFSET. */
    static class RecordingDialect extends PostgresDialect {
        int offsetOnlyCalls = 0;

        @Override
        public String offsetOnlyClause(long offset) {
            offsetOnlyCalls++;
            return super.offsetOnlyClause(offset);
        }
    }

    /**
     * Records the last executed SQL so save() upsert SQL can be asserted
     * without a database that executes ON CONFLICT DO UPDATE (H2 rejects it).
     */
    static final class RecordingDatabase implements Database {
        private final Dialect dialect;
        private String lastSql = "";
        private Object[] lastParams = new Object[0];

        RecordingDatabase(Dialect dialect) {
            this.dialect = dialect;
        }

        String lastSql() {
            return lastSql;
        }

        Object[] lastParams() {
            return lastParams;
        }

        @Override
        public Dialect dialect() {
            return dialect;
        }

        @Override
        public Query query(String sql, Object... params) {
            throw new UnsupportedOperationException("not needed for save() assertions");
        }

        @Override
        public ExecuteResult execute(String sql, Object... params) {
            lastSql = sql;
            lastParams = params;
            return new ExecuteResult(0, null);
        }

        @Override
        public BatchQuery batch(String sql) {
            throw new UnsupportedOperationException("not needed for save() assertions");
        }

        @Override
        public void transaction(Transactional work) {
            try {
                work.run();
            } catch (Exception e) {
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            }
        }

        @Override
        public void transaction(IsolationLevel isolation, Transactional work) {
            try {
                work.run();
            } catch (Exception e) {
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            }
        }

        @Override
        public boolean ping() {
            return true;
        }

        @Override
        public DatabaseStats stats() {
            return new DatabaseStats(0, 0, 0, 0, 0, 0, 0, 0);
        }

        @Override
        public void close() {
        }
    }

    @Test
    void ofWithCoercerUsesDatabaseDialect() {
        Database db = builder("orm_coercer").build();
        try (db) {
            Schema.ensure(db, Post.class);
            Orm orm = Orm.of(db, new CoercerDefault());
            orm.insert(new Post("title", "body"));
            assertTrue(orm.findById(Post.class, 1L).isPresent(),
                "Orm.of(db, coercer) should derive the dialect from the database");
        }
    }

    @Test
    void updateWithNoUpdatableColumnsFailsClearly() {
        var db = builder(uniqueDb("orm_update_empty")).build();
        try (db) {
            Schema.ensure(db, IdOnly.class);
            Orm orm = Orm.of(db);
            IdOnly entity = new IdOnly("key");
            orm.insert(entity);

            SqlException ex = assertThrows(SqlException.class, () -> orm.update(entity));
            assertTrue(ex.getMessage().contains("updatable"),
                "empty UPDATE must fail with a clear message, got: " + ex.getMessage());
        }
    }

    // ==================== helpers ====================

    private static DatabaseBuilder builder(String name) {
        return new DatabaseBuilder()
            .config(PoolConfig.defaults(
                "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    private static String uniqueDb(String prefix) {
        return "freeway_orm_" + prefix + "_" + UUID.randomUUID().toString().replace('-', '_');
    }

    // ==================== insert ====================

    @Test
    void insertRecordReturnsGeneratedId() {
        var db = builder(uniqueDb("insert_record")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);

            Post p = new Post("Hello", "World");
            var r = orm.insert(p);

            assertTrue(r.hasKey());
            assertTrue(r.longKey() > 0);
        }
    }

    @Test
    void insertWritesBackGeneratedIdToRecord() {
        var db = builder(uniqueDb("writeback")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);

            Post p = new Post("Hello", "World");
            var r = orm.insert(p);

            // records are immutable, id is on the result
            assertTrue(r.hasKey());
            assertEquals(1L, r.longKey());
        }
    }

    @Test
    void insertBean() {
        var db = builder(uniqueDb("insert_bean")).build();
        try (db) {
            db.execute("CREATE TABLE comments (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, text VARCHAR(255), post_id BIGINT)");
            Orm orm = Orm.of(db);

            Comment c = new Comment("nice post", 1L);
            var r = orm.insert(c);

            assertTrue(r.hasKey());
            assertNotNull(c.id);
            assertTrue(c.id > 0);
        }
    }

    @Test
    void insertNonGeneratedId() {
        var db = builder(uniqueDb("nongen")).build();
        try (db) {
            db.execute("CREATE TABLE app_user (username VARCHAR(255) PRIMARY KEY, email VARCHAR(255))");
            Orm orm = Orm.of(db);

            User u = new User("alice", "alice@example.com");
            var r = orm.insert(u);

            assertEquals(1, r.rows());
        }
    }

    @Test
    void insertWithOnlyGeneratedColumnThrows() {
        var db = builder(uniqueDb("insert_gen_only")).build();
        try (db) {
            Schema.ensure(db, GeneratedOnly.class);
            Orm orm = Orm.of(db);

            SqlException ex = assertThrows(SqlException.class, () -> orm.insert(new GeneratedOnly(null)));
            assertTrue(ex.getMessage().contains("insertable"),
                "empty INSERT must fail with a clear message, got: " + ex.getMessage());
        }
    }

    // ==================== find ====================

    @Test
    void findById() {
        var db = builder(uniqueDb("find")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);

            orm.insert(new Post("A", "body a"));
            orm.insert(new Post("B", "body b"));

            Post found = orm.findById(Post.class, 2L).orElseThrow();
            assertEquals("B", found.title());
            assertEquals("body b", found.body());
        }
    }

    @Test
    void findByIdNotFound() {
        var db = builder(uniqueDb("notfound")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);

            assertTrue(orm.findById(Post.class, 999L).isEmpty());
        }
    }

    @Test
    void findAll() {
        var db = builder(uniqueDb("findall")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);

            orm.insert(new Post("A", "a"));
            orm.insert(new Post("B", "b"));

            List<Post> posts = orm.findAll(Post.class);
            assertEquals(2, posts.size());
        }
    }

    // ==================== update ====================

    @Test
    void updateBean() {
        var db = builder(uniqueDb("update")).build();
        try (db) {
            db.execute("CREATE TABLE comments (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, text VARCHAR(255), post_id BIGINT)");
            Orm orm = Orm.of(db);

            Comment c = new Comment("original", 1L);
            orm.insert(c);
            c.text = "updated";
            orm.update(c);

            Comment reloaded = orm.findById(Comment.class, c.id).orElseThrow();
            assertEquals("updated", reloaded.text);
        }
    }

    // ==================== delete ====================

    @Test
    void deleteEntity() {
        var db = builder(uniqueDb("delete")).build();
        try (db) {
            db.execute("CREATE TABLE comments (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, text VARCHAR(255), post_id BIGINT)");
            Orm orm = Orm.of(db);

            Comment c = new Comment("text", 1L);
            orm.insert(c);
            orm.delete(c);

            assertTrue(orm.findById(Comment.class, c.id).isEmpty());
        }
    }

    @Test
    void deleteById() {
        var db = builder(uniqueDb("deleteById")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);

            orm.insert(new Post("x", "y"));
            orm.deleteById(Post.class, 1L);

            assertTrue(orm.findById(Post.class, 1L).isEmpty());
        }
    }

    // ==================== edge cases ====================

    @Test
    void updateWithoutIdThrows() {
        var db = builder(uniqueDb("noid_update")).build();
        try (db) {
            db.execute("CREATE TABLE comments (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, text VARCHAR(255), post_id BIGINT)");
            Orm orm = Orm.of(db);

            Comment c = new Comment("text", 1L);
            assertThrows(SqlException.class, () -> orm.update(c));
        }
    }

    @Test
    void nonGeneratedIdIsPreserved() {
        var db = builder(uniqueDb("preserve")).build();
        try (db) {
            db.execute("CREATE TABLE app_user (username VARCHAR(255) PRIMARY KEY, email VARCHAR(255))");
            Orm orm = Orm.of(db);

            User u = new User("bob", "bob@example.com");
            orm.insert(u);
            User found = orm.findById(User.class, "bob").orElseThrow();

            assertEquals("bob", found.username);
            assertEquals("bob@example.com", found.email);
        }
    }

    // ==================== findAll ordering / limit / offset ====================

    @Test
    void findAllOrderedBy() {
        var db = builder(uniqueDb("ordered")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);
            orm.insert(new Post("C", "c"));
            orm.insert(new Post("A", "a"));
            orm.insert(new Post("B", "b"));

            List<Post> posts = orm.findAll(Post.class, "title ASC", 0, 0);
            assertEquals("A", posts.get(0).title());
            assertEquals("B", posts.get(1).title());
            assertEquals("C", posts.get(2).title());
        }
    }

    @Test
    void findAllWithLimit() {
        var db = builder(uniqueDb("limit")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);
            orm.insert(new Post("A", "a"));
            orm.insert(new Post("B", "b"));
            orm.insert(new Post("C", "c"));

            List<Post> posts = orm.findAll(Post.class, "id ASC", 2, 0);
            assertEquals(2, posts.size());
        }
    }

    @Test
    void findAllWithOffset() {
        var db = builder(uniqueDb("offset")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);
            orm.insert(new Post("A", "a"));
            orm.insert(new Post("B", "b"));
            orm.insert(new Post("C", "c"));

            List<Post> posts = orm.findAll(Post.class, "id ASC", 0, 1);
            assertEquals(2, posts.size());
            assertEquals("B", posts.get(0).title());
            assertEquals("C", posts.get(1).title());
        }
    }

    @Test
    void findAllOffsetWithoutLimitDelegatesToDialect() {
        RecordingDialect recording = new RecordingDialect();
        var db = builder(uniqueDb("offset_nolimit_dialect")).dialect(recording).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);
            orm.insert(new Post("A", "a"));
            orm.insert(new Post("B", "b"));
            orm.insert(new Post("C", "c"));

            List<Post> posts = orm.findAll(Post.class, "id ASC", 0, 1);

            assertEquals(2, posts.size());
            assertEquals("B", posts.get(0).title());
            assertEquals(1, recording.offsetOnlyCalls,
                "offset without limit must go through the dialect's offsetOnlyClause, not a bare OFFSET");
        }
    }

    // ==================== save (upsert) ====================

    @Test
    void saveInsertsWhenIdIsNull() {
        var db = builder(uniqueDb("save_insert")).build();
        try (db) {
            db.execute("CREATE TABLE comments (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, text VARCHAR(255), post_id BIGINT)");
            Orm orm = Orm.of(db);

            Comment c = new Comment("hello", 1L);
            orm.save(c);
            assertNotNull(c.id);
            assertEquals(1L, (long) c.id);
        }
    }

    @Test
    void saveReturnsGeneratedId() {
        var db = builder(uniqueDb("save_id")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT)");
            Orm orm = Orm.of(db);

            Post p = new Post("Hello", "World");
            var r = orm.save(p);
            assertTrue(r.hasKey());
        }
    }

    @Test
    void saveWithExistingGeneratedIdUpdatesInPlace() {
        // H2 cannot execute ON CONFLICT DO UPDATE, so verify the generated
        // SQL shape instead: the explicit @Generated id must be part of the
        // INSERT column list, otherwise the conflict target is never supplied
        // and save() silently inserts a duplicate row.
        RecordingDatabase db = new RecordingDatabase(new PostgresDialect());
        Orm orm = Orm.of(db);

        Comment c = new Comment("updated", 1L);
        c.id = 5L;
        orm.save(c);

        assertTrue(db.lastSql().startsWith("INSERT INTO comments"),
            "expected INSERT, got: " + db.lastSql());
        assertTrue(db.lastSql().contains("(text, post_id, id)"),
            "explicit id must be in the INSERT column list: " + db.lastSql());
        assertTrue(db.lastSql().contains("ON CONFLICT (id) DO UPDATE"),
            "conflict target must reference the id: " + db.lastSql());
        assertTrue(Arrays.asList(db.lastParams()).contains(5L),
            "the explicit id value must be bound: " + Arrays.toString(db.lastParams()));
    }

    @Test
    void saveWithExplicitIdInsertsWhenRowMissing() {
        RecordingDatabase db = new RecordingDatabase(new PostgresDialect());
        Orm orm = Orm.of(db);

        Comment c = new Comment("hello", 1L);
        c.id = 42L;
        orm.save(c);

        assertTrue(db.lastSql().contains("(text, post_id, id) VALUES"),
            "explicit id must be supplied to the INSERT: " + db.lastSql());
        assertTrue(Arrays.asList(db.lastParams()).contains(42L),
            "the explicit id value must be bound: " + Arrays.toString(db.lastParams()));
    }

    @Test
    void saveWithPrimitiveGeneratedIdInsertsAndGetsSequenceKey() {
        var db = builder(uniqueDb("prim_gen_id")).build();
        try (db) {
            db.execute("CREATE TABLE primitive_id (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(255))");
            Orm orm = Orm.of(db);

            // A fresh entity reads its primitive id as 0L, never null — save()
            // must treat that as "unset" and insert (letting the sequence
            // assign the key) instead of upserting an explicit zero id.
            PrimitiveId a = new PrimitiveId("a");
            ExecuteResult ra = orm.save(a);
            assertTrue(ra.hasKey());
            assertTrue(ra.longKey() > 0, "generated key must be non-zero");
            assertTrue(a.id > 0, "id must be written back to the entity");

            PrimitiveId b = new PrimitiveId("b");
            ExecuteResult rb = orm.save(b);
            assertTrue(rb.longKey() > 0);
            assertTrue(rb.longKey() != ra.longKey(),
                "second save must get a different id");
            assertTrue(b.id != a.id);

            List<PrimitiveId> rows = db.query("select id, name from primitive_id order by id")
                .list(PrimitiveId.class);
            assertEquals(2, rows.size());
            assertTrue(rows.stream().noneMatch(r -> r.id == 0),
                "no row may carry the zero id: " + rows);
            assertEquals("a", rows.get(0).name);
            assertEquals("b", rows.get(1).name);
        }
    }

    // ==================== explicit columns (not SELECT *) ====================

    @Test
    void handlesExtraColumnInTable() {
        var db = builder(uniqueDb("extra_col")).build();
        try (db) {
            db.execute("CREATE TABLE post (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(255), body TEXT, hidden VARCHAR(255) DEFAULT 'x')");
            Orm orm = Orm.of(db);

            Post p = new Post("A", "body");
            orm.insert(p);

            Post found = orm.findById(Post.class, 1L).orElseThrow();
            assertEquals("A", found.title());
            // hidden column not in the entity — SELECT only lists known columns
        }
    }
}
