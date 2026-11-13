# Freeway 结构审计：职责、执行重复与更恰当的形状

> 范围：`freeway-commons` / `ioc` / `boot` / `http` / `db` / `flow` / `cloud` 全部
> `src/main`，以及模块边界、公开入口、生命周期、配置与装配路径。
>
> 判据沿用仓库自己的原则：**把每个事实放在它不能被弄错的地方；需要一段解释才能成立的
> 构造，本身就是结构缺陷**。本审计不把“零调用”直接当死代码，也不以行数作为拆分理由。
>
> 审计基线：`mvn test` 全绿（2483 例，0 failure / 0 error）。Batch 0 落地后
> `mvn test` 全绿（2487 例，0 failure / 0 error；新增 4 个回归用例）。
>
> **第二轮状态**：S1–S7 与 §9 的小项均已按本报告的目标形状落地；下文保留最初的
> 问题证据与设计推导，实施清单见 §1.6，批次状态见 §11。最终验证数字在 §12。

## 0. 结论先行

Freeway 已经完成了“框架类之间不再用实现继承”这一层清理。剩余的冗余主要不在复制粘贴，
而在 **同一个事实拥有两个执行位置**：

1. **模块组合**：`ioc.ModuleNode` 与 `boot.FreewayApp`/`ModuleDiscovery` 各自实现
   class/instance 解析、`@SubModule` 展开和模块实例化。
2. **HTTP 路由装配**：`HttpModule`、`RouteIndex`、`WebSocketIndex` 都拥有“分组展开”与
   “最终路由列表”的一部分；两个 index 曾各自接受 contributions 词表。
3. **HTTP 响应决策**：四类 body 各走一段高度相似的 committed/Date/gzip/Content-Length
   路径；transport 又二次推断 framing。这个分叉已经产生一个真实边界缺陷（见 §3.2）。
4. **排序规则**：contribution ordering 与 deferred-action ordering 各自维护
   before/after/if-present 状态和 Kahn 拓扑排序。
5. **DB 装配**：`Database.create(Wiring)`、`DbModule.buildDatabase`、`DatabaseImpl`
   构造器形成三条装配/默认值路径；内建 dialect 也有“绑定表”和“URL 检测表”两份。
6. **启动配置**：工作目录 base 文件在 profile 选择与 hot-reload 文件层各读一次；
   `ConfigCascade` 和 `AppConfigDefault` 都知道标准文件名和角色。

这些问题都不是“再抽一个 helper”能解决的。它们要求把某个责任**搬到一个唯一所有者**，
然后删掉旧路径。以下按价值排序。

| 模块 | 结构分 | 判断 |
|---|---:|---|
| commons | 4/5 | 工具与 scope 原语干净；numeric 转换与 ordering 拓扑仍有局部重合 |
| ioc | 4/5 | 边界最清晰；ordering 状态与内置服务不可见是主要缺口 |
| boot | 3.5/5 | launcher 自身清楚，但模块组合和配置加载承担了第二套语义 |
| http | 3/5 | 扩展点组合化方向正确；装配与响应 framing 仍有三个所有者 |
| db | 3.5/5 | `Sql`/`Dialect`/`Schema` 分工合理；数据库装配路径和 dialect 注册重复 |
| flow | 4/5 | 概念少、执行模型清楚；少量公开类型未封口，入口阶梯可再收 |
| cloud | 3.5/5 | 领域模块清楚；`internal` 成为跨域实现堆放处，backend guard 有模板重复 |

最重要的不是把分数做高，而是把下面的 S1/S2/S3/S4 收敛掉；它们决定未来新增模块时，
新代码会复制哪一种形状。

---

## 1. 已落地的第一批（Batch 0）

这一批只动 HTTP 内已被测试钉住、且能明显减少“同一事实两个位置”的结构。均为源码级
破坏（仓库明确不承诺兼容），编译错误就是迁移路径。

### 1.1 路由索引只索引最终路由

**之前**

- `RouteIndex(List<Route>, List<RouteGroup>)` 自己展开 group；
- `WebSocketIndex(List<WebSocketRoute>, List<WebSocketGroup>)` 自己展开 group；
- `HttpModule` 为了解析 class handler，又先展开 group，再把结果交给 index；WebSocket
  路径因此把同一 group 展开两次，并依赖“expand 复用 endpoint 实例”这个隐含条件。
