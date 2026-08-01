# freeway-db 使用模式手册

> 基于 freeway-db 模块的完整开发体验总结。涵盖：实体定义 → DDL 自动建表 → ORM 存取 → 原始 SQL → SQL 构建器 → 事务 → 自增 ID 行为。

---

## 一、实体定义

支持两种风格：**Record（不可变）** 和 **Bean（可变）**。使用注解驱动元数据。

### 1.1 Record 风格（不可变，适合查询/插入）

```java
@Table("t_user")                  // 指定表名（不指定则取类名小写）
record User(
    @Id @Generated Long id,       // 自增主键
    @Column String name,          // 列名默认同字段名，VARCHAR(255)
    @Column Integer age           // INTEGER
) {
    // 构造时不用管 id，数据库自动生成
    User(String name, Integer age) { this(null, name, age); }
}
```

### 1.2 Bean 风格（可变，适合 update 场景）

```java
@Table("t_user")
static class UserBean {
    @Id @Generated Long id;       // 自增主键
    @Column String name;
    @Column Integer age;

    UserBean() {}
    UserBean(String name, Integer age) { this.name = name; this.age = age; }
}
```

### 1.3 注解说明

| 注解 | 作用 | 说明 |
|------|------|------|
| `@Table("xxx")` | 映射表名 | 不指定则取类名小写 |
| `@Id` | 标记主键 | 必填 |
| `@Generated` | 自增主键 | 搭配 `@Id` 使用，数据库自动生成 |
| `@Column("xxx")` | 映射列名 | 不指定则取字段名 |
| `@Transient` | 忽略字段 | 不参与持久化 |

---

## 二、DDL 自动生成

### 2.1 查看 DDL 语句（不执行）

```java
String ddl = Schema.define(User.class);
System.out.println(ddl);
// → CREATE TABLE t_user (
//     id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
//     name VARCHAR(255),
//     age INTEGER
//   )
```

### 2.2 自动建表（安全执行，表已存在不报错）

```java
Database db = ...;
Schema.ensure(db, User.class);
```

---

## 三、数据库构建

### 3.1 直接构建

```java
Database db = new DatabaseBuilder()
    .config(PoolConfig.defaults(
        "jdbc:h2:mem:mydb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "sa", ""))
    .build();
```

### 3.2 IoC 模块方式

```java
Container container = Freeway.create(new DbModule());
Database db = container.get(Database.class);
```

### 3.3 多数据源

```java
Database primary = ...;
Database audit = ...;

Container container = Freeway.create(
    new DbModule(),
    binder -> {
        binder.contribute(NamedDatabase.class)
              .add(new NamedDatabase("primary", primary));
        binder.contribute(NamedDatabase.class)
              .add(new NamedDatabase("audit", audit));
    }
);

DatabaseHub hub = container.get(DatabaseHub.class);
Database p = hub.get("primary");
Database a = hub.get("audit");
Database def = hub.primary();
```

---

## 四、ORM 存取（`Orm` 类）

### 4.1 获取 ORM 实例

```java
Orm orm = Orm.of(db);
```

### 4.2 Create — INSERT

```java
// Record 风格（返回结果中获取 ID）
ExecuteResult r = orm.insert(new User("闪电", 3));
long id = r.longKey();

// Bean 风格（ID 写回对象）
UserBean bean = new UserBean("煤球", 5);
orm.insert(bean);
// bean.id 已被赋值
```

### 4.3 Read — 查询

```java
// 按 ID 查找
User found = orm.findById(User.class, 1L).orElseThrow();

// 查找全部
List<User> all = orm.findAll(User.class);

// 排序 + 分页（orderBy, limit, offset）
List<User> ordered = orm.findAll(User.class, "age ASC", 0, 0);  // 全部，age 升序
List<User> limited = orm.findAll(User.class, "age ASC", 2, 0);  // 取前 2 条
List<User> offset  = orm.findAll(User.class, "id ASC", 0, 1);   // 跳过第 1 条
```

### 4.4 Update — 更新（仅 Bean）

```java
UserBean bean = orm.findById(UserBean.class, 1L).orElseThrow();
bean.age = 4;                       // 修改字段
ExecuteResult r = orm.update(bean); // 按 ID 更新
// r.rows() → 影响行数
```

### 4.5 Delete — 删除

```java
// 按对象删除
orm.delete(bean);

// 按 ID 删除
orm.deleteById(UserBean.class, 1L);
```

### 4.6 Save — 插入或更新

```java
// id=null → INSERT
UserBean u = new UserBean("闪电", 3);
orm.save(u);
// u.id 已被赋值

// id=已有值（PostgreSQL 下走 ON CONFLICT DO UPDATE）
// H2 不支持 ON CONFLICT，所以仅 INSERT 路径可用
```

---

## 五、原始 SQL

### 5.1 执行 INSERT / UPDATE / DELETE

```java
// 参数用 ? 占位，自动防注入
ExecuteResult r = db.execute("INSERT INTO t_user (name, age) VALUES (?, ?)", "闪电", 3);
long id = r.longKey();   // 自增 ID
int rows = r.rows();     // 影响行数
```

### 5.2 查询并映射

