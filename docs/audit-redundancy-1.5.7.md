# freeway 1.5.7 复审之二：冗余清理与收敛

> 在上轮 [audit-simplification-1.5.7.md](audit-simplification-1.5.7.md) 以及之后两批落地
> （S1/S2/S4/S5/S8/S11/S12，加上 `normalizePath` → `normalize`）之后的又一轮审计。本轮只做
> 两件事：**找出仍然重复的事实**，以及**指出可以合并收敛的入口**。每条都先验证再写——本轮
> 有 4 个"看起来是死的"候选在查证后被排除（见 §5），不把它们写进缺陷清单。

## 0. 方法与基线

- 量：7 个模块 `src/main` 共约 60k 行；公开类型 commons 38 / ioc 28 / boot 12 / http 72 /
  db 35 / flow 26 / cloud 88；测试 `417 / 319 / 125 / 493 / 530 / 126 / 357`（+ demo 1）。
- 扫描（自动 + 人工复核）：
  1. 全仓公开方法调用点计数（main + test + **freeway-ext**），筛出零调用者；
  2. 方法体归一化去重（>160 字符）——**0 组**，说明没有整段复制粘贴，冗余都在概念层；
  3. 跨模块字面量：状态码、媒体类型、请求头；
  4. 配置键：逐命名空间列出，核对"声明了但没有人读"的键；
  5. 定向读：作用域、HTTP 客户端、密钥比较、显示/描述模型、模块装配。
- 基线构建保持绿（未改代码）。

## 1. 结论

上一轮清掉的是"第二套实现"；这一轮剩下的冗余更细：**同一个概念有两个入口/两份词汇**，而且
多数是**公开 API 表面**的问题，不是内部逻辑错误。按"是否值得动"分三档：

| 档 | 条目 | 说明 |
|---|---|---|
| **值得做（小、明确）** | R13、R14、R7、R10、R11 | 密钥比较、SHA-256、媒体类型常量、flow 死访问器/死方法 |
| **值得讨论（形状/API）** | R1、R2、R3、R4、R9、R12、R6 | 作用域三入口、`Defer` 阶梯、`FreewayApp` 工厂、生命周期事件、未使用 wither、描述模型、状态码词表 |
| **观察（先记录，不动）** | R5、R8、R15 | 启动期重复解析、流式 gzip、三个 HttpClient 构造器 |

## 2. 逐模块冗余清单

### 2.1 commons

**R1 · 作用域有三个入口，并靠文档规定嵌套顺序。**
证据：`Defer.within(...)` 管理延迟动作（`scoped/Defer.java:64-111`），`ScopedCache.within(...)`
管理随作用域销毁的资源（`scoped/ScopedCache.java:66-90`），且 `Defer` 的 javadoc 明确写出
"`ScopedCache.within(() -> Defer.within(...))` 才是对的，反过来会先清理后提交"
（`Defer.java:87-93`；`ScopedCache.java:88-91`）。ioc 的 `Scoping` 只是第三个门面
（`ContainerImpl.java:210` 调 `ScopedCache.within`）。
为什么是冗余：同一个问题——"在作用域里跑一段代码，退出时按顺序收尾"——有三个入口加一条必须
记住的顺序规则；任何新代码都要先判断该用哪个。
收敛：一个 `ScopeSession`（打开/关闭 + 收尾回调列表）拥有顺序，`Defer`/`ScopedCache`/`Scoping`
成为它的三个视图；或最小改动，先提供 `Scopes.within(work)` 组合入口。规模 L，需单独立项。

**R2 · `Defer` 的 4 个 `within` 重载。**
证据：`within(Runnable)`、`within(Supplier)`、`within(Consumer<DeferScope>)`、
`within(Function<DeferScope,T>)`（`Defer.java:64/77/96/111`），外加 `defer`/`supply` 共 9 个静态
方法。后两个 `within` 只是"把 scope 递进去"，用 `try (var scope = Defer.scope())` 可表达。
收敛：保留 2 个 `within` + `scope()`；规模 S/M（公开 API）。

**R3 · 两个"没有内部主调用者"的公开工具。**
`LazyValue`、`ContextExecutor` 在 main 与 ext 中都只有 package-info 的提及，调用者只有测试。
它们是**给应用的公开工具**，不是死代码（有测试），所以不删；列出来是为了说明"公开类型数"
里有一部分是纯外部 API，后续再看到零调用不要误判。`Digests.sha256Base64` 类似（仅测试使用）。

### 2.2 ioc

