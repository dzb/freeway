  ## Freeway-DB 使用模式总结

  ### 基本用法

  ```java
  // 1. 构建数据库
  Database db = new DatabaseBuilder()
      .config(DatabaseConfig.defaults("jdbc:...", "user", "pass"))
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

  // 7. 事务
  db.transaction(() -> {
      db.execute("UPDATE accounts SET balance = balance - ? WHERE id = ?", 100, 1);
      db.execute("UPDATE accounts SET balance = balance + ? WHERE id = ?", 100, 2);
  });

  // 8. ORM
  Orm orm = Orm.of(db);
  orm.insert(new User("Alice", "alice@example.com"));
  User u = orm.findById(User.class, "Alice").orElseThrow();
  u.setEmail("new@example.com");
  orm.update(u);
  orm.delete(u);

  // 9. 更新插入
  orm.save(new User("Bob", "bob@example.com"));  // 插入或更新

  // 10. Schema 自动迁移
  Schema.ensure(db, User.class, Post.class);

  // 11. SQL 构建器
  SQL q = SQL.select("*").from("users")
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
  ORM 存取      →  Orm.insert / findById / findAll / update / delete / save
  原始 SQL      →  db.execute() / db.query()
  SQL 构建器    →  SQL.select().from().where().orderBy()
  Row 列访问    →  row.string("name") / row.integer("age")
  事务处理      →  db.transaction(() -> ...)
  ```

结论：**LLM 完全可以读懂并使用 freeway 进行开发**，从实体定义到 DDL 建表再到 CRUD 存取的完整链路已跑通。
(deepseek-v4-天命人, 950088tk, 259s)
