# freeway 1.5.7 复审：各模块评估与可简化的设计

> 在 1.5.7 审计修复（台账见 [audit-coherence-1.5.7.md](audit-coherence-1.5.7.md)）与
> freeway-ext 适配器契约修正之后，重新按同一标准看一遍各模块；本轮的重点不是再找 "第二套机制"
> （上一轮已清掉），而是找**可以更少的概念、更少的类型、更少的约定**。
> 判据仍是仓库自己的那条：**把每个事实放在它不会被弄错的地方；需要一段解释才能成立的构造，
> 本身就是缺陷**。

## 0. 方法与基线

- 读法：先量（模块行数、公开类型数、配置键数、测试数），再按"同一问题有几个答案"定向读
  （路由/路径、upsert、TLS 材料、作用域、显示模型、事件类型、模块装配），最后只保留能指到
  文件与行的结论。
- 构建基线（本轮未改代码，仅取证）：
  `commons 417 · ioc 319 · boot 125 · http 493 · db 529 · flow 126 · cloud 357`，
  demo 1；freeway-ext 干净构建 8 模块、200 例（7 例 env-gated 跳过）。
- 上一轮的修复复核：未发现回归。访问日志观察者、`ResponseFraming` 单一规则、`Sql` 子句字段化、
  `OwnedTransaction`、`JoinDomains` + 迭代深度、readiness 单次写入、`TIER_*` 层级顺序、
  OVERFLOW 重载、`EventBus` 能力化、日志键启动点名——都还在，且测试覆盖着。

| 模块 | main 行数 | 测试数 | 公开类型 | 配置键 | 一句话 |
|---|---:|---:|---:|---:|---|
| commons | 9 255 | 417 | 38 | 日志表 | 工具与基础设施齐，作用域有两个入口 |
| ioc | 7 085 | 319 | 28 | — | 边界与约定最干净 |
| boot | 2 576 | 125 | 12 | 2 | 小，但入口工厂与生命周期事件可再收 |
| http | 15 459 | 493 | 77 | 39 | 最大；路径词汇与分组记录重复 |
| db | 9 249 | 529 | 35 | 21 | `Sql` 与 `Dialect` 各有一套 upsert |
| flow | 5 081 | 126 | 28 | 0 | 显示模型是 null 联合，有冗余句柄类型 |
| cloud | 11 904 | 357 | 88 | 58 | 角色清晰；TLS 装载与 http 各写一份 |

## 1. 结论摘要

修复后各模块内部已经没有"第二个实现"，但仍有 **7 处"同一个问题两个答案"**，以及 **4 处
"类型/入口比它解决的问题多"**。它们不是正确性缺陷（都有测试），而是**维护成本**：新读者
要先判断"该用哪一个"，而两个答案会随时间分叉。

**可简化设计，按价值排序（本轮实施状态见 §7）：**