除 §2.1 R1 的 `Scoping` 外，未发现新的重叠：`Advisor`/`MethodAdvice`/`MethodInvocation` 是
一个 AOP 接缝的三部分；`Ordering` 是 `Contribution` 的流式句柄（`Extension.add` 返回它）；
`@Primary` 与 `.primary()` 是**同一个 marker 的两种拼写**（`Primary.java` 的 javadoc 明说），
不算重复。

### 2.3 boot

**R4 · `FreewayApp` 7 个静态入口没有阶梯说明。**
证据：`FreewayApp.java:78-123`——`run(ModuleEx…)`、`run(String[] args, ModuleEx…)`、
`run(Class…)`、`run(String[] args, Class…)`、`create()`、`create(ModuleEx…)`、`create(Class…)`。
两两成对（args/无 args、实例/class），每个方法有自己的 javadoc，但类型级没有"该用哪个"。
收敛：类型级补一张阶梯表；若还要收 API，`run(ModuleEx…)` + `run(Launch, ModuleEx…)`
（`Launch` 携带 args/autoDiscovery）可把 4 个 run 压成 2 个。规模 S（文档）/ M（API）。

**R5 · 两个生命周期事件同形。**
证据：`AppStartedEvent(Container)` 与 `AppStoppingEvent(Container)` 字段完全相同；
core/ext 中没有实际订阅者（只有 `AppRuntime` javadoc 与测试提及）。
收敛：`record AppLifecycleEvent(Container, AppState)`；或保留两个类型，在 javadoc 写明
"分开是为了 EventBus 按类过滤"。规模 S，判断项（上轮 S7 未做）。

**R6 · 启动期把同一批配置文件解析了两遍。**
证据：`ConfigCascade.load(...)`（`ConfigCascade.java:86`）解析类路径文件；日志侧
`AppLogSource.values()` 又调 `ConfigCascade.loadLayers(loader, new String[0])`
（`AppLogSource.java:43`）再解析一遍同一批 `application.properties/json`。
为什么是冗余：两个读者必须对同一文件得出同一结果，规则一改就要同步两处；启动 I/O 也翻倍。
收敛：`loadLayers` 的类路径部分做成一个启动期缓存（类路径文件不可变，缓存安全），
`load` 与 `AppLogSource` 共用；或明确"日志侧只读类路径"这条边界并接受重复（当前文档已如此）。
规模 M（跨 bootstrap 时序，收益是防止漂移而非性能）。

### 2.4 http

**R7 · 媒体类型缺少裸 `application/json`，cloud 只能自己写字符串。**
证据：`MediaTypes` 只有 `TEXT_PLAIN_UTF8`/`JSON_UTF8`/`EVENT_STREAM_UTF8`/`OCTET_STREAM`
（`MediaTypes.java:12-16`）；cloud 在 4 处硬编码 `"application/json"`：
`rpc/RemoteCaller.java:41`、`rpc/CloudRequest.java:43,47`、`registry/RegistryNodeClient.java:210`。
为什么是冗余：同一个媒体类型两个来源；`JSON_UTF8` 带 charset，替换会改线上 header，所以现在
只能各写各的。
收敛：`MediaTypes.JSON = "application/json"`，cloud 改用常量；规模 S。

**R8 · 状态码有两套词汇。**
证据：`HttpStatus` 有 24 个常量（`HttpStatus.java`），全仓只用到 12 个；另一方面 cloud 的
handler 大量直接写 `sendJson(200/400/426)`、`send(401/405)`（`RegistryApi`、`ReadyHandler`、
`MetricsHandler`），http 内也有裸 `setStatus(...)`。
为什么是冗余：一个常量类存在却只覆盖一半调用点，新代码要在"常量"和"数字"之间选，两者会
逐渐分叉（改文案/改语义时只有一处被改到）。
收敛（二选一）：要么在 http+cloud 的响应路径统一用 `HttpStatus`，要么删掉用得少的常量、
让裸数字成为唯一写法。规模 S（判断项，不建议做一半）。

**R9 · 流式 gzip 是 `Compression` 之外的第二段实现。**
证据：缓冲路径用 `Compression.gzip(byte[])`（`Compression.java:70`）；`HttpContextImpl` 的
未知长度流式路径自己包 `GZIPOutputStream`（`HttpContextImpl.java:303`）。
为什么是弱冗余：gzip 格式由 JDK 提供，两处都只是包装；但"压缩怎么写"有两个落点，未来加
（例如 flush 策略、压缩级别）要改两处。
收敛：`Compression.gzipStream(OutputStream)` 返回配置好的 `GZIPOutputStream`，缓冲/流式共用；
规模 S。**观察级**，可缓做。

### 2.5 db

