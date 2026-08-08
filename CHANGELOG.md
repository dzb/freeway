# Changelog

All notable changes to Freeway 2 will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **移除 `freeway.web.*` 配置键回退（freeway-http）** — v1.2.1 曾使用
  `freeway.web.*` 前缀，v1.2.2 起改为 `freeway.http.*` 并保留旧键回退兼容。
  现移除 legacy 回退（`HttpConfigKeys.LEGACY_PREFIX` 与
  `config(...)` helper 的 legacyKey 参数）：仅 `freeway.http.*` 生效，
  仍使用 `freeway.web.*` 配置的应用需改为新前缀。
- **依赖升级** — JUnit Jupiter 5.12.0 → 6.1.3（JUnit 6），SLF4J 2.0.17 →
  2.0.18（2.0 线最新稳定版；2.1 仍是 alpha），H2 2.3.232 → 2.4.240（测试依赖）。
  JMH 1.37 已是最新。
- **`HttpContext.headerSet(...)` renamed to `setHeader(...)`** — aligns the
  response-header setter with the chainable `status(...)`/`maxBodySize(...)`
  style and the `header(...)` getter (breaking rename; `headerSet` is gone).
- **SQL 解析改为方言驱动** — `Dialect` 声明词法能力（标识符引号、`#` 注释、
  `$tag$`/`E'...'` 字面量），`SqlTextParser` 收敛为单一 `scan()` 原语；查询与
  迁移按目标数据库画像解析，扫描器不再硬编码数据库语法。
- **`Dialect` 提取为独立包、DDL 装配移出接口** — `com.jujin.freeway.db.dialect`
  自包含（只依赖 `db.Database`）；CREATE TABLE/INDEX/ALTER/DROP 由
  `Schema`/`SchemaGenerator` 基于方言原语组装，`Dialect` 只声明语法特性
  （inline PK、DROP CASCADE、ALTER 约束能力等）。

### Fixed

- **事务语义** — 事务内抛 `Error` 正确回滚（此前 restore 连接状态会静默提交失败
  事务）；事务绑定改为按连接身份（并发事务互不误杀对方在途查询）；一个 Database
  的事务不再泄漏到另一 Database（跨库查询此前静默跑在错误连接上）。
- **连接池生命周期** — 修复 cleaner 与 borrow 竞态（连接双销毁、计数漂移）、
  release 与 close 竞态（连接滞留已关闭池）、借用超 maxIdleTime 的活跃连接被
  误销毁、创建失败泄漏物理连接；close 唤醒排队等待者并区分错误消息。
- **ORM/Schema** — `Orm.save()` 带非空自增 id 不再静默插入重复行（ON CONFLICT
  真正生效）；`@Index` 配合 `@Column` 改名生成正确列；无 LIMIT 的 OFFSET 按方言
  输出；向既有表加主键/自增列显式拒绝；零属性类型映射显式报错。
- **SQL 解析与构建** — 反引号/方括号标识符、MySQL `#` 注释、`E'...'` 转义串、
  dollar-quote 按方言正确跳过；INSERT 检测只认语句头部；`Sql` 构建器的空
  insert/update、子句乱序、INSERT 表达式误用均 fail-fast。
- **迁移** — `V1` 与 `V01` 等数字等值版本判重；已应用但文件缺失的迁移启动即报
  错；版本身份归一化（文件重命名不再重放或误报缺失）。
- **HTTP/2 与 HTTP 资源** — trailers、RST_STREAM、连接级流控、HPACK 溢出、帧
  大小边界按 RFC 7540 加固；WebSocket 关闭唤醒读循环、SSL 握手失败不再泄漏
  fd、SSE 写失败关闭 emitter。
- **数值转换正确性** — `BigInteger` 饱和、`1e400 → Double/Float` 溢出、
  `getLong` 越界静默截断、char 数字源低位回绕均显式拒绝；Optional 目标
  null → `empty()` 且防溢出。
- **JSON 与反射** — 具体集合/Map 目标（ArrayList/HashMap）可反序列化；JDK 超类
  类型不再因模块访问崩溃（方法句柄查找统一 publicLookup 回退）。
- **日志** — `applyNamedFileLoggers()` 不再重复挂载命名文件 handler；轮转不再
  丢失缓冲记录、GZIP 原子写入、启动即清理过期归档；无关 `*_LEVEL` 环境变量
  不再创建幻影 logger。
- **校验与作用域** — `@Valid` 支持 Optional/Iterable 且限深 100（防
  StackOverflow）；`Defer.supply` 失败显式重抛且 Error 同样缓存；`ScopedCache`
  与 `Defer` 嵌套契约文档化并运行时告警。
- **IoC 容器** — PROTOTYPE+advice 不再共享缓存 target；关闭 drain 期间新
  实例化的服务获得完整 @PreDestroy/close（此前快照后 realize 的服务泄漏）；
  `publishAsync` 尊重活动 Defer 作用域（事务内异步事件延迟到提交后派发，
  回滚即丢弃）；关闭与 realize 互斥（孤儿实例化竞态与死锁向量一并消除）；
  `SymbolProvider` 类贡献按需接线（声明即注册惰性门面，声明顺序不再影响
  构造）；注册后再声明的 `.marker()`/`.primary()` 生效；`@Value List<...>`
  参数与嵌套 `${a:${b}}` 默认值解析修复；单例注入 THREAD 具体类在无作用域
  时也给出专用诊断。