| # | 简化 | 现状 | 规模 | 建议 |
|---|---|---|---|---|
| S1 | 统一 upsert 渲染 | `Sql` 手写 `ON CONFLICT`，`Dialect.upsertClause` 另有一套（支持 MySQL）；`Orm.save` 用后者、`Sql` 用户拿不到 | M | 做；先明确 `Sql` 是否必须保持 Postgres-literal |
| S2 | 一个路径归一化 | `PathJoiner.normalize` 与 `PathPattern.normalizePath` 对根路径给出 `""` / `"/"` 两个答案；`PathJoiner` 只服务分组 | S | 做；把 `join` 并入 `PathPattern`，删 `PathJoiner` |
| S3 | 一套分组展开 | `RouteGroup` 与 `WebSocketGroup` 是同一形状写两遍 | S/M | 做；共享展开帮助器，或删分组 |
| S4 | 显示模型去 null 联合 | `PlantUmlDisplayContext` 同时装 Node/Link，`id()` 对 Link 返回 null；`Result` 三态；4 个 `toPlantUml` 重载 | S/M | 做；sealed subject 或两个 formatter |
| S5 | 删 `NamedTaskHandler` | 与 `addActivity(id).title(...).task(...)` 等价，仅 `GraphSpec:148` 使用 | S | 做；删接口 + 重载 |
| S6 | 作用域只留一种嵌套约定 | `Defer.within` 与 `ScopedCache.within` 有手写嵌套顺序要求，ioc 的 `Scoping` 又委托后者 | L | 探索；先补一个"组合入口"再决定是否合并原语 |
| S7 | 生命周期事件合一 | `AppStartedEvent(Container)` / `AppStoppingEvent(Container)` 同形 | S | 判断；若不做，至少写明"两个类型是为了订阅侧过滤" |
| S8 | 收 http2 公开面 | `hpack` 子包使 5 个内部类型必须 public | M | 做；把 `hpack` 并入 `http2` 包 |
| S9 | 入口工厂写成阶梯 | `FreewayApp` 7 个静态入口，3 个 class 变体 + 实例变体，无阶梯说明 | S（文档）/M（API） | 做文档；API 是否收再定 |
| S10 | `Defer` 重载收敛 | 9 个静态方法、4 个 `within` | S/M | 判断；`within(Consumer/Function)` 可用 `scope()` 替代 |
| S11 | 一份 keystore 装载 | `http.SslContexts` 与 `cloud.TlsStores` 各写 null/空密码与 PKCS12/JKS 规则 | S/M | 做；下沉到 commons，一处规则 |
| S12 | 公共类型补类型级 javadoc | 主 API 中 `Route`、`CorsFilter`、`Query`、`Binder`、`MigrationRunner` 等只有方法文档 | S | 做；见 §3 清单 |

---

## 2. 可简化设计（详细）

### S1 · upsert 有两个渲染器

**现状（证据）**

- `Dialect.upsertClause(conflictColumns, updateColumns)`（`dialect/Dialect.java:308`）产出
  `ON CONFLICT (…) DO UPDATE SET … EXCLUDED`；`MySqlDialect`（`:77`）覆盖为
  `ON DUPLICATE KEY UPDATE … VALUES(…)`。`Orm.save` 用的就是它（`Orm.java:203`）。
- `Sql` 自己有一套：`onConflict(String)`（`Sql.java:457`）把列名当文本存进子句字段，
  渲染时固定写 ` ON CONFLICT (…)`（`Sql.java:990-1015`），并在 `sql(Dialect)` 里对
  `supportsOnConflict()` 为 false 的方言直接拒绝（`Sql.java:808-810`，提示语甚至写
  "use upsertClause()"）。

**为什么是负担**：同一个业务动作（upsert）有两条路，且只在一条上支持 MySQL；读代码的人要
先知道"`Sql` 是 Postgres 语法直写，`Orm` 才是可移植 upsert"。更糟的是拒绝信息把用户推向
另一套 API，等于公开承认这是两个能力。

**简化形状**：让冲突子句**语义化**——`onConflict(List<String> columns)` + `doUpdateSet(...)`
只表达"冲突目标 + 更新"，渲染交给 `sql(Dialect)`（内部调 `dialect.upsertClause`）。
`Orm.save` 改为组装 `Sql.insert(…).onConflict(ids)…`，两条路合成一条。列名从文本变成列表，
是必要的 API 收紧（现状的文本无法安全转成方言引号）。

**代价/风险**：`Sql` 的文本输出会随方言变化（现在永远 `ON CONFLICT`），MySQL 用户获得支持；
测试里对字面文本的断言要跟着方言走。规模 M。**建议先确认**：`Sql` 是否被定位为"Postgres 语法
直写"（若是，则改为在 `onConflict`（S1）里写明这一限制，并把错误信息改成指向 `Orm`）。

### S2 · 路径归一化有两个答案

**现状（证据）**：`PathPattern.normalizePath` 把根路径归一为 `"/"`（`route/PathPattern.java:213-218`）；
`PathJoiner.normalize` 包装它后把 `"/"` 再改成 `""`（`route/PathJoiner.java:8-13`），
`join(prefix, path)` 依赖这个空串约定（`:16-17`）。`PathJoiner` 的全部消费者只有
`RouteGroup` 与 `WebSocketGroup`（两个文件）。

