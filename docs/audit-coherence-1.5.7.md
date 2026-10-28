# freeway 1.5.7 评估：设计理念与实现的一致性、自洽、自包含

> **基线**：`1.5.7-SNAPSHOT`（`57e26175`），Temurin JDK 25.0.5，`mvn -o test` 全绿，
> 核心 7 模块 **2 322 例**（commons 416 / ioc 316 / boot 115 / http 479 / db 523 /
> flow 118 / cloud 355）。
>
> **判据优先级：代码 > 测试 > 文档。** 每条发现标注证据类型，行号只作定位（代码一动即漂移），
> 判据一律写成**行为陈述**，可在不读注释的情况下复核。
>
> **范围**：七个核心模块的 `src/main` 及其对外的承诺（javadoc、`docs/`、`skills/`、README）。
> `freeway-ext` 与 `demo/` 不在代码审计范围，只在「自包含」一节作为消费者出现。

## 0.1 本批修复（1.5.7 批次）

本文的 P0/P1/P2 里，**代码级缺陷与文档漂移**已在本批修完；结构性建议（错误体归一、h2 排空、
`Sql.on` 参数通道、公共面收口、cloud readiness 顺序等）仍留在 §5。已修条目在正文标 **✅**，
未标的是待办。

| 正文 | 修法 | 形状变化 |
|---|---|---|
| §3.7-1 失败分支的写层 | `FlowContext.beginBranch()` 返回 `Branch` 作用域（`commit()`/`close()` 丢弃），`runBranch` 用 try-with-resources | **API 破坏**：`Runnable` → `Branch` |
| §3.7-2 join 校验只有一扇门 | 分析移入 `Graph` 构造（`JoinDomains`），`load` 只注册 | 不可计数的图建不出来 |
| §3.7-3 PARALLEL 出边的 `when` | `GraphSpec.normalize` 拒绝 | 构建期失败 |
| §3.2-1 类级标注 | `ContainerImpl.register` 对自绑定提取一次 | 两种写法同一 marker 集 |
| §3.2-2 `SymbolSource` 类型化读取 | 删除接口默认方法；替换实现须带 `Coercer` | **API 破坏**：编译错误即迁移 |
| §3.5-1 h2 头名大小写 | `Headers.copyOf` 统一小写；`RequestView.headers()` 声明该契约 | trace 在 h2 上恢复 |
| §3.4-1 `ExchangeMetaDefault` | 改名 `ExchangeMetaImpl`，从 `AGENTS.md` 的 `XDefault` 例子移除 | **API 破坏**：类改名 |
| §2.1 组合两源 | `ARCHITECTURE.md`/`AGENTS.md` 写明两源与 opt-out；`ModuleDiscovery` 打 INFO | 行为不变 |
| §2.2 文档漂移 | `#graph`/`#subgraph`/`AppRuntime.container()`/`bodyAsJson "equivalent"` 等修正 | 行为不变 |
| §3.3-1 OVERFLOW | `OVERFLOW` 触发一次重载 | 热重载恢复 |
| §3.3-3 层级顺序 | 合并列表按 `TIER_*` 排序 | 层级顺序一个来源 |
| §2.7 命名/坐标 | `ConfigLoaderImpl`→`ConfigCascade`；README 注明 groupId 理由 | 命名后缀恢复单义 |
| §5-14 随机图探针 | `FlowDeclarationOrderTest` + 环报错按 id 选根 | 判定/报错与声明顺序无关 |
| §3.6-2/3 db 手册 | snake_case、`setColumn`、`MigrationRunner.Options`、`$1` | 行为不变 |
| §2.5 demo 出列 | 依赖 `${freeway.version}`，根 POM 增加 `-Pdemos` 剖面 | 仓内消费者随 reactor 编译 |
| §2.3 事件出口 | `FlowEngine.create(..., BiConsumer)` sink；`FlowModule` 桥到 `EventBus` 的 `flow.<topic>` | 核心可桥接，容器面负责接 |
| §3.4-2 错误体 | `ErrorResponses` 也走 `sendJson`（404/405/500） | 客户端一种解析 |
| §3.5-3 无体响应的 CL | 规则移入 `ResponseFraming.suppressesHeader`，两个 writer 同问 | h2 不再发 204 的 CL |
| §3.6-1 `Sql.on` | `on(String, Object...)` 走同一条归一化，head 参数单列 | ON 的 `?` 进 `args()` |
| §3.7-4 normalize 缓存 | `normalize()` 对已规范化的 spec 直接返回 | 缓存与 `touch()` 变为承重 |
| §3.8-4 注册面自述 | 路由清单从 `SURFACE` 表派生；proto 接受 `<= PROTO` | 索引不再漂移 |
| §2.4 公共面 | `ExecState` 降为包私有；`SslContexts` 保持 public（freeway-ext 的 Undertow 适配器是其消费者——收窄会编译破坏，已回滚） | 公共面按角色收口 |
| §2.5 CI | 核心 `verify` + `-Pdemos verify` 两条 job | 消费者第一次有人编译 |
| §2.2 ③ 检查 | `ApiDocsConsistencyTest`：README/AGENTS/skills 的 `Type.member(` 必须存在 | 文档漂移从此会红 |
| §3.1-1 标量 getter | 委托内置 `Coercer`（JSON 严格度不再分叉） | 语义放宽 |
| §3.1-2 file MDC 开关 | 两个 formatter 读同一处 | 文件行跟随开关 |
| §3.2-4 EventBus 持容器 | 构造改收能力；`EventBus.create(container)` 仅装配 | **构造签名破坏** |
| §3.5-2 h2 排空 | linger close（64 KiB / 500 ms）+ RST_STREAM | 有界，不再无限占线程 |
| §3.5-4 头上限 | 新键 `freeway.http.server.max-header-bytes`（16 KiB），两协议共用 | 边界一致、可配 |
| §3.6-5 batch 事务 | 抽 `OwnedTransaction`，两处共用 | 语义不变 |
| §3.7-5 反向邻接 | `Graph` 构造时建反向索引；深度改 Kahn 遍历 | load 不再 O(V×E)/爆栈 |
| §3.8-1 readiness | `register(instance, Health)`；钩子以 starting 注册 | 注册即未就绪 |
| §3.8-3 自愈 | 客户端只回答丢失，钩子重注册（带 readiness） | 一种观测 |
| §3.5-2 h2 排空（测试） | 原始帧回归：上传中 413 → 有界等待后 `RST_STREAM(NO_ERROR)` | 行为被端到端钉住 |
| §3.6-4 子句字段化 | `Clauses` 记录替换 `tail` 字符串；渲染顺序固定 | 调用顺序不再决定 SQL |
| §3.6-4 ON CONFLICT | 动作必须跟在目标后；第二个冲突子句拒绝 | 构造期失败，不再发无效 SQL |
| §3.4-5 访问日志 | 观察者化（`AccessLog` + 事件补 peer/UA），删 `AccessLogFilter` | 短路请求也有一行 |
| §3.3-2 日志窄级联 | CLI/工作目录日志键启动期点名；类加载期键写文件也点名 | 设了无效不再静默 |

---

## 0. 三个词在这份评估里的含义

| 词 | 它问的问题 | 本仓的反例（后文展开） |
|---|---|---|
| **一致性** | 文档/javadoc 声明的行为，实现是否兑现 | `Sql` 的报错让人调用一个已被删除的方法 |
| **自洽** | 同一个问题在全仓是否只有一个答案 | 组合的来源：`ARCHITECTURE` 说一个，`freeway-module` 说两个 |
| **自包含** | 一个模块能否在没有未声明的输入时工作（无容器、无外部服务、无隐藏 classpath 约定） | `Database.create` 做到了；`FreewayApp` 的组合却把 classpath 当输入 |

**评分轴**（1–5 的整数；分数是坐标，不是判决）：

1. **定位兑现** — 声明的能力真的在，且是真的那样工作。
2. **机制唯一** — 同一个概念只有一份实现、一份状态。
3. **概念经济** — 没有"没有问题的答案"的类型；公开面 ≈ 必须公开的面。
4. **边界洁净** — 不知道不属于自己的概念；`Container` 只在容器尚未建成的地方出现。
5. **规则遵从** — `AGENTS.md` 的具名规则（命名、工厂动词、`require*`/`ensure*`、`ConfigKeys`）。
6. **文档诚信** — 具体断言与代码一致。

**证据分级**（沿用 1.5.6 审计的口径，本文不重复其结论）：

| 标记 | 含义 | 强度 |
|---|---|---|
| **实测** | 写了探针在当前快照上跑出输出 | 最强 |
| **代码事实** | 读调用图/控制流即可判定，不读注释 | 强 |
| **文档漂移** | 实现语义正确，是文本与之矛盾 | 中（改文档即消失） |
| **判断** | 无单一右答，给出理由与取舍 | — |

**方法**：七个模块各做一轮只读深审（engine 与 cloud 按平面拆成两轮），再由主代理对每条
"S1/代码事实"做执行级复核——读代码路径、必要时写探针实跑；正文只保留复核过的条目，
子审计里未经复核的线索一律丢弃。每个模块小节的结构相同：定位 → 做到了什么 → 发现 → 评分。

---

## 1. 结论速览