- **EventBus** — 事件派发层级匹配（子类事件送达父类订阅者，不再误报
  DeadEvent）；`stats()` 派发统计；`publishOrdered(key, …)` 全局有序通道
  （事务内 outbox 顺序场景）；Stoppable 事件短路 bridge；失败模型显式
  文档化（at-most-once：订阅者异常隔离并计数，不重试）。
- **工作流引擎（freeway-flow）** — 表达式求值修复并发缓存竞态、超长表达式
  编译期拒绝（此前 eval 栈溢出且毒化缓存）、long≥2⁵³ 比较不再失真、布尔
  字符串（"false"/"0"）按值解释且与 `==` 一致；interrupt 改为全局语义；
  resume 重放无法到达恢复点时显式失败（此前静默跳过全部任务）；子图每次
  调用重新执行 body（此前第二次调用静默跳过）、trace 关闭不再破坏子图调用；
  INCLUSIVE join 计数激活时重置；LOOP 栈按节点隔离；`$in` 含 null 不再残留
  上一轮循环变量；图模型拒绝 UNKNOWN 类型、显式 entry 与 START 共存、
  无条件重复链接拒绝、PlantUML 输出转义、trace 恢复位经 JSON round-trip
  保留。
- **启动装配（freeway-boot）** — profile 选择优先级修正（此前 `FREEWAY_PROFILE`
  静默输给文件里的 `freeway.profile`，违反文档级联）；ServiceLoader 发现模块
  失败时给出带 classloader 上下文的明确错误；`AppBuilder` 单次使用（重复
  start() 此前会注册第二个 shutdown hook 并构建独立容器）。
- **并发契约标记（freeway-ioc）** — 新增 `@ThreadSafe`/`@NotThreadSafe`
  标记注解，复用现有 @Marker 机制：标注在实现类上（`to(Class)` 绑定自动
  携带）、可经 `container.get(type, ThreadSafe.class)` 解析；单例持有者
  注入 `@NotThreadSafe` 具体类被拒绝（原型/线程作用域持有者无共享、允许）；
  同标 `@ThreadSafe`+`@NotThreadSafe` 绑定期拒绝。声明非证明——未标注服务
  不校验，渐进采用。+4 测试。
- **框架原语（freeway-commons/ioc/boot）** — `LazyValue<T>` 线程安全惰性值
  （volatile 双检、恰好一次计算、失败重试、null 拒绝——取代各模块手写
  双检）；`ContextualExecutor.wrapping(executor, keys…)` 显式跨线程传播
  ScopedValue 绑定（JDK Snapshot API 包私有，key 显式设计——事务/Defer
  上下文按需到达异步线程）；`Defer.with(executor)` 打通框架上下文传播
  （CURRENT key 原本私有使该原语对 Defer 场景不可用——现在事务作用域
  可按选择到达自定义 executor）；`Metrics` 观测 SPI（counter/gauge，零依赖，
  容器 builtin 默认 NoopMetrics、可 primary 覆盖）——EventBus 接入
  （published/delivered/subscriber_failures/dead_events 镜像计数）；
  `ConfigSpec<T>` 类型化配置（解析 + 默认 + 含 key 上下文的错误消息，
  `AppConfig.get(ConfigSpec)` 统一入口——替代分散 parseInt；**无 parser
  形式（Coercer 默认解析）**：`of(key, type, default)` + `parse(raw, Coercer)`
  支持 Duration/"2s"/用户 CoerceRule——DbModule 池配置全量适配（URL/USERNAME
  required、池大小 parseInt、6 个 Duration 键走 Coercer，手写 helper 删除）。
  **移至 commons.config**（http/db 等模块不依赖 boot 也可声明类型化键）；
  新增 `required()` 工厂（命名经评估：ConfigKey → ConfigSpec，区分裸 key 常量族）（缺失/空白 fail-fast，不再静默回默认）。+10 测试。

### Docs

- **Defer 文档补全** — `docs/freeway-defer-summary.md`：DB 事务场景的三方接线、
  提交/派发时序图（mermaid）、时序契约与语义边界；新增与 `ScopedCache` 的
  嵌套契约章节。

## [1.3.6] — 2026-08-07

### Added

- **TLS session on `HttpContext`** — `sslSession()` exposes the TLS protocol and peer certificates; `isSecure()` derives encryption state from the session.
- **Schema dialect derived from the database** — `Database.dialect()` drives schema DDL; redundant `Dialect` parameters removed from `Schema.ensure()`, `Schema.drop()`, `SchemaEntity.of()`, and related APIs.

### Changed

- **`Extension` read paths lock-free** — `all()`/`asMap()`/`get()` use volatile double-checked caching (invalidated on `add()`); only writes take the lock. `EventBus.executor()` lazy init is double-checked as well. Runtime reads of contributions no longer contend.
- **Naming conventions aligned across modules** — breaking renames: `FreewayHttpContext → HttpContextDefault`, `ServerHandle → HttpServerHandleDefault`, `BootConfigLoader → ConfigLoaderDefault`, `SQL → Sql`, `DatabaseNamed → NamedDatabase`, `Coercer.supported() → conversions()`, `Temporary → ExecState`; engine package `http20 → http2`.
- **freeway-flow single canonical GraphSpec** — legacy solon-flow v1 `layout` format removed; `GraphSpec2`/`NodeSpec2`/`LinkSpec2` promoted to the root package as `GraphSpec`/`NodeSpec`/`LinkSpec`; `Graph.toMap()/toJson()` emit the canonical `nodes`+`links` format; `Graph.fromText()` accepts canonical JSON only.
- **IoC boundaries tightened** — `Container` and `Extension<V>` are no longer injectable; consume contributions via `List<V>` / `Map<String, V>`.