**为什么是负担**：`normalizePath` 与 `normalize` 名字几乎一样、对同一输入给出不同答案；任何
新代码判断"path 是否已归一"都要先知道用的是哪一个。`PathPattern` 的 javadoc 已经宣称自己是
路径规则的家（"path parsing/validation helpers shared by …"），`PathJoiner` 却是规则之外的
第三个入口。

**简化形状**：把 `join` 移进 `PathPattern`（或单独一个 `PathOps`），内部统一用 `/` 表示根，
在拼接时直接把根当作空片段处理；删除 `PathJoiner`。若 S3 删除分组，`PathJoiner` 连存在理由
都没有了。

**代价**：删除一个公开类型，两处调用点。规模 S。

### S3 · 两个分组记录是同一份代码

**现状（证据）**：`RouteGroup`（`route/RouteGroup.java:7`）与 `WebSocketGroup`
（`websocket/WebSocketGroup.java:7`）都是 `record(prefix, List<T>)` + `of` + `expand()`，
连 `expand` 的实现都逐行对应（`normalize(prefix) + normalize(path)`）。两者只被
`RouteIndex`（`:40-46`）与 `WebSocketIndex`（`:34-38`）的构造器消费。

**为什么是负担**：分组这个概念的规则（前缀归一、展开、顺序）写了两遍；以后改一条规则要改两处。
它同时也是 S2 存在的唯一理由。

**简化形状**（择一）：
1. 共享一个包内帮助器 `PathGroups.expand(String prefix, List<T> items, BiFunction<String,T,T> prefixer)`，
   两个 record 只留类型与 `of`；或
2. 删除分组：`RouteIndex`/`WebSocketIndex` 只收完整路径的 `Route`/`WebSocketRoute`，前缀由
   调用方拼（`PathPattern.join`）。API 更小，但失去"一个前缀下一组路由"的写法。

**代价**：方案 1 是纯内部整理（S）；方案 2 是公开面收缩 + 调用点迁移（M）。建议方案 1。

### S4 · PlantUML 显示模型是 null 联合

**现状（证据）**

- `PlantUmlDisplayContext`（`flow/PlantUmlDisplayContext.java:6`）同时持有 `node` 与 `link`，
  二选一非空；`id()` 对 Link 返回 null（`:39-44`），`task()` 对 Link 返回 null（`:56-60`），
  `when()` 对 Node 返回 null（`:63-67`）；调用方必须先 `isNode()/isLink()`。
- `PlantUmlDisplayResult`（`PlantUmlDisplayResult.java:6`）三态：`HIDDEN` / `ofDefault()` /
  `of(text)`；两个 boolean 表达三个状态。
- `Graph.toPlantUml` 有 4 个重载（`Graph.java:148,152,156,160`）。

**为什么是负担**：三个 null 语义（"不是节点"、"没有 task"、"默认显示"）混在一个类型里，
调用方每一步都要判断；类型系统完全可以表达"节点或边"和"隐藏/默认/自定义"。

**简化形状**：
- `sealed interface PlantUmlSubject permits NodeSubject, LinkSubject`（或直接
  `record NodeSubject(Node)` / `record LinkSubject(Link)`），访问器只出现在各自分支；
- 结果用 `Function<PlantUmlSubject, Optional<String>>`：空 = 隐藏，值 = 自定义，`null` 返回值
  不再表示"默认"（或保留一个常量 `DEFAULT`）；或两个 formatter
  `(Function<Node,String>, Function<Link,String>)`。
- 重载收敛为 `toPlantUml(PlantUmlOptions)` + `toPlantUml(PlantUmlOptions, Function)`。

**代价**：公开 API 变更（3 个类型 + Graph 重载），测试跟着改；flow 的显示功能使用面小，
风险低。规模 S/M。

### S5 · `NamedTaskHandler` 与 `NodeSpec` 的既有能力重复