| 模块 | 定位 | 机制 | 概念 | 边界 | 规则 | 文档 | 一句话 |
|---|---|---|---|---|---|---|---|
| commons | 4 | 2 | 3 | 3 | 4 | 2 | 能力齐、零依赖；标量转换与 MDC 开关各有两个答案，三处文档停在旧行为 |
| ioc | 3 | 3 | 4 | 4 | 4 | 3 | 公开面与自包含是全仓最好的之一；类标注与类型化读取在扩展点上是两态 |
| boot | 4 | 3 | 4 | 4 | 4 | 3 | 级联与生命周期可信；日志键与 profile 顺序各有第二份级联 |
| http·服务器 | 4 | 3 | 4 | 4 | 3 | 3 | 四个锚点是真的；错误体与请求观测各有两份（`ExchangeMetaImpl` 已按 XImpl 收口 ✅） |
| http·引擎 | 4 | 3 | 4 | 4 | 4 | 3 | gzip/限额/framing 确为一份；偏差全在协议边界——h2 丢头名大小写，cloud 的 trace 在 h2 上静默失效 |
| db | 4 | 3 | 4 | 5 | 4 | 2 | 入口/池/方言/事务自洽；SQL 构造器缺一个参数通道，手册落后两次改名 |
| flow | 3 | 3 | 3 | 5 | 5 | 3 | 骨架与代码同构；两道门禁只在一条路径上兑现 |
| cloud·服务 | 3 | 3 | 4 | 4 | 4 | 2 | 缓存/单 store/seam 都成立；就绪门顺序与协议自述落后于文档 |
| cloud·事件 | 4 | 4 | 4 | 4 | 4 | 4 | 全仓最自洽的一层；只有两处代价与一处 doc 缺失 |

> 分数只用于互相对照，不用于跨框架比较。"文档"一列低，主要是**可校验的文档少**，
> 而不是文档写得差——这套文档的密度与精确度在同类项目里是上乘的；只是越精确的文本，
> 漂移一次的代价越高。

**六条总判断**（按重要性排序，后文均有展开）：

1. **这个框架最稀缺的东西它已经做出来了：形状。** `Container` 只有 7 个方法，`HttpPipeline`
   是一个 record，`Database.Wiring.defaults()` + `withX`，`ConfigKeys` 表 + `KnownKeys`
   在 bind 期拒绝表外键，`MeshFrame` 是密封接口，`PeerConnection` 把背压策略长在连接上——
   这些不是文档承诺，是编译器与启动期强制的事实。同类框架里少见。
2. **但"形状化"只覆盖了一半规则。** 命名（`XDefault`/`XImpl`）、`Container` 持有、文档权威
   三件事仍靠散文维系；而 `AGENTS.md` 自己给这条迁移路径写了方法论（"把事实挪到不能出错的地方"）。
   下一轮的最高性价比工作，是把这三条规则也变成形状或检查。
3. **剩下的一半里，最贵的三处恰好都在"扩展点/接缝"上**：`bind(Concrete)` 丢掉类级标注
   （最该被拒绝的单例被放过，§3.2）、`SymbolSource` 的类型化读取在替换实现上必崩（§3.2）、
   h2 的头名大小写让 cloud 的 trace 静默失效（§3.5）。共同点：**单一实现里的两态**——
   同一个概念，两条代码路径给出不同答案，而两条都有测试（分别的）。
4. **组合有两个来源，而最先读的两份文档只写了一个**（§2.1）。实现是自洽的、有 opt-out、
   有去重、启动树会打印；不自洽的是权威文本。这是本轮最重的"理念—实现"缺口。
5. **"模块如何把事件交给应用"有两个答案**（§2.3）：http 用注入的 `Consumer<Object>`，
   flow 用一个自带的总线。前者可桥接、与容器无关；后者不可桥接，且它的理由（避免"全局消息面"）
   在 `EventBus` 变成 per-container 之后已不成立。
6. **仓库不验证自己的消费者**（§2.5）：没有 CI，`demo/` 不在 reactor、依赖还停在 1.5.6。
   而 `AGENTS.md` 把"编译错误即迁移路径"当作破坏的正当性来源——那条路径目前没人走。

---

## 2. 跨模块发现

### 2.1 X1 组合有两个来源，文档有四个答案（**✅ 文档已修/行为保留**）

**证据类型：实测 + 代码事实 + 文档漂移**

**代码事实**：`FreewayApp.start()` 在 `autoDiscovery`（默认 `true`）时调用
`ModuleDiscovery.fill`，后者把 `ServiceLoader` 里的每个 `ModuleEx` 追加到调用方声明的列表后
（声明的类优先，按类去重）。`freeway-http` 与 `freeway-db` 各自在
`META-INF/services/com.jujin.freeway.ioc.ModuleEx` 里登记了自己的模块：

| 来源 | 谁写 | 默认 |
|---|---|---|
| 调用方声明的模块列表 | 应用作者 | 总是生效 |
| classpath 上的 `META-INF/services` | 库作者（含 core 自己） | **默认生效**，`.autoDiscovery(false)` 关闭 |

**实测**（探针在 `/tmp`，classpath 只放 commons/ioc/boot/db，不放置任何模块）：

```
FreewayApp.create().shutdownHook(false).start()
  autoDiscovery=true  → FAILED: IllegalArgumentException: Missing required config key 'freeway.db.url'
  autoDiscovery=false → STARTED  state=RUNNING
```

只要 `freeway-db` 在 classpath 上，一个从未使用数据库的应用会**启动失败**。换成
`freeway-http` 则是另一种后果——**没人声明，服务器起来了**：

```
classpath: commons/ioc/boot/http，未放置任何模块
  → Freeway HTTP engine (http) started on 127.0.0.1:…
  → Freeway web server started on 127.0.0.1:…
  → HttpServer bound=true, state=RUNNING
```

这就是"组合"的实际含义：**调用方的列表 + classpath 上的服务声明**，后者默认生效。

**文档漂移**：同一个问题，四个文件四个说法。

| 文件 | 说法 |
|---|---|
| `AGENTS.md`（Keep concepts few） | "Composition is the ordered modules handed to an entry point" |
| `docs/ARCHITECTURE.md`（ModuleEx） | "Composition is the ordered modules handed to the entry point" |
| `docs/DEVELOPER-GUIDE.md` / `docs/freeway-module.md` | SPI auto-discovery **by default**, opt-out |
| `docs/freeway-reflection.md` | "auto-discovery is opt-out **by design**" |

前两份是 `AGENTS.md` 明确要求"先读"的文档；后两份是参考手册与反射清单。也就是说：
**权威层说一个来源，事实层说两个**。

**判断**：实现本身没错——它是 Spring Boot 式的 classpath 自动配置，有 opt-out、有去重、
启动树会打印全部模块。错的是**权威关系**：读者按 `ARCHITECTURE.md` 设计部署，会以为
classpath 无关；`Freeway.create(...)`（ioc，不做发现）上的测试通过，也不代表
`FreewayApp.run(...)` 的组合相同。

**改进方向**（document，S）：在 `ARCHITECTURE.md` 的 ModuleEx 一段把两源、优先级、默认值、
关闭方式写成事实，并让启动树把"发现来的"标出来（现在它与作者声明的模块同形）。若要更彻底，
把 core 模块的 SPI 文件留给 **adapter**（ext 的引擎/池/发现实现），core 模块要求显式放置——
这正是 `Freeway.create` 已有的语义，也是"explicit wiring"的本意。这是一个产品决定，不宜顺手改。

### 2.2 X2 文档是四套表面，只有一套被机器校验（**✅ 四处漂移已修，检查待办**）

**证据类型：代码事实 + 文档漂移**

**体量**：`docs/` 9 707 行 + `skills/freeway-dev/` 2 004 行 + README 388 + AGENTS 388
≈ **12.5k 行文档对 60k 行 main 代码**。四套表面各自回答同一批问题（模块图、节点类型、
配置键、API 列表）。

**已经做对的那一套**：`ConfigDocsConsistencyTest`（boot 测试）把 `docs/freeway-config.md`
变成可执行契约，方向是**双向**的——样例文件里的键必须在文档里有行，文档承诺的键必须被某个
模块读到（键从源码字面量扫描而来，不是第二份手抄清单）。我独立复算了一遍：
**132 个声明键 / 126 个文档键，差集只有 `*` 前缀与 hook id**。这是全仓最值得复制的做法。

**没有被校验的三套，已经漂移**：

| 位置 | 文本 | 事实 |
|---|---|---|
| `docs/ARCHITECTURE.md:118` | 任务词表是 `@name` 或 **`#graph`** | 代码只认 `@name` / **`#graphId`**（`GraphSpec` 的报错文本就写着），且同文件 `:111` 写的是 `#graphId`——**七行之内自相矛盾** |
| `skills/freeway-dev/SKILL.md:39` | `@name`/`#subgraph` 任务词表 | 同上；`#subgraph` 是个不存在的 token |
| `skills/freeway-dev/references/boot.md:21` | 列出 `AppRuntime.container()` | 该方法在 `42292171`（"tighten IoC boundaries"）已删除；skill 是 1.5.6 之后才加的 |
| `docs/DEVELOPER-GUIDE.md:1075` | `bodyAsJson` 与 `BodyHandler` "equivalent" | `BodyHandler` 路径会跑 `BeanValidator.validate` 并抛 `ValidationException`，`bodyAsJson` 不跑（§3.4） |