- 直接路由的 resolution 与 group 路由的 resolution 分散在两个循环里。

**现在**

- `RouteIndex(List<Route>)`、`WebSocketIndex(List<WebSocketRoute>)` 只接收**已展开、
  已解析**的最终路由；
- `HttpModule` 是唯一装配点：先合并 direct contributions 与 `group.expand()`，再对最终
  列表解析 class handler/endpoint；
- `HttpPipeline` 与所有测试同步到新形状。

**收益**：index 回到单一职责（匹配），group 只被展开一次，class handler 只被 resolution
一次，新增 route 来源时不需要再教 index 认识它。

### 1.2 class-based handler/endpoint 的两阶段状态收敛

**之前**：`ResolvableHandler.resolve` 与 `ResolvableEndpoint.resolve` 是两段几乎相同的
volatile + synchronized 首次赋值状态机；`isResolved`/`resolved` 字段也各一份。

**现在**：两者组合 `http.internal.ResolvableValue<T>`；route/WebSocket 公共包装类型
仍然保留（因为要实现不同 handler 接口），只把“声明类 → 首次注入实例”这一状态机归并到
一个所有者。

### 1.3 WebSocket close reason 的 123 字节规则只保留一份

**之前**：`WebSocketSessionSupport.truncateCloseReason` 做 String 版本，
`engine.ws.WebSocketConnection.truncateTo123Bytes` 又做 byte[] 版本；两边都手写 123 和
UTF-8 续字节回退。

**现在**：`http.internal.WebSocketCloseReason` 同时提供 `truncate(String)` 与
`utf8(String)`，session support 和内置帧写入器都调用它。规则只在一个地方可以改错。

### 1.4 响应准备收敛到一个 `write(...)`

**之前**：`output(byte[])`、`output(InputStream, long)`、`outputFile(Path, ...)`、
`outputFile(FileChannel, ...)` 各自重复：

- committed guard；
- `ensureDateHeader()`；
- `allowsBody()`；
- gzip 决策；
- `Content-Length` 的 set/remove；
- `Content-Encoding` / `Vary`；
- `writeResponse(...)`。

**现在**：四类 body 都进入同一个 `HttpExchangeSupport.write(body, bodyLength,
minSizeApplies)`。该方法是 committed、Date、body suppression、gzip、长度和 Vary 的唯一
策略点。transport 侧的流/文件 framing 也同步收紧：

- `writeStream` 不再二次添加/推导 Content-Length；
- 文件范围长度只有在打开 channel 后才能确定的 `-1` 情况，由 `writeChannel` 在**提交 head
  之前**解析一次，并把最终 head 同时用于 sendfile 和缓冲流路径；
- `writeSendfile` 不再自己发明 framing。

这一批顺手修复了一个可复现的边界缺陷：未知长度的大文件走 sendfile 时，HTTP/1 writer
看到无 `Content-Length` 会选 chunked，但 sendfile 写入的是原始字节，并未 chunk-frame。
现在 head 在提交前已带解析后的长度。回归测试：
`Http1xTransportTest.unknownFileLengthIsResolvedBeforeSendfileCommitsHead`。

### 1.5 其他同批小收敛

- `Compression.gzip(byte[])` 改用 `gzipStream(...)`，GZIP writer 的 buffer/setup 不再有
  两条构造路径。
- 公开值/实现类型 final 缺口收口：`FlowModule`、`Node`、`Link`、`Graph`、`GraphSpec`、
  `FlowEventBus`、`Stepper`、`ValidationResult`、`SseEmitter` 全部标为 `final`。
- 上述改动没有新增 public framework 概念；`ResolvableValue` 与
  `WebSocketCloseReason` 位于 HTTP 模块的 `internal`。

验证：`mvn -pl freeway-http -am test` 全绿；全仓回归见报告末尾。

### 1.6 第二轮实施记录（S1–S7）