**现状（证据）**：`NamedTaskHandler extends TaskHandler` 只多 `name()`/`title()`
（`NamedTaskHandler.java:9-20`）；`GraphSpec.addActivity(NamedTaskHandler)` 的实现就是
`addActivity(handler.name())` + `title(handler.title())` + `task(handler)`
（`GraphSpec.java:148-154`）——三行已有 API 的组合，被包成一个接口 + 一个重载。

**简化形状**：删除接口与重载；硬编码图用
`addActivity(id).title(title).task(handler)`。若希望保留"一个对象描述一个命名任务"，那它应该是
一个值（`record NamedTask(String name, String title, TaskHandler handler)`）而不是
`TaskHandler` 的子类型——接口继承在这里没有带来任何多态。

**代价**：一个公开类型 + 一个重载删除，测试/调用点少。规模 S。

### S6 · 两个作用域原语 + 一条手写嵌套约定

**现状（证据）**

- `Defer.within(...)` 管理延迟动作（`commons/scoped/Defer.java`），`ScopedCache.within(...)`
  管理随作用域销毁的资源（`commons/scoped/ScopedCache.java:66-90`）。
- `Defer` 的 javadoc 明确写出顺序规则：`ScopedCache.within(() -> Defer.within(...))` 才是对的，
  反过来会"先清理缓存、再提交"（`Defer.java:87-93`；`ScopedCache.java:88-91`）。
- ioc 的 `Scoping` 只是 `ScopedCache.within` 的门面（`ContainerImpl.java:210`），
  于是**框架有两个 Scope 入口，外加一条必须记住的嵌套顺序**。

**为什么是负担**：这是"需要一段解释才能正确使用"的构造，正是仓库判据要消除的那类。两个
`ScopedValue` 的打开/关闭顺序本应由一个原语拥有。

**简化形状**（探索性）：
1. 一个 `ScopeSession`（commons）同时持有"延迟动作队列"和"清理回调"，`open()` 时压栈、
   `close()` 时按固定顺序 drain（先提交、再清理，或反之由策略决定）；`Defer` 与 `ScopedCache`
   变成它的两个视图/策略，`Scoping` 直接用它。
2. 或最小改动：提供 `Scopes.within(work)` 组合入口，内部按正确顺序打开两者，并把裸用两个
   `within` 的路径标注为内部；把"顺序规则"从文档变成代码。

**代价**：跨 commons/ioc 的结构调整，涉及事件延迟与 bean 销毁两个正确性面；**不建议在
没有新增 scope 需求时先动**。规模 L，先设计再实施。

### S7 · 生命周期事件同形两份

**现状（证据）**：`AppStartedEvent(Container container)` 与 `AppStoppingEvent(Container container)`
（`boot/event/`）字段完全相同；差别只在发布时机与订阅侧的类过滤。

**简化形状**：`record AppLifecycleEvent(Container container, AppState state)`，订阅侧按
`state` 过滤；或维持两类型但在 javadoc 写明"分开是为了 EventBus 的按类过滤"。**判断项**：
两类订阅是 EventBus 的惯用法，合并会把过滤责任推给每个订阅者；如果只有 1-2 个订阅者，合并
更简单。规模 S。

### S8 · `hpack` 子包把内部类型顶成 public

**现状（证据）**：`engine/http2/hpack` 与 `engine/http2` 互引，使
`HPackContext`、`HeaderFields`、`Http2HeaderField`、`ContinuationFrame`、`Http2Exception`
必须 public（跨包）。它们在 `http2` 之外**没有** main/test 消费者（`ContinuationFrame`、
`HPackContext`、`Http2Exception` 为零；`HeaderFields`/`Http2HeaderField` 仅测试在
`http.engine` 包内引用）。`Http2Connection`/`Http2Stream`/`SettingsFrame` 等确实被
`Http1xSession`/`Http2Session` 跨包使用，必须保留 public。