**R10 · `PoolConfig` 有 6 个 wither 全仓零调用。**
证据：`withUrl`/`withUsername`/`withPassword`/`withMaxIdleTime`/`withCleanInterval`/
`withHealthCheckTimeout`（`PoolConfig.java:91,96,101,126,131,136`）在 core、test、ext 中均为 0；
其余 wither（`withMaxSize` 等）也只有 1–2 处（测试）。配置实际由 `PoolConfig.defaults(...)`
与 `from(symbols)` 产生。
为什么是冗余：公开 API 存在但**没有被任何测试或调用点钉住**——这正是"冻结的签名 + 漂移的
行为"最坏组合的温床；要么它们是给应用的（应至少有一个测试），要么是多余的。
收敛：给 6 个 wither 各补一个断言（推荐），或删除；不应保留"没人调、没人测"的状态。
规模 S。

### 2.6 flow

**R11 · `Node` 有 4 个访问器全仓零调用。**
证据：`hasMeta`（`Node.java:107`）、`metaAsBool`（`:111`）、`metaAsNumber`（`:121`）、
`prevNodes`（`:171`）在 core/test/ext 中均 0 调用；实际使用的是 `meta(key)`、
`metaAsString`、`metaAs`、`metaOrDefault`。
为什么是冗余：`hasMeta` 等价于 `meta(key) != null`，`metaAsBool/metaAsNumber` 是
`metaAs(Boolean.class)/metaAs(Number.class)`（由 Coercer 完成）的未使用特化，`prevNodes` 是
`prevLinks().stream().map(Link::prevNode)` 的未使用缓存。四个方法各自维护一套转换规则，
而 `metaAs` 已经有一个统一入口。
收敛：删除 4 个访问器（或把 `metaAsBool/Number` 改为 `metaAs` 的调用）；规模 S。

**R12 · 两个"描述"类型形状几乎相同。**
证据：`TaskDesc`（`TaskDesc.java:9-12`：owner `Node` + description + handler）与
`ConditionDesc`（`ConditionDesc.java:6-9`：owner `Graph` + description + handler），
构造、`isEmpty`、`toString` 都是同一形状。
为什么是弱冗余：它们服务于 `FlowDriver.handleTask` / `handleCondition` 两个方法，owner 类型不同
（Node vs Graph），但也因此有两份几乎相同的样板；未来加一个字段（如 source line）要改两处。
收敛：抽一个包内 `Ref<O>`（owner + description + optional handler）让两者委托，或至少让
`ConditionDesc` 也持有 `Node`（条件属于边，但现有 API 用 Graph）。规模 M（公开 API）。
**建议先记录**：两个类型各自服务于一个 driver 回调，合并的收益是省样板而不是省概念。

**R13 · 两个未被调用的公开方法。**
证据：`FlowEvaluation.runTask(Node, String)`（`FlowEvaluation.java:84`）与
`Graph.nodeOrThrow(String)`（`Graph.java:125`）在 core/test/ext 中零调用；引擎目前直接调
`driver.handleTask(new TaskDesc(...))`。`nodeOrThrow` 是 `graph(id)` + 抛错的便捷写法，
与 `FlowEngine.graphOrThrow` 重复语义。
收敛：删除；规模 S（公开 API 小破坏）。

**R14 · `PlantUmlOptions` 的两个 wither 零调用。**
`withShowGatewayType`/`withShowIdInTitle`（`PlantUmlOptions.java:13,17`）在 core/test/ext 中
零调用；`toPlantUml(options)` 是唯一入口。
收敛：保留（record + wither 是仓库统一的配置形态，且这两个字段本身在用），但补一个测试或
在 javadoc 说明它们服务于程序化构造；规模 S。**观察级。**

### 2.7 cloud

**R15 · 密钥比较有两份，且一份不是常数时间。**
证据：`PeerHub.constantTimeEquals`（`PeerHub.java:494-498`，`MessageDigest.isEqual`，注释写明
"length-independent so the token cannot be probed by timing"）；而注册面的
`RegistryApi.authorized` 直接用 `String.equals`（`RegistryApi.java:118-127`，含 token 轮换的
两个比较）。
为什么是冗余且危险：同一个规则（比较共享密钥）在两个入口有两个实现，其中一个可被计时探测。
收敛：commons 或 cloud 内提供一个 `constantTimeEquals`，两处共用；规模 S。**本轮首选之一。**