**判断**：仓库已经证明自己有能力把文档变成契约，只是没有把这个方法用到别的断言上；权威关系
靠四处"以代码为准"的免责声明维系（skill 的 Working Rules、RELEASE-NOTES 指向 CHANGELOG）。
免责声明是诚实，但它不是机制。

**改进方向**（document + verify，S/M）：① 修掉上表四处；② 在 `AGENTS.md` 的 Further Reading
前加一张**权威表**（问题 → 唯一权威文件），把"哪个说了算"写成一条可查的规则；
③ 把 `ConfigDocsConsistencyTest` 的思路扩到"API 列表页"——backtick 里的 `Type.member`
必须存在。这个检查零依赖、纯仓内，能直接抓到 `AppRuntime.container()` 那一类。

### 2.3 X3 事件出口：一个模块一个答案

**证据类型：代码事实**

| 模块 | 出口 | 能否桥接到应用的事件总线 |
|---|---|---|
| http | `HttpServer.create(..., Consumer<Object> eventSink)`；`HttpModule` 把 sink 接到 `EventBus` | 能——sink 是构造参数，核心不认识容器 |
| flow | 自带 `FlowEventBus`（字符串 topic、每执行域一份） | **不能**——没有 sink，也没有到 `EventBus` 的桥 |
| cloud | 自带 `CloudEventBus`（WS mesh） | 设计上不桥——两个平面是两次决定 |

**判断**：三个"平面"的划分本身是清楚的，而且被写进了 javadoc（ioc 的总线说"the in-process
plane, and knows no other"；cloud 的总线说"publish leaves the JVM and nothing else"）。
真正的不一致只有一处：**同一件事（模块把事件交出去）在 http 与 flow 用了两种机制**。http 的
sink 形状让"核心不依赖容器"和"应用能统一订阅"同时成立；flow 的总线只能自己用。
`FlowEventBus` 的 javadoc 给的理由是"avoids introducing a global message surface"——这个理由在
`EventBus` 变成 per-container 之后不成立了。它真正的理由应该是"引擎必须能在无容器下工作"
（`FlowEngine.create()`），而那正是 http 用 sink 的理由。

**改进方向**（wire，S/M）：给 `FlowEngine` 一个可选的 `Consumer<Object>`（或 `FlowEventSink`）
出口，由 `FlowModule` 桥到 `EventBus`；`FlowEventBus` 退化为该 sink 的默认实现。三个模块的
"事件出口"于是是同一个形状——这与 `HttpServer` 已有的做法一致，不是新概念。

### 2.4 X4 公共面比"必须公共"的面大（可度量）

**证据类型：代码事实**

`AGENTS.md` 的规则是：「a type is `public` only when another package must assemble or substitute
it」。这条规则可以机械地量：**public 类型中，没有任何跨包引用者的比例**。

| 模块 | public 类型 | 无跨包引用者 |
|---|---|---|
| commons | 38 | 28 |
| ioc | 28 | 6 |
| boot | 12 | 2 |
| http | 77 | 4 |
| db | 35 | 1 |
| flow | 29 | 24 |
| cloud | 88 | 25 |

**这个数字本身不是结论**——按 1.5.6 审计 §1.3，仓内零调用方不构成删除理由（应用与 ext 在仓外）。
它只用来筛样本。真正可判定的是**角色上不可能是 API** 的那几个：

- `flow/ExecState`：一次执行的记账状态，唯一读者是同包 `FlowEvaluation` 的**包私有字段**；
  包私有即可。
- `http/SslContexts`：本仓使用者是同包 `HttpModule`，但**仓外**的 Undertow 适配器（freeway-ext）也用它构建 `SSLContext`——2026-10 的收窄把 ext 的干净构建打红，已回滚为 public。教训：**仓内没有调用者不是证据**。
- `commons/logging/JULFileHandler`、`JULFileFormatter`、`JULConsoleFormatter`：唯一使用者是
  同包的 `JULEnhancer`。

**判断**：规则清楚、执行靠人眼。这三五个类型挪进 `internal`（或降为包私有）之后，"公共面"
变成了一眼可核对的形状——这正是 `ioc.internal` 只留一个 public 类型的原因，那个模块做到了。

**改进方向**（relocate，S）：按角色而不是按调用方判断，挪 3–5 个类型；不必全仓扫。

### 2.5 X5 仓库不验证自己的消费者：无 CI、demo 出列、版本落后

**证据类型：代码事实**

- `.github/` 下只有 3 张图片，**没有 workflow**；没有任何 CI 配置。
- 根 reactor 只有 7 个模块；`demo/` 三个工程（`rest-db`、`registry-server`、`cloud-event-mesh`）
  **不在 reactor**，`mvn test` 不编译它们。
- 三个 demo 的 `pom.xml` 依赖 `freeway-*:1.5.6`，而父工程是 `1.5.7-SNAPSHOT`——**落后一版**。
- `AGENTS.md` 写着「only a clean build proves anything」「the compile error *is* the migration
  path」；`demo/rest-db` 的 `RestDbDemo` javadoc 甚至把 classpath 当组合输入写进了注释
  （"Nothing else is on the classpath, so nothing else can take part"）。

**判断**：这个项目把破坏的正当性建立在"消费者会收到编译错误"上，但没有任何自动化在消费者
侧制造那些错误；仓内唯一的端到端消费者已经落后一个版本，而它恰好也是 `AGENTS.md` §1.3
说的"仓内 grep 证明不了什么"的反面证据——有它会好得多。

**改进方向**（wire，S/M）：demo 用 `${project.version}` 并纳入 `-Pdemos` 剖面（或并入
reactor 的 `test` 阶段）；一条 CI 跑 `mvn -B verify` + demo 编译。这是本轮性价比最高的
流程改动。

### 2.6 X6 测试密度与不变量密度反相关

**证据类型：代码事实**

| 模块 | main 行 | 测试行 | 测试数 | 每千行测试数 |
|---|---|---|---|---|
| db | 9 007 | 10 984 | 523 | 58 |
| boot | 2 436 | 2 919 | 115 | 47 |
| commons | 9 342 | 7 260 | 416 | 45 |
| ioc | 7 033 | 8 092 | 316 | 45 |
| http | 15 268 | 13 123 | 479 | 31 |
| cloud | 11 791 | 10 150 | 355 | 30 |
| **flow** | **4 878** | **3 156** | **118** | **24** |

http/cloud 的数字偏低可以解释（I/O 密集、测试按场景而非按分支写），flow 不能：它的执行器
（frontier 遍历、join 记账、迭代域、分支写层）是全仓最"状态机"的部分。而 1.5.6 一轮审计就在
flow 里查出 **4 条**缺陷——join 记账竞态、跨迭代域的 join 配置无效、`load()` 在十万节点上
271 秒、PlantUML display 函数吞异常——其中一条只有约 1/3 的概率失败。

**判断**：不是"该加测试"的口号。flow 的不变量（每个 join 恰好到达 `prevLinks().size()` 次、
每个分支写层恰好弹出一次）没有被任何类型表达，所以只能靠测试兜，而测试最少。这正是
§2.1 那条方法论的另一面：**形状兜不住的地方，测试要跟上；两边都缺，缺陷就会在用户那里出现。**

**改进方向**（verify，M）：一条带固定种子的随机 DAG 探针——生成→`load`→运行→断言
"被接受的图要么跑完，要么给出点名节点/边的错误；且每个 join 的到达数等于期望数"。它把
4 条历史缺陷变成同一性质，比逐条补回归更划算。

### 2.7 X7 命名与坐标的毛刺

**证据类型：代码事实**

- `boot.internal.ConfigLoaderImpl` 是一个**纯静态工具**（无实例、无接口、无绑定）。`Impl`
  后缀在这个仓里只被许可给两种东西：不可替换的容器内部装配，以及"与同角色并存"的每属主实现。
  按操作性判据（"能不能被 `.primary()` 替换"）答案为否——**两者都不是**：它不装配任何东西，
  就是一个函数集合。这不是硬性违规（规则没写静态工具该叫什么），但它让"Impl"这个词在
  全仓失去单一含义；叫 `ConfigCascade` 更诚实。**✅ 已改名 `ConfigCascade`**。
- **坐标与包名不一致**：`groupId` 是 `com.jujin8.freeway`，Java 包是 `com.jujin.freeway`。**✅ README 依赖片段旁已注明理由**。
  单独看都正常，放在一起是"用户第一行要敲的字符串"层面的不一致。改任何一个都是破坏，
  所以更现实的处理是**写清理由**（或至少在 README 的依赖片段旁说明），而不是留给读者猜。
- `db.DbModule.ConfigKeys.SCHEMA_AUTO` 上的 `@Deprecated` 是**合规**的（退役键留在表里让
  未知键检查闭嘴，启动期点名新键），不要顺手删——见 §4。

---

## 3. 分模块评估

### 3.1 freeway-commons（9 342 行 / 416 例）

**定位**：零依赖基座——JSON、标量转换、`Defer`/`ScopedCache`、校验、日志后端、`bean` 反射、
指标。**做到了什么**：流所有权写进 API（`JsonUtils.parse(InputStream)` 明说会关闭流）；
输入上限具名且带位置报错（深度/字符/字节/数字长度/容器大小）；反射全部走 `ClassValue`
（负结果也缓存，随类卸载回收）；毒输入一律响亮失败（环检测、identity 判重）。