**简化形状**：把 `hpack` 的类并入 `engine.http2`（RFC 7541 的编解码仍可单独成文件/测试），
让上述 5 个类型降为包内；必要时只留一个 `public` 的 facade（如 `Hpack`）。公开面 77 → 约 72，
并让"帧/HPACK 是连接内部"的事实由包边界表达。

**代价**：机械搬移 + 测试包名调整；无行为变化。规模 M（文件多、引用多）。

### S9 · `FreewayApp` 7 个静态入口没有阶梯说明

**现状（证据）**：`FreewayApp.java:78-123`：
`run(ModuleEx…)`、`run(String[] args, ModuleEx…)`、`run(Class…)`、`run(String[] args, Class…)`、
`create()`、`create(ModuleEx…)`、`create(Class…)`。class 变体与实例变体的差别（谁来实例化模块）
只能从实现读出；仓库规则要求"一两个可选参数用重载阶梯，每一步说明它加了什么"。

**简化形状**：至少补类型级 javadoc 的阶梯表（哪个是主入口、class 变体解决什么）。若还要收
API：`run(ModuleEx…)` + `run(Launch, ModuleEx…)`（`Launch` 携带 args/autoDiscovery），把 4 个
run 压成 2 个；class 变体可通过 `ModuleDiscovery.instantiate` 由调用方预处理，但这会改变
"模块类由容器实例化"的语义，需先确认。规模 S（文档）/ M（API）。

### S10 · `Defer` 的重载阶梯可以更短

**现状（证据）**：`Defer` 有 9 个 public static（`scoped/Defer.java`），其中
`within(Runnable)`、`within(Supplier)`、`within(Consumer<DeferScope>)`、
`within(Function<DeferScope,T>)` 四者是同一能力的两种表达；`defer`/`supply` 又各有带 id/不带 id。

**简化形状**：`within(Runnable|Supplier)` 两个 + `scope()` 返回 `AutoCloseable` 的
`DeferScope`（try-with-resources 直接拿 scope），删除 Consumer/Function 两变体；id 变体保留
（它是排序/去重的输入）。规模 S/M，公开 API 收敛。

### S11 · keystore 装载规则两份

**现状（证据）**

- 服务端：`http.SslContexts` 私有 `loadKeyStore`（`SslContexts.java:94-101`）按显式
  `keyStoreType` 加载，`keyStorePasswordChars(null)` 返回 **null**（`:118-120`，javadoc 说明空的
  PKCS#12 密码必须是 null 而不是空数组）。
- 客户端：`cloud.rpc.TlsStores`（`TlsStores.java:15-48`）先试 PKCS12 再回退 JKS，
  `passwordChars(null)` 返回 **空数组**（`:41-43`）。

**为什么是负担**：同一个"读 keystore"的事实有两套格式探测规则和两个 null/空密码答案；一边的
注释说 null、另一边给空数组，迟早会在某一种 keystore 上分叉。`TlsStores` 的 javadoc 自己写
"one place that knows the file formats"——但它只是 client 侧的 one place。

**简化形状**：把格式探测 + 密码归一收敛到 commons 的一个 `TlsStores`（或 `KeyStores`），
`SslContexts`（服务端，含 SNI 目录）与 cloud 的旋转材料都调用它；"类型由扩展名推断"与
"PKCS12 失败回退 JKS"作为同一函数的两个参数，而不是两处分支。

**代价**：commons 需新增一个公开工具（两模块共用），`SslContexts` 的内部私有方法删除；需决定
null 与空数组哪一个是对的（SslContexts 的注释给出了理由：null 用于空密码 PKCS#12）。规模 S/M。

### S12 · 主 API 缺类型级 javadoc

抽查确认（声明行前无 `/** */`；方法/字段文档仍在）：