### Fixed

- **HTTP/2 wire-format audit** — full byte-level verification against RFC 7540 §4-§6 / RFC 7541 §5, locked by `H2WireFormatTest` (frame headers, RST_STREAM/WINDOW_UPDATE/GOAWAY/SETTINGS complete-frame bytes, RFC §5.1 integer examples, §5.2 Huffman flag, C.4.1 vector) and extended `HPackContextTest`; all frame writes confirmed inside the connection lock (no interleaving). Header strings now encode/decode with explicit UTF-8 (platform-default encoding previously made non-ASCII header values corrupt on non-UTF-8 JVMs).
- **IoC close lifecycle** — thread-scope values remain registered after container close so the global scope-exit hook still runs their `@PreDestroy`/`AutoCloseable` cleanup (unregistering on close leaked them); regression-tested. `JULEnhancer.resetForTest()` clears its tracked-handler set.
- **Flow join semantics** — INCLUSIVE gateways now join exactly once when all incoming branches arrive (standard BPMN join; previously every arrival passed through); `FlowContext.stop()` keeps the serialized `stopped` flag in sync when used standalone.
- **IoC close contract** — `@PreDestroy` callbacks now run before the container is sealed, so cleanup code can still resolve services via `get()`/`extension()`; `close()` is synchronized against concurrent double-shutdown. Documented: only *realized* singletons are cleaned up (a never-invoked lazy proxy gets no `@PreDestroy`).
- **Boot lifecycle** — `AppStoppingEvent` is only published for a runtime that actually ran (not a startup-failed `FAILED` state); close-once semantics documented.
- **Flow parallel hardening** — `ExecState` composite operations (inclusive/loop join `peek→count→pop`) are atomic under shared stacks, removing structural races between PARALLEL branches converging on the same gateway; status documented in `docs/freeway-flow-parallel-context-isolation.md`.
- **IoC class contributions resolve across modules** — `contribute(T).add(Impl.class)` instantiation deferred until after every module's `bind()` has run (`flushPendingCreates()`), so a contributed class may depend on services declared by a later module regardless of declaration order. Previously each module's flush instantiated its class contributions immediately, failing on unregistered bindings from later modules.
- **HTTP engine** — h2c upgrade repair, request-line cap, connection draining on shutdown, multipart parsing guards, WebSocket subprotocol negotiation.
- **DB** — never recycle closed physical connections; `queryTimeout=0` supported.
- **Boot/config** — documented config cascade honored in value injection; negative CLI values parsed correctly.
- **Commons** — coercion/validation/JSON edge cases hardened; symbol escape; EventBus lifecycle and extension concurrency hardened. Short/Byte string coercion now matches Integer/Long decimal semantics; `Defer` deferred suppliers are synchronized against duplicate computation; `DeferScope` drain continues past throwing actions.
- **Flow** — LOOP iteration cap (`MAX_LOOP_ITERATIONS`), cyclic graphs rejected at build time, `ExprEvaluator` nesting depth guard, `FlowEventBus.clear()`, `@beanName` condition components resolved via `FlowModule`'s container adapter, singleton realize lock deduplicated across concurrent first resolution. `Node` topology caches and the graph registry are now safe under concurrent execution; EXCLUSIVE gateways warn on multiple/default-missing branches.
- **HTTP (HTTP/2)** — response header size budget enforced before HPACK encoding (64 KB), rejecting oversized header values instead of unbounded buffer growth.
- **Boot/config** — documented config cascade honored in value injection; negative CLI values parsed correctly; env-var→key mapping covered by tests. `freeway.env.prefix` is now a single replaceable prefix: default `FREEWAY_` keeps mapping into `freeway.*`; a custom prefix hands the mapping to the app (`APP_SERVER_PORT` → `server.port`, `APP_FREEWAY_HTTP_PORT` → `freeway.http.port`).
- **Logging env convention unified** — all `freeway.log.*` env lookups (`JULEnhancer` cascade, console color/MDC, MDC priority, caller-info flags) now honor `freeway.env.prefix` via a shared mapping (`freeway.log.level` ↔ `FREEWAY_LOG_LEVEL`, or `APP_FREEWAY_LOG_LEVEL` under a custom prefix), consistent with the config cascade.
- **IoC** — `String` constructor parameters now pass through the same marker/scope validation as other types (previously skipped scope-compatibility checks); `Extension` read paths lock-free.

### Docs

- **Naming rules clarified** — `XImpl` is the definitive implementation; `XDefault` is the replaceable default; `ModuleEx` avoids `java.lang.Module` collision.
- **Docs and English skill synced** — stale class names and API signatures updated across `docs/` and `skills/freeway-dev/`.

## [1.3.5] — 2026-07-23

### Added