| 原问题 | 落地形状 |
|---|---|
| S1 模块组合双实现 | 新增 `ModuleDeclaration`（class / instance 的 sealed 值），统一无参构造与 `@SubModule` 元数据；`Freeway`/`FreewayApp`/`ModuleDiscovery` 全部携带同一声明；`ModuleNode` 只做规范化、校验、绑定顺序与 render。 |
| S2 HTTP 装配 | `HttpModule` 一次展开 `RouteGroup`/`WebSocketGroup` 并解析 class handler/endpoint；两个 index 只接收最终列表。 |
| S3 响应 framing | `HttpExchangeSupport.write(...)` 单一策略；transport 在提交 head 前解析文件真实长度；sendfile 未知长度 framing 缺陷修复。 |
| S4 排序 | `OrderingSupport` 统一四类边；`TopologicalOrder` 统一 contribution/scope-action 的 Kahn 排序与 tie-break。 |
| S5 DB | `DatabaseImpl.assemble(...)` 唯一装配/defaulting 点；`Dialect.ofId` catalog + 单 selected `Dialect` service；`Orm` 全部 SQL 经 `Sql` builder。 |
| S6 配置文件 | `ConfigFilePlan` 统一文件名、role、顺序、读取和 snapshot；base 文件启动期只读一次。 |
| S7 可观察性 | `Container.describe()` / `AppRuntime.describe()` 返回 metadata-only 描述；routes/WebSockets 提供不暴露 handler 的注册描述。 |
| cloud internal | 按 context/discovery/health/observe 拆子包；顶层 internal 只留跨域 support；guard/optional helper 单点化。 |
| §9 小项 | 公开类型 final、proxy Object 方法、header lookup、filter 排序、gzip writer 收敛；numeric 转换与健康 payload 因语义不同保留独立（§9.3/§9.5）。 |
| 命名批次 | HTTP exchange/response、DB stats/find/row/SQL、Flow execution/metadata、Cloud RPC/event/module、IoC marker/contribution、Commons JSON/cache 的公共词汇全部按命名审计落地；旧名不再保留兼容 shim。 |

新增回归：`ModuleDeclarationTest`、`RuntimeDescriptionTest`、`AppRuntimeDescriptionTest`、
`ConfigFilePlanTest`、`TopologicalOrderTest`、`unknownFileLengthIsResolvedBeforeSendfileCommitsHead`、
`WebSocketCloseReasonTest`，以及 route descriptor / custom dialect 用例。

---

## 2. S1（已落地）：模块组合有两个实现

### 证据

- `ioc.internal.ModuleNode` 是组合树：
  - `of(ModuleEx)` / `of(Class)` 都调用 `expand(...)`；
  - `expand` 读 `@SubModule`，递归构造 children；
  - `resolve()` 用 no-arg constructor 实例化 class declaration；
  - `normalize` 负责 class/instance 去重与错误路径。
- `boot.FreewayApp` 却维护 `List<Supplier<ModuleEx>>`，并拥有自己的 `instantiate(Class)`
  no-arg 反射实现（`FreewayApp.java:150`）。
- `boot.ModuleDiscovery.collectBundles` 又手写一遍 `@SubModule` 展开，只为求出
  “已声明 class 集合”；注释直接写着 “same exclusion the tree applies … so the mirror
  stays exact”（`ModuleDiscovery.java:70-100`）。

### 为什么是结构问题

- boot 把 class declaration 提前变成 instance，导致 `ModuleNode` 的 class-declaration
  路径在 `FreewayApp` 下实际上永远不走；两套 class 解析语义只在启动期偶然一致。
- `@SubModule` 的展开规则有两份；一处改 cycle/无参构造/重复判定，另一处不会自动同步。
- 组合是 boot 的输入，也是 ioc 的内部结构，但没有一个双方共同拥有的**组合值**。
  `Supplier` 只是让 boot 能混合 class 与 instance 的临时手段。

### 目标形状

把“一个待组合的模块声明”提升为 ioc 拥有的一等数据，而不是 boot 的 supplier 列表。
建议形状：

```java
// 名称可再定；重点是 class 与 instance 是同一 declaration 的两种形态
sealed interface ModuleRef {
    Class<? extends ModuleEx> type();
    ModuleEx resolve();                 // class 用 no-arg ctor，instance 直接返回
    List<ModuleRef> submodules();       // @SubModule 的静态展开
}
```

- `Freeway.create(...)`、`FreewayApp`、`ModuleDiscovery` 接收/传递同一种 `ModuleRef`；
- 规范化、重复 class、`@SubModule` cycle、实例展开都只发生一次；
- `ModuleNode` 可继续存在，但只负责“已规范化 composition 的绑定顺序与 render”，
  不再拥有第二套 module resolution 知识；