**R16 · SHA-256 hex 的第三份实现。**
证据：`ObjectStorageDefault.etag` 内联 `MessageDigest.getInstance("SHA-256")` +
`HexFormat`（`ObjectStorageDefault.java:242-249`），而 commons 已有
`Digests.sha256Hex`（`Digests.java:27`，db 的 MigrationRunner 正在用）。
收敛：`etag` 改调 `Digests.sha256Hex`，删掉内联实现与 try/catch；规模 S。

**R17 · 三个 `HttpClient` 构造器。**
证据：`CloudHttpClientDefault.java:247`（超时/TLS/代理 knobs）、
`RegistryNodeClient.java:53`（connectTimeout）、`PeerConnector.java:236`（connectTimeout + TLS）。
为什么是弱冗余：三者角色不同（RPC 出站、注册中心写入、网格拨号），但"用哪套超时/TLS 默认"
没有共享规则；新增一个出站通道就会多第四份。
收敛：一个 `CloudHttpClients.builder(connectTimeout, sslContext)` 公共构造，各调用点只传差异；
规模 M。**观察级**，除非近期还会增加出站客户端。

**R18 · 内容类型字面量（与 R7 同一事实的另一半）。**
cloud 的 `MetricsHandler` 写 `"text/plain; version=0.0.4; charset=utf-8"`（Prometheus 专用，
无法用 `MediaTypes`）；其余 `application/json` 已由 R7 覆盖。

## 3. 收敛建议与顺序

| 顺序 | 条目 | 动作 | 规模 |
|---|---|---|---|
| 1 | R15 | 常数时间比较收敛到一处，注册面改用 | S |
| 2 | R16 | `ObjectStorageDefault` 复用 `Digests.sha256Hex` | S |
| 3 | R7 | 新增 `MediaTypes.JSON`，cloud 4 处改用 | S |
| 4 | R11 | 删 `Node` 4 个未用访问器 | S |
| 5 | R13 | 删 `FlowEvaluation.runTask`、`Graph.nodeOrThrow` | S |
| 6 | R10 | `PoolConfig` 6 个 wither：补测试或删除 | S |
| 7 | R8 | 状态码词表二选一（统一用 `HttpStatus` 或删常量） | S |
| 8 | R14 | `PlantUmlOptions` wither 补测试或说明 | S |
| 9 | R4/R5 | `FreewayApp` 阶梯文档；生命周期事件合并（判断） | S/M |
| 10 | R2 | `Defer` 重载收敛 | S/M |
| 11 | R6 | 启动期类路径配置解析缓存 | M |
| 12 | R9 | `Compression.gzipStream` 共用 | S |
| 13 | R12 | `TaskDesc`/`ConditionDesc` 共享形状 | M |
| 14 | R1 | 作用域原语合并（单独立项） | L |
| 15 | R17 | `HttpClient` 构造器收敛（观察） | M |

前 8 项互不依赖，可逐项实施并各自跑模块测试；R8 与 R12 是公开 API 判断项，建议先定语义。

## 4. 不建议动（避免为收敛而收敛）

1. **`JsonUtils` 与 `JsonCodec` 的双入口**：一个无容器（静态、内置 coercer），一个有容器
   （注入 coercer）；javadoc 已写明分工，合并会把容器依赖拖进静态层。
2. **三套事件平面**（本地 EventBus / 云事件网格 / Kafka）：可靠性等级不同，不是重复。
3. **`Route` 的 19 个工厂**（9 动词 × handler/class 两形）：仓库明确的重载阶梯惯例，删一个
   反而让调用点更长。
4. **`@Primary` 与 `.primary()`**：同一个 marker 的两种拼写，`Primary.java` 有说明；不是重复。
5. **`Dialect` 的扩展能力默认方法**（如 `bracketQuoting`）：没有内置实现覆盖不等于死代码，
   它是给自定义方言的钩子。
6. **`CloudResponse.bodyAs` / `RpcExport.propagateMessages`**：仓内零调用，但它们是应用侧
   RPC API（一个解码 body，一个声明例外消息透传），有测试/文档支撑，保留。
7. **`ReadyHandler` 与 `CloudHealthModule` 的两个健康路由**：`/health/live` 与 `/health/ready`
   语义不同（前者进程存活，后者依赖就绪），不是重复。

## 5. 被排除的候选（查证后不成立）

| 候选 | 为什么不是冗余 |
|---|---|
| `freeway.cloud.{secret,storage,discovery,registry}.type` 四个键 | 看起来只出现在 `ConfigKeys` 与文档里；实际由 `BackendTypeGuard.warnIfExternal(...)` 读取（`DiscoveryConnectionHook`、`CloudStorageModule`、`CloudSecretModule` 各传一次），"声明了外部后端但本地实现仍生效"时告警 |
| `@Primary` vs `.primary()` | 同一个 marker（`Primary.class`）的两种拼写，`BindingImpl` 归一到同一集合 |
| `LazyValue` / `ContextExecutor` / `Digests.sha256Base64` | 无 main 调用者，但有测试，是给应用的公开工具 |
| `RegistryApi`/`RegistryServerModule`/`RegistryIndexHandler` | 上轮已收敛为同一张 `SURFACE` 表；三者分别是 HTTP 视图、路由装配、索引自述，职责不同 |

