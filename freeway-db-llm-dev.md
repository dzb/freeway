  **全部通过！** `Tests run: 7, Failures: 0, Errors: 0` ✅

---

  ### 运行结果总览

| #    | 测试                        | 结果 | 说明                                                         |
| ---- | --------------------------- | ---- | :----------------------------------------------------------- |
| ✅    | `step01_showDDL`            | 通过 | `Schema.define(User.class)` 生成 DDL                         |
| ✅    | `step02_ensureCreatesTable` | 通过 | `Schema.ensure()` 自动建表                                   |
| ✅    | `step03_fullCrudWithRecord` | 通过 | **核心**：Record 风格 C(INSERT) + R(findById/findAll) + U(update Bean) + D(delete) |
| ✅    | `step04_rawSql`             | 通过 | 原始 SQL + Row 列访问 + Record 映射                          |
| ✅    | `step05_sqlBuilder`         | 通过 | `SQL.select().from().where().orderBy()` 构建器               |
| ✅    | `step06_transaction`        | 通过 | 事务提交 & 回滚验证                                          |
| ✅    | `step07_saveUpsert`         | 通过 | `save()` INSERT + `update()` 常规更新                        |

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

  结论：**LLM 完全可以读懂并使用 freeway 进行开发**，从实体定义到 DDL 建表再到 CRUD 存取的完整链路已跑通。 (deepseek-v4-天命人, 950088tk, 259s)