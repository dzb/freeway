# Freeway 框架 ioc/db 模块审计报告

> **审计时间**: 2026-05-29 22:00
> **基线版本**: `1.0.2` (commit `cb474ac`)
> **测试状态**: 212 个测试全部通过 ✅

---

## 一、测试覆盖率概览

| 模块 | 测试类数 | 测试用例 | 状态 |
|------|---------|---------|------|
| freeway-ioc | 1 | 45 | ✅ 全部通过 |
| freeway-db | 13 | 143 | ✅ 全部通过 |
| **合计** | **14** | **212** | ✅ |

---

## 二、freeway-ioc 审计发现（共 16 项）

### 🔴 P0（致命）— 2 项

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| 1 | `ContainerImpl.java:60` | `bindings` 使用非线程安全的 `LinkedHashMap`，读路径无同步 | 替换为 `ConcurrentHashMap` 或 `synchronized` 包裹 |
| 2 | `ContainerImpl.java:334-343` | 单例创建存在 TOCTOU 竞态：两个线程可同时创建同一单例 | 使用 `ConcurrentHashMap.computeIfAbsent` |

### 🟠 P1（高）— 4 项

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| 3 | `ContainerImpl.java:71-73` | ThreadLocal 作用域与虚拟线程不兼容 | 提供 `ScopedValue` 替代实现 |
| 4 | `ProxyFactoryDefault.java:49,130` | MethodHandle 每次调用都重新反射查找 | 用 `ConcurrentHashMap<Method, MethodHandle>` 缓存 |
| 5 | `BindingImpl.java:78-83` | `to(instance)` 静默覆盖 scope 为 SINGLETON | scope 非 SINGLETON 时抛 `IllegalStateException` |
| 6 | `ContainerImpl.java:301-308` | 未绑定具体类静默自动装配，无约束 | 增加 `@AutoBind` 或模块级开关 |

### 🟡 P2（中）— 5 项

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| 7 | `ExtensionHub.java:51-103` | `order()` 拓扑排序 O(n²) | 改用 Kahn 算法 O(V+E) |
| 8 | `ContainerImpl.java:784-818` | Container/SymbolSource/Coercer/Logger 隐式注入绕过 `@Inject` | 统一要求注解 |
| 9 | `ContainerImpl.java:839-855` | 无注解字段也走 `resolveDefaultExtensionValue` | 字段注入统一要求注解 |
| 10 | (多个文件) | 测试覆盖不足：并发单例、虚拟线程、循环依赖、close 后调用等 | 补充专项测试 |
| 11 | `ContainerImpl.java:140-178` | `@PreDestroy` 中回调 `get()` 会抛 `IllegalStateException` | 文档明确禁止 |

### ⚪ P3（低）— 5 项

| # | 文件 | 问题 |
|---|------|------|
| 12 | `ContainerImpl.java` | Javadoc 中英文混用 |
| 13 | `Inject.java:10` | `@Inject` 构造器注入无文档示例 |
| 14 | `ServiceIds.java` / `ExtensionHub.java` | ID 规范化逻辑重复 |
| 15 | `BindingImpl.java:64-75` | 异常嵌套冗余堆栈 |
| 16 | `AdviceEntry.java:6` | 完全限定名冗余 |

---

## 三、freeway-db 审计发现（共 20 项）

### 🔴 P0（致命）— 5 项

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| 1 | `ConnectionPool.java` | `borrow()` 使用 `wait(100)` 轮询，非精确唤醒 | 改为无限 `wait()`，依赖 `release()` 中 `notify()` |
| 2 | `ConnectionPool.java` | `close()` 未处理 `active` 中已借出连接 | 遍历 active 集合关闭物理连接 + 标记无效 |
| 3 | `ConnectionPool.java` | 连接池无有效性验证（`isValid()`） | borrow 时调用 `isValid(validationTimeout)` |
| 4 | `ConnectionPool.java` | 无 borrow 超时控制，可永久阻塞 | 引入 `borrowTimeout` 配置 + `wait(timeout)` |
| 5 | `Transaction.java` | 事务无隔离级别控制 | 增加 `isolationLevel()` 默认方法 |

### 🟠 P1（高）— 5 项

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| 6 | `RowMapperResolver.java` | 每次解析都做反射，无缓存 | 引入 `ConcurrentHashMap<Class<?>, RowMapper<?>>` |
| 7 | `SQL.java` | 不支持 INSERT ... RETURNING / UPSERT / CTE / UNION | 增加对应方法 |
| 8 | `BatchQuery.java` | 批量操作无事务保护 | 自动 `setAutoCommit(false)` + commit/rollback |
| 9 | `MigrationRunner.java` | 仅支持 classpath SQL | 支持外部目录 + 校验和检测 |
| 10 | `DatabaseHub.java` | 无默认数据库概念 | 增加 `hub.primary()` 便捷方法 |

### 🟡 P2（中）— 6 项

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| 11 | `DatabaseImpl.java` | 每次查询都 borrow/release 无连接复用 | 考虑 session 概念 |
| 12 | `SQL.java` | `toString()` 丢弃参数值信息 | 返回 `sql() + \" | args=\" + args()` |
| 13 | `NamedParamParser.java` | PostgreSQL `$N` 占位符被误解析 | 增加 `raw=true` 参数跳过解析 |
| 14 | `QueryImpl.java` | 流式查询未设 fetchSize，大结果 OOM | 设 `stmt.setFetchSize(Integer.MIN_VALUE)` |
| 15 | `DatabaseStats.java` | 缺少等待队列/平均等待时间等指标 | 扩展监控指标 |
| 16 | `RowMapperResolver.java` | 对抽象类/接口无处理 | 抛出明确异常信息 |

### ⚪ P3（低）— 4 项

| # | 文件 | 问题 |
|---|------|------|
| 17 | (测试) | 缺少连接池高并发压力测试 |
| 18 | `SQL.java` | `select()` 返回类型不够明确，无法编译期约束调用顺序 |
| 19 | (测试) | `builder()` 辅助方法大量重复，应提取共享工具类 |
| 20 | `PooledConnection.java` | `lastReturned` 命名与 `borrowedAt` 不一致 |

---

## 四、核心风险总结

### IoC 模块
- **并发安全是最大隐患**：P0 的 `bindings` 线程安全和单例竞态问题可能在多线程环境中导致数据不一致
- **虚拟线程兼容性亟需解决**：作为 JDK 25+ 框架，ThreadLocal 作用域设计需要升级到 `ScopedValue`

### DB 模块
- **连接池是核心风险链**：P0-1 ~ P0-4 四个问题串联 —— 轮询等待 + 无超时 + 无有效性验证 + close 竞态，生产环境长时间运行几乎必然出问题
- **事务能力有待完善**：缺少隔离级别控制和批量操作事务保护
- **查询能力有缺口**：缺少 UPSERT/CTE/UNION/RETURNING 等现代 SQL 特性

### 整体
- **架构质量很高**：API 设计清晰一致（compose-first、不可变 SQL Builder、Record 自动映射）
- **测试覆盖良好**：212 个测试全部通过，SQL 解析和 RowMapper 覆盖尤其扎实
- **连接池需优先修复**：5 个 P0 问题中 4 个在连接池，建议优先处理

---

*报告由 freeway-ioc/db 模块深度审计生成*