- **`freeway-log.properties`** — dedicated logging configuration file at classpath root. Replaces JUL's `logging.properties` as the single entry point for all logging config. The file is not bundled in the JAR; all defaults are built into code.
- **Multi-file logging** — `freeway.log.files=biz,audit` declares named log files with independent `JULFileHandler` instances, each configurable via `freeway.log.file.<name>.*` keys. Supports per-file logger binding and level control with `useParentHandlers=false` isolation.
- **`FREEWAY_*` env var support for all `freeway.log.*` keys** — `FREEWAY_LOG_LEVEL=DEBUG` is equivalent to `-Dfreeway.log.level=DEBUG`. Config cascade: `-D` > env var > `freeway-log.properties` > code default.
- **Config-driven console control** — `freeway.log.console.enabled=true|false` and `freeway.log.console.level` in the config file.
- **Per-logger level via any `.level` key** — `com.myapp.audit.level=FINE` sets the corresponding JUL logger level. Accepts SLF4J names (TRACE/DEBUG/INFO/WARN/ERROR) and JUL names (FINEST/FINE/INFO/WARNING/SEVERE), case-insensitive via `parseLogLevel()`.
- **Caller info propagation through SLF4J bridge** — `JULLoggerAdapter.fixCallerInfo()` uses `StackWalker` to correctly set `sourceClassName` and `sourceMethodName` on each `LogRecord`.
- **`LogBootstrap.applyNamedFileLoggers()`** — late-stage re-attachment API for named file handlers that may have been cleared during JUL's lazy `LogManager` initialization. Safe to call multiple times.

### Changed

- **`JULEnhancer` rewritten** — owns the full config lifecycle: level management, console handler creation, formatter installation, single and multi-file handler activation. Reads `freeway-log.properties` via three-tier classloader cascade (TCCL → own → system).
- **`JULLoggerServiceProvider.initialize()` triggers `JULEnhancer.configure()`** — ensures JUL enhancements are active regardless of when SLF4J initializes, guarding against `LoggerFactory.getLogger()` calls before Freeway bootstrap.
- **DB: `DbModule.buildConfig()` provides friendly parseInt error messages** — non-integer pool config values now produce clear errors instead of bare `NumberFormatException`.
- **DB dialect system rebuilt** — `Dialect` gains `upsertClause()` (PostgreSQL `ON CONFLICT`, MySQL `ON DUPLICATE KEY`), capability flags (`supportsReturning`, `supportsOnConflict`, `truncateTable`, `forUpdateClause`), and `dialectId()`; `Database` exposes `dialect()`/`truncate()`; ORM identifiers quoted via `quoteName()`; new `H2Dialect`; `SQL.sql(Dialect)` validates dialect-specific clauses; SQL parameter scanning consolidated into `SqlTextParser`.
- **DB dialect fixes** — PostgreSQL INFORMATION_SCHEMA case handling, SQLite truncate/addColumn, `RowMapperResolver` cache race, `SqlTypeMapping` instanceof removal.

### Removed

- **`logging.properties` removed from JAR** — Freeway no longer depends on JUL's standard config file. `freeway-log.properties` is the replacement, and it's user-provided, not bundled.

### Docs

- **Sample configs cleaned** — `application-*.properties.sample` no longer carry `freeway.log.*` keys. Logging config lives in `freeway-log.properties`; samples point to the reference template.
- **`docs/freeway-log.properties.reference`** — annotated reference with best practices, `auto` semantics, multi-file patterns, and level formatting.
- **SKILL files updated** — `SKILL.zh.md` gains comprehensive logging section; `SKILL.md` and `references/commons.md` updated with multi-file, env var, and late re-attach docs.

## [1.3.3] — 2026-07-17

### Added

- **Auto file logging by default** — `JULEnhancer` now writes `logs/{app.name}.log` (or `logs/freeway.log`) without configuration; opt out with `-Dfreeway.log.file=off`. Time + size dual rolling `JULFileHandler` with GZIP compression.

### Changed

- **Logging module audit and polish** — compacted log formatters, simplified `JULMDCAdapter` (`ThreadLocal.withInitial()`), MDC priority keys configurable via `-Dfreeway.log.mdc.priority`; redundant FQN cleanup across modules.

### Fixed

- **SQL builder PostgreSQL `::` casts** — `SQL.where()`, `.set()`, and `.having()` correctly handle `::` type casts in named-parameter fragments (e.g. `created_at::date = :d`); the second colon is no longer misread as a named-parameter start.

### Docs

- **Logging docs updated** — DEVELOPER-GUIDE.md logging section reflects auto file logging defaults and configuration.

## [1.3.2] — 2026-07-07

### Added

- **MDC context display in log formatters** — `JULLogFormatterSupport` renders MDC key-value pairs in log output when MDC context is present. Both `JULConsoleFormatter` and `JULFileFormatter` support MDC rendering.



### Docs

- **Flow design decisions** — 补全 Flow 模块设计决策文档：统一构建路径、driver 扩展点、entry 类型保留、缓存失效、不可达节点序列化、子图 driver、异常策略、FlowOptions 防御复制。

## [1.3.1] — 2026-07-03

### Added

- **`GraphSpec2`** (v2 graph definition) — canonical DAG format with explicit `entry`, separated `nodes` + `links`, and `normalize()` validation (link references, BFS reachability). Designed as the primary authoring surface going forward.
- **`@Marker` service disambiguation** — `@Marker(Builtin.class)` on modules, `bind().marker(Fast.class)` on individual bindings, `container.get(type, marker)` for resolution. `MarkerIndex` with `containsAll` semantics. Extends Flow with `@FlowMarker` for `!markerName` task resolution.
- **`Contributions.add(Class)`** — auto-generates canonical id as `snake_name@package`, ordering via `before`/`after`.

