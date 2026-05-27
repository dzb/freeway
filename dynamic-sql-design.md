# Freeway 动态 SQL 设计方案

## 核心哲学

1. **静态 SQL 是主角**（Java 25 文本块），动态 SQL 是附加项
2. **零依赖**，单个文件，~150 行
3. **不可变（Immutable）**，每个方法返回新实例，符合 compose-first
4. **只生产 `db.sql(String, Object...)` 需要的东西**，不入侵现有执行层

## 命名与入口

```java
import com.jujin.freeway.db.SQL;

// 类名 `SQL`（全大写，与变量名 `sql`/`q` 视觉区分）
// 静态工厂方法作为入口
```

## API 设计（完整）

```java
// ─── SELECT ───────────────────────────────────────────
SQL.select("id, name, email")      // SELECT columns
   .from("users")                  // FROM table
   .where("status = ?", "active")  // WHERE (AND 累积)
   .where("age >= ?", 18)
   .orderBy("id DESC")             // ORDER BY
   .limit(10)                      // LIMIT
   .offset(20);                    // OFFSET

// 支持的 JOIN
SQL.select("u.*, o.total")
   .from("users u")
   .join("orders o ON o.user_id = u.id")
   .leftJoin("profiles p ON p.user_id = u.id")
   .where("o.total > ?", 1000);

// ─── UPDATE ───────────────────────────────────────────
SQL.update("users")
   .set("name", "new name")
   .set("status", "inactive")
   .where("id = ?", 42L);

// ─── INSERT ───────────────────────────────────────────
SQL.insertInto("users")
   .values("bob", "active");
// => INSERT INTO users VALUES (?, ?)

SQL.insertInto("users", "name", "status")
   .values("bob", "active");
// => INSERT INTO users (name, status) VALUES (?, ?)

// ─── DELETE ───────────────────────────────────────────
SQL.deleteFrom("users")
   .where("id = ?", 42L);

// ─── 使用方式 ─────────────────────────────────────────
SQL q = SQL.select("id, name").from("users");
if (name != null) q = q.where("name LIKE ?", name);
if (status != null) q = q.where("status = ?", status);
q = q.orderBy("id");

// 直接喂给现有的 db.sql()
List<User> users = db.sql(q.sql(), q.args()).list(User.class);
```

## 与现有系统的关系

```
SQL builder (新文件)
     ↓ 生产
sql() → String     ─┐
args() → Object[]  ─┤
                     ↓
          db.sql(String sql, Object... args)  ← 现有 API，不改动
                     ↓
          QueryImpl (现有实现，不改动)
```

## 关键原则

| 原则 | 说明 |
|------|------|
| **WHERE 是 AND 累积** | 每次 `.where()` 追加一个 AND 条件。OR 自己写在条件字符串里 |
| **参数永远走 `?`** | 全部用位置参数，不走命名参数。减少复杂度，跟现有系统自然对接 |
| **不可变** | 每步返回新 `SQL` 实例，变量需要重新赋值 `q = q.where(...)` |
| **不验证 SQL 合法性** | 只管拼接，不解析 SQL 语法。不存在的表名、拼错的列名交给 DB 报错 |
| **不做缓存** | `sql()` 和 `args()` 每次都重新构建，无状态 |

## 方法清单（按使用频率排序）

| 方法 | 用途 |
|------|------|
| `SQL.select(cols)` | 开始 SELECT 查询 |
| `.from(table)` | FROM 子句 |
| `.where(cond, args...)` | WHERE 条件（AND 累积） |
| `.orderBy(order)` | ORDER BY 子句 |
| `.limit(n)` | LIMIT |
| `.offset(n)` | OFFSET |
| `.join(clause)` | JOIN（字符串原样拼接） |
| `.leftJoin(clause)` | LEFT JOIN |
| `.innerJoin(clause)` | INNER JOIN |
| `.groupBy(cols)` | GROUP BY |
| `.having(cond, args...)` | HAVING |
| `SQL.update(table)` | 开始 UPDATE |
| `.set(col, val)` | SET 赋值（累积） |
| `SQL.insertInto(table, cols...)` | 开始 INSERT |
| `.values(vals...)` | VALUES |
| `SQL.deleteFrom(table)` | 开始 DELETE |
| `.sql()` | 获取最终 SQL 字符串 |
| `.args()` | 获取最终参数数组（防御性拷贝） |

## 不做清单

- ❌ 不做 `orWhere()` — OR 写在字符串里，如 `.where("status = ? OR status = ?", "a", "b")`
- ❌ 不做 `whereIf(cond, ...)` — `if` 写在 Java 代码里，这是 Java 的优势不是劣势
- ❌ 不做嵌套分组 `and(() -> ...)` — 太复杂，用原生 SQL
- ❌ 不做 `<if>/<foreach>/<where>` 标签 — 那是 XML 时代的产物
- ❌ 不做 SQL 语法验证 — 让数据库报错
- ❌ 不做类型推断 — 参数类型由 `PreparedStatement.setObject()` 处理