## 7. 实施记录（本轮）

| # | 状态 | 落地 |
|---|---|---|
| R1 | ✅ | 按 S2 实施：`Defer`/`ScopedCache` 共用一个 `ScopeSession`（一绑定、一关闭算法）；value scope 在 action scope 内附着，顺序规则消失；`Scopes`/`DeferScope`/`ScopedCache.Session` 删除 |
| R2 | **撤回** | 复查发现 scope 句柄必须在回调内绑定 `ScopedValue`，`try (scope)` 无法表达；4 个 `within` 是"结果/句柄"两条轴，均被使用（`rollback()`），保留 |
| R3/R4 | ✅ | `FreewayApp.run` 收敛为 `run(ModuleEx...)` + `run(Launch, ModuleEx...)`；`Launch` 记录 args/autoDiscovery；按类声明走 `create(Class…).start()`/`.add(Class…)` |
| R5 | ✅ | `AppLifecycleEvent(Container, AppState)` 取代两个同形记录；订阅侧按 state 过滤，传递策略不变 |
| R6 | ✅ | `ConfigCascade.bootstrapLayers` 按 ClassLoader 弱缓存启动期类路径解析，日志侧与主级联不再各解析一遍 |
| R7 | ✅ | `MediaTypes.JSON`；cloud 的 4 处硬编码改用 |
| R8 | ✅ | cloud 响应统一 `HttpStatus`（新增 426）；http 原有常量不变 |
| R9 | ✅ | `Compression.gzipStream`，`HttpContextImpl` 流式路径改用它 |
| R10 | ✅→反转 | 先删了 6 个零调用 wither；收尾审计发现"半套 wither"不一致，已恢复并补 `everyFieldHasAWither` 测试 |
| R11 | ✅ | 删除 `Node.hasMeta/metaAsBool/metaAsNumber/prevNodes` |
| R12 | ✅（内部） | `TaskDesc`/`ConditionDesc` 共享包内 `Ref`；公开 API 不变 |
| R13 | ✅ | 删除 `FlowEvaluation.runTask`、`Graph.nodeOrThrow` |
| R14 | ✅ | `PlantUmlOptionsTest` 钉住默认值与 wither 不可变性 |
| R15 | ✅ | `ConstantTime`（cloud internal）；注册面与网状共用 |
| R16 | ✅ | `ObjectStorageDefault.etag` → `Digests.sha256Hex` |
| R17 | ✅ | `CloudHttpClients.builder(...)`，三处出站客户端共用 |

验证：core 全量 + demo 1 绿；freeway-ext 干净构建绿（`UndertowFrameProbeTest` 的旧 `run(new String[0], …)`
调用已同步为新入口）。

## 6. 分模块评分（1–5）

| 模块 | 定位 | 机制 | 概念 | 边界 | 规则 | 文档 | 本轮要点 |
|---|---:|---:|---:|---:|---:|---:|---|
| commons | 4 | 4 | 3 | 4 | 4 | 4 | 作用域三入口（R1）、`Defer` 阶梯（R2） |
| ioc | 5 | 5 | 4 | 5 | 4 | 4 | 仅 `Scoping` 的第三入口（R1） |
| boot | 5 | 4 | 3 | 5 | 4 | 4 | 7 个入口（R4）、两个事件（R5）、重复解析（R6） |
| http | 4 | 4 | 3 | 4 | 4 | 4 | 状态码词表（R8）、媒体常量缺位（R7）、流式 gzip（R9） |
| db | 4 | 4 | 4 | 4 | 4 | 4 | 未使用 wither（R10） |
| flow | 4 | 4 | 3 | 4 | 4 | 4 | `Node` 访问器/死方法（R11/R13）、描述模型（R12） |
| cloud | 4 | 4 | 3 | 4 | 4 | 4 | 密钥比较（R15）、SHA-256（R16）、客户端构造（R17） |

**一句话**：上一轮清掉的是"同一件事两套实现"，这一轮要清的是"同一件事两个入口/两份词汇"；
其中 R15（密钥比较）与 R16（SHA-256）是真正该马上做的，因为它们不只是重复，还带着安全/一致性
的尾巴。