**发现**：

1. **同一个 JSON 值，逐字段读与按类型转换是两个答案**（重复机制，实测）。**✅ 已修**——getter 走同一 Coercer。
   `JsonObject.getBoolean` 经 `JsonAccessors.booleanValue` 只认 `true`/`false`；
   `JsonCodec` 的类型转换经 `CoercerDefault.coerceToBoolean` 认 `yes/on/1`。
   `{"b":1}` 上前者抛 `IllegalArgumentException`，后者得 `true`；同理 `"1.5"` 的 `getInt`
   抛、`coerce` 得 `1`。影响：应用自定义的 `CoerceRule` 对节点 getter 不生效，接入方要记
   两套严格度；这不是"严格 vs 宽松"的分工，因为没有任何一处声明它。
   改进：让节点 getter 的标量规则委托同一个 `Coercer`，严格度作为该机制的参数（M）。
2. **`freeway.log.mdc=false` 只关控制台**（重复机制，代码事实）。**✅ 已修**。
   `JULConsoleFormatter` 从 `LogKeys.MDC` 读开关，`JULFileFormatter` 的 `FormatConfig`
   把 `showMDC` 写死 `true`，而 `LogKeys` 的 javadoc 说该键决定 "MDC 块是否渲染"。
   设了键的人落盘文件里仍有 traceId，且无任何提示。改进：两个 formatter 共用一处配置读取（S）。
3. **`Defer` 的"已知语义边界"已经不存在，文档还停在旧语义**（文档漂移）。
   `DeferScope.drain` 对每个动作 `catch (Throwable)` 只 WARN，作用域正常返回；
   `docs/freeway-defer-summary.md` 却写"事件处理器抛异常 → `Defer.within` discard + rethrow →
   `transaction()` 再 rollback……调用方看到失败"。按那份文档设计错误处理的人，会以为事件
   失败可见。影响面：DB 事务 + 事件派发这条最常被引用的组合（S，改文档）。
4. **`BeanPlan.of` 的 javadoc 说它被缓存，实现每次新建**（文档漂移）。
   `BeanIntrospector.plan` 才是 `ClassValue` 缓存；`BeanPlan.of` 每次 `forRecord`/`forBean`。
   javadoc 同时写着 "Results are cached" 与 "a new bean plan"，读的人只会记住前一句，
   于是在热路径按 javadoc 用 `of`。另：`BeanIntrospector` 的类注释引用了一个不存在的
   `BeanPlan.plan`（真实方法名是 `of`）。改进：`of` 委托 `plan`（S）。
5. **`docs/freeway-commons.md` 的 Stable API 列了两个不在本模块的类型**（边界泄漏）。
   `SymbolSpec` 在 `ioc.symbol`，`AppConfig` 在 `freeway-boot`；只依赖 commons 的读者
   按文档找不到它们。这与 `AGENTS.md` 的"配置形状规则归 ioc"是同一条边界（S，改文档）。

**评分**：定位 4｜机制 2｜概念 3｜边界 3｜规则 4｜文档 2。基座的能力是齐的，失分全在
"同一件事两份说法"：转换、MDC 开关、`BeanPlan` 入口，三处文档停在旧行为。

### 3.2 freeway-ioc（7 033 行 / 316 例）

**定位**：容器、绑定 DSL、作用域、注入、扩展点、符号配置、进程内事件总线。
**做到了什么**：`Container` 只有 7 个方法（`get`×3、`isActiveBinding`、`extension`、`create`、
`close`），`internal` 只有一个 public 类型（`ContainerImpl`）——这两条都是"公共面 = 必须公共
的面"的正面样本；primary 只有一个记录处（`Primary` marker），类型查询与 marker 查询共用
同一个选择函数；关闭竞态收在一处（铸造代理与 closed 复检在同一把锁内，seal 三段式）；
扩展点的排序只有一份算法，boot 用严格档（未知引用即失败）、其余用宽松档（WARN），
不是两份实现。

**发现**：

1. **类级标注只在 `.to(Impl)` 写法上被读取**（规则违背）。**✅ 已修**——注册期统一提取。
   `BindingImpl.to(Class)` 会 `MarkerIndex.extractClassMarkers(actual)`；而 `bind(Concrete)`
   不带 `.to(...)` 时，构造函数与 `BinderImpl.bind` 都不提取——只有模块级 `@Marker` 会被加上。
   于是同一个类：

   ```java
   binder.bind(Cache.class).to(FastCache.class);   // @NotThreadSafe / @Primary 被看到
   binder.bind(FastCache.class);                   // 被静默丢弃
   ```

   影响不止于 marker：`InjectionResolver` 对"共享的单例非线程安全"的拒绝只读 binding 的
   markers，所以第二种写法会**放过最该被拦下的对象**；按类绑定的 `@Primary` 也会在
   `get(接口)` 时报"多个绑定且无 primary"。而 `NotThreadSafe`/`Marker` 的 javadoc 都让标注
   落在实现类上。改进：在 `bind`/注册处对具体类型统一提取一次（S）。
2. **`SymbolSource` 的替换缝只兑现了一半：类型化读取在替换实现上必然失败**（重复机制）。**✅ 已修**——默认方法删除，接缝要求带 `Coercer`。
   接口的 `resolve(SymbolSpec)` 默认实现是 `spec.parse(resolve(key, null))`——**没有 Coercer**；
   `SymbolSourceDefault` 覆写成 `parse(raw, coercer)`。而 `SymbolSpec.parse(raw)` 在
   "无 per-key parser"时直接抛 `IllegalStateException`。这意味着：按 `AGENTS.md` 与
   `SymbolSourceReplacementTest` 认可的写法手写一个 `SymbolSource`（只实现 `resolve(name)` /
   `expand`，从 `c.extension(SymbolProvider.class)` 取贡献），任何
   `SymbolSpec.of(key, Duration.class, default)`（http/db/cloud 里大量存在，注释还专门写着
   "no per-key parser — the chain's Coercer resolves them"）在**第一次读取时**就会抛
   "has no parser and no Coercer"。`SymbolSourceReplacementTest` 只测了 `resolve("probe", null)`，
   所以这条缝在测试里是绿的。这正好违反 `AGENTS.md` 对替换 seam 的要求
   （"Substituting a role means honoring its whole seam"）。改进：把 Coercer 纳入接口
   （只留一个类型化读取实现），或删掉接口默认方法让编译器逼替换者实现（M）。
3. **`primary()` 的 `@throws` 承诺不在 `primary()` 发生**（文档漂移）。**✅ 已修**（改文档）。
   `Binding.primary()` 的 javadoc 写 "`@throws IllegalArgumentException` if multiple primary
   bindings exist"，实现只加一个 marker；重复 primary 的失败被推迟到首次 `get()`。未被解析的
   重复 primary 永远不会失败——组合错误留到生产流量。改进：在注册期检查同类型 primary 数，
   或把 `@throws` 移到解析入口（S）。
4. **`EventBus` 与它的订阅索引在组合完成后仍持有 `Container`**（边界泄漏，判断）。**✅ 已修**——构造收能力。
   `EventBus` 的公开构造函数收 `Container`，用来取 `Metrics`、探测 `AsyncCarrier`、
   查 `EventSubscriber` 贡献。它只需要三样能力，却拿到了整个容器；替换/装配 bus 必须先有
   容器，测试也已按 `new EventBus(container)` 固化这个形状。按 `AGENTS.md` 的"Pass a
   capability, not the container"，这里应传能力。改进：构造函数改收 `Metrics` + 订阅视图 +
   `AsyncCarrier`（M）。

**评分**：定位 3｜机制 3｜概念 4｜边界 4｜规则 4｜文档 3。公开面与自包含是全仓最好的之一；
失分在"同一件事在运行时分成两态"（类标注、类型化读取），而这两处恰好都是**扩展点**——
扩展者最容易踩到的地方。

### 3.3 freeway-boot（2 436 行 / 115 例）

**定位**：启动器、运行时生命周期、profile、配置级联（含热重载）、JUL 引导。
**做到了什么**：级联的层级表与 `ARCHITECTURE.md` 逐行对得上（0 CLI / 5 `-D` / 10 env /
15 模块贡献 / 20 文件），且 profile 变体里的 `freeway.profile` 会被单点剥离，`profiles()` 与
符号链对同一个键不会给出两个答案；热重载的形状是对的——volatile 的文件层 + 每次查找读快照 +
失败保留旧快照；文件解析只有一个入口（`ConfigFileReader`，16 MiB 上限，boot 只产出字符串，
类型化读取交给链的 `Coercer`）；`AppConfig` 明确拒绝做"读 API"，理由写在 javadoc 里
（"第二张 map 形状的视图会与链分叉，或泄漏 env/CLI 注入的密钥"）——这是本仓最克制的一条边界。

**发现**：

1. **`OVERFLOW` 事件会吞掉一次热重载**（规则违背）。**✅ 已修**。
   `ConfigFileWatcher.touched` 只看 `event.context() instanceof Path`；`OVERFLOW` 事件的
   context 是 `null`，因此永远不算命中：不重载，也不打日志。`ARCHITECTURE.md` 承诺
   "an edit is visible without a restart"。目录事件繁忙（批量写入、IDE 保存风暴）时正是
   OVERFLOW 出现的时刻，也是热重载最该工作的时刻。改进：`OVERFLOW` 触发一次重载（S）。