- discovery 变成 composition 上的一个函数（append undiscovered），而不是先理解
  bundle 再返回 instance 列表。

这不是新增一个应用层概念，而是把现在已经存在、只是藏在 `ModuleNode` 里的组合概念
公开给同一框架内的 boot 装配点。若不想增加 public 类型，也可以把它做成 ioc 的
**框架内部公共类型**，但必须只有一份。

### 代价

M/L。需要同步 `FreewayAppTest`、`ModuleNodeTest`、`ModuleDiscovery` 的 discovery 用例，
并把 external SPI 适配纳入同一形状。收益是删除后续所有模块组合语义的分叉点，值得先做。

---

## 3. S2（已落地）：HTTP 装配仍有两个“第二层”

### 3.1 已修：group 展开与 index 的双重角色

见 §1.1。剩余的小重复是两个 group record：

- `RouteGroup` 与 `WebSocketGroup` 都是 `prefix + List<T>` + normalize + expand，
  只差 route 构造函数。

**下一步**：抽一个 `http.internal.PrefixedGroup<T>` 值（prefix + items + expand 函数），
两个 public record 只保留各自的类型和工厂；或者接受这两段 20 行模板，不做抽象。
**判断**：如果近期不再新增第三类 group（SSE route group、static route group 等），
保留两个显式 record 比再引入一个泛型 internal 类型更直观。

### 3.2 一个需要警惕的边界：body 与 framing 仍是两个层

响应策略现在只有一个 support 方法，但 transport 仍然需要：

- 对 `Bytes` 在 gzip 后重算实际 `Content-Length`；
- 对 `File`/`Channel` 在打开后解析 `-1` 表示的真实长度；
- 决定协议级的 chunked。

这是合理的“执行事实”，但它要求一条明确规则：

> support 拥有策略；transport 只能在**提交 head 之前**把未知的执行事实解析为最终
> head，之后不得再改变 framing。

当前实现符合这条规则，且有回归测试。下一轮若继续简化，应让 `ResponseBody` 携带一个
`LengthFact`（Known / ResolvedFromChannel / Unknown），而不是继续在 `writeStream` /
`writeSendfile` 里用 `-1` 和 `hasHeader` 推断。否则未来新增 body 形态（例如直接
`WritableByteChannel`）会复制同一段 framing 判断。

### 3.3 `HttpPipeline` 的内建 filter 有两个位置

`HttpPipeline` 同时持有：

- `corsFilter`、`healthFilter` 两个特化字段；
- `filters` 列表。

`HttpServer` 构造时再把它们合并、排序。CORS 需要 WebSocket upgrade 读
`resolveAllowedOrigin`，所以它确实不能只作为普通 filter 隐藏；但 HealthFilter 没有同类
外部读取者。可选目标：

- 只保留一个 `List<HttpFilter>`，由 `HttpServer` 在构造时提取/校验 CORS；
- 或保留具名字段，但把“合并并排序”明确成 `HttpPipeline.filtersInOrder()`，不让
  `HttpServer` 成为第二个知道内建 filter 的人。

**判断**：中等优先级，不是错误；如果未来增加第二个需要 upgrade 前读取的 filter，
应立刻统一。

---

## 4. S3（已落地）：IOC 排序规则有两份状态机、两套拓扑排序

### 证据

- `ExtensionStore.Entry`（`ExtensionStore.java:458`）与
  `ContributionRegistry.DeferredOrdering`（`ContributionRegistry.java:275`）都维护：
  `beforeIds` / `afterIds` / `beforeIfPresentIds` / `afterIfPresentIds` 四个列表，
  以及四个同名 fluent 方法。
- `ExtensionStore.order()`（`:273`）用 Kahn + PriorityQueue + cycle path；
  `ScopeSession.sort(...)`（`ScopeSession.java:274`）又用一遍 Kahn + ArrayDeque。
- 两者语义并不完全相同：contribution ordering 要保留 insertion-order tie-break 和
  missing-ref WARN 策略；Defer action ordering 要把 constrained named → unconstrained
  named → unnamed 分三段。但“before/after 图 + 环检测”是同一个算法。