| 类型 | 位置 | 备注 |
|---|---|---|
| `Route` | `http/route/Route.java:10` | 路由 DSL 的主类型 |
| `RouteGroup` | `route/RouteGroup.java:7` | — |
| `WebSocketRoute` | `websocket/WebSocketRoute.java:7` | — |
| `WebSocketMatch` | `websocket/WebSocketMatch.java:5` | 公开缝合点的返回类型 |
| `CorsFilter` | `filter/CorsFilter.java:16` | 扩展点 |
| `StaticResourceMount` | `staticfile/StaticResourceMount.java:38` | 754 行，选项多 |
| `Binder` | `ioc/Binder.java:5` | 绑定 DSL 的主类型 |
| `Query` | `db/Query.java:7` | 查询链 |
| `MigrationRunner` | `migration/MigrationRunner.java:38` | — |
| `JsonArray` | `commons/json/JsonArray.java:7` | — |
| `CloudModule` | `cloud/CloudModule.java:57` | 文档在 `@SubModule` 段落里，但类型本身无注释 |

不是"没文档"（方法都很细），而是**打开类型先看到一堆方法签名，得自己归纳它是什么**。
规模 S，纯文档。

---

## 3. 分模块评估

评分维度与上一轮一致（1–5）：定位 / 机制 / 概念 / 边界 / 规则 / 文档。

### 3.1 commons · 4.0（4/4/3/4/4/4）

- **好的部分**：JSON 的"静态树层 / 注入 codec 层"分工明确且各有 javadoc；coercion 规则只有
  一份（上轮把 JSON getter 并进来了）；日志格式化与 MDC 开关共用一处；`Defer` 的 id/排序
  语义有测试。
- **概念挤点**：作用域相关有 `Defer`、`DeferScope`、`DeferAction`、`ScopedCache` 四个公开
  类型，且两者有手写嵌套约定（S6）；`Defer` 重载 9 个（S10）。
- **文档缺口**：`JsonArray`、`CoercerDefault`、`ValidationError`、`Strings`、`Digests` 无
  类型级注释（后两者是内部工具，优先级低）。

### 3.2 ioc · 4.3（5/5/4/5/4/4）

- **好的部分**：`Binder` 只有 2 个方法，`Binding` 7 个；`ContainerImpl` 是唯一公开装配点；
  `Extension`/`Contribution`/`KnownKeys` 各司其职；`EventBus` 上轮已去容器化并加了能力测试；
  `Scope`/`Scoping` 的语义与生命周期有测试。这是全仓边界最干净的一层。
- **缺口**：`Binder` 无类型级 javadoc（S12）；`LoggerSource` 的定位（为什么日志注入在 ioc 而不是
  boot/commons）只在方法 javadoc 里，建议补一句。

### 3.3 boot · 4.3（5/4/4/5/4/4）

- **好的部分**：`ConfigCascade` 把解析/覆盖/告警收在一处；`TIER_*` 排序单一来源；日志键
  不生效会出声；热重载 OVERFLOW 已修；`AppState` 与 lifecycle hook 的时序有测试。
- **挤点**：`FreewayApp` 7 个入口无阶梯（S9）；`AppStartedEvent`/`AppStoppingEvent` 同形（S7）。
- **文档**：`AppRuntimeDefault` 无类型注释（内部实现，低优先级）。

### 3.4 http · 3.8（4/4/3/4/4/3）

- **好的部分**：`HttpServer.create` 只有 2 个工厂；管线在引擎之前编译成单一 `ExchangeHandler`，
  所以 adapter 与内置引擎的过滤器/CORS/健康行为天然一致；`ResponseFraming` 与
  `AbstractHttpContext.readBody` 是共享规则；访问日志已改为事件观察者；header 预算单键。
- **概念挤点**：路径词汇（S2）、分组记录（S3）、h2 内部类型的公开面（S8）、TLS 装载与 cloud
  各一份（S11）。
- **表面宽度**：`Route` 19 个工厂（9 个动词 × lambda/class 两形）是刻意的便利，不建议收；
  `HttpPipeline` 把 `corsFilter`/`healthFilter` 单列是必要的（CORS 在 WS 升级时要按 origin
  解析，不能只当过滤器）——**不要**为了 7→5 个字段把它塞进 `filters`。
- **文档**：`Route`、`CorsFilter`、`StaticResourceMount`、`WebSocketRoute`/`WebSocketMatch`
  缺类型级注释（S12），对一个 15k 行的模块是可见的缺口。