### Changed

- **Flow v1/v2 unified** — `GraphSpec.create()` internally converts to `GraphSpec2`, eliminating duplicate `Graph`/`Node`/`Link` constructors. Runtime always builds through `Graph(GraphSpec2)`. `Graph.fromText()` auto-detects format. Renamed `GraphBlueprint`→`GraphSpec2`.
- **Flow task resolution** — consolidated under `!markerName` (marker intersection via `@FlowMarker`) and `@beanName` (IoC container lookup). The `!marker` mechanism replaces class-name-based task matching with a more flexible, refactoring-safe alternative.
- **`Container` API refined** — `instantiate()` renamed to `create()`; `RouteIndex` no longer depends on `Container`.
- **`Module2` renamed to `ModuleEx`** — the module entry-point type renamed to avoid collision with `java.lang.Module`. This is a breaking change for early adopters: replace all `Module2` references with `ModuleEx`.
- **`Contributions.add(T)` fluent chaining** — `add(value)` now returns `Contributions<T>` instead of `void`, enabling chained calls. Note: `before()`/`after()` ordering is only available via `add(id, value)` or `add(Class)`, which return `Contribution`.
- **Flow driver extension point** — `FlowDriver` is a contributed extension point; `FlowModule` builds `FlowDriverDefault` as id `"default"` and graphs select a driver via the `"driver"` field. Custom drivers are contributed via `binder.contribute(FlowDriver.class)`.
- **Logging system completed** — JUL logging upgraded from console-only fallback to a full-featured system: `JULFileHandler` (time+size dual rotation, async GZIP compression), `JULFileFormatter` (ISO 8601 timestamps, recursive exception rendering), `LogBootstrap.ensureProvider()` (auto-detects Logback/Log4j, installs JUL only as fallback), `logging.properties` loaded from classpath, virtual-thread-aware thread name rendering. Fixes: SLF4J state constants (2=FAILED in 2.x), DCL race in provider install, GZIP resource leak, `Files.move` missing `REPLACE_EXISTING`.

### Fixed

- **HTTP/1.1 parser hardening** — duplicate `Content-Length` rejection, `Transfer-Encoding` comma+unknown rejection, pipeline buffer preservation, truncated request/header rejection, `Upgrade` requires both `Connection: Upgrade` and `Upgrade: websocket`.
- **HTTP/2 frame correctness** — `DataFrame` PADDED off-by-one, `PingFrame.writeTo` body, `WindowUpdateFrame` 31-bit masking, HPACK integer bounds/header lowercase/dynamic table tracking.
- **WebSocket strict compliance** — UTF-8 validation on text frames, close code reserved range rejection, extended 8-byte length for >65535 payloads, fragmented message assembly.
- **Coercion edge cases** — NaN/Infinity/BigInteger/BigDecimal guards, narrow overflow rejection, `@Min`/`@Max` BigDecimal comparison, `@Size` Map support, Optional/OptionalInt/OptionalLong/OptionalDouble coercion.
- **IoC lifecycle** — `findOwnerBinding` walks full interface hierarchy; module dedup uses `IdentityHashMap`; PROTOTYPE+advise routes through `createAdvised()`; thread scope cycle detection.
- **Multipart** — boundary terminator validation, semicolons in quoted strings.
- **SSE** — `\r` handling, field injection prevention.

## [1.2.2] — 2026-06-28

### Added

- **`freeway-flow`** — lightweight graph orchestration engine (port of solon-flow). 7 node types, JSON-based definitions, PlantUML export, execution tracing, subgraph calls, interceptor chains. Zero extra dependencies.
- **HTTPS auto-configuration** — `HttpModule` reads `freeway.http.ssl.*` config keys; creates TLS 1.3 engine when `ssl.enabled=true`. Supports PKCS12/JKS keystores and HTTP/2 over TLS via ALPN.
- **Express-style `:name` path variables** — routes support both `:name` and `{name}` syntax.
- **`JsonObject.getBigDecimal()` / `JsonArray.getBigDecimal()`** — convenience accessors.
- **Handler class injection for routes** — `Route.handlerType` enables IoC-injected handlers without manual `container.create()`.
- **CLI auto-prefix** — args without a dot (e.g. `--profile=dev`) auto-receive `freeway.` prefix.

### Changed

- **`@Named` removed** — superseded by `@Inject("id")`.
- **Documentation restructured** — `DEVELOPER-GUIDE.md`, config samples, and module summaries moved to `docs/` directory.

### Fixed

- **Header key normalization** — HTTP/1.1 parser normalizes header keys to lowercase per RFC 7230.
- **Header value OWS tolerance** — trailing whitespace stripped per RFC 7230 §3.2.6.
- **HEAD Content-Length** — HEAD responses report same Content-Length as GET (RFC 7231 §4.3.2).
- **Connection header token-list** — parsed as comma-separated per RFC 7230 §6.1.
- **BufferedOutputStream close ordering** — resolved ordering issue in HTTP response flush.
- **`setAccessible` fallback** — when module system blocks `privateLookupIn`, falls back to `setAccessible`.
- Response header injection hardening — `headerSet()` validates no `\r`/`\n` in values.