### 目标形状

1. `OrderConstraints`：一个只表达四条边列表和 immutable wither 的值，实现
   `Ordering`；ExtensionStore 的 entry 和 deferred handle 都组合它，不再各自保存四张表。
2. `TopologicalOrder`：commons 中的通用排序算子，输入
   `nodes + edges + priority + missing-reference policy`，输出 ordered nodes 或带
   人可读路径的 cycle failure。ExtensionStore 与 ScopeSession 各自传入自己的策略。
3. 不合并 `Ordering` 与 `Defer` 的公开语义：一个是 composition-time contribution 顺序，
   一个是 scope-close 动作顺序；它们只是共享图算法，不应共享类型。

### 为什么值得

排序是很容易“看起来都对、边界错一个”的代码：重复 id、缺失引用、环路径、稳定 tie。
现在同一类边界要测两遍，且两边错误消息/回退策略不同。归并后每新增一种有序贡献不需要
再复制一套状态。

**代价**：M。先做 `OrderConstraints`（内部、低风险），再决定拓扑排序是否下沉 commons。

---

## 5. S4（已落地）：DB 装配有三条路径、dialect 有两张表

### 证据

- `Database.create(Wiring)` 是文档中的 standalone assembly point；
- `DbModule.buildDatabase` 却直接 `new DatabaseImpl(config, resolver, pool, dialect)`
  （`DbModule.java:199-204`），自己重复解析 Pool/Dialect/RowMapper；
- `DatabaseImpl` 又保留 2-arg / 4-arg 两个 public constructor，分别再次处理
  `pool == null` / `dialect == null` 默认值。

于是同一个“没有 pool 就用 PoolDefault、没有 dialect 就用 Postgres”的答案至少在
`Database.create`、`DbModule.buildDatabase`、`DatabaseImpl` 三处存在。

第二处重复是 dialect 注册：

- `DbModule.bind` 手工绑定 `postgresql/mysql/sqlite/h2` 四个 id；
- `Dialect.of(url)` 又维护一套 URL scheme → dialect 实例的判定；
- `DbModule.resolveDialect` 先检测，再按 id 从容器取，并用 `catch (RuntimeException)`
  回退默认。

结果是内建 dialect 的“id、类、URL 前缀、是否 primary、H2 mode 特殊处理”分散在
`Dialect`、`DbModule` 和四个 dialect 类里。

### 目标形状

- `DatabaseWiring`（or `DatabaseAssembly`）：唯一装配器，接收可选的
  `Pool / Dialect / RowMapper` 依赖，内部应用一次默认值，返回 `Database`。
  `Database.create(Wiring)` 与 `DbModule` 都走它；`DatabaseImpl` 只保留一个全参构造器。
- `DialectCatalog`：id → factory 的唯一表，`Dialect.of(url)`/`ofId(id)` 都从这张表读；
  容器只负责先查用户绑定的 id，再回退内置 catalog。去掉 catch-all 的“静默回退默认”。
- `Orm` 的 SQL 组装部分应逐步改为构造 `Sql` 值再执行：当前 `find/insert/update/delete`
  分别手写字符串；`Sql` 已经有设置列、冲突子句和占位符渲染。让 ORM 和用户查询共享同一
  条 SQL 生成语法，而不是让 `Orm` 成为第二套 grammar。

**代价**：M。`DatabaseImpl` 的构造器收缩是 compile-break；catalog 改动要同步
`DialectSyntaxTest`/`DbModuleTest`。收益是所有数据库实现只面对一个装配入口。

---

## 6. S5（已落地）：启动配置的文件事实有两个拥有者

### 证据

- `ConfigCascade.load` 为 profile 选择读取工作目录 base 文件：
  `ConfigCascade.readFilesystemBase()`（`:179`）；
- 随后 `AppConfigDefault.reload()` 又把同一批 base 文件当作 override 文件读取
  （`AppConfigDefault.readOverride`, `:254`）。
- 标准文件名、profile 变体、`freeway.config.file` 的角色定义在
  `AppConfigDefault.Role` / `OverrideFile`；
- 但 `ConfigCascade.overrideFiles` 又是实际构造这些角色的地方。