### 3.5 db · 4.0（4/4/4/4/4/3）

- **好的部分**：`OwnedTransaction` 上轮统一了借还；`Sql` 子句字段化后渲染顺序不再依赖调用顺序，
  能力判定读字段；`SqlTextParser` 单扫描器；`Pool` 契约有 foreign-handle 守卫（ext 侧同样）。
- **概念挤点**：upsert 两个渲染器（S1）是本模块最值得动的；`Sql` 1 416 行按仓库规则不算问题
  （一个语法），但 S1 若做，会把其中一段渲染职责移给 `Dialect`，反而更贴"方言拥有方言语法"。
- **文档**：`Query`、`MigrationRunner` 无类型级注释；`DatabaseImpl`/`PoolDefault` 是内部实现，
  优先级低。

### 3.6 flow · 3.7（4/4/3/4/4/3）

- **好的部分**：`JoinDomains` 与图构建同点；深度计算迭代化；`FlowDeclarationOrderTest` 把
  "声明顺序不影响判定"钉住；`FlowEventBus` 的 sink 与本地订阅职责清楚；`FlowDriver` 的
  executor/钩子默认值让最小实现成立。
- **概念挤点**：显示模型 null 联合（S4）；`NamedTaskHandler` 冗余（S5）；`TaskDesc` 反向持有
  `Node`（`TaskDesc.java:10`，由 `Node` 构造，`FlowEvaluation` 也构造）——目前没造成环，但
  "任务描述知道自己属于哪个节点"不是它的职责，建议只留 `description`/`handler`，需要节点时由
  调用方给。
- **文档**：`TaskHandler`、`ConditionHandler`、`FlowModule` 无类型级注释；`Stepper` 在
  `internal` 且被 `FlowEngineDefault` 使用，保留。

### 3.7 cloud · 3.8（4/4/3/4/4/3）

- **好的部分**：模块边界按能力切（context/health/discovery/rpc/observe/resilience/secret/
  storage/event），每个 `Cloud*Module` 可单独放置；`ServiceRegistry.register(instance, Health)`
  与 `markReady` 上轮把 readiness 接上了；自愈只有一个机制；`InvocationContext` 的
  ScopedValue + ambient 双通道有明确注释；resilience 三件套各自可替换。
- **概念挤点**：TLS 装载与 http 各一份（S11）；88 个公开类型里有一批是 `internal` 包内为跨包
  装配而 public（符合规则，不算缺陷），但 `CloudModule` 本身缺类型注释（S12）。
- **不建议动**：三套事件平面（本地 EventBus / 云事件网格 / Kafka）——上轮已论证是不同可靠性
  等级，不是重复。

## 4. 不建议简化的地方（避免为简化而简化）

1. **扁平配置 record + 逐字段 wither**（`HttpServerConfig` 12 组件、`PoolConfig` 12、
   `SslSettings` 13）：再包一层 `Limits`/`Tls` 子 record 会让每个调用点多一跳，而 flat 结构
   正是"每个键一处"的形态。
2. **`XDefault` seam**（cloud 的十几个 Default、db 的 `PoolDefault`、http 的 `JsonCodecDefault`）：
   它们的存在理由是 ext/app 可 `.primary()` 替换；没有仓内第二个实现不是删除理由。
3. **`HttpPipeline` 的 cors/health 专用字段**：CORS 在 WS 升级处需要 `resolveAllowedOrigin`，
   health 是内置过滤器，合并进 `filters` 会引入 `instanceof` 扫描。
4. **`Database` 的宽接口**：query/execute/batch/transaction/stats 是一张表的自然分组；拆成
   `QueryOps`/`DmlOps` 子接口只会让 handler 注入时多选一个类型。
5. **`flow` 的 `FlowDriver` 词汇**（handler + onNodeStart/End + executor）：它让"最小驱动"只需
   实现两个方法，是收窄而非膨胀。
6. **`hpack` 的独立性**（若不执行 S8）：RFC 7541 的编解码值得独立测试；S8 的价值是把 public
   收窄，不是把包合并本身。