## [1.2.1] — 2026-06-23

### Fixed

- **4KB response crash** — `FreewayHttpContext` had a fixed 4096-byte buffer; bodies larger than 4KB crashed the handler. Response now streams directly to raw socket output.
- **Keep-alive path variable leak** — `pathVariables` not cleared on context reset, causing cross-request variable leakage between keep-alive requests on the same connection.
- **Daemon acceptor thread** — `acceptor.setDaemon(true)` caused the JVM to exit immediately after `main()` returned, because the acceptor and all virtual request-handling threads were daemon. Acceptor is now a non-daemon thread, matching the behavior of JDK HttpServer, Tomcat, Undertow, and Netty.

### Changed

- **`HttpConfigKeys` / `DbConfigKeys`** — config key constants extracted from `HttpModule`/`WebServer`/`HealthFilter` and `DbModule`/`PoolConfig`. All raw string literals (`"freeway.web.health.path"`, `"freeway.db.url"`, etc.) replaced with constant references.
- **Deferred binding registration** — bindings flushed after each module's `bind()` completes instead of immediately in `BinderImpl.bind()`. Default ids are now unique (`type@N` counter suffix), avoiding false cross-module collisions.

### Performance

- **HTTP request hot path** — request-line and path parsing rewritten to manual scanning, parser buffers and filter chain pre-built, per-request allocations eliminated.

### Removed