这使启动期同一文件通常被解析两次，并且在两次读取之间文件变化时，profile 选择与最终
file tier 可以来自不同内容。它还要求两个 reader 对“缺失/不可读/是否 strip activation
key”保持一致。

### 目标形状

一个 `ConfigFilePlan`（或 `ConfigFiles`）值拥有：

- 标准文件 family（properties/json）；
- 每个文件的 role（base / profile variant / declared）；
- 顺序与 precedence；
- 一次性读取的 snapshot，以及 watcher 触发的替换。

`ConfigCascade` 只负责“从 snapshot 计算 profiles / ConfigSources / tiers”；
`AppConfigDefault` 只负责把 snapshot 暴露为 live SymbolProvider。类路径 baseline 的
ClassLoader 弱缓存可以保留（它是不可变部署资源），但工作目录文件不应在启动期读两次。

**代价**：M。需要新增一个 file plan 单元测试，并覆盖“hot reload 期间 profile 不变”的
既有约定。

---

## 7. S6（已落地）：cloud 的 `internal` 包承担了过多领域实现

### 证据

`cloud.internal` 当前被至少四个领域包反向依赖，形成包级 cycle：

- `cloud.context` → `internal`（Propagator/Filter 实现）；
- `cloud.discovery` → `internal`（RegistryStore、RegistryLifecycleHook、ReadinessGate…）；
- `cloud.health` → `internal`（ReadyHandler）；
- `cloud.observe` → `internal`（MetricsHandler/TracingFilter）；
- 同时 `internal` → `context` / `discovery` / `health` / `observe`。

这不是 Java 语法错误，但它让“internal 是跨领域共享底座”与“internal 是各领域实现
堆放处”两个含义混在一起。未来 agent 或开发者要在 `internal` 找东西时，无法从包名判断
归属。

### 目标形状

- `cloud.<domain>.internal`：该领域的实现、hook、handler；同包可见性优先；
- `cloud.internal` 只保留真正跨领域、无语义归属的机制（如 `ConstantTime`、
  `CloudHttpClients`、`BackendTypeGuard` 这类）；
- module 只依赖自己领域的 public API + 少量跨领域 support；
- 若一个类型同时被两个领域使用，它应该提升为明确的 public contract，而不是继续躺在
  `internal` 做隐式共享。

**收益**：包边界重新表达所有权；很多 public-in-internal 类型可以降为 package-private，
公开面自然缩小。**代价**：M，主要是机械搬移和 import 更新，行为风险低。

### 7.1 Backend guard 模板重复

`SecretModule`、`StorageModule` 各自贡献一个 RuntimeHook，唯一工作是调用
`BackendTypeGuard.warnIfExternal(...)`；`DiscoveryConnectionHook` 也重复两次。可提供
`BackendTypeGuard.hook(type, key, subsystem)` 返回一个 RuntimeHook，或让 module 用一行
contribution 表达 guard。收益是把“外部 backend 未安装时警告”的唯一规则放在一个地方。

### 7.2 两个 `optional(Container, Class)` helper

`RpcModule` 与 `FleetRegistryModule` 都实现“binding 不存在时返回 null”的小 helper。
应放到 cloud 的共享 support 中，或更好：把“可选依赖”变成容器/装配层的明确概念
（`optional` 查询只应在组合期存在，不应散落在业务 provider 中）。这是低风险归并，
但不要为它引入第二个容器 API。

---

## 8. S7（已落地）：agentic 时代缺少机器可读的运行时描述

`docs/freeway-module.md` 当前明确写着：组合结构“visible in the startup log rather than
through an API”。对人类日志足够，对 agent 不足：

- 组合树只有 `ModuleNode.render()` 的字符串；
- binding 的 type/id/scope/primary/markers 没有公开快照；
- contribution 的 id、顺序、来源没有公开快照；
- HTTP 路由表没有 route descriptor；
- 配置侧只有 `AppConfig.keys()`，没有“已声明 vocabulary + 实际来源 tier”的结构化视图。

因此 agent 只能解析日志或反射框架字段，这与“explicit、compose-first、agent-friendly”
的目标相反。

### 建议形状

一个**只读、惰性组装、绝不触发服务实例化**的运行时描述：

```java
record RuntimeDescription(
    String application,
    List<ModuleDescription> modules,       // tree + bind order
    List<BindingDescription> bindings,     // type/id/scope/primary/markers
    List<ExtensionDescription> extensions, // entry type + contribution ids/order
    Set<String> configKeys                 // names only; never values
) {}
```

