  ## Freeway-DB 使用模式总结

  ### 基本用法

  ```java
  // 1. 构建数据库
  Database db = new DatabaseBuilder()
      .config(PoolConfig.defaults("jdbc:...", "user", "pass"))
      .build();

  // 2. 原始查询
  List<User> users = db.query("SELECT * FROM users WHERE status = ?", 1).list(User.class);
  Optional<User> user = db.query("SELECT * FROM users WHERE id = ?", 42).one(User.class);

  // 3. 命名参数
  User u = db.query("SELECT * FROM users WHERE id = $id")
      .param("id", 42L)
      .one(User.class).orElseThrow();

  // 4. 流式处理
  try (Stream<User> stream = db.query("SELECT * FROM users").stream(User.class)) {
      stream.forEach(System.out::println);
  }

  // 5. DML
  ExecuteResult r = db.execute("INSERT INTO users (name) VALUES (?)", "Alice");
  long id = r.longKey();
  int affected = r.rows();

  // 6. 批处理
  db.batch("INSERT INTO users (name) VALUES (?)")
      .rows(new Object[]{"A"}, new Object[]{"B"})
      .execute();

  // 7. 事务 + 事件总线感知
  db.transaction(() -> {
      db.execute("UPDATE accounts SET balance = balance - ? WHERE id = ?", 100, 1);
      db.execute("UPDATE accounts SET balance = balance + ? WHERE id = ?", 100, 2);
      bus.publish(new TransferEvent(1, 2, 100));  // 自动延迟到提交后发布
  });

  // 8. ORM (Record 实体 — insert + find 可用，update/delete 走原始 SQL)
  Orm orm = Orm.of(db);
  orm.insert(new User(null, "Alice", null, "alice@example.com", null));
  User u = orm.findById(User.class, 1L).orElseThrow();
  db.execute("DELETE FROM users WHERE id = ?", 1L);

  // 9. Upsert（id 为 null → INSERT；id 有值 → ON CONFLICT DO UPDATE）
  orm.save(new User(null, "Bob", null, "bob@example.com", null));  // INSERT
  // orm.save(existingUser);                                        // UPSERT

  // 10. Schema 自动迁移
  Schema.ensure(db, User.class, Post.class);

  // 11. SQL 构建器
  Sql q = Sql.select("*").from("users")
      .where("status = ?", 1)
      .orderBy("created_at DESC")
      .limit(10);
  List<User> users = db.query(q).list(User.class);
  ```
### 注解驱动实体

  ```java
  @Table("users")              // 表名覆盖（默认：User → user）
  public record User(
      @Id @Generated Long id,  // 自动生成的主键
      @Column("user_name")     // 列名覆盖
      @Size(max = 100)         // VARCHAR(100)
      String name,
      @Column(type = "TEXT")   // 显式 SQL 类型
      String bio,
      @Index(name = "idx_email", unique = true)  // 唯一索引
      String email,
      @Transient                // 非持久化
      String temp
  ) {}
  ```

### 演示的 freeway-db 能力

  ```
  注解驱动实体  →  @Table @Id @Generated @Column
  DDL 自动生成  →  Schema.define() / Schema.ensure()
  ORM 存取      →  Orm.insert / findById / findAll / save (upsert)
  原始 SQL      →  db.execute() / db.query()
  SQL 构建器    →  Sql.select().from().where().orderBy()
  Row 列访问    →  row.string("name") / row.integer("age")
  事务处理      →  db.transaction(() -> ...) + EventBus 事务感知
  ```

结论：**LLM 完全可以读懂并使用 freeway 进行开发**，从实体定义到 DDL 建表再到 CRUD 存取的完整链路已跑通。