2. **日志键走第二条更窄的级联**（重复机制 + 文档漂移）。**✅ 已修**——CLI 与工作目录
   覆盖文件里的 `freeway.log.*` 在启动时逐键 WARN；类加载期键写进文件也点名。
   应用键只有一条链（`SymbolSource`）；日志键有两条：`AppLogSource` 只把**类路径**文件里的
   `freeway.log.*` 交给 `JULEnhancer`，`JULEnhancer` 自己读 `-D`/env/两个文件。于是
   `--freeway.log.level=DEBUG` 或工作目录 override 文件里的日志键会进入符号链，**却不作用于
   logger**，且没有提示。更糟的是 `docs/freeway-config.md` 在同一节里自相矛盾：一处写应用主
   配置包含"工作目录"，下一处写"工作目录文件声明与热重载不参与日志侧"（后者才是实现）。
   改进：对"进了链却没被日志侧采纳"的键在启动期 WARN 并点名可用通道，同时改掉文档的矛盾（S）。
3. **profile 的优先级被手写第二遍**（重复机制，判断）。**✅ 已修**——按 `TIER_*` 排序。
   `ConfigLoaderImpl` 用 `List.of(properties, json, filesystemBase, environment, cli)` 这个
   五层字面列表决定 `freeway.profile` 的胜者，其余读取都走 `SymbolProvider.order()`。
   这在结构上不可避免（profile 必须在链建成之前选出），但那个列表连 `TIER_*` 常量都没引用，
   于是"哪层压哪层"这件事有两个定义。改进：列表引用 `SymbolProvider.TIER_*`，让层级顺序只有
   一个来源（S）。
4. **`AppState.STOPPING` 的 javadoc 声称了一件 boot 无法验证的事**（文档漂移）。**✅ 已修**（改文档）。
   "the registry already answers 503"——boot 里没有 registry；而且状态是先置 `STOPPING`、
   再跑 hook 的，云侧的 draining 只会更晚。只装 boot 的应用没有 503，按这句写 readiness 的人
   会误判"流量已摘"。改进：删掉 registry 断言，只留状态机自身的语义（S）。

**评分**：定位 4｜机制 3｜概念 4｜边界 4｜规则 4｜文档 3。级联与生命周期骨架可信；失分在两处
"第二份级联"（日志、profile 顺序）和一条 OVERFLOW 路径。

### 3.4 freeway-http · 服务器层（约 11k 行 / 479 例）

**定位**：路由、过滤器、WS/SSE、静态资源、错误映射；`HttpServer` 从四个锚点
（`HttpEngine` / `HttpServerConfig` / `HttpPipeline` / 自身）唯一推导。**做到了什么**：
`HttpPipeline` 是 record，"装配声明"与"运行行为"的分离是可编译的；`HttpServer.create` 在
`main` 中只有 `HttpModule` 一个调用方；`route/`、`websocket/` 只在 javadoc 里出现
`Container`；WS 与 HTTP 复用同一个 `RouteIndex`（匹配、特异性、重复注册只有一份）；
`baseMaxBodySize` 是 final 构造输入，每请求副本在 `reset()` 里恢复——`AGENTS.md` 拿它当
"把事实放对位置"的范例，它确实经得起检查。

**发现**：

1. **`ExchangeMetaDefault` 没有名字承诺的替换缝**（规则违背）。**✅ 已修**——改名 `ExchangeMetaImpl`，移出 `XDefault` 例子。
   它是 `final`，由 `AbstractHttpContext` 与 `AbstractWebSocketSession` 直接 `new`；
   全模块没有 `ExchangeMeta` 的绑定，也没有任何解析点。也就是说：外部把它 `@Primary` 绑进
   容器，容器会接受，但**没有任何代码会去取**。而 `AGENTS.md` 的 `XDefault` 例子清单里
   恰好写着 `ExchangeMetaDefault`。这是规则文件用自己判据的反例当正例。
   改进：要么给 `ExchangeMeta` 一条真正的解析路径，要么按 `XImpl` 改名并改掉 `AGENTS.md` 的
   例子（S）。后者更省钱：它是一个 per-exchange 的状态载体，与 `HttpContextImpl` 同类。
2. **同一个服务器的错误体有两种格式**（重复机制）。**✅ 已修**——统一 JSON。
   `ErrorResponses` 写 `text/plain` 的 404/405/500，`ErrorHandler.defaults()` 写 JSON 的
   400/413/415，`HttpServer` 两条都走。客户端收到 404 是纯文本、413 是 JSON，无法用同一段
   解析。1.5.6 的 R10 已经为 `/health/live` 修过同一类问题（"同一模块的三个探针端点，两个说
   JSON、一个说纯文本"），404 这条路径是同一个缺陷的遗留。改进：两类错误共用一个错误体，
   归属只留一处（M）。
3. **`setPathVars` 名为 set，实为 merge**（规则违背，`A name states one act`）。**✅ 已修**——clear + putAll。
   `AbstractHttpContext.setPathVars` 只 `putAll`；清空只发生在引擎的 `reset()` 里。复用
   context 的适配器/过滤器漏了清空，上一个请求的 tenant/id 会残留到下一个——鉴权读到过期
   路径变量的典型形状。改进：方法自己先 `clear()` 再 `putAll`，删掉引擎侧的重复清空（S）。
4. **`bodyAsJson` 与 `BodyHandler` 不是等价物，指南说等价**（文档漂移）。**✅ 已修**（改文档）。
   `Route.wrapBody` 会跑 `BeanValidator.validate` 并在失败时抛 `ValidationException`；
   `ctx.bodyAsJson(...)` 只反序列化。`DEVELOPER-GUIDE` 建议从 lambda 改成 handler class 时
   说 "equivalent to the `BodyHandler<T>` convenience"——照做的人会静默丢掉 `@NotNull`
   校验。改进：把校验写进两条路径的契约，删掉 "equivalent"（S）。
5. **访问日志在过滤器链内，短路请求不写行**（重复机制）。**✅ 已修**——`AccessLog` 改为
   `HttpExchangeEvent` 的观察者，`AccessLogFilter` 删除；事件补 `remoteAddress`/`userAgent`。
   CORS 预检与健康探针现在也各写一行（`AccessLogObserverTest`、`AccessLogWiringTest` 钉住）。

**评分**：定位 4｜机制 3｜概念 4｜边界 4｜规则 3｜文档 3。四锚点与唯一装配者成立；
失分集中在"每请求状态的归属"和代码旁的几句假陈述。

### 3.5 freeway-http · 引擎层（约 4k 行）

**定位**：零依赖手写 HTTP/1.x + HTTP/2（h2c/ALPN）+ WebSocket + TLS，虚拟线程同步 I/O。
**做到了什么**：三条最容易写成两份的东西确实是**一份**——gzip 协商只在 `Compression`、
body 限额只在 `LimitedInputStream`、响应 framing 规则只在 `ResponseFraming`；`maxBodySize`
靠形状（final 构造输入 + `reset()`）而不是靠记得复位；引擎不读系统属性、不碰 ioc/boot/cloud，
TLS 材料以 `Supplier<SSLContext>` 推入，`SslReloader` 不读配置。这是"自包含"做得最好的模块。

**发现**（全部是"h1 与 h2 各写一遍，然后漂了"）：

1. **h2 的入站头名保留首字母大写，h1 全部小写——cloud 的 trace 在 HTTP/2 上静默丢失**
   （重复机制，跨模块）。**✅ 已修**——`Headers.copyOf` 归一。
   `Http1xParser` 解析时 `toLowerCase`；`Http2Connection` 用 `Http2HeaderField.normalizedName`
   （只把首字母大写）作 map 键。于是同一个 `traceparent` 在 h1 是 `traceparent`、在 h2 是
   `Traceparent`。cloud 的 `PropagationFilter` 把 `ctx.headers()` 摊成**大小写敏感**的
   `HashMap`，`TracePropagator` 再 `get("traceparent")`——h2 上取不到，W3C 上下文被丢弃，
   而现有用例只走 h1。修法是一处：在 context 边界统一小写键（h2 侧或 `Headers.asMap()`
   一并归一），S。
2. **h2 未读体的排空是第二份实现：绕开限额、无时限**（重复机制）。**✅ 已修**——有界 linger + RST。
   h1 的 `RequestBody` 超限即拒绝复用；h2 在响应 end 时调 `DataIn.close()`，循环读到 EOF，
   读的是裸 `DataIn`，不经 `LimitedInputStream`。404/401/413 发生在上传中途时，h2 会把剩余
   体读完（h1 直接断开）；`DataIn.read` 在队列上 park，而活跃流时连接级读超时被刻意归零
   （多路复用不该杀活跃流），慢上传可以一直占住流与虚拟线程。改进：已应答即结束输入（M）。
3. **无体状态码的 `Content-Length` 只在 h1 被剥离**（重复机制）。**✅ 已修**——规则上移 `ResponseFraming`。
   h1 writer 按 RFC 9110 §8.6 跳过 `Content-Length`；h2 writer 只过滤连接级头，原样转发。
   handler 设 CL 再答 204，h1 抹掉、h2 发出——RFC 的 MUST NOT 只在一条协议上成立，
   回归测试也只钉了 h1。改进：规则移进 `ResponseFraming`（S）。