- **`freeway-benchmark`** — migrated to [freeway-ext](https://github.com/dzb/freeway-ext). All 31 source files, benchmark scenarios, and CLI tooling removed from core repository.
- **GitHub Actions auto-deploy** — `publish-release.yml` and `publish-snapshot.yml` deleted. Deploys now done manually via `mvn deploy`.

## [1.2.0] — 2026-06-22

### Added

- **`JULConsoleFormatter`** — ANSI-colored single-line JUL console output, auto-detected from the attached console. Colors are disabled when output is piped, redirected, or `NO_COLOR` is set. Override with `-Dfreeway.log.color=always|never`. Opt out entirely with `-Dfreeway.log.format=simple` or `FREEWAY_LOG_FORMAT=simple`.
- **`MySqlDialect`** — built-in MySQL/MariaDB dialect with backtick quoting, `AUTO_INCREMENT`, `VARCHAR(36)` UUID, `DATETIME(6)` Instant, `LONGBLOB` binary.
- **`SqliteDialect`** — built-in SQLite dialect with double-quote quoting, `AUTOINCREMENT`, `TEXT` UUID/Instant, `BLOB` binary, `sqlite_master` introspection.
- **Dialect auto-detection** — `detectDialect()` maps JDBC URLs to built-in dialects. H2 maps to PostgreSQL (or MySQL if `MODE=MySQL`). Explicit unknown dialect throws `IllegalStateException`; auto-detected unknown falls back with warning.
- **`SymbolSource.resolve(name, defaultValue)`** — default value overload. Returns `defaultValue` when the key is not found; delegates to `expand()` with `${name:default}` syntax.
- **`commons.util`** — consolidated utility package: `IoUtils` (bounded/readBytes streams), `Strings` (blankToNull, camelToSnake), `Maps` (nested flatten), `Digests` (sha256Hex/sha256Base64). Replaces `commons.io.InputStreams`.
- **Coercer API** — `canCoerce` → `supports`, `conversions` → `supported`, `CoerceRule.converter` → `mapping`. `coerceInternal` if-else chain replaced with O(1) `Map<Class, BuiltinCoercer>` dispatch; class reduced 531→370 lines.
- **Config keys** — `web.*` → `freeway.web.*`, `shutdown-grace-seconds` → `shutdown-grace` with `Duration` type. Profile-specific config samples for dev/prod in properties + JSON.
- **`List<T>` contribution injection** — contributions can now be injected directly as `List<T>` instead of requiring `Extension<T>` + manual `.all()`. Constructor params auto-resolve; fields need `@Inject`. (`resolveContributed`)
- **`HealthCheck`** — `@FunctionalInterface` for pluggable health endpoint responses. Default returns `{"status":"ok"}`; bind a custom implementation for DB/external service checks.
- **`HealthFilter`** — `HttpFilter` that intercepts the health endpoint (`freeway.web.health.enabled`, `freeway.web.health.path`) before routing. Injected into `WebServer` alongside `CorsFilter`.
- **CRLF validation in `headerSet`** — all `HttpContext` implementations now reject `\r`/`\n` in header values, preventing HTTP response header injection.

### Fixed

- `toUpperCase()`/`toLowerCase()` without `Locale.ROOT` across 12 files — Turkish locale would corrupt SQL DDL, config keys, column labels, and migration lock detection.
- `SymbolSource.resolve(name, null)` no longer expands to string `"null"` — fixes health-check-query default.
- `SqliteDialect.addColumn()` no longer doubles `ADD COLUMN`.
- `IndexDef.toSql()` conditionally omits `IF NOT EXISTS` for MySQL (via `Dialect.supportsIndexIfNotExists()`).
- `RequestContext.attribute()` now validates null key (was inconsistent with `setAttribute()`).
- `StaticResourceMount` `URLDecoder` `+` → space bug fixed by pre-replacing `+` with `%2B`.

### Changed

- **`Coercer.coerce()`** — throws `IllegalArgumentException` instead of `IllegalStateException` for coercion failures.
- **`CorsFilter`** — only intercepts genuine CORS preflight (`Access-Control-Request-Method` header present). Non-preflight `OPTIONS` requests pass through to route handlers.
- **`HttpServerConfig`** — invalid port/backlog/shutdownGrace now throw `IllegalArgumentException`; `shutdownGrace` is now `Duration` (config key `freeway.web.server.shutdown-grace`, e.g. `2s`), consistent with DB pool duration keys.
- **`PathPattern.validateRegistrationPath()`** — rejects empty path segments (`/a//b`), unbalanced braces (`/{id`), and empty parameter names (`{}`, `/:regex`).
- **`RouteIndex`** — wildcard params (`{path:.*}`) now reject literal children and vice versa, preventing unreachable routes. Param conflicts now compare regex by pattern string (value equality).
- **`MigrationRunner.isDuplicateKey()`** — SQL state code checking (`state.startsWith("23")`) added as fallback to keyword matching.
- **`BatchQueryImpl`** — rejects mixed positional/named parameters at construction time. Defensive `List.copyOf()` for `rows()`/`named()` inputs.
- **`Extension.order()`** — throws `IllegalArgumentException` on unknown `before`/`after` ids (was silent skip).
- **Environment variable mapping** — `FREEWAY_DB_URL` now maps to `freeway.db.url` (prefix stripped, `_` → `.`, `freeway.` prepended).
- **Extension mechanism simplified** — removed `Extension.Key` record (was `Class<?> entryType` + `String name`, the latter dead). `extensions` map changed to `Map<Class<?>, Extension<?>>`. FQN-based binding registration removed; extensions live exclusively in their own `ConcurrentHashMap`.
- **`Binder` API cleaned** — removed unused `contribute(Class, String name)` overload. Removed never-implemented `contributeMapped`.
- **HTTP package restructuring** — filter, route, body, event, sse, staticfile, and websocket classes extracted into sub-packages. `JdkHttpContext`/`JdkHttpEngine`/`RequestContextDefault` moved from `internal` back to root. `PathJoiner` moved to `route`. `RequestBodyTooLargeException` renamed to `BodyTooLargeException`. Test packages mirrored to match source layout.
- **`PooledConnection` interface** — extracted from the old concrete class (now `PooledConnectionDefault`). Public `Pool` API now returns the interface, eliminating the cross-module `internal` boundary violation in the HikariCP adapter.
- **`HikariPoolModule`** — now binds `Pool.class` instead of `HikariPool.class`, aligning with `DbModule.resolvePool()`.
- **`Schema.ensure()` / `drop()`** — no-dialect convenience overloads removed; caller must supply explicit dialect. `SchemaGenerator` no-arg constructor removed.
- **Engine selection** — switched from config-key-based (`freeway.web.engine`) to `.primary()`-based IoC resolution. `HttpModule` binds `FreewayHttpEngine` without `.primary()`; extension modules (e.g. `UndertowModule`) bind with `.primary()`. No config key needed — just add or remove the extension module.

### Removed

- **`JdkHttpContext` / `JdkHttpEngine`** — built-in JDK `com.sun.net.httpserver` engine removed. The only built-in engine is now `FreewayHttpEngine`. Users needing an alternative engine add `freeway-http-undertow`.
- **HTTP/2 frame types** — flat `engine/` subpackage classes restructured into `engine/http20/frame/`, `engine/http20/hpack/`, and `engine/http20/util/`. Deleted: `BufferedBuilder` (replaced by `StringBuilder`), `ChunkedOutputStream` (replaced by inner class), `FixedLengthOutputStream` (unused).
- **Strict mode (`freeway.strict`)** — removed entirely. Duplicate modules now logged (not thrown). Unbound concrete types always auto-instantiate. Engine fallback always warns + falls back. Eliminates `System.setProperty` side channel between `AppBuilder` and `ContainerImpl`/`WebServer`.
- **Extension adapter modules** — `freeway-http-robaho`, `freeway-http-undertow`, `freeway-http-jetty`, `freeway-mq-kafka`, and `freeway-db-hikari` moved to the [freeway-ext](https://github.com/dzb/freeway-ext) repository. Core modules (`commons`, `ioc`, `boot`, `http`, `db`) remain in this repository, keeping their zero-external-dependency guarantee.

## [1.1.1] — 2026-06-13

### Added

- **ScopedCache** — scoped value cache primitive built on top of JDK 25 `ScopedValue`. Provides a key-value cache that lives within a scope boundary and is automatically discarded on scope exit. Prunes the IoC scope layer by replacing heavier scope machinery with a lightweight cache primitive. (`78e448f`)
- **ModuleEx** — `@FunctionalInterface` for module definitions. Adds `binder.install()` to compose modules declaratively. Enables multiple `FreewayApp` instances per JVM. (`2eadd5f`)
- Comprehensive **docs/DEVELOPER-GUIDE.md** — dual-purpose documentation for humans and AI assistants, with a dedicated Module section. (`fd0f67c`, `20114cb`)

### Changed

- **StaticResourceMount** — added fallthrough behavior when no static file matches, allowing the request to continue to the next handler. (`097f218`)
- **Query.execute()** — new terminal operation for DML statements (INSERT/UPDATE/DELETE) that returns an `ExecuteResult`. (`097f218`)
- **Named parameter auto-bind** — query named parameters (`:name`) now auto-bind to matching record/bean property names. (`097f218`)
- **Generics audit** — eliminated all raw types and unchecked casts across the codebase. (`f1ed490`)



## [1.1.0] — 2026-06-10

### Added

- **Defer** — scope-bound deferred execution mechanism. Actions buffered inside a scope drain on commit, discard on rollback. Powers transaction-aware `EventBus.publish()`, per-HTTP-request scopes, and per-Kafka-record scopes with zero user wiring. `ScopedCache` is the companion scope-lifetime cache. (`5b1aba8`)
- **EventBus** — in-process publish-subscribe event bus with string topics, `DeadEvent` diagnostics, O(1) subscriber indexing, and `publishAsync`. (`50605d5`, `58728ce`, `694425f`, `e55d14a`)
- **freeway-mq-kafka** — distributed EventBus extension via Kafka, enabling cross-process pub/sub with the same EventBus API. (`cd8e2ea`, `fc38a63`)
- **freeway-db-hikari** — HikariCP connection pool adapter. (`288c7ed`)
- **Connection pool abstraction** — `Pool` interface + `PoolDefault` built-in implementation, selectable via `freeway.db.pool`. (`afb5aa9`, `9b7187b`)
- **JsonCodec** moved into `freeway-commons`, making JSON serialization available without IoC dependency. (`fc38a63`)
- **Lifecycle events** — `AppStartedEvent` / `AppStoppingEvent` published on the EventBus. (`9efa7e9`)
- **HTTP events** — `HttpRequestEvent`, `HttpErrorEvent`, `AssetServedEvent`, WebSocket open/close events published on the EventBus. (`f8c5cff`)
- **Schema auto-migration** — `@Table`, `@Column`, `@Id`, `@Index` annotations + `AutoMigrate` for automatic DDL generation. (`f6a9ee9`)
- **Orm** — basic CRUD operations with audit support. (`5168479`)
- **freeway-db standalone** — `DatabaseBuilder` + `PoolConfig` allow DB usage without the IoC container. (`0c2211a`, `66406ad`)
- **ExecuteResult key** — `ExecuteResult.id` changed from `long` to `Object key`, supporting non-numeric auto-generated keys. (`732a4bf`)

### Changed

- **ExtensionPoint<V>** — new extension point system with `ScopedValue`-based thread scoping. (`d2f69fb`)
- **Database API** — `Database.query()` and `Database.execute()` split query vs write entry points. (`5e3eac5`)
- **Coercion system** — unified type conversion with `Coercer`/`CoerceRule` refactored; JDBC date coercion rules added. (`f36acdf`)
- **Logging bootstrap** — SLF4J `ServiceLoader` standardization; JUL-backed fallback provider only activates when no external SLF4J provider is detected. (`f55c85d`, `e140304`)
- **freeway-db** — decoupled from IoC container, making it usable as a standalone library. (`0c2211a`)
- Removed `afterCommit`/`TransactionContext` in favor of the Defer mechanism. (`bd71f4d`)

### Fixed

- Config cascade priority corrected: env vars now properly override file-based config. (`f993ddd`)
- `ConcurrentHashMap.computeIfAbsent` JDK 25 false recursion during engine readiness polling. (`0acd60d`)
- 304 Not Modified response not sent in `StaticResourceMount`. (`419415c`)

## [1.0.x] — 2026-05

### 1.0.8

- **ExecuteResult** — `long id` → `Object key` for non-numeric auto-generated keys.
- **EventBus** — initial in-process event bus implementation.
- **Orm** — basic CRUD with audit support.
- **Schema** — `@Table`/`@Column`/`@Id`/`@Index` + `AutoMigrate`.

### 1.0.7

- Logging completion + container close clears extensions.
- SLF4J `ServiceLoader` standardization.
- Unified naming, logging, and language feature style across codebase.
- `Database.query()` + `execute()` API split.

### 1.0.5

- ExtensionPoint<V> system + `ScopedValue` thread scoping.
- Coercion system refactored with JDBC date `CoerceRule`.
- Register/Module simplification.

### 1.0.3

- Refined naming conventions.
- Removed obsolete design and audit notes.

### 1.0.2

- Logging auto-configuration — auto-defers to external Logger when present.
- Dynamic SQL design document added.

## [Initial Release] — 2026-05

- **Freeway 2** initial release — a modern, lightweight Java application framework built on JDK 25+.
- **freeway-commons** — shared utilities: JSON, coercion, logging bootstrap.
- **freeway-ioc** — IoC container with singleton/prototype/thread scopes, constructor and field injection, `@Symbol`/`@Value` config injection, extension/contribution mechanism.
- **freeway-boot** — application launcher with config cascade (CLI → env → profile files → default files), profile activation, and runtime lifecycle hooks.
- **freeway-http** — HTTP/WebSocket layer with trie-based routing, path variables, regex constraints, static resources, multipart, SSE, pluggable engines.
- **freeway-db** — JDBC data access with ORM, connection pooling, transactions, and query builder with named parameters and collection expansion.
- Extension adapters (robaho, undertow, jetty, hikari, kafka) available in [freeway-ext](https://github.com/dzb/freeway-ext).

[1.3.1]: https://github.com/dzb/freeway/compare/v1.2.2...v1.3.1
[1.1.1]: https://github.com/dzb/freeway/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/dzb/freeway/compare/v1.0.0...v1.1.0