## 5. 建议路线（按 ROI）

1. **S2 + S3（http，S/M）**：删 `PathJoiner`、共享分组展开。小、可独立验证，公开面 -1。
2. **S5（flow，S）**：删 `NamedTaskHandler`；顺手把 `TaskDesc.node` 收缩（若调用点允许）。
3. **S12（全仓文档，S）**：补 8 个主 API 的类型级 javadoc；纯文档，零风险。
4. **S11（commons/http/cloud，S/M）**：一份 keystore 装载；先写测试钉住 null-vs-空密码的
   正确规则，再统一。
5. **S1（db，M）**：统一 upsert；先确认 `Sql` 是否允许方言化渲染，再动 API。
6. **S4（flow，S/M）**：sealed subject + 结果去三态。
7. **S8（http，M）**：hpack 并入 http2 包，收 5 个 public。
8. **S6（commons/ioc，L）**：作用域原语合并——单独立项设计，不与上面混做。

## 7. 实施记录（本轮）

| # | 状态 | 落地情况 |
|---|---|---|
| S1 | ✅ | `Sql` 冲突子句收列名、按方言渲染；`Orm.save` 改为组装 `Sql`；`sql(MySqlDialect)` 现在产出 `ON DUPLICATE KEY UPDATE`，`doNothing` 保持方言校验 |
| S2 | ✅ | `PathPattern.join` 成为唯一拼接规则，`PathJoiner` 删除；`RouteGroup`/`WebSocketGroup` 改用 `/` 作为根的唯一写法。后续：`normalizePath` 改名 `normalize`——类名已给出 "Path"，限定词原本只为规避被删的 `PathJoiner.normalize` |
| S3 | 部分 | 两个分组共享同一路径规则（S2），但展开循环仍各一份——抽公共展开器要新增一个公开类型，收益不抵成本，故保留 |
| S4 | ✅ | 删除 `PlantUmlDisplayContext`/`PlantUmlDisplayResult`，改为 sealed `PlantUmlSubject` + `String` 回调（`null` 默认、空串隐藏、其它文本替换） |
| S5 | ✅ | `NamedTaskHandler` 与 `GraphSpec.addActivity(NamedTaskHandler)` 删除；`TaskDesc.node` 保留（自定义 driver 可能读取，删除是额外破坏，未做） |
| S6 | 未做 | 按报告建议单独立项：作用域原语合并涉及事件延迟与 bean 销毁两个正确性面 |
| S7 | 未做 | 判断项：保留两个生命周期事件类型（订阅侧按类过滤是 EventBus 的惯用法） |
| S8 | ✅ | `hpack` 并入 `engine.http2`，5 个内部类型降为包私有；公开类型数 77 → 72 |
| S9 | 未做 | 仅文档建议，未纳入本轮 |
| S10 | 未做 | `Defer` 重载收敛会改公开面，留待有需求时做 |
| S11 | ✅ | `commons.security.TlsStores` 统一格式探测与空密码规则，`SslContexts` 与 cloud 旋转材料共用；cloud 私有副本删除 |
| S12 | ✅ | 12 个主 API 补类型级 javadoc；`DEVELOPER-GUIDE` 的 SQL Builder 一节写明冲突子句语义 |

验证：`mvn -o -Pdemos test` 与 freeway-ext 干净构建均绿（数字见下节附录口径；本轮新增 1 个
db 用例，删除/改写 flow、http 各 1 个用例，净测试数 2 367 + demo 1（db 新增 1 例））。

## 6. 附：测量口径

- 行数：`src/main/**/*.java` 的物理行；测试数取自 1.5.7 全量构建。
- 公开类型：`^public (final|abstract|sealed)?(class|interface|enum|record)` 去重后的类型数
  （含 `internal` 包的 public，因为它们是公开面的一部分）。
- "可简化"的度数以 **S（≤半天）/ M（1–2 天）/ L（需要设计评审）** 估计，均按仓库现有测试规模
  折算。