4. **入站头上限两条协议各一个常数，没有所有者**（概念经济）。**✅ 已修**——一个配置键，两协议共用。
   h1：200 个头 / 8 KiB 头块；h2：64 KiB 头块 / 64 KiB 列表，并向对端通告。同一应用按协议
   有不同输入边界，`HttpServerConfig` 里没有头的上限字段，运维无从对齐。改进：一个上限同时
   喂 h1 parser 与 h2 SETTINGS（M）。

**评分**：定位 4｜机制 3｜概念 4｜边界 4｜规则 4｜文档 3。核心共享做到了同类的上限水平；
偏差全部出在"协议边界"——那是两份代码唯一的接缝，也正是漂移发生的地方。

### 3.6 freeway-db（9 007 行 / 523 例）

**定位**：`Database` 入口、命名参数、事务、内建池、方言探测、schema/migration。
**做到了什么**：`Database.create(PoolConfig)` 是"没有容器时的唯一推导点"，`Wiring.defaults()` +
`withX` 是 `AGENTS.md` 点名的形状（且它主动把 JDBC 的 `CoerceRule` 补进调用方的 `Coercer`，
让两条路径语义一致）；`ioc` 在 `db/src/main` 只命中 `DbModule` 一个文件，模块真的能脱容器用；
池的 `settled`+`claim` 让 release/invalidate 恰好一次；SQL 扫描只有 `SqlTextParser` 一份，
方言通过 `LexerConfig` 声明引号、`#` 注释、`$tag$`、`E''`、`::`——不是正则打天下。

**发现**：

1. **`on(...)` 是全仓唯一没有参数通道的条件位，`args()` 因此会说谎**（规则违背）。**✅ 已修**——`on(String, Object...)`。
   `Sql.on(String)` 只拼 `head + " ON " + expr`，不经 `normalizeArgs`；`args()` 只汇总
   CTE/SET/WHERE。`leftJoin(...).on("o.user_id = ?")` 的 `?` 不会进 `args()`，而
   `db.query(...)` 按全文数 `?`，必然报"参数太少"；`args()` 的 javadoc 却称"按 `?` 位置排序"。
   影响：LEFT JOIN 的动态谓词只能拼字符串（注入面）；挪进 `where` 会把 LEFT JOIN 静默变成
   INNER。改进：`on(String, Object...)` 走同一条归一化路径（S）。
2. **默认表名/列名的文档与实现相反**（文档漂移）。**✅ 已修**（改文档）。
   `docs/freeway-db.md` 说"不指定则取类名小写""列名默认同字段名"，实现是
   `Strings.camelToSnake(...)`：`record UserProfile(String userName)` 落
   `user_profile.user_name`。多词字段是常态，照手册写 SQL 的人会在运行期才看到
   "表不存在"。改进：四处文档改成 snake_case 并补一个多词示例（S）。
3. **手册与报错仍在教已删除的 API**（文档漂移 + 规则违背）。**✅ 已修**（报错与文档）。
   ① `Sql` 的两处报错让人 `call set("column", value)`，而 `Sql` 只有 `setColumn`/
   `setExpression`（改名记在 CHANGELOG 里）——**报错点名了一个不存在的方法**，而
   `AGENTS.md` 要求 loudness 必须"name the fix"。
   ② `docs/freeway-db.md` 的 INSERT 示例用 `.set(...)`，`README` 也写 `Sql.insert("t").set(...)`，
   都不可编译；`docs/freeway-db.md` 还教 `new MigrationRunner(db, true, …)`，唯一构造器是
   `MigrationRunner(Database, Options)`。
   ③ `Sql` 的注释说 `$1` 会变成 `?`，lexer 实际把 `$1` 原样保留（测试钉住了这一点）。
   改进：改两处报错、两处文档、删一首假承诺（S）。这条是"产物说假话"里最贵的一类：
   它让人照着做，却走不通。
4. **子句事实只活在 `tail` 字符串里**（重复机制）。**✅ 已修**——每个子句一个字段、按 SQL
   顺序渲染；重复子句构造期拒绝；`HAVING` 的绑定值按渲染顺序排。
   `Sql` 用 `tail.contains("RETURNING")` / `("ON CONFLICT")` 再问 `Dialect` 能力；
   `doNothing()`/`doUpdateSet()` 只查 `requireInsert`，不要求先 `onConflict`，于是
   `Sql.insert("t").setColumn("id",1).doNothing()` 渲染出 `INSERT … VALUES (?) DO NOTHING`
   还不报错。构造器自己写着判据"the failure would otherwise be the database's, far from the
   call that made it"——正好被自己违反。改进：把 `returning`/`onConflict` 记成字段，
   渲染时按字段判能力（S/M）。
5. **`BatchQuery` 是第二套执行器：自带事务 + 复制的连接复位**（重复机制）。**✅ 已修**——`OwnedTransaction`。
   `BatchQueryImpl` 自己 `setAutoCommit(false)`/commit/rollback，并且有一个
   `restoreAutoCommitQuietly`，注释自承 "Mirrors `DatabaseImpl.restoreConnectionState`"。
   裸调 `batch().execute()` 与包在 `db.transaction { ... }` 里原子性不同，而 `BatchQuery`
   的契约只字未提。改进：把"借连接→事务→复位"收进一个包内入口，批处理只留
   addBatch/executeBatch（M）。

**评分**：定位 4｜机制 3｜概念 4｜边界 5｜规则 4｜文档 2。入口、池、方言、事务是自洽的；
失真集中在 SQL 构造器（缺一个参数通道、能力靠扫文本）与手册（落后两次改名）。

### 3.7 freeway-flow（4 878 行 / 118 例）

**定位**：in-JVM 图编排——7 种节点、v3 DAG、boot 期校验、任务词表、分支隔离、PlantUML。
**做到了什么**：一处分派（`FlowEngineDefault` 的节点 switch）、一处 join 记账
（`ExecState.join`，`INCLUSIVE` 与 `PARALLEL` 共用）、一份表达式（构建期只 `validate`，
运行期只 `ExprEvaluator`，PlantUML 只读 `description()` 不复算）；边界极干净：
`pom` 只有 ioc+commons，`@name` 只有 `FlowDriverDefault` 一处解析，没有第二套服务发现；
`FlowEngine.create()` 让引擎可以完全脱离容器运行。

**发现**：

1. **失败分支的写层留在执行线程上**（规则违背，最重的一条）。**✅ 已修**——`Branch` 作用域。
   `runBranch` 把"提交"绑在 `walk` 正常返回上：

   ```java
   Runnable merge = evaluation.context().beginBranch();   // push Layer
   walk(evaluation, branch);                              // 抛出 → 永远不 merge
   merge.run();                                           // pop + 合并
   ```

   注释说"a throwing walk never reaches the merger … its partial writes discarded with it"，
   但**没有任何代码丢弃它**：`Layer` 留在 `FlowContextImpl` 的 `ThreadLocal` 栈上。并行路径把
   异常收进 `errorRef` 后，执行线程回到池里，脏层还在。同一个 `FlowContext` 被重试复用
   （resilience 场景）时，下一轮的写入会落到脏层，merge 进脏层而不是 root——**父层读不到，
   静默丢写**。主动 `stop()` 的分支则相反：它正常返回，于是**会** merge，和 javadoc 说的
   "stopped or failed contributes nothing"相反。
   改进：`try/finally` 包住 `walk`，提交与丢弃由形状决定（M）。这是本轮唯一一条会在重试
   场景静默丢写的缺陷。
2. **join 迭代域校验只挂在 `load()` 这一扇门上**（规则违背）。**✅ 已修**——移入 `Graph` 构造。
   `rejectUnsatisfiableJoin` 只有一个调用点，在 `load(id, graph)` 里；而公开的
   `eval(GraphSpec)`、`eval(Graph, FlowContext)` 不经过 `load`。`GraphSpec.create()` 校验了
   环、引用、词表、`when` 可编译、`join` 位置，唯独不含迭代域。于是**一张 `load` 会拒绝的图，
   用 `engine.eval(spec)` 能跑起来**，末尾只报 `dead end`——把配置错误伪装成丢分支。
   改进：把这项分析挪进图的构建期（它只读 nodes/links），`load` 只负责注册（M）。
3. **PARALLEL 出边的 `when` 构建期编译、运行期忽略**（文档漂移）。**✅ 已修**——构建期拒绝。
   `GraphSpec` 对**每条边**的 `when` 调 `ExprEvaluator.validate`，而 `parallelOut` 直接取
   `node.nextNodes()`，从不 `conditionTest`。给 fork 的一支加守卫，编译通过、分支无条件执行，
   运行期静默走错支。改进：仿 `validateJoin`，在 normalize 期拒绝 PARALLEL 出边的 `when`（S）。
4. **`normalize()` 的缓存协议是空转的**（空转抽象）。**✅ 已修**——已规范化的 spec 短路返回。
   每个 setter 都调 `invalidate()` 把 `bfsOrder` 置空，而 `toMap()` 每次无条件调用
   `normalize()`，后者没有"已缓存就返回"的分支——置空与重算互为无用功，`normalize()` 的
   javadoc 却写 "Idempotent — subsequent calls are no-ops"。读者要追 8 个 `invalidate()` 调用点
   才能确认它们不影响任何结果。改进：删掉 `bfsOrder`/`invalidate()` 与 8 处调用，BFS 留在
   `normalize()` 局部（S）。