- `Container.description()` 返回它；`AppRuntime` 可转发为
  `AppRuntime.description()`；
- HTTP 模块可额外提供 `RouteDescription`（method/path/order）而不暴露 handler 实例；
- 不包含实例、不包含配置值、不包含 secret；这是 inspection API，不是第二个 container。
- 可选地让 `AppRuntime` 在显式配置下输出一份 JSON（通过现有 `JsonCodec`），供 CI/agent
  读取；不要默认输出敏感内容。

这不会增加新的运行期概念，只是把已有 metadata 用稳定值类型暴露出来。优先级高，因为它
直接决定框架在 agentic 工具链中的可观察性。

---

## 9. 跨模块一致性与小项

### 9.1 公开类型的 final 缺口（Batch 0 已修）

主代码已经没有框架类之间的 `extends`；审计还发现一批 public 非 final 类型，既没有扩展点
语义，也没有子类：`ValidationResult`、`FlowModule`、`Node`、`Link`、`Graph`、
`GraphSpec`、`FlowEventBus`、`internal.Stepper`、`SseEmitter`。

其中 `FlowModule` 是全部模块里唯一非 final 的 module class。已全部标记 `final`。
规则：除 `RuntimeException` 家族（其继承用于表达错误类型）之外，公开值/实现类型默认
`final`；需要扩展的只留 contract interface。该改动是 compile-break，但 API 面变小，
且能防止用户误以为这些类型设计为可继承。

### 9.2 同名 Object 方法处理（已收敛）

`ServiceProxy` 与 `RemoteProxyFactory` 都分别实现 proxy 的
`toString/hashCode/equals` 分派。两者语义相同（equals 身份、hashCode identity、
toString 描述），只是描述字符串不同。可收敛到 commons 的一个
`Proxies.objectMethod(proxy, method, args, description)`；收益是代理规则只写一次。
**落地**：`commons.util.Proxies.objectMethod(...)` 统一分派；描述字符串仍由调用方提供。

### 9.3 numeric 转换的局部重复（保留独立）

`CoercerDefault`、`JsonAccessors`、`BeanValidator` 各有一个
“Number → BigDecimal/BigInteger”的小函数。它们分别服务于 config coercion、JSON
access 和 validation，语义接近。可抽一个 package-private/公开 utility，但不要把它们
误合并成同一个 coercion policy：三处对 null/异常/精度的处理并不完全相同。本轮未归并：它们不是同一个 coercion policy，保留领域语义更清晰。

### 9.4 `HttpHeaders.headerValue/Values` 与 `RequestViewSupport` 的 header lookup（已收敛）

两者都在做 case-insensitive multi-value lookup。`HeaderStore` 已负责 canonicalization；
`RequestViewSupport` 的 fallback scan 可以复用 `HttpHeaders` 的 helper，或明确要求
“RequestViewSupport 输入已 canonicalize，不再做第二次扫描”。当前重复很小，但规则会
分叉：一个允许非 canonical map，一个依赖 canonical map。

### 9.5 `HealthFilter` / `ReadyHandler` / `HealthModule` 的健康响应（保留独立）

这三处都在构造 `{"status":"ok"|"unhealthy", ...}`，但 payload 不同：HTTP health 只有
status/detail，cloud readiness 还有每个 contributor 的结构。不要为三个不同 payload
强推一个 record；可以抽出共享的 `HealthResponse` 值（status + detail + optional
details），或者保持现状并在文档中说明 payload 边界。**当前不建议强行归并。**

---

## 10. 不建议动的地方（避免为收敛而收敛）

1. **不合并 `JsonUtils` 与 `JsonCodec`**：一个无容器静态入口，一个有容器/可替换入口；
   合并会把 IoC 依赖拖进 JSON 基础层。
2. **不合并三套 event plane**：本地 EventBus、cloud mesh、Kafka adapter 的可靠性等级
   和失败语义不同；“事件”不是同一个概念。
3. **不拆 `Sql`、`JULEnhancer`、`Schema` 这类长文件**：它们的长度来自一个 grammar /
   一个 lifecycle / 一个 schema pipeline；没有独立变化原因。