```java
// 映射到 Record
User user = db.query("SELECT * FROM t_user WHERE id = ?", 1L)
    .one(User.class).orElseThrow();

// 映射到 List
List<User> users = db.query("SELECT * FROM t_user WHERE age >= ?", 3)
    .list(User.class);

// 映射到单列
Long count = db.query("SELECT count(*) FROM t_user")
    .one(Long.class).orElseThrow();
```

### 5.3 命名参数

```java
User user = db.query("SELECT * FROM t_user WHERE id = $id")
    .param("id", 1L)
    .one(User.class).orElseThrow();
```

### 5.4 集合参数（IN 查询）

```java
List<User> users = db.query("SELECT * FROM t_user WHERE id IN (?)", List.of(1L, 2L, 3L))
    .list(User.class);
```

### 5.5 批量操作

```java
db.batch("INSERT INTO t_user (name, age) VALUES (?, ?)")
    .rows(
        new Object[]{"闪电", 3},
        new Object[]{"煤球", 5}
    )
    .execute();
```

---

## 六、Row 行访问（`Row` 类）

当查询结果映射到 `Row.class` 时，可以按列名灵活取值：

```java
List<Row> rows = db.query("SELECT * FROM t_user WHERE id = ?", 1L)
    .list(Row.class);

Row row = rows.get(0);
String name  = row.string("name");       // 字符串
Integer age  = row.integer("age");      // 整数
Long    id   = row.longValue("id");       // 长整型
Double score = row.doubleVal("score");  // 浮点数
List<String> cols = row.columns();      // 获取所有列名
```

---

## 七、SQL 构建器（`Sql` 类）

链式构建 SQL 语句，适合动态条件场景：

```java
List<User> adults = db.query(
    Sql.select("*")
        .from("t_user")
        .where("age >= ?", 3)
        .orderBy("age DESC")
).list(User.class);
```

INSERT 构建器：

```java
ExecuteResult r = db.execute(
    Sql.insert("t_user")
        .set("name", "闪电")
        .set("age", 3)
);
```

---

## 八、事务

```java
// 成功提交
db.transaction(() -> {
    orm.insert(new User("闪电", 3));
    orm.insert(new User("煤球", 5));
});

// 异常时自动回滚
try {
    db.transaction(() -> {
        orm.insert(new User("会回滚", 99));
        throw new RuntimeException("模拟异常");
    });
} catch (RuntimeException ignored) {
    // 事务已回滚，数据不变
}
```

### 事务感知的事件发布

事务内调用 `bus.publish()` 自动延迟到提交后执行——提交成功才发，回滚不发。零配置，框架基于 `Defer` 的提交后执行机制自动处理。

```java
db.transaction(() -> {
    orm.insert(new User("闪电", 3));
    bus.publish(new UserCreatedEvent(user));  // 提交后才真正发布
});
```

---

## 九、ExecuteResult 返回值

`db.execute()` / `orm.insert()` 等操作返回 `ExecuteResult`，用于获取执行结果：

```java
ExecuteResult r = db.execute("INSERT INTO t_user (name, age) VALUES (?, ?)", "闪电", 3);

r.rows();        // 影响行数（int）
r.hasKey();      // 是否包含自增 ID（boolean）
r.longKey();     // 自增 ID 值（long）

// 典型断言
assertTrue(r.hasKey());
assertTrue(r.longKey() > 0);
assertEquals(1, r.rows());
```

---

## 十、自增 ID 核心行为

| 操作 | `hasKey()` | `longKey()` | 说明 |
|------|-----------|-------------|------|
| **INSERT** (普通) | `true` | 生成的 ID | 自增值从 1 开始递增 |
| **INSERT** (显式指定 id) | `true` | 指定的值 | 如 `set("id", 100L)` |
| **INSERT** (事务内) | `true` | 生成的 ID | 事务提交前也可获取 |
| **INSERT** (SQL 构建器) | `true` | 生成的 ID | 和原始 SQL 一致 |
| **INSERT** (含注释 SQL) | `true` | 生成的 ID | `/* ... */ insert ...` 仍识别 |
| **UPDATE** | `false` | `0` | 不返回自增 ID |
| **DELETE** | `false` | `0` | 不返回自增 ID |

---

## 十一、完整开发模板

```java
// 1. 定义实体
@Table("t_user")
record User(@Id @Generated Long id, @Column String name, @Column Integer age) {
    User(String name, Integer age) { this(null, name, age); }
}

// 2. 建库 + 建表
Database db = new DatabaseBuilder()
    .config(PoolConfig.defaults("jdbc:h2:mem:demo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
    .build();
Schema.ensure(db, User.class);
Orm orm = Orm.of(db);

// 3. CRUD
var r = orm.insert(new User("闪电", 3));           // Create
User u = orm.findById(User.class, r.longKey())...;   // Read
// ...（Bean 风格 Update/Delete）

// 4. 收尾
db.close();  // Database 实现了 AutoCloseable
```

---

> **结论**: freeway-db 提供了从 DDL 到 CRUD 的完整数据库开发能力，注解简洁、API 一致、支持 Record 和 Bean 双风格。LLM 完全可以读懂并用它进行开发。