5. **反向邻接没有唯一拥有者**（重复机制）。**✅ 已修**——`Graph` 构造时建索引，深度迭代。
   `Node.prevLinks()` 首触即扫全图（`AGENTS.md` 点名的形状），`FlowEngineDefault` 两处用它；
   同一关系又在另一处被重建成 `parents`，`depthAbove` 按祖先链**递归**——与"无路径长度栈上限"
   的承诺相反，声明顺序不是拓扑序时，长链会在 load 期栈溢出而不是干净报错。
   改进：`Graph` 构造时随正向邻接一并建反向邻接，删懒扫描与 `parents` 重建（M）。

**评分**：定位 3｜机制 3｜概念 3｜边界 5｜规则 5｜文档 3。骨架与代码同构，优点是真的；
两道门禁（分支隔离、图可运行）都只在正常路径/单一入口上兑现，这也是它 118 例测试最该覆盖
却最薄的地方（§2.6）。

### 3.8 freeway-cloud · 服务平面（rpc / discovery / registry / observe / health / secret / storage）

**定位**：发现与注册（内建 fleet + 可替换后端）、远程调用（JDK HttpClient）、可观测、健康、
凭证、对象存储——不引入任何外部系统的云原生基座。**做到了什么**：本地实现与节点 HTTP 面
共用同一个 store（`ServiceDiscoveryDefault` 与 `RegistryApi` 是同一份表的两面）；缓存只加在
**后端**的发现实现上，进程内 store 不包（查表已是 0 成本），`TtlPolicy=0` 可让推送型后端退出
缓存——"套不套由后端决定"写在决策点；十二个 `XDefault` 全部可按 `AGENTS.md` 的判据替换；
`CloudModule` 是 bundle（8 个子模块 `@SubModule`），`CloudEventModule` 故意不在里面。

**发现**：

1. **就绪门的顺序与文档相反**（规则违背）。**✅ 已修**——注册即带 `Health`。
   `RegistryLifecycleHook.start()` 先 `registry.register(instance)`，循环结束后才
   `ReadinessGate.arm(...)` 去标记未就绪。文档承诺的是"先以未就绪注册（在注册表里、不在发现
   结果里）"。窗口只有一次扩展点遍历那么长，但它正是门要挡的那类竞态：一个正在被派发的慢启动
   实例。改进：让 readiness 成为 `register` 的输入（注册即带 ready 位），而不是"先注册再改口"（M）。
2. **`CloudRpcModule` 不能按文档那样单独放置**（文档漂移）。**✅ 已修**（文档示例补齐 discovery）。
   它取 `ServiceDiscovery` 与 `LoadBalancer`，后者只在 `CloudDiscoveryModule` 里绑定；单独放
   `CloudRpcModule` 时容器能建成，**首次远程调用**才抛 `MissingBinding`——错误从组合期挪到了
   生产流量上。而 `CloudModule` 的 javadoc 与 cloud.md 都把"只放 RPC 客户端"当示例。
   改进：文档示例补上 discovery，或让 RPC 模块声明它的依赖（M/S）。
3. **"丢租约后自愈"有两份，fleet 那份让 hook 的失败上报失效**（重复机制）。**✅ 已修**——只有钩子重注册。
   通用的自愈在 hook 的心跳里（`renew` 返回 false → 重新注册，并 `renewal.failed()`）；
   fleet 后端在 `RegistryNodeClient.renew` 里对 404 **自己重注册并返回 true**。结果：换到 fleet
   后端时，hook 永远看不到"条目丢了"，那条 WARN 与失败指标不会出现；换到 Consul 则会出现。
   同一个事件，两种观测。改进：把"重新注册"上移到 hook，客户端只回答"还在不在"（M）。
4. **`GET /registry` 的自述少了两条路由，协议接纳与文档相反**（文档漂移）。**✅ 已修**——索引派生 + `<= PROTO`。
   索引列了 `register/renew/unregister/discover` 四条，而 `RegistryServerModule` 实际贡献六条
   （漏 `ready` 与 `index` 自身）；proto 检查是**等值**（不相等即 426），文档写的是
   "节点永远接受更旧的协议"——只有"完全不声明 proto"的旧客户端能过。改进：索引改为从路由表
   派生（它本来就是同一张表），协议检查改成 `announced <= PROTO`（S）。

**评分**：定位 3｜机制 3｜概念 4｜边界 4｜规则 4｜文档 2。缓存、单 store、seam 替换都成立；
失分在几处"文档承诺了一个实现没做的顺序/协议"，以及一份重复的自愈。

### 3.9 freeway-cloud · 事件平面（event / context / resilience）

**定位**：跨 JVM 广播（WS mesh，CloudEvents 1.0，at-most-once）、上下文传播、韧性策略。
**做到了什么**：这是全仓"把事实放进形状"做得最彻底的一层。

- **背压策略长在连接上，不在发布方**：`PeerConnection` 每连接一个有界队列 + 独立发送线程，
  队列满或写失败即关闭该连接；发布线程从不等待 peer。注释直接写"at-most-once 的既有失败模式"，
  而不是让调用方记得 try/catch。
- **分类只有一份**：`MeshFrame` 是密封接口（Hello/Event/EventBeforeHello/DuplicateHello/
  Malformed/Unrecognized），两条腿共用；`specversion` 先于 `proto` 判，理由写在代码里
  （`proto` 是扩展属性，第三方可以合法使用——先判它会把一个合法事件判成 hello）。
- **兴趣只有一份**：订阅是组合期声明（`contribute(CloudEventSubscription.class)`），它同时
  是 hello 握手的拉取前缀、入站门的匹配表、投递路由——没有运行期 `subscribe` 可以与它分叉。
- **trace 复用同一条 W3C 实现**：`EventTrace` 用 HTTP 路径同一个 `TracePropagator`，
  且只带 trace、绝不带 principal/baggage，理由（"没有东西认证事件的生产者，只承认因果"）
  写在 javadoc 里；它 public 的唯一理由是 ext 的 Kafka 适配器要读它——**指名了消费者**。
- **韧性只有一个状态机**：`ResiliencePolicy` 把 limiter→breaker→attempt→retry 的顺序写在一处，
  并钉住两条容易写错的规则（限流在熔断之前，所以本地拒绝不消耗半开探针；半开时任何失败都
  结算探针，熔断不会卡死）。

**发现**（这一层没有"错"，只有两处形状带来的代价）：

1. **每个事件把 payload 序列化两次**（判断）。
   `CloudEventEnvelope.toJson` 为了把 payload 嵌成 JSON 节点，先 `codec.toJson(payload)` 得到
   文本，再 `JsonUtils.parse` 成树，最后 `codec.toJson(frame)` 把整帧（含 payload 文本）再序列化
   一次。根因是 `JsonCodec` 的契约刻意只有"文本与类型、没有树"。这不是缺陷，是**接缝形状的
   代价**：mesh 的 CPU 热点上，一个事件 2 次序列化 + 1 次解析。建议在 `CloudEventEnvelope` 的
   javadoc 里写明这个代价（并在压测时把它当作已知基线），或在 `JsonCodec` 一侧补一个产树入口
   （S/M）。
2. **`CloudEventBus` 没有 `close()`，生命周期完全靠 hook**（判断）。
   进程内的 `EventBus` 是 `AutoCloseable` 且由容器收尾；mesh 总线由
   `CloudEventLifecycleHook.stop()` 释放。两者契约不同是**有理由的**（mesh 的关闭要先解绑
   peer、再停连接器，顺序属于 hook），但"总线"这个词在两个平面上的生命周期含义不同，值得在
   两边 javadoc 互相点名（S）。

**评分**：定位 4｜机制 4｜概念 4｜边界 4｜规则 4｜文档 4。全仓最"自洽"的一层：每个机制
都能在代码里指出唯一归属，每条看似奇怪的顺序都在注释里带着理由。

---

## 4. 有意保留：已核实为"有意为之"，不要顺手改

这一节存在的理由：本仓上一轮审计撤回了 5 条误判，几乎全部是"看起来像，其实是分工"。
以下每一条我都读过两处实现，确认不是缺陷。