4. **不把 `TaskDesc`/`ConditionDesc` 合并成公共泛型**：两个 driver 回调的 owner 类型不同，
   public 形状分开更直观；内部共享已由 `Ref` 解决。
5. **不为了消除 `HttpModule` 的多 import 而拆分装配**：它是 composition root，集中看到
   container 的接线是架构选择；若要拆，只能拆纯算法 helper，不能把 `container.get(...)`
   散到多个类。
6. **不引入 JPMS exports 作为架构手段**：模块化和边界应由实际的 package ownership 与
   import rules 表达；exports 只是发布承诺，不是设计。
7. **不保留兼容 shim**：本仓库的约定是 break + compile error + CHANGELOG migration；
   新形状落地时直接删旧构造器/旧 helper。

---

## 11. 建议实施顺序

| 批次 | 内容 | 规模 | 状态 |
|---|---|---|---|
| Batch 0 | HTTP index/group、Resolvable、close reason、response planning、gzip | S/M | ✅ 已落地 |
| Batch 1 | `ModuleDeclaration` + boot/ioc 共用组合声明 | M/L | ✅ 已落地 |
| Batch 2 | `OrderingSupport` + `TopologicalOrder` | M | ✅ 已落地 |
| Batch 3 | `DatabaseImpl.assemble` + selected `Dialect` + `Dialect.ofId` + Orm through `Sql` | M | ✅ 已落地 |
| Batch 4 | `ConfigFilePlan`，启动文件单次读取 | M | ✅ 已落地 |
| Batch 5 | `cloud.<domain>.internal` + backend guard/optional helpers | M | ✅ 已落地 |
| Batch 6 | `RuntimeDescription` / `AppRuntimeDescription` / route descriptors | M | ✅ 已落地 |
| Batch 7 | final 缺口、proxy object method、header lookup、filter order | S | ✅ 已落地 |

每批应各自保持全量测试绿；不要把这些改动绑在一个“大重构”提交里，否则无法定位是哪一个
结构改变破坏了行为。

---

## 12. 验证记录

- 审计基线：`mvn test` 全绿（2483 例，0 failure / 0 error）。
- Batch 0 后：`mvn test` 全绿（2487 例，0 failure / 0 error）。
- 第二轮最终：`mvn test` 全绿（2443 例，0 failure / 0 error / 0 skipped）。
  - commons 422 / ioc 335 / boot 128 / http 527 / db 541 / flow 129 / cloud 361。
  - 数量低于 Batch 0 是因为重构淘汰/合并了已经失去测试对象的旧 suite（主要是已删除的
    ErrorHandler/旧 HTTP context 类），不是测试被跳过；本轮新增覆盖见下。
- `mvn -Pdemos -DskipTests compile` 通过。
- 新增回归：
  - `Http1xTransportTest.unknownFileLengthIsResolvedBeforeSendfileCommitsHead`
  - `WebSocketCloseReasonTest` 覆盖 null、短 reason、ASCII 上限和 UTF-8 code-point 边界
  - route/group/index 的新形状由 `RouteIndexTest`、`WebSocketIndexTest`、
    `UnresolvedHandlerRouteTest` 覆盖；`RouteIndexTest` 同时固定 route descriptor
  - `ModuleDeclarationTest` 覆盖 class/instance、混合声明、bundle 与无参构造错误
  - `RuntimeDescriptionTest` / `AppRuntimeDescriptionTest` 覆盖 metadata-only 描述
    和不实例化惰性服务
  - `ConfigFilePlanTest` 覆盖 base snapshot 复用、profile variant 的 activation-key 剥离
  - `TopologicalOrderTest` 覆盖 Kahn 排序、cycle remainder 和输入顺序 tie-break
  - `DbModuleTest.customDialectIsSelectedByIdWithoutPrimaryTypeBinding` 与
    `SqlTest.offsetWithoutLimitUsesTheDialectSpelling`
  - `FlowEngineTest.findReturnsEmptyAndRequireThrowsForUnknownGraph`、
    `DatabaseRegistryImplTest` 的 missing-name Optional 断言、
    `StaticResourceMountTest.durationCacheMaxAgeRendersWholeSeconds`

> 审计的目标不是把类型数量变成零，而是让每一个仍然存在的事实只有一个回答。