| 项 | 为什么不是缺陷 |
|---|---|
| `RegistryNodeClient` 自带 `new JsonCodecDefault()` | 注册面线格式不能跟着应用的自定义 codec 变；javadoc 写明"owned, not injected" |
| `JsonUtils`（静态）与 `JsonCodec`（注入）并存 | 分层：有容器用后者，没有容器（配置加载、启动前）用前者；节点层只经 `JsonUtils`，这是刻意的契约 |
| 三个事件总线 | ioc 与 cloud 的分工写进了各自的 javadoc，且互不自动桥接；flow 的见 §2.3（那是"缺出口"，不是"该删总线"） |
| 连字符键（`freeway.log.file.max-size`）的环境变量不可 `export` | 已在 `docs/freeway-config.md:301/775` 与 skill 中写明，并给出 `-D` / `env 'NAME='` / 容器 `-e` 三条可用通道 |
| `SCHEMA_AUTO` 的 `@Deprecated` | `AGENTS.md` 的 loudness 义务：删除要留一个点名修法的启动期提示，键留在表里才不会污染未知键报告 |
| `FreewayApp` 可变、单次使用、自带两个默认值 | 它是 launcher 而不是 value type；默认值没有第二个所有者；`AtomicBoolean` 挡并发 `start()` |
| `Sql` 自己不做 SQL 扫描 | 扫描只有 `SqlTextParser` 一份（`Sql` 委托），方言通过 `LexerConfig` 声明语法，不是正则打天下 |
| `ConfigFileReader` 手写 JSON？ | 不，它调 `commons` 的 `JsonUtils`；boot 里没有第二个 JSON 解析器 |
| 无 `module-info` / 无 JPMS 门禁 | `AGENTS.md` 明写"JPMS posture today is *none*"，是有意缺席而不是遗漏 |
| `ReadinessGate` / `RpcExportHook` / `ActiveBindingProbe` 持有 `Container` | 都是 `RuntimeHook` 启动期（容器已建成但仍在组合）用的句柄，正是 `AGENTS.md` 允许的那一类；长寿命的 `EventBus` 不属此列，见 §3.2 |
| `MeshFrame` 先判 `specversion` 再判 `proto` | `proto` 是第三方可合法使用的扩展属性；先判它会把合法事件判成 hello。顺序带着理由，别"简化"回去 |
| `RegistryLifecycleHook.stop()` 的顺序（置 `stopped` → 停心跳并 `awaitTermination` → 注销） | 这是 1.5.6 修过的竞态：先注销、后停心跳会让一个在途 renew 把条目写回去。顺序是事实，不是风格 |
| `EventTrace` 只带 trace，不带 principal/baggage | 事件路径上没有东西认证生产者；只承认因果，不承认身份。理由写在 javadoc |
| `ResiliencePolicy` 的 limiter→breaker 顺序 | 本地拒绝不消耗半开探针；半开时任何失败都结算探针。两条都是防止熔断卡死的形状 |
| h2 活跃流不套连接级读超时 | 多路复用下杀活跃流是错的；`h2cActiveStreamSurvivesReadTimeout` 钉住了它（`HttpServerConfig` 的措辞该改，行为不该改） |
| `CloudModule` 不含 `CloudEventModule` | 事件网格要开监听、要拨号，是应用显式要求的能力，不该由 bundle 默认带上 |

---

## 5. 改进路线（先形状，后检查，最后文档）

按"改完之后是否还需要一段解释"排序。每条给出证据出处与规模。
**本批已完成的条目见 §0.1**；下面保留原始排序，未完成的仍是待办。

### P0 — 形状（改完代码自证，不需要注释）

1. **flow：分支写层的清理交给 `try/finally`**（§3.7）。现在"异常分支丢弃缓冲"靠注释声明，
   代码只保证正常返回；异常路径把 `Layer` 留在执行线程上。这是本轮唯一一条**会在重试场景
   静默丢写**的缺陷。规模 M。
2. **flow：join 迭代域校验挪到图的构建期**（§3.7）。现在只有 `load()` 一道门，
   `eval(GraphSpec)` / `eval(Graph, …)` 是另外两扇没有门的门。规模 M。
3. **ioc：类级标注在 `bind(Concrete)` 上也提取一次**（§3.2）。两种绑定写法必须得到同一个
   marker 集，否则 `@NotThreadSafe` 的拒绝形同虚设。规模 S。
4. **ioc：`SymbolSource` 的类型化读取只有一个实现**（§3.2）。把 `Coercer` 纳入接缝，或删掉
   接口默认方法——现在按文档手写的替换实现会在第一次无 parser 的 `resolve(spec)` 上崩。
   规模 M。
5. **flow：PARALLEL 出边的 `when` 在构建期拒绝**（§3.7）。现在它编译通过、运行期忽略。规模 S。
6. **`Sql.on(String, Object...)`**（§3.6）。`on(...)` 是全仓唯一没有参数通道的条件位；
   动态 JOIN 谓词只能拼字符串。规模 S。
7. **boot：`OVERFLOW` 触发一次重载**（§3.3）。目录事件繁忙时正是热重载最该工作的时刻。规模 S。

### P1 — 检查（让规则变成可执行的失败）

8. **http：入站头名在 context 边界统一小写**（§3.5）。现在 h2 上是 `Traceparent`、h1 上是
   `traceparent`，cloud 的 W3C trace 在 h2 上静默丢失。规模 S。
9. **http：错误体归一**（§3.4）。404/405/500 是 `text/plain`，400/413/415 是 JSON。
   1.5.6 已为 `/health/live` 修过同一类问题，404 是遗留。规模 M。
10. **http：无体状态码的 `Content-Length` 规则移进 `ResponseFraming`**（§3.5），h2 目前会发出
    它。规模 S。
11. **cloud：readiness 成为 `register` 的输入**（§3.8）。现在先注册为就绪、再标记未就绪，
    窗口正是门要挡的竞态。规模 M。
12. **文档一致性检查扩面**（§2.2）：API 列表页的 `Type.member` 存在性；顺手修
    `#graph`/`#subgraph`/`AppRuntime.container()`。规模 S。
13. **demo 进 reactor + 一条 CI**（§2.5）。规模 S/M。
14. **flow 的随机 DAG 探针** **✅ 已完成**（`FlowDeclarationOrderTest`：种子化随机图，两种声明顺序，判定/报错/规模一致；顺带修掉环报错的声明顺序依赖）。（§2.6）。规模 M。
15. **公共面按角色收口**（§2.4）：3–5 个类型。规模 S。

### P2 — 文本与命名（改完只留一句话）

16. **`ARCHITECTURE.md` 写明组合的两个来源**（§2.1），并在启动树里标出"发现的"模块。规模 S。
17. **db 手册与报错对齐**（§3.6）：snake_case、`set(...)`、`MigrationRunner` 旧构造器、`$1`。
    规模 S。
18. **日志键的"窄级联"要出声**（§3.3）：进了符号链却没被日志侧采纳的 `freeway.log.*` 在启动期
    WARN；同时修掉 `freeway-config.md` 同一节里"工作目录参与/不参与"的矛盾。规模 S。
19. **几处一句假陈述**（各自独立）：`AppState` 的 registry/503（§3.3）、`Binding.primary()` 的
    `@throws`（§3.2）、`bodyAsJson` 与 `BodyHandler` "equivalent"（§3.4）、访问日志
    "one line per request"（§3.4）。规模 S。
20. **cloud：`/registry` 索引从路由表派生；proto 接受 `<= PROTO`**（§3.8）。规模 S。
21. **`ConfigLoaderImpl` 改名**、README 旁注明 `groupId` 与包名不一致的理由（§2.7）。规模 S。**✅ 已完成**。

### 不建议做

- 不要为了"消灭重复"去合并 `JsonUtils`/`JsonCodec`、三个总线、`PoolDefault`/`Pool` 缝——见 §4。
- 不要给 core 模块加 `module-info`、不要保留兼容重载（`AGENTS.md` 明令）。
- 不要按"public 类型数量"一刀切降可见性：仓内没有调用方不等于仓外没有（§1.3 的教训）。

---

## 6. 复核命令

每条"代码事实"都能在任意快照上重跑；输出应与正文一致（行号会漂移，判据不会）。

```bash
# §2.1 组合的两个来源
grep -rn "ModuleDiscovery.fill" freeway-boot/src/main/java
ls freeway-*/src/main/resources/META-INF/services/com.jujin.freeway.ioc.ModuleEx

# §2.2 任务词表到底认什么（对比 docs/ARCHITECTURE.md:118 与 skills/.../SKILL.md:39）
grep -rn "The vocabulary is" freeway-flow/src/main/java

# §3.2 类标注的提取点只有一个
grep -rn "extractClassMarkers" freeway-ioc/src/main/java

# §3.2 替换接缝：默认方法没有 Coercer，实现类有
grep -n "resolve(SymbolSpec" -A3 freeway-ioc/src/main/java/com/jujin/freeway/ioc/symbol/SymbolSource.java
grep -n "resolve(SymbolSpec" -A4 freeway-ioc/src/main/java/com/jujin/freeway/ioc/symbol/SymbolSourceDefault.java

# §3.5 同一头的两种大小写
grep -n "toLowerCase" freeway-http/src/main/java/com/jujin/freeway/http/engine/Http1xParser.java
grep -rn "normalizedName" freeway-http/src/main/java/com/jujin/freeway/http/engine/http2 | head -3

# §3.6 报错点名了不存在的方法
grep -n 'call set(' freeway-db/src/main/java/com/jujin/freeway/db/Sql.java

# §3.7 提交绑在正常返回上
grep -n "beginBranch" -A6 freeway-flow/src/main/java/com/jujin/freeway/flow/FlowEngineDefault.java

# §2.4 / §2.6 规模与测试
for d in freeway-*; do printf "%-16s main=%5s test=%5s\n" "$d" \
  "$(find $d/src/main -name '*.java' | wc -l | tr -d ' ')" \
  "$(find $d/src/test -name '*Test.java' | wc -l | tr -d ' ')"; done
```

**§2.1 的实测**（classpath 只放 `commons/ioc/boot`，再按需加 `db` 或 `http`）：

```java
try (AppRuntime app = FreewayApp.create()
        .autoDiscovery(true)          // 再跑一次 false 作对照
        .shutdownHook(false)
        .start()) {
    System.out.println(app.state());
}
```

```
+ db   → FAILED: Missing required config key 'freeway.db.url'
+ http → STARTED, 引擎在默认端口启动（未声明任何模块）
- 两者 autoDiscovery=false → STARTED
```
