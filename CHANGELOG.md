# Changelog

All notable changes to Freeway 2 will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Migration

`WebServer` 更名为 `HttpServer`（见本表末三行），装配收敛成**一个派生点**：
`HttpServer.create(engine, config, pipeline[, eventSink])`。
原来并存两条装配路径：`HttpModule`（容器）与 `WebServerBuilder`（"无 IoC 的独立用法"）——
builder 不是第二个入口而是第二个组装根：自带一份默认值、自己追加 `ErrorHandler.defaults()`、
自己判 `secure`，于是同一件事有两种答案（内置错误映射在 builder 里排最后、在容器里按贡献
顺序排最前；类路由在容器里能解析、在 builder 里直接报错）。这一轮把派生规则搬进 `create`，
两条路都只是"填部件再调用"，**独立构建的能力原样保留且不再需要容器**。

同时删掉 `internal.HttpModuleConfig`：它是键的第二份所有者——13 个 server 旋钮的默认逐个重述
（只是运气好引用了 `HttpServerConfig.DEFAULT_*`），CORS/health 的默认与 `CorsFilter.defaults()` /
`HealthFilter.defaults()` 各说两遍，还让 `HttpServerConfig` 有了两条取得通路（`container.get(...)` 与
`cfg.server()`）。改为既有先例 `SslSettings.from(symbols)` 的形状：**值类型自己读自己的键**。

| 旧 API / 行为 | 新 API / 行为 |
|---|---|
| `WebServerBuilder.builder().config(c).route(r).build()` | `HttpServer.create(engine, c, HttpPipeline.of(r))` |
| `WebServerBuilder.engine(e)` / `.sslContext(ctx, h2)` / `.metrics(m)` | 都是 engine 的事：把 `new FreewayHttpEngine(Wiring.defaults(json, coercer).withSsl(…).withMetrics(…))` 直接交给 `create` |
| `.route(…)` / `.webSocketRoute(…)` / `.filter(…)` / `.staticFile(…)` / `.errorHandler(…)` / `.cors(…)` / `.health(…)` | `HttpPipeline.of(…).withRoutes/withWebSockets/withFilters/withStaticFiles/withErrorHandlers/withCors/withHealth(…)`（wither 返回新实例） |
| `.accessLog(out)` | `HttpPipeline.of(…).withFilter(new AccessLogFilter(out))`；容器里是键 `freeway.http.access-log.enabled` |
| `.eventSink(sink)` | `create` 的第 4 参（`Consumer<Object>`）；三参照旧发布"没人观察就不造事件" |
| `.jsonCodec(…)` / `.coercer(…)` / `.routeGroup(…)` | 删除（core+ext 零调用点）。builder 里那两个私有 `new JsonCodecDefault()` / `new CoercerDefault()` 正是"容器注册的 `CoerceRule` 到不了 HTTP"的源头 |
| `internal.HttpModuleConfig`（快照 record + 26 个 SymbolSpec） | 删除：`HttpServerConfig.from(symbols)` / `CompressionConfig.from` / `CorsFilter.from` / `HealthFilter.from` / `SslSettings.from`（已存在），每个键一行 `withX(resolve(SPEC.orDefault(现值)))`；`access-log` 一个布尔键由 `HttpModule` 自己读 |
| `SslSettings` 只能从 `HttpModuleConfig.ssl()` 拿 | 它自己是绑定服务：`container.get(SslSettings.class)`（引擎与热重载 hook 共用一次解析） |
| `HttpServerConfig` 由配置键唯一决定，无覆盖缝 | `HttpModule` 以 `.id("builtin")` 绑定，覆盖走 `.primary()`（与 `HttpEngine` 同一套词汇） |
| `HttpEngine` 实现者不声明传输 | `HttpEngine.secure()` 为实现方法：谁持有密钥谁回答（内置引擎看 `SSLContext`，Undertow/Jetty 看自己的 listener） |
| `WebServer` 包私有构造器（5 参，含 `secure` 与 `ReadinessProbe`） | `HttpServer.create(...)` 两个重载；构造器仍包私有，probe 只作包内测试缝 |
| 容器里应用自定义 `ErrorHandler` 排在内置映射**之后** | 内置映射由 `create` 追加在最后，两条路一致：贡献顺序等于放置顺序，而应用总在 `HttpModule` 之后放置，于是 413/415/400 这些"应用想重映射的案例"会被框架先抢走。`HttpModuleErrorHandlerOrderTest` + `HttpServerStandaloneTest` 各钉一半 |
| `CorsFilter.builder().allowedOrigins("a,b").allowCredentials(true).build()` | `CorsFilter.defaults().withAllowedOrigins(List.of("a","b")).withAllowCredentials(true)` |
| `new CorsFilter(false, null, null, null, null, null, false)` / `new HealthFilter(false, …, null)` | `CorsFilter.defaults().withEnabled(false)` / `HealthFilter.defaults().withEnabled(false)` |
| `CorsFilter.DEFAULT` / `HealthFilter.DEFAULT` | `defaults()`（与 `HttpServerConfig.defaults()`、1.5.3 的 `PlantUmlOptions.defaults()` 同一动词，常量与工厂不留两名） |
| `StaticResourceMount.cacheMaxAgeSeconds(n)` / `.immutable(b)` / `.fallthrough(b)`（写） | `withCacheMaxAgeSeconds` / `withImmutable` / `withFallthrough`；裸名词只剩读（`fallthrough()`），调用点从此能分辨赋值与取值 |
| `.builder()` 作为"配置型对象流式装配"的合法示例 | 约定收紧为：builder **不得持有默认值**（`FlowDriverDefault.Builder` 合法：两个必填部件，无一条默认）；差量构造一律 `defaults()` + `withX` |
| `WebServer`（类型名） | `HttpServer`：与 `HttpServerConfig` / `HttpServerHandle` / `HttpServerStartedEvent` 同族（家族里类型才是异类），`create` 签名与语义不变 |
| `RequestComponents`（类型名） | `HttpPipeline`：服务器四个锚点之一（`HttpEngine` 能力 / `HttpServerConfig` 传输声明 / `HttpPipeline` 处理声明 / `HttpServer` 派生）——引擎无关的声明，在 `engine.start` 之前被 `create` 编译成 `ExchangeHandler`，三方引擎只见接缝 |
| `pipeline.withErrorMapper(…)` | `withErrorHandlers(…)`：类型早在 `ExceptionMapper`→`ErrorHandler` 改名时就换了，wither 补齐——两个活名字只剩一个 |
| `HttpServerConfig.h2ResetBurstLimit` / `h2ResetWindow`（字段与 wither） | `FreewayHttpEngine.Wiring.withH2Reset(burst, window)`：引擎私有旋钮归能力侧；键不变（`freeway.http.h2.*`），容器路径由 `HttpModule` 自动接线，独立路径直传 `Wiring` |
| `com.jujin.freeway.http.internal.SslReloader` | `com.jujin.freeway.http.engine.SslReloader`：热重载的驱动方由 `HttpModule` 生命周期钩子改为 `FreewayHttpEngine.start` 自驱（输入经 `Wiring.SslReload` 下推），类回引擎包、降包私有 |

行为变化（无需改调用点，但值得知道）：

- `HttpServer.secure()` 是引擎的判据，不再重读 `freeway.http.ssl.*`。
- 事件发布跟着 `create` 的重载走：三参（独立）不发布，四参发布；`HttpModule` 用四参交给 `EventBus`。
- `HttpPipeline` 的规范构造器不再容忍 `null` 部件：容器绑定返回 `null` 会在**启动时**抛 NPE，
  而不是静默落到默认 CORS/健康策略；`of(...)` 自己显式声明默认值，规则只此一处。
- `withRoutes(...)` / `withWebSockets(...)` 变参档的 javadoc 曾写"追加"、实现是替换——
  文档改为替换语义（`RouteIndex` 是冻结 trie，wither 只能替换；仓库内无追加调用点）。
- 引擎合同落成文字：`HttpEngine.start` 的 honor 合同分两层（必须 honor / 每 exchange
  策略，后者再分状态型 maxBodySize 与线旁型 compression）、`ExchangeHandler.websocket`
  的"每个升级候选请求都必须咨询"——这是 `HttpServerConfig` 与 `HttpPipeline` 能同等地
  apply 到 Undertow/Jetty 三方引擎的边界依据；`h2Reset*` 收编进 `Wiring` 后"引擎私有"
  层整个消失，config 的每个字段对每个引擎都适用。
- `HttpServerConfig` 不再携带 `freeway.http.h2.*`：第三方引擎收到的 config 从此每个
  字段都对它适用；调优值配非内置引擎在启动时 WARN 点名归属，`from(...)` 对这两键
  只指路不携带。
- TLS 热重载自驱：`Wiring.SslReload` 承载被监视的证书材料与重建器，引擎在
  `start()` 里先于监听端口启动 reloader（材料不可读即启动失败、不占端口），
  返回的 handle 在 `close()` 时先停观察再排水；`HttpModule` 不再装配 reloader，
  只在非内置引擎激活且配置了 reload 时打跳过提示。接缝上的 `FreewayHttpEngine`
  引用由 2 处降为 1 处（仅 `HttpModule` 的 builtin 绑定）。
- `HealthFilter` 的 `healthCheck` 不再接受 `null`（构造期 `requireNonNull`）：关闭的探针此前会留一个
  每次请求都可能 NPE 的字段。默认路径成为 `HealthFilter.DEFAULT_PATH`，`normalize` 与 `defaults()`
  不再各写一个 `"/healthz"`。

### Added

- `SymbolSpec.orDefault(fallback)`：叠加式读取的默认由**被构建的值**给出，键表因此不必重述默认；
  `SymbolSource.resolve(spec)` 一行一键（`SymbolSpecTest` 钉住"缺省键留原值、空值留原值、存在则
  经链的 `Coercer` 解析"）。
- `HttpServer.create` 两个重载、`HttpPipeline.of/withX`、`HttpServerConfig.from` 与
  `CompressionConfig.from`、`CorsFilter.from`、`HealthFilter.from`、`HealthFilter` 的
  `enabled()/healthPath()/healthCheck()` 读取与 `withPath/withCheck`、`CorsFilter` 的七个字段读取。
- 测试：`HttpServerStandaloneTest`（零容器构建并服务、mapper 优先级、withers 全部生效）、
  `HttpModuleErrorHandlerOrderTest`（容器路径的同一条优先级）。
- `freeway-http` 每个包的 `package-info.java`：根包声明合同面四组（装配半、接缝数据半、TLS 契约、
  共享词表）与稳定性口径，`engine*` 声明放置规则（会话桥归 `engine`、纯帧编解码归 `http2`/`ws`、
  全部无稳定性承诺），`route`/`filter`/`body`/`websocket`/`sse`/`staticfile`/`event` 声明应用面角色；
  `internal/package-info` 里早已过期的描述（config snapshot 与 TLS builders 迁走后）一并改正。
- `RequestViewTest` 钉住 HTTP/WS 共享读面：`HttpRequest` 与 `WebSocketSession` 都 `extends
  RequestView`，且读面恰好是那 10 个只读访问器——给共享面加方法意味着两个半边同时继承。
- 角色核验结论（合并/消融/移除评估均不成立）：`engine` 四个 public 全有跨包角色——
  `HttpResponseWriter` 由 `engine.http2.Http2ResponseWriter` 跨包实现、`ResponseFraming` 与
  `HttpContextImpl` 被 ext 适配器与 benchmark 复用、`FreewayHttpEngine` 是 `HttpModule` 的绑定物，
  零降级；`RequestView` 是交换与 WS 会话的共同只读读面（2 个继承者），消融它等于两个接口各自
  重述 10 个声明（第二个所有者），移除它则 WS 会话要么复制声明、要么谎称自己是 `HttpRequest`；
  `ErrorResponses` 有 5 个调用点（`HttpServer`×3、`Http1xSession`、`StaticResourceMount`），
  消融即 404/500 的正文与头在 5 处各写一遍——正是它的防漂移承诺所阻止的事。

### Removed

- `WebServerBuilder`（209 行）与 `internal.HttpModuleConfig`（151 行）：前者是第二个组装根，后者是
  第二个默认值所有者；派生规则现在只有 `HttpServer.create` 一处，键与默认只有值类型一处。
- `CorsFilter.Builder`：它不是"少写几个参数"的糖，而是第二个默认值持有者——methods/headers/maxAge
  在 `DEFAULT` 之外又硬写一遍，`*` + credentials 的校验判两次且两处异常类型不同，同时它比规范构造器
  还窄（给不出 `enabled=false`、`exposedHeaders`、`maxAge`）。

### Fixed

- **cloud 注册 scheme 判错**：`HttpServiceDeclaration` 用 `WebServer.secure()` 决定 `http`/`https`，
  而它读的是 freeway 自己的 `freeway.http.ssl.*` 键——由可能忽略这些键的 Undertow/Jetty 适配器终止
  TLS 时，https 节点被注册成 `http://`。判据上收到 `HttpEngine.secure()` 后，注册的身份与真正提供
  传输的组件一致。
- **应用无法重映射框架已映射的异常**（容器路径）：见迁移表倒数第 6 行。
- **静默失效的 `freeway.web.*` 配置键**：旧前缀在 v1.2.2 改名为 `freeway.http.*`、
  回退随后被删除（e37ba527），仍写旧前缀的配置从此被无声忽略、服务器一直跑默认值。
  `HttpModule` 启动时逐键探测"旧前缀键存在而对应新键缺失"这唯一会静默失效的形状，
  一条 WARN 点名每个死键及改法（`旧键 → 新键`）；新键存在即不报——值无论如何生效，
  包括以 `${freeway.web.*}` 引用旧键的写法；死键自身的值展开失败也只报不抛。

### Changed

- `freeway-http` 的 66 处测试构造改为直接 `HttpServer.create(...)`（零容器，引擎由 `TestHttp.engine()`
  给出）；只有两个测 `HttpModule` 本身的测试仍走容器。ext testkit 与 benchmark 保持走 `HttpModule`：
  契约要量"应用真正拿到的那个服务器"，那里 `Metrics`/`CorsFilter`/`HealthFilter` 的默认仍来自键。
- AGENTS 的命名段改写（builder 不得持有默认值；`withX` 为写、裸名词为读），`docs/ARCHITECTURE.md`
  与 `freeway-http/README.md`、`docs/DEVELOPER-GUIDE.md`、skills 两份随之更新。

## [1.5.3] - 2026-09-20

### Migration

模块组合从"树包装 + 假模块"收敛为单一 `ModuleNode`：结构只有应用根与模块节点，bundle 由
`@SubModule` 声明，class 声明改为加载期实例化。升级时按下表替换：

| 旧 API / 行为 | 新 API / 行为 |
|---|---|
| `ModuleNode.tree()` → `TreeNode<ModuleEx>` | `ModuleNode.children()`（`List<ModuleNode>`）与 `ModuleNode.render()` |
| `ModuleNode.children()` → `List<TreeNode<ModuleEx>>` | `List<ModuleNode>` |
| `ModuleNode.module()` | 节点自带声明：`type()` / `instance()` / `resolve()` |
| `ModuleNode.bindOrder()` → `List<ModuleEx>` | `List<ModuleNode>`（模块节点），只含模块，应用根不再出现 |
| `ModuleNode.group(name, …)` | 删除；bundle 用 `@SubModule`（子模块仍可单独 `of(...)` 放置取子集） |
| `ModuleNode.of(module, children…)`（显式带子节点） | 删除；模块节点用 `ModuleNode.of(class \| module)`，bundle 由 `@SubModule` 声明 |
| `ModuleNode.of(TreeNode<ModuleEx>)` | 删除；树值本身就是 `ModuleNode` |
| `CloudModules.standard()` | `ModuleNode.of(CloudModule.class)`（或 `.add(CloudModule.class)`） |
| `Freeway.create(Collection<? extends ModuleEx>)` | `Freeway.create(modules.toArray(ModuleEx[]::new))` |
| `EventBus.enableInboundDeduplication(int)` / `disableInboundDeduplication()` | `EventBus.inboundDeduplication(int)`（容量 ≤0 即关闭） |
| `FreewayApp.run()` | `FreewayApp.run(new String[0])` |
| `AppBuilder` 中第二个应用根被静默嵌套 | `IllegalStateException`；要合并请显式构造单个 `ModuleNode.app(...)` |
| class 声明在 `of(Class)` 时实例化 | 加载期（容器创建）实例化；无参构造缺失的报错时机随之推迟，信息不变 |
| `freeway-flow` 的 52 个属性访问器 `getXxx()` | bare accessor：`getNodes()` → `nodes()`、`getTitle()` → `title()`（Spec 的 `title()` 读 / `title(String)` 写为同名重载）；`FlowContext` 的 keyed lookup 保持 Map 词汇 `get` / `getAs` / `getOrDefault` |
| `PlantumlOptions` / `PlantumlDisplayContext` / `PlantumlDisplayResult`、`Graph.toPlantuml(…)` | `PlantUmlOptions` / `PlantUmlDisplayContext` / `PlantUmlDisplayResult`、`toPlantUml(…)` |
| `CloudHttpClientDefault.Wiring(…, 9 参)` | 规范构造器 10 参（末位 `shutdownGrace`，传 `null` 取默认）；不再保留旧 arity 的委托构造器 |
| `Sql.insert(…).set("col", v)` / `Sql.update(…).set("expr", v)` | `setColumn("col", v)` / `setExpression("expr", v)`——模式写在方法名上，用错模式抛 `IllegalStateException` 并指名另一个方法 |
| `new HttpServerConfig(host, port, backlog, grace[, …])` | `HttpServerConfig.defaults().withPort(…).withBacklog(…)…`（测试代码用 `TestServerConfig.loopback()` / ext testkit 的 `EngineFixture.defaultConfig()`） |
| `HttpServerConfig.builder()…build()` | `HttpServerConfig.defaults().withX(…)` |
| `new WebServer(engine, config, sink, pipeline)` | `WebServerBuilder.builder().engine(…).config(…).route(…)….build()`（ext 测试经 testkit 的 `TestServers.start(engine, config, pipelines)`） |
| `FreewayApp.of(…)` | `FreewayApp.create(…)`（入口点统一用 `create`；`of` 留给"由给定部件造值"的记录工厂） |
| `FlowEngine.newInstance(…)` | `FlowEngine.create(…)` |
| classpath 根的 `freeway-log.properties` | 不读（启动打一行 stderr 提示改名）；改名为 `freeway-logging.properties` |
| `SymbolSource.systemProperties()`（无容器的独立来源） | 删除；独立装配用同一条链 `SymbolSource.of(coercer, SymbolProvider.systemProperties())`（`coercer` 自备 `new CoercerDefault()`），系统属性这一 tier 用 `SymbolProvider.systemProperties()` |
| `PlantUmlOptions.DEFAULT` + `isShowGatewayType()` / `showGatewayType(boolean)`（可变） | `PlantUmlOptions.defaults()`（record 值）；读 `showGatewayType()` / `showIdInTitle()`，改 `withShowGatewayType(…)` / `withShowIdInTitle(…)` |
| flow "No driver found" 的修法文字指向 `newInstance(Map…)` | 指向 `FlowEngine.create(Map…)`（该测试断言同步钉住"不得指向已删 API"） |
| RPC 以 POJO / List / Map 作 handler 参数 | 直接可调用：服务端按声明参数类型重绑定（此前以 `ClassCastException` 出现，且被当作业务异常回报） |
| RPC 参数个数与导出签名不符 | 派发前判为 `Kind.REJECTED`（400 + reject reason）；此前落到业务异常分类 |
| singleton holder 经接口注入 `@NotThreadSafe` 实现 | 启动期拒绝（与直接注入具体类同等）：单例代理恰好缓存一个 target，隔一层接口并不洗掉标记 |
| flow 图 `"version": 2`、`task: "$metaKey"`、`task: "!marker"` | 升到 `3`；静态值写节点 `data` 字段，标记匹配改为带 id 的 contribute + `@name`——旧写法在 `GraphSpec.create()` 报错并指路 |
| `engine.register(TaskComponent)` / `engine.markerIndex()` / `@FlowMarker` | 删除；任务解析只走 `@name` → `container.get(TaskComponent.class, name)` |
| `FlowContainer` 适配缝 | 删除；`FlowDriverDefault` 直接持 `Container` |
| `engine.addInterceptor(i[, index])` / `removeInterceptor` / `FlowOptions.interceptorAdd` / `FlowInvocation` | `FlowInterceptor` 经 `binder.contribute(FlowInterceptor.class)` 贡献，加载成链、之后不可变；`interceptFlow(context, graph, chain)` 收 `FlowChain` |
| `eval(graph, steps, ctx)` / `recordNode` / `FlowTrace` / `ctx.trace()/enableTrace/lastRecord/lastNodeId/interrupt()` / `FlowContext.exchanger()` | 删除——回放-跳过不是耐用执行；停止用 `ctx.stop()`，早完成合法；序列化 `toJson()` 只用于诊断 |
| `NodeType.UNKNOWN` / `NodeType.code()` | 删除；解析为全函数，未知类型名当场报错列出合法值 |

行为变化（无需改调用点，但值得知道）：

- flow 执行改为迭代前沿行走：路径长度不再消耗 JVM 栈（实测 20,000 节点链正常运行），
  `MAX_EXECUTION_DEPTH` 守卫与 `StackOverflowError` 兜底删除；构建期的环检测 DFS 同步改迭代（它此前会在
  同样的深度上先炸栈）。
- flow 的构建门禁扩大：所有 `when` 表达式在 `create()` 编译；`join` 键只允许 PARALLEL、取值限
  `merge`/`shared`；`data` 键非空白。这些过去在运行期才暴露的错误如今 boot 即报。
- flow PARALLEL 默认 `join: "merge"`：分支写入线程本地缓冲、干净结束时合并、写写冲突报错——共享可变
  context 从"文档里的道歉"变成图上声明；`shared` 显式退回旧行为。
- `@graph` 子图未达 END 现在在调用节点抛错，不再静默 interrupt 父流程。
- flow join 计数、loop 迭代器、死端标记收进 `ExecState` 引擎私有命名空间；custom driver 不再有与引擎
  monitor 键同池的字符串袋（`vars()`/root 计数器/泛型 `stack()` 等测试外零调用者的面删除）。

- 接口单例代理的稳态方法调用不再争抢 JVM 级 realize 锁：锁只护首次构造与关闭密封，命中缓存即无锁返回——注释里
  的"cached lookups are lock-free"自此为真（实测此前一次无关慢构造可阻塞其它容器已建单例的调用 1.2s）。
- `Pool.borrow()` 每次返回新的借出句柄（池内对象不变）：对已消费句柄的迟到 `release`/`invalidate` 是静默 no-op，
  不再从当前持有者手里拿走连接；foreign 对象仍拒绝，文案不变。
- enum 参数按 `name()` 绑定（读侧本就 `valueOf`）；此前写侧把原始 enum 交给 `setObject`，H2/PostgreSQL 抛类型转换错误。
- mesh 重连的指数退避按"握手失败"推进、只在 hello ack 通过后清零——接受 socket 但拒绝 hello 的对等节点不再全速重拨。
- 负载均衡器读 `ServiceInstance.weight()`：权重即周期份额，0/负值按 1；zone/canary 仍属自定义策略。
- JSON 数字 token 上限 1000 字符（原 ≤10MiB 合法，而 BigDecimal 解析超线性，单个 token 可占核数分钟）；
  JUL 大小估算对环形 cause 链不再在 handler monitor 内死循环。
- hook 在自己的 `start()` 中调用 `close()`：该 hook 停干净后中止启动，其后的 hook 不再启动（此前 closer 不被停、
  后续 hook 在已关容器上启动且无人再停）。
- provider 返回 null 报 `Provider returned null for X@id`（此前是 `targetCache.put` 的匿名 NPE）。
- HTTP/2 对 HEADERS 后立即到来的 RST_STREAM：流输入先标记结束再拆除，连接读循环不再停摆；stream dispatch 里的
  死 RST 分支删除，复位只有一条路径。

- class 声明是**声明**：同一棵 class-only 树加载进多个容器，每个容器得到新模块；实例/lambda 声明仍是组合期已有的那一个，跨容器共享。
- 模块节点的 `name()` 在 resolve 前是 class 的 simple name，覆盖了 `name()` 的模块只在实例放置时显示自定义名。
- `TreeNode` 随本次收敛删除（Unreleased 新增、从未发布）；应用若直接调 `moduleTree().tree()`，迁移到 `children()` / `render()`。
- 独立装配（无容器）现在是真正的链：`SymbolSource.of(...)` 会展开 `${...}`，`SymbolSpec` 用你传入的 `Coercer`
  解析，`register(SymbolProvider)` 可用 —— 旧的扁平来源三项都不是。含未知 `${...}` 的 `-D` 值现在与容器路径
  一致地报错，而不是原样返回。

### Changed

- **freeway-flow 再设计：从"移植的引擎"到"freeway 的图解释器"（schema 升级 v2→v3）** —
  模块此前自称"port of solon-flow 4.0.2"，注释把"保持上游行为"当目标；机制层还随身带着独立小框架的
  配件（自带 DI 缝 `FlowContainer`、自带标记表 `FlowMarkerIndex`、自带拦截器链 `FlowOptions`+可变
  注册、自带回放-跳过"持久性" `FlowTrace`）。这一轮把所有"自制迷你机制"换成 freeway 已有的答案，
  只留图解释本身：
  - **执行**：递归下降改迭代前沿行走；删 `MAX_EXECUTION_DEPTH` 与 `StackOverflowError` 兜底；深度守卫的
    递归构建期环检测同步迭代化。构建期校验姿态不变式（死端响亮、task_exec 恰一对齐、失败分类）逐条保留并有测试。
  - **机制归位**：拦截器=贡献的 `Extension<FlowInterceptor>`（加载成链，启动后不可变，删
    `addInterceptor/removeInterceptor/FlowOptions/FlowInvocation` 与 COW 排序竞态）；`@name` 直接走
    `container.get(TaskComponent|ConditionComponent, name)`（删 `FlowContainer` 适配缝）；`!marker` 整条删除；
    `ExecState` 收为引擎私有的 (graph,node) 键空间（删 `vars()`、root 计数器、泛型 `stack()`——均无仓内调用者）；
    solon 式公共死面 `Node.TAG`、`TaskDesc/ConditionDesc.isNotEmpty`、`ConditionDesc.attachment` 一并删除
    （上一轮曾以"外部调用者"为由恢复它们；本轮按"兼容不是目标"再删，迁移即 `node.type()` 与 `!desc.isEmpty()`）。
  - **语义诚实**：pause/resume/`FlowTrace`/`steps`/`reverting`/`interrupt()` 删除（回放-跳过不是耐用执行，
    demo 与仓内零使用）；子图未达 END 改为在调用点报错；`FlowContext.put(null)` 从静默丢弃改为清除语义，
    LOOP 的 remove 变通随之删除；`toJson()` 文档改口为诊断用。
  - **schema v3**：task 词汇表封闭为 `@name`/`#graphId`/内联组件 + 节点 `data` 字段（替 `$meta` 魔法键）；
    `when` 表达式在 `create()` 编译；`PARALLEL` 节点新增 `join` 声明（`merge` 缺省：分支写隔离+冲突报错，
    `shared` 显式退回）；`NodeType.UNKNOWN` 哨兵与 `code()` 删除。v1/v2 文档格式与旧词汇命中即构建期报错并指路。
  - **文档**：`package-info` 与 README 从"移植说明文"改写为语义规格（保留 Apache 2.0 来源标注）；
    现行 schema 规格落在 `freeway-flow/docs/graph-v3.md`，`graph-v2.md` 标注为历史提案；
    `migration-notes/assessment/plan` 保留为带日期的轨迹记录。
- **SymbolSource 收敛为一条链，系统属性成为一个 SymbolProvider（freeway-ioc）** — 此前 SYS tier 在 ioc 内部
  有两个实现，而且语义不同：容器链里是一个 `SymbolProvider`（`order()=TIER_SYS_PROPS`，由
  `SymbolSourceDefault.standard()` 匿名构造），独立来源 `SymbolSource.systemProperties()` 则是另一份扁平实现
  —— 它不展开 `${...}`（javadoc 还把这个差异写成"没有可展开的对象"，可一条只有一个 tier 的链照样能对 `-D`
  做展开）、自带一个与容器无关的 `CoercerDefault`、`register()` 直接抛异常。现在 tier 只有一处定义：
  `SymbolProvider.systemProperties()`；链也只有一处实现：`SymbolSource.of(Coercer, SymbolProvider...)`
  （`SymbolSourceDefault` 随之从 `ioc.internal` 移入 `ioc.symbol`，保持包私有，改为构造时接收 `Coercer`，
  删掉事后注入的 `coercer(...)` setter 与只服务于它的 `standard()`）。
  `ContainerImpl` 用 `SymbolSource.of(coercer, SymbolProvider.systemProperties())` 起链，传入的就是容器自己
  那个 `Coercer`，于是模块贡献的 `CoerceRule` 对 `resolve(SymbolSpec)` 同样生效（这条路径此前没有任何测试，
  新增 `SymbolSourceSpecTest`：容器路径的 `resolve(SymbolSpec)` 与贡献规则各一例）。
  独立装配不再有第二种语义，ext 的 Jetty / Undertow / Hikari 三个无容器构造器各改一行，API 面因此变小而不是
  变大 —— 删掉一个"看起来像 source、其实关掉了半条链"的入口。

- **批次 B（第六批）：入口工厂统一到 `create`（freeway-boot / freeway-flow）** — 审计 B3。框架入口此前用两个
  动词：`Freeway.create(...)` 与 `FreewayApp.of(...)` 是并列入口却各叫各的，`FlowEngine.newInstance()` 又是
  第三种。现在按角色分工并把规则写进 `AGENTS.md`：**`of` 造"值"**（记录工厂：`Endpoint.of`、
  `ServiceInstance.of`、`SymbolSpec.of`、`ModuleNode.of`，对齐 `List.of`）；**`create` 是框架入口**，交给你
  一个待配置或待运行的东西（`Freeway.create`、`FreewayApp.create`、`FlowEngine.create`、`Graph.create`）；
  **`.builder()`** 是配置型对象的流式装配（`WebServerBuilder.builder()`）。
  改名：`FreewayApp.of(...)` → `FreewayApp.create(...)`（4 个重载 + 69 处调用点）、
  `FlowEngine.newInstance(...)` → `FlowEngine.create(...)`（2 个重载 + 24 处）。选这一侧而不是把
  `Freeway.create`（306 处调用点）改名为 `of`：同样的结果，三分之一的改动量，而且 `Graph.create`/
  `GraphSpec.create` 本来就已是"入口用 create"。

- **批次 B（第五批）：`Orm.findAll` 的位置哨兵收成 `FindOptions`（freeway-db）** — 原签名
  `findAll(Class, String orderBy, int limit, int offset)` 的两个相邻 `int` 在调用点可互换：
  `findAll(type, "id ASC", 20, 0)` 与 `(…, 0, 20)` 都能编译而语义相反；`""`/`0` 还同时兼任"未设置"。
  现在 `Orm.FindOptions.defaults()` + `withOrderBy`/`withLimit`/`withOffset`（一个 wither 只动一个字段），
  空白 `orderBy` 归一为"无 ORDER BY"，**负的 limit/offset 当场抛**（旧实现用 `limit > 0` 判断，负值被
  静默当成"不限"，一次笔误就变成全表返回）。`findAll(Class)` 保留为 `defaults()` 的便捷形态；
  11 处调用点按编译错误迁移（db 测试 10 处 + ext benchmark 1 处）。

- **批次 B（第四批）：`PeerConnector` 的 7 位置参数收成 `Wiring`，`PoolConfig` 补 wither（freeway-cloud / freeway-db）**：
  - `PeerConnector(PeerHub, Duration connectTimeout, String scheme, Duration handshakeTimeout, long backoffBaseMs, long backoffMaxMs, SSLContext)`
    换成 `PeerConnector(PeerHub, Wiring)`。原签名里有**两对相邻同类型参数**（两个 `Duration`、两个 `long`），
    互换任意一对都能编译，代价是网格按错误的节奏超时或退避。`Wiring.defaults()` 的每个默认值都取自
    `freeway.cloud.event.*` 的声明默认（新增 `CONNECT_TIMEOUT` 常量，与配置键同源），
    `withBackoff(base, max)` 一次给两个值——互换在调用点不再可表达；唯一的装配点
    `CloudEventLifecycleHook` 收敛成一条 wither 链。
  - `PoolConfig`（12 组件）补 12 个 `withX`，类 javadoc 的示例从"12 个位置参数"改成
    `defaults(url, user, pass).withMaxSize(20)…`；删掉 `DEFAULT_HEALTH_CHECK_QUERY = null`
    （"不设查询"不需要一个 null 常量，规范构造器传 `null` 即可，注释说明 `isValid` 决定）。校验仍在紧凑
    构造器里，所以每个 wither 都会重新校验。
- **测试补两条契约**：`PeerConnector.Wiring` 的默认值/归一化/`withBackoff`，以及 `PoolConfig` 的
  "defaults 说全 + wither 只动一个字段 + 每次都重新校验"。反向检查都做了：把 `defaults()` 里两个
  `Duration` 互换 → 用例红（`expected: <PT3S> but was: <PT10S>`）；让 `withMinIdle` 顺手改 `maxSize` →
  用例红（`expected: <20> but was: <5>`）。

- **批次 B（第三批）：两处"位置参数矩阵"收成参数记录（freeway-db / freeway-http）**：
  - `MigrationRunner` 的两个构造器（4 参与 5 参，后者只多一个 `lockTtl`）换成
    `MigrationRunner(Database, Options)`；`Options` 记录持有 `enabled`/`path`/`table`/`lockTtl`，
    `defaults()` 是**唯一**的默认值出处（此前 `DbModule` 把 `"db/migration/"`、`"_migrations"` 又写了一遍），
    归一化（反斜杠、尾斜杠、表名白名单）搬进紧凑构造器，`withLockTtl(null)` 恢复默认租约。24 处调用点按
    编译错误迁移，`new MigrationRunner(db, true, "db/migration", "_migrations")` 变成
    `new MigrationRunner(db, Options.defaults())`——位置布尔 `enabled` 不再需要读者去数参数。
  - `FreewayHttpEngine` 的 5 个位置构造器换成 `FreewayHttpEngine.Wiring`：它不是"每步加一项"的梯子，而是
    可选参数矩阵——第 3 档加 metrics，第 4 档 `(codec, coercer, ssl, http2)` 又把 metrics 悄悄重置成 noop。
    现在 `Wiring.defaults(jsonCodec, coercer)` + `withSsl`/`withSslParameters`/`withMetrics`，一个 wither 只动
    一个字段；`WebServerBuilder` 的三元表达式与 `HttpModule` 的两条路径随之收敛成一条链。
- **测试补两条契约**：`MigrationRunner.Options` 的默认值/归一化/`withLockTtl(null)`，以及 `Wiring` 的
  "wither 互不覆盖"（用非 noop 的 metrics 替身，否则"被重置"与"被保留"无法区分——这条断言最初正是
  因为传了 `NoopMetrics` 而形同虚设）。

- **批次 B（第二批）：旧 arity 便捷构造器与只被测试引用的 internal public 类型（freeway-commons / boot / cloud）**：
  - `JULFileHandler(String, long, int, boolean)` 删除：它是"比规范构造器少一个 `flushIntervalMs`"的便捷形态，
    与前面几轮删掉的同类构造器是一回事；21 处测试调用点改传
    `JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS`（语义完全一致，只是把默认值写在调用点上）。
  - `HookLifecycle`、`ConfigFileReader`（boot）与 `ServiceIdentity`（cloud）由 public 收成包私有：按
    `AGENTS.md` 的判据（"另一个包必须装配或替换它才是 public"），它们只有同包使用者——先前的引用计数把
    `AppRuntimeDefaultTest` 里的一句**注释**当成了引用。
  - `ConfigSources` 保持 public，并在 `boot/internal/package-info.java` 写明理由：跨模块测试
    （`CloudSymbolPrecedenceTest`）需要直接装配分层来源来钉住级联优先级，这是"测试装配"而非"容器装配"的
    唯一一处例外。

- **批次 B（第一批）：导出面、别名、bind 期校验、JSON 访问器与缓存泄漏（freeway-cloud / http / ioc / commons）** —
  审计 §6 批次 B 的前五项：
  - **RPC 导出面以申报类型为准**（cloud）：`RpcTarget` 原先进 `handler.getClass().getMethods()`，于是
    `bind(窄接口).to(宽实现)` 会把实现类多出来的 public 方法一并导出——与"导出即申报"的姿态相反。现在读
    `export.type()` 的方法面；`RpcExport` 的 javadoc 统一成"类型即边界"（想要更小的面就声明 facade 类型）。
    新增用例直接断言"不在申报类型上的方法不进方法表"。
  - **`ResponseFraming.shouldGzipFile` 删除**（http）：它是 `shouldGzipStream` 的纯转发，而 javadoc 声称
    多一道 `body-allowed` 闸门（`shouldGzipStream` 本来就查）——一个没有行为的别名配一段不成立的说明。
    唯一 core 调用点改名，测试里重复的那组断言删除。
  - **`.advise()` 在 bind 期校验接口约束**（ioc）：约束在 `bind(GreeterImpl.class).advise(...)` 当场就已知，
    原先拖到首次 `get()` 才抛，错误信息落在一个与起因无关的调用点上；现在抛在 binding 调用处并给出修法
    （"bind X to an interface to use .advise()"）。`Scoping` 补 `default void within(Runnable)`，测试里 8 处
    `within(() -> { …; return null; })` 的尾巴随之消失。
  - **构造器缓存不再钉住类加载器**（commons）：`BeanIntrospector` 用
    `WeakHashMap<Constructor, BeanConstructor>` 存包装器，而 value 强引用 key——条目永不回收，凡走过的类
    与其类加载器都被静态 map 钉住。改为按声明类分片的 `ClassValue<Map<…>>`（与 `JsonCoercions` 的做法一致），
    类加载器卸载即可回收；新增用例钉住"同一 Constructor 只包装一次"。
  - **JSON 写方法改名、类型不符不再静默**（commons）：`JsonObject.object(key)`/`array(key)` 与
    `JsonArray.object()`/`array()` 是**写**操作（建子节点并挂上去），却与读方法 `getObject`/`getArray` 只差
    一个 `get`；改为 `newObject`/`newArray`/`addObject`/`addArray`。`JsonAccessors.object/array` 原先在
    "键不存在"与"键存在但类型不符"两种情况下都返回 `null`，现在只有缺失返回 `null`，类型不符抛
    `IllegalArgumentException`（与其数值/布尔兄弟一致，错误信息用 `JsonUtils.typeName` 说出实际类型）。

- **ioc：可替换的角色按"外部能否 `.primary()` 替换"命名，贡献链也按被解析的实例走（freeway-ioc）** — 审计 A8：
  - `SymbolSourceImpl` → `SymbolSourceDefault`，`LoggerSourceImpl` → `LoggerSourceDefault`：两者都能被模块用
    `.primary()` 顶掉（`InjectionResolver` 的注释原本就这么写着），按判据它们是 `XDefault`，不是 `XImpl`。
    `AGENTS.md` 把这把尺子写成一句话——"模块 `.primary()` 绑定后容器是否认账"，并明确"框架自己具体装配它"
    不构成 `XImpl` 的理由。
  - **替换 `SymbolSource` 不再静默丢掉配置链**：容器原先把 `SymbolProvider` 贡献注册进自己的内建实例，而
    注入端走 `container.get(SymbolSource.class)`——模块一旦替换源，boot 的整条级联（CLI/env/文件）就落进
    一个没人读的实例。现在贡献会记入一份有序清单，并在**所有模块绑定完成后**（模块自己的绑定要等 `bind`
    体跑完才注册，声明期看不到）回放进真正被解析的那个源；内建实例仍在声明期即时收到，bind 期查询不受影响。
  - `SymbolSource.register(SymbolProvider)` 成为接口上的贡献 seam，默认实现**抛错**：接不了贡献的替换实现
    在启动时就报出来，而不是安静地少一条链。`SymbolSourceReplacementTest` 两半都钉住。

- **`HttpServerConfig` 收成一种写法：规范构造器 + `defaults()` + 每字段 wither（freeway-http）** — 13 组件
  记录此前同时挂着**4 级委托构造器阶梯**与一个内嵌 `Builder`，而且默认值声明了三遍并已漂移：`Builder` 说
  port 0 / grace 0，模块声明的 `freeway.http.*` 与 `WebServerBuilder` 说 8080 / 2s。现在只剩规范构造器
  （校验仍在一处）+ `defaults()`（host 127.0.0.1、port 8080、backlog 0、grace 2s 加各库默认值）+
  13 个 `withX`；`HttpModuleConfig` 的键默认与 `WebServerBuilder` 都改为引用它，构造器阶梯、`Builder`
  与字符启发式判定全部删除。调用点按编译错误迁移（core 测试约 60 处、ext 约 25 处），测试专用形状收进
  `freeway-http` 测试源的 `TestServerConfig.loopback()` 与 ext testkit 的 `EngineFixture.defaultConfig()`。
- **`WebServer` 只剩一个构造器，且它按真实判据回答 `secure()`（freeway-http）** — 原来的 public 4 参构造器
  把 `secure` 写死为 `false`：任何经它建起来的 TLS 服务器，`secure()` 都会回答"不是 TLS"——ext 的 TLS 测试
  正踩在这条上（生产路径走 `HttpModule` 的 6 参，所以线上没受影响）。现在唯一公开装配路径是
  `WebServerBuilder`（按传入的 `SSLContext` 判定 `secure`）。ext 的 6 个适配器测试文件随之改为走 testkit 的
  `TestServers` + `Pipelines`（原先手搭 `RequestComponents`，与真实装配路径不同，也顺带是 ext 审计 §9.4
  的待办）；`Pipelines` 扩展出 error handler 一档，避免迁移时丢掉 413 映射与异常捕获这两类断言。

- **flow：v1 残留清干净、失败信息给出修法、快照语义对齐（freeway-flow）** — 审计 P1/P2：
  - `NodeType` 的"缺/空 type 默认为 ACTIVITY"删除：v2 解析器先经 `requireString` 保证非空，这个默认
    分支不可达，留着只会让未来的调用方得到一个静默错误的节点类型；同时按工厂命名把 `nameOf` 改名为
    `of`（未知类型抛错并列出合法值），`code()` 补上用途说明。
  - `FlowMarkerIndex.register` 对**空标记集**从静默 `return` 改为一行 WARN 并点名修法：lambda 与匿名类
    拿不到 `@FlowMarker`，这正是丢 handler 的常见方式，而失败原本要到 eval 才以"没有组件匹配"暴露。
    `freeway-flow/README.md` 的示例也据此改成 `engine.markerIndex().register(component, Set.of(...))`
    ——原示例用 lambda 注册却引用 `!marker` 任务，照抄必然失败。
  - 组件解析、`$in` 解析、`Graph.nodeOrThrow`、`GraphSpec.validateEntry` 的错误信息补上节点/图上下文与
    期望形态（原先有 `"The task component 'x' not exist"` 这类不成句且不给修法的消息）。
  - `GraphSpec.fromDom` 收成包私有（`fromText` 是唯一版本门禁）；`FlowEngineDefault.graphs()` 返回
    `List.copyOf` 快照，与 `FlowTrace`/`FlowMarkerIndex` 的快照约定一致；`FlowTrace` 的
    `setRootGraphId`/`enable` 改为 `rootGraphId(...)`/`enabled(...)`，与模块内 bare 写方法一致。
  - 文档：README 的快速开始补 `version: 2`（原示例缺该字段，门禁直接拒绝）并删掉"v1 仍兼容"的说法；
    `migration-notes.md`、`graph-v2.md` 标注 v1 已删除；`docs/freeway-flow-design-decisions.md` 的 AST
    缓存条目改为现状（`synchronizedMap`，并写明为什么读写锁被撤回）。
  - **有意保留并写进设计决策**：flow 状态文档里 `trace` 的字符串形态仍可读——那是应用已经持久化的
    历史数据（与迁移表里旧版本写入的行同类），不是 API 兼容；附了移除条件。

- **文档：改正与实现相反的 17 处说明（全仓）** — 审计里最普遍的一类问题不是设计错误而是"文档说假话"，
  逐条改正：http 的 `HttpResponse` 两处叠置 javadoc（悬空那段声称 `contentLength` 必须已知，实际允许
  `-1` 走 chunked）与指向不存在方法（`request()`/`response()`/`status(s)`）的引用；cloud 的事件网格
  hook **排序三处说反**（注释说 before，代码是 after，且理由写在代码里）与 readiness 的"always healthy"
  （实现有三种非健康答案）；db 的 `DatabaseHub` "unmodifiable view"（实际是构造期快照）；ioc 的
  `ServiceRuntime` "64 段锁条带化"（实际单锁，同文件自述相反）与 `Contributions` 的 id 形态（实际
  `snake@package`）；boot 的级联 javadoc 少列 `-D` 层、`AppRuntime`/`AppState` 补上生命周期契约；
  commons 的 `JsonUtils` 补契约（含"会关闭入参流"这条隐式副作用）；`AGENTS.md` 的 SLF4J 声明口径与实测
  对齐（6/7 模块显式声明，只有 ioc 继承）。

- **boot：配置文件层的读失败只剩一种口径，profile 变体不再能改写激活键，组合期失败也清理 config（freeway-boot）**
  — 三条审计 P1：
  - 同一个"读不到文件"此前有三种命运：类路径与工作目录 base 硬失败；工作目录 profile 变体与
    `freeway.config.file` 附加文件只 WARN、随后整份文件的键消失；显式声明的路径不存在则完全无声。现在：
    **文件存在但读不出来 → `IllegalStateException`**（点名路径与修法），热重载期由 watcher 保留旧快照；
    `freeway.config.file` 声明却不存在的路径 → 一行 WARN 点名；可选的工作目录文件缺失仍按常态静默。
  - `overrideFiles` 改为带**角色**（`BASE` / `PROFILE_VARIANT` / `DECLARED`）：profile 变体的
    `freeway.profile` 与类路径侧一样被剥离，`profiles()` 与解析值不再可能分叉——`AppConfig` 的
    "cannot disagree" 承诺此前只兑现了一半（只做了类路径侧）。
  - `AppBuilder.start()` 的**组合段移进 try**：同模块类声明两次、或坏 SPI provider 抛出时，同样会
    `config.close()`；否则调用方每次"捕获后重试"都会泄漏一个热重载 watcher（线程 + `WatchService`）。

- **`Coercer`：Number→boolean 不再按 `int` 截断判真假（freeway-commons）** — `0.5`、`0.9`、
  `4294967296L`（2³²）这类"非零但整数部分为零"的值此前静默变成 `false`（实现是 `n.intValue() != 0`），
  而同一文件的整数路径恰好把这个模式列为 corrupting data 并明令禁止。现在按精确十进制量值判定；两个非
  有限来源保持已声明行为（NaN 为 false、±Infinity 为 true，后者原先靠饱和恰好为 true，现在显式写出）。
  **行为变化**：`0.5` 由 `false` 变 `true`——这是一次静默错值的修正，不是口味调整。
- **日志：读值策略统一、键名集中、MDC 不再预设应用字段（freeway-commons）** — 三处：
  - 同一批 `freeway.log.file.*` 键此前在两条读路径上策略相反：框架路径把非法值静默换成默认值，原生注册的
    `JULFileHandler` 直接抛。现统一为"**报出并回落**"：一行 `logEarly` 点名键名、原值与实际采用的默认值；
    既不抛（抛会在 `LogManager` 实例化时丢掉整个 handler），也不静默（静默是笔误活过一周的方式）。
  - `freeway.log.*` 的键名集中到 package-private `LogKeys`：此前 23 处字面量散在五个类，改名只会静默失配，
    现在改一处、漏改即编译失败。
  - `freeway.log.mdc.priority` 的默认值清空：框架不再把某个应用的字段名（`code,market,diagId`）当作自己的
    默认值发布进文档；未配置时所有 MDC 键按字母序，要突出的键由应用用 `-D`/env 声明。

- **设计规则修订：兼容不是目标（全仓）** — 上一轮写进 `AGENTS.md` 的"记录作为适配器装配点时保留旧 arity 的
  委托构造器"删除：它保护的是**已编译的调用点**，代价是每个新组件都在规范构造器旁留一条旧路径，读者永远
  要问"该用哪个"。规则反过来——新增组件直接改变规范构造器的形状，**编译错误就是迁移路径**，`freeway-ext`
  同批适配。随之清掉两处已发现的残留：
  - `CloudHttpClientDefault.Wiring` 的 9 参委托构造器（"shutdown grace 之前的那一版形状"）删除，唯一调用点
    （ext 的 `RemoteRpcContract`）改传 10 参。
  - `freeway-log.properties` 的过渡读取删除：旧名只被**检测**、不被加载，`JULEnhancer.renamedFileNotice()`
    在启动时打印改名提示——配置不被采纳，但绝不静默失效。契约由 `JULEnhancerLogSourceTest` 钉住。
  同时明确**不删**的两处，因为它们不是 API 兼容，而是外部世界里已经存在的事实：迁移校验的双轨 checksum
  （库里已记录的行、CRLF 检出）、`CoercerDefault` 的时长输入形式。
  - **稳定的定义随之写明**（`AGENTS.md`）：稳定性落在**语义**上，不落在**形态**上——冻结的签名配上漂移的
    行为是最坏的不稳定，它绕过编译器直达生产；适配是个"读得见"的问题，使用方（或 agent）只要能看到改了
    什么就能跟上，所以变更记录属于变更本身。三条判据：行为变化必须在 CHANGELOG 里有"为什么"；形态变化必须
    在编译器里响（**只有 clean 编译算验证**，陈旧 `target/` 会把它吞掉，`Wiring` 那次就是先假绿）；值得付
    代价的破坏让调用方代码**更少或不变**，只换名字、加参数、搬概念的破坏是抖动而非进化。

- **`WebServer` 的构造器阶梯由 4/5/6 收成 4/6** — 包私有的 5 参构造器（只比 4 参多一个
  `readinessProbe`）在仓库内已无任何调用者：core 的装配点（`HttpModule`、`WebServerBuilder`）都直接走
  6 参，4 参为外部适配器保留。删掉它不改变任何行为，也让"公开 4 参 / 内部 6 参"的边界更清楚；
  其余两项复核结论见 docs 审计记录（`HttpContextImpl.reset` 的 10 参与 `ServiceRegistry.drainWindow()`
  经核实仍应保留）。

### Added

- **审计记录：`docs/audit-philosophy-modernity-1.5.2.md`（理念一致性 + 现代性）** — 七个核心模块的量化
  普查（访问器风格、JDK 25 惯用法、公共面无死代码、internal 收口、单文件规模）+ 跨模块与分模块的分级
  发现：29 条 P1 全部带 `文件:行`，其中 22 条经二次复核（另标出三处量化假阳性与一处被模块反例撤回的
  结论）。**全轮唯一正确性缺陷**是 `CoercerDefault` 的 Number→boolean 先 `intValue()` 再判真假
  （`0.5`/`4294967296L` 静默变 `false`）；其余集中在"文档说假话"（17 处）与"同一件事两套策略"。
  文档末尾给三批修复清单（A 一处改到位 / B 形态统一 / C 观察择机）与可复现的统计口径。

- **`@SubModule`（freeway-ioc）** — bundle 声明：模块类上列出随它一起放置的子模块（前序、按声明
  顺序），构建树时展开成 `ModuleNode` 子树。`ModuleNode.of(Bundle.class)` 即整包；子模块是普通
  模块，`ModuleNode.of(SubModule.class)` 即子集，因此不需要排除 API。`@SubModule` 成环在构建树
  时报错。`CloudModule`（freeway-cloud）用它声明 8 个标准云模块，取代 `CloudModules.standard()`
  工厂。
- **`SslSettings` / `SslContexts`（freeway-http 根包）** — `freeway.http.ssl.*` 现在只声明一次：
  `SslSettings.from(SymbolSource)` 读取全部 TLS 键（enabled / key-store(+password/type) /
  trust-store(+password/type) / client-auth / protocols / ciphers / sni-directory / reload-interval /
  http2）并做同一个三态判定（显式 true/false 优先，未设则看 keystore 是否存在，全空即明文）；
  `SslContexts` 由内部 `SslContextFactory` 提升而来，公开 `build(SslSettings)` 与
  `parameters(SslSettings)`（keystore/truststore 装载、SNI 多证书、协议与密码套件限制）。
  此前这段"键读取 + 三态"在内置引擎的 `HttpModuleConfig` 与两个适配器里各写一遍；现在内置引擎、
  Jetty、Undertow 共用同一份，`internal.HttpModuleConfig.Ssl` 与 `internal.SslContextFactory` 删除
  （`SniKeyManager` 移入根包）。适配器只保留各自独有的键（Jetty 的 `ssl.key-password`/`key-alias`）。
- **修复 `SymbolSource.systemProperties()` 的 SymbolSpec 解析**：该独立来源此前只有
  `resolve(String)`，遇到按 `SymbolSpec` 读取的适配器会抛"has no parser"——容器路径正常、直接构造
  失败。现在与容器链一致，内部接一个 `CoercerDefault` 走 `spec.parse(raw, coercer)`。
- **给外部适配器复用的三个 core 缝隙** — 两个 HTTP 适配器（Jetty/Undertow）的归一化对比显示，
  它们的重复里有三块并不含引擎 API，只是 core 对自家公开面缺少支撑，于是同一段逻辑存在三份
  （含内置引擎一份）。现在下沉到 core：
  - **`Compression`（freeway-http）** — `acceptsGzip(List<String>)` / `acceptsGzip(String)` +
    `gzip(byte[])`。放在根包 `MediaTypes` 旁边（同样的理由：让内置引擎与外部适配器共用一份判定，
    而不是各写一份字符串扫描）。语义统一为：缺头即"无偏好"不压缩、`gzip` 开启、`gzip;q=0` 拒绝、
    `q` 参数名大小写不敏感、畸形 `q` 视为接受。此前内置引擎、Jetty 适配器、Undertow 适配器各有一份
    `acceptsGzip`/`qValueIsZero`/`gzip`。
  - **`AbstractWebSocketSession`（freeway-http.websocket）** — 会话的"请求标识 + exchange 元数据"
    半边：correlationId/startTime/principal/attributes、method/path/pathVar(s)/queryParam(s)/header(s)，
    映射一律做不可变深拷贝。实现者只再实现引擎相关的帧操作。与 `AbstractHttpContext` 对 HTTP 交换
    的分工完全同构；内置引擎的 `WebSocketSessionImpl` 与两个适配器的会话都改继承它。
  - **`SymbolSource.systemProperties()`（freeway-ioc）** — 只读 JVM 系统属性的独立来源（缺键时严格
    访问器抛 `UnknownSymbolException`、宽松访问器回落默认值、`expand` 原样返回）。只有机制、不含任何
    键名，因此放在 `SymbolSource` 上，而不是让每个"既有容器路径又要能独立构造"的适配器各写一遍
    （此前 Jetty/Undertow/HikariCP 三份）。
- **修复 `closeReason` 的 UTF-8 截断**：把关闭原因裁到 123 字节时按字节硬切，可能切在多字节字符中间，
  替换字符（U+FFFD）回编码后是 3 字节，于是"裁到 123"反而可能重新超过 123 字节（实测 125 字节），
  严格的对端会拒收。现在回退到码点边界，结果保证 ≤123 字节且不出现替换字符。两个适配器此前都有这个
  缺陷，随共享实现一并修掉。
- **`ModuleNode`（freeway-ioc）** — 模块组合的值类型：`app(name, …)` 应用根、`of(class | module)`
  模块节点（自带声明：class 或实例）；一个模块类用 `@SubModule` 声明子模块时，`of` 展开为 bundle
  子树，子模块仍可单独 `of` 放置取子集。
  **常规写法是给 class**：`of(OrderModule.class)`、`app("orders", OrderModule.class,
  HttpModule.class)`、`FreewayApp.run(OrderModule.class)`、`AppBuilder.add(Class…)` —— 类由组合
  显式点名（没有任何扫描），经**无参构造在加载时**实例化；构造器带参数的模块才传实例
  （`of(new TenantModule("acme"))`），因为模块的构造器承载的是配置而不是依赖（容器尚不存在，
  没有东西可注入）。没有无参构造却按 class 声明时，在加载时报错并同时点名类与修法；同一个
  class 声明两次与"同 class 两个实例"同样报错（修法：只声明一次，共享请用同一棵节点值）。
  `bindOrder()` 给出模块节点的前序（节点自带 `type()` / `instance()` / `resolve()`），
  `render()` / `children()` 给出结构，`classes()` 给 SPI 发现看"哪些 class 已在树里"，
  `isApplication()` 让入口复用调用方建好的根。
- **`Pool.invalidate(PooledConnection)`（freeway-db）** — 连接池 SPI 上的"销毁"语义：池必须物理
  关闭该连接并释放它占用的槽位，而不是回收给下一个借用者。此前"这个连接不能再用了"无处表达，
  只能 `conn.connection().close()`：对自建池恰好等于销毁，对交出代理的池（HikariCP）却只是
  "回滚 + 重置状态 + 回收"，于是"状态无法复原的连接必须销毁"的三条路径（`Database.transaction`
  的状态复原、`BatchQuery` 的 autoCommit 复原）在适配器下退化为静默回收。现在这三处改调
  `invalidate`：`PoolDefault` 销毁并释放配额，`HikariPool` 走
  `HikariDataSource.evictConnection`。已归还/已销毁的句柄是幂等空操作（清理路径可以放心调用），
  外来句柄与 `release` 同样报错。`Pool` 的替代实现需跟进该方法。

### Changed

- **模块树收敛为单一类型 `ModuleNode`，bundle 由 `@SubModule` 声明，class 声明改为加载期实例化
  （freeway-ioc + freeway-boot + freeway-cloud）** — 此前同一棵树有三套表示：
  `ModuleNode` 包装通用 `TreeNode<ModuleEx>`，结构节点由实现 `ModuleEx` 的假模块 `GroupModule`
  充当（`isStructural`、`classes()` 剔除、`bindOrder` 计入分组等补丁随之而来）；且 class 形态在
  `of(Class)` 当场实例化，树里存的永远是实例。现在 `ModuleNode`
  自己就是不可变树，只有两种节点：应用根（命名、不绑定）与模块节点（自带声明：class 或实例，
  `type()` / `instance()` / `resolve()`），不再经过 `TreeNode`，也不再有假模块与"命名分组"——
  一个库要打包多个模块时，bundle 类用 `@SubModule` 声明子模块，构建树时展开（静态元数据，不是
  回调模块）；子模块是普通模块，可单独放置取子集，因此没有排除 API。`bindOrder()` 只含模块节点
  （应用根不绑定，启动日志的模块数因此准确），结构展示由 `render()` 负责。class 声明不再在组合期
  实例化：容器加载时（`BinderImpl.load`）对每个节点调 `resolve()` 才构造，此后才 `bind`。由此结构
  校验（重复声明、双路径报错、`@SubModule` 环）先于任何用户构造器完成，class-only 的树/片段可被
  多个容器重复加载、每次得到新模块；lambda/匿名模块是实例声明（捕获状态无法重建），照旧只按
  identity 去重、不参与 class 身份规则。`ModuleNode.group(...)`、`of(…)` 三个工厂与 `tree()`
  访问器删除，`ModuleNode.module()` 由 `type()` / `instance()` / `resolve()` 取代，无参构造失败
  的报错从组合期移到加载期（信息不变）；`Freeway.create(Collection)`（仓库内无调用者）一并删除。
  cloud 侧 `CloudModules.standard()` 工厂删除，恢复为 `CloudModule` + `@SubModule`（绑定全在子模块，
  bundle 本身留作共享面）。`AppBuilder` 不再"攒 composed 列表再重建树"：直接累积子节点，应用根
  最多一个（第二个显式失败，过去是静默嵌套），名字与子节点各归其位。发现路径的
  `ServiceConfigurationError`
  捕获范围同时修正：过去只包住循环体，而 provider 加载失败发生在 `hasNext()` / `next()`，带
  classloader 上下文的报错在最常见失败路径上不会生效。
- **`freeway-flow` 访问器命名 Freeway 化（破坏性）** — 移植自 solon-flow 的 52 个属性
  JavaBean `getXxx()` 改为 bare accessor：`graph.nodes()` / `spec.title()` /
  `node.meta(key)`；`FlowContext` 的 keyed lookup 保持 JDK `Map` 词汇
  （`get` / `getAs` / `getOrDefault`）；`Plantuml*` 统一为
  `PlantUml*`（含 `Graph.toPlantUml`）。JSON/图定义格式与运行时行为不变，纯命名对齐；上游
  来源与署名保留在 `package-info`，并在其中注明"属性访问器已 Freeway 化"的有意分叉。
- **公共面边缘清理（freeway-commons + freeway-ioc + freeway-boot）** — 随模块树收敛一起删掉
  已无角色的公开成员：`TreeNode`（Unreleased 新增，模块树改值后 core 与 freeway-ext 零引用）
  及其测试；`EventBus` 的 `enableInboundDeduplication(int)` / `disableInboundDeduplication()`
  合并为 `inboundDeduplication(int)`（容量 ≤0 即关闭并释放窗口）；`FreewayApp.run()`（无参）
  删除，用 `run(new String[0])`。`ModuleNode.app(String)` 保留：它是一参形式的歧义消解
  （`app(String, ModuleNode...)` 与 `app(String, Class...)` 无法单靠 varargs 区分）。
- **配置分类规则 + 逐键档位，并修正 16 处文档/样例不一致（docs/freeway-config.md）** — 配置面此前只有"决策键 /
  默认最优 / 高级"三档，把声明键、机制键和静默调优键混在同一档里。现在写明规则本身：四个判据（没有合理默认？
  默认只是开发姿态？只在关特性时才写？纯调优数字？）落到六档（**必填 / 决策 / 姿态 / 调优 / 声明 / 机制**），
  再用形态后缀标注值从哪来（`·auto` / `·presence` / `·哨兵` / `·聚合闸`），126 行键表新增"档"列；新增
  "配置文件怎么组织"一节：默认单文件 + profile 作环境轴，按模块拆分是可选约定（三条纪律，配 `ConfigMaps`
  的跨文件重复键告警）。修正的不一致里三处照抄即错：日志文件调优键写成 `freeway.log.max-size`（真名在
  `log.file.*` 下，静默失效）、prod JSON 样例的 `freeway.cloud.events.*`（真名
  `freeway.cloud.event.*`，8 个键静默
  失效）、dev 样例 `allowed-origins=*` 配 `allow-credentials=true`（`CorsFilter` 构造即抛异常）；prod
  properties 样例的空 `db.username` 改为占位值。其余为类型与标注修正：`cors.max-age` 是 Integer、四个 CORS
  键是 `List(String)`、`log.file.flush-interval` 是 Long；`ssl.key-store-password` 的"生产是必填"改为
  "启用 TLS 时"（代码不预检）；删掉重复的 `db.query-timeout` 行；补上 `freeway.app.name` 与三个进程级键
  （`-D app.name`、`-D slf4j.provider`、`NO_COLOR`）。日志章节改成实话：`log.color` / `log.mdc` /
  `log.mdc.priority` / `log.caller-info` 只在 `-D`/环境变量生效，写进两个文件家都静默无效。
- **新增 `ConfigDocsConsistencyTest`（freeway-boot）** — 从两个方向钉住配置面：样例里出现的每个 `freeway.*` 键
  必须在 `docs/freeway-config.md` 有键行；文档里的每个键必须被某个模块的源码读得到（键的发现方式与框架一致：
  字符串字面量，加 `PREFIX + "suffix"` 拼接的常量）。前者会在写下 `freeway.log.max-size` 这类名字时失败，
  后者会在文档承诺一个没人读的旋钮时失败。仓库内运行时生效，模块单独构建时自动跳过。

- **注册身份的 `auto` 推导：scheme / host / drain（freeway-cloud + freeway-http）** — 三个键的默认值都改成
  `auto`，因为它们的正确值在这台机器上才成立、静态写不出来；三者都在启动日志里说明选了哪个：
  - **`registry.service-scheme`**：新增 `WebServer.secure()`（`HttpModule` 传入解析后的 SSL 判决，
    `WebServerBuilder` 传入是否提供了 `SSLContext`），`auto`（默认）跟随 HTTP 服务器是否启用 TLS。
    注册的 `http/https` 与事件网格拨号的 `ws/wss` 现在出自**同一次推导**（网格读的是 `HttpServiceDeclaration`
    解析出的实例端点）——此前默认硬编码 `http`，启用 TLS 后忘改这个键就会注册 `http://` 并且网格明文拨号，
    只有"配了 token 又用 ws"这一种组合会告警。显式值只接受 `http`/`https`，其它值启动失败点名键。
  - **`registry.service-host`**：`auto`（默认）在服务器绑定具体地址时就用该地址（它只在那里监听），绑定
    `0.0.0.0`/`::` 时优先 `POD_IP`、其次首个可路由本地地址（IPv4，排除回环/链路本地/any），都没有才回落
    绑定地址并保留原有的不可路由告警。多网卡主机显式点名，推导不猜。
  - **`registry.shutdown-drain`**：`auto`（默认）由注册表后端回答——新增
    `ServiceRegistry.drainWindow()` 默认方法（默认 `0s`）：内置进程内注册表答 0，适配器答自己的传播窗口。
    此前文档只能要求"用注册中心时手配 5s"，现在由真正知道这个数的后端回答；显式时长优先，负值启动失败。
  - 破坏性：无（三者都是默认值变更 + 新增默认方法与访问器；显式配置行为不变）。

- **日志专用配置文件更名：`freeway-log.properties` → `freeway-logging.properties`（freeway-commons）** — 新名是唯一
  正式名字，放在 classpath 根；旧名**不再被读取**——文件仍留在 classpath 根时启动打一行 stderr 告警并指明改名
  （配置不被采纳，但也不静默失效）。参考模板同步更名 `docs/freeway-logging.properties.reference`。同时改正"所有日志键都能住进
  文件"的说法：`freeway.log.color` / `.mdc` / `.mdc.priority` / `.caller-info` 四项只在 `-D`/环境变量生效
  （它们在类加载期读取，早于文件解析），模板与文档都按此标注。

- **文档：freeway-cloud 四份设计文档合并为 `docs/freeway-cloud-design.md`** — `freeway-cloud-unified-design.md`
  （总设计）、`freeway-cloud-events-design.md`、`freeway-cloud-rpc-design.md`、`freeway-cloud-implementation-plan.md`
  四份文档互有重复且已出现实质矛盾（总设计头部宣布"不做方法级 RPC"、§10 又把它排除在排除之外；hook 顺序与
  events/rpc 两份及代码不符；去重位置写成拦截器而非 `EventBus.publishInbound`；错误映射表缺
  `INTERRUPTED`/`DISPATCH`/`OTHER`）。现在合成一份设计基线：定位与原则、模块与装配、核心对象、九项能力设计
  （含线上协议表与 `kind()` 全表）、配置键形态、明确不做、路线图、修订记录；四份旧文档删除，仓库内引用
  （`DEVELOPER-GUIDE`、`freeway-config`、`RemoteCaller`/`RpcExport` javadoc）改指新文档。逐键清单不再复制，
  统一指向 `docs/freeway-config.md` 以免两处漂移；已交付的 Phase 0–8 任务清单只保留交付标准与回归清单，
  仍然有效的排除项（MQ 语义、全局成员视图、webhook 出站、`@CloudEvent` 注解实体）与 core 后续项并入
  §7/§8；同时补上此前未记录的能力边界（无应用层心跳、网格无背压、指标无标签、readiness 未发布到注册表）。

- **文档：AGENTS.md 成为唯一约定源，架构下沉 docs/ARCHITECTURE.md（仓库根）** — `CLAUDE.md` 与
  `AGENTS.md` 此前是两份自动加载的指导文件，内容互有重叠。现在分成两层，每层一个家：
  **`AGENTS.md`**＝仓库级**约定**（构建、模块地图、命名、设计规则、测试、回归清单、提交规则、阅读清单）；
  **`docs/ARCHITECTURE.md`**＝**架构与内部机制**（依赖图、各模块边界、注入注解、配置级联机制、生命周期），
  README/AGENTS/指南按需指向它，**不自动加载**以免每次对话都带上。配套清理：DEVELOPER-GUIDE 的
  "Naming Rules"/"Code Style" 两节（同一批规则的第三份副本）合并为一行指向 AGENTS.md 的指针，并修掉
  其中引用的不存在类型 `RequestContext`；设计文档与 README 中"遵循 CLAUDE.md"的引用改指
  ARCHITECTURE.md/AGENTS.md。`CLAUDE.md` 最终删除，见 Removed。

- **API 审计落实：失败判别、注册表心跳契约、网格 TLS（freeway-cloud）** — 三处 API 设计问题与一条
  成文规则：
  - **`CloudException.Kind`**：`noInstance`/`circuitOpen`/`rateLimited` 此前字段完全相同，只有 message
    文本不同——调用方要区分"服务没部署"与"熔断打开"（运维动作不同）只能做字符串匹配，设计文档 §6
    那张错误表在代码里不可达。现每个失败带 `kind()`（`NO_INSTANCE`/`CIRCUIT_OPEN`/`RATE_LIMITED`/
    `CONNECT`/`TIMEOUT`/`TRANSPORT`/`INTERRUPTED`/`HTTP`/`BUSINESS`/`REPLY_UNREADABLE`/`REJECTED`/
    `DISPATCH`/`OTHER`，一个值对应一种调用方动作），并由 `business(...)`/`unreadableReply(...)`/
    `rejected(...)` 三个具名工厂取代原先三处 `of(...)` 的通用调用。破坏性：无（新增访问器与工厂，
    `of(...)` 保留给扩展作者，kind 为 `OTHER`）。
  - **`ServiceRegistry.renew` 返回 boolean**（SPI 破坏性）：此前返回 void 且在条目已被淘汰时**静默
    无操作**（契约未说明），于是心跳 hook 只能额外发一次 discovery 查询自检——适配器后端每 10s 多
    一次网络往返。现 `renew` 回答"注册表是否仍然持有该实例"，hook 据此自愈（不在了就重新注册并计入
    readiness 打击数），不再发第二次查询。实现者必须如实回答：返回 true 而实际不持有，等于让调用方
    以为自己可达。ext 无该 SPI 实现，无需改动。
  - **网格出站 TLS**：`TransportSecurity` 自称 outbound transport security，但此前只有 RPC 客户端
    遵守它，事件网格自建 `HttpClient` 不接 TLS 配置——于是 wss 拨号拿不到客户端身份。现
    `PeerConnector` 用同一份 `TransportSecurity` 的 `SSLContext` 建客户端（可选依赖：不装 RPC 模块
    时退回 JDK 默认），角色文档写明"两条出站腿共用一个身份"与"启动期解析一次，轮换需重启"。
  - **文档**：API 形状规则写入 `AGENTS.md` 的设计规则（当时还在 `CLAUDE.md`）——一到两个可选参数用
    逐级 javadoc 的重载阶梯，三个以上用参数记录（`defaults()` + withers）。同批写入的"记录作为适配器装配点
    时保留旧 arity 的委托构造器"（ext 引擎测试被这一点打断过一次）**已被推翻**：保留旧形状的代价是每个新
    组件都多一条路径，见 Changed 首条的规则修订。

- **API 一致性：贡献 id 命名空间、网格内部面收窄、`@Local` 覆盖补齐（freeway-cloud）** — 三处
  与框架既有规则不一致的地方：
  - **贡献 id**：控制器/路由 id 此前混用裸名（`metrics`、`health-live`、`health-ready`、
    `cloud-event`、`trace`、`baggage`、`http`）与点分名（`freeway.cloud.rpc`）。id 是**每种扩展类型
    一个全局命名空间**且重复即启动失败，裸名会让应用给一条**不同路径**的路由起名 `metrics` 时莫名
    启动失败。现框架侧一律 `freeway.cloud.*`（`freeway.cloud.metrics`、`freeway.cloud.health.live`、
    `freeway.cloud.event`、`freeway.cloud.propagation.trace`/`.baggage`、
    `freeway.cloud.declaration.http`）；"抢占框架路由"的意图仍由**重复路径**与**重复主绑定**响亮拒绝
    （`Duplicate route detected: GET /metrics` / `AmbiguousBindingException`），语义比"重复 id"清楚。
    破坏性：以裸 id 作 `before`/`after` 锚点的代码需改名（仓库内无此类锚点）。
  - **网格内部面**：`PeerConnector`、`CloudEventSink` 收窄为包私有；`PeerHub`/`PeerConnection` 只保留
    **检查面**为 public——`connections()`/`origin()`/`serviceId()` 与连接的只读访问器加 `close()`——
    `wire`/`addInterceptor`/`register`/`unregister`/`token`/`subscriptions`/`receive`/`codec`/`send`/
    `matches` 全部回到包内（此前它们只是顺带 public）。类文档写明公开面就是检查面。
  - **`@Local` 标记**：凡"有内置实现、可被适配器 `.primary()` 替换"的角色都应带该标记（它同时是
    注入点限定符与后端守卫的判据）。`Metrics` 与 `TransportSecurity` 此前漏了，现补上。
- **停机收尾：drain 窗口 + 在飞调用优雅结束 + 网格告别帧（freeway-cloud）** — 此前摘除注册与关闭
  socket 之间没有任何间隔，滚动更新时仍持有该端点的负载均衡会打到已关闭的连接；出站在飞调用则被
  `close()` 直接以异常失败（对端可能已经执行）。现在：
  - `freeway.cloud.registry.shutdown-drain`（Duration，默认 `0s`）：停机时**先发信号**——readiness 置
    `draining`（探针驱动的 LB 立刻把实例摘出）并摘除注册（注册中心驱动的 LB 同样立刻停止投递）——
    然后继续服务这段时间再让 HTTP 服务停止。默认 `0s` 对内置进程内注册表是正确的（同一 JVM 内
    没有传播延迟），适配器部署把它设成后端传播窗口；
  - `freeway.cloud.rpc.shutdown-grace`（Duration，默认 `5s`）：`CloudHttpClient.close()` 先停止接纳
    新调用（同步与异步一视同仁），再等已在飞的调用结束，超过窗口仍未完结的才以
    `CloudHttpClient is closed` 失败并 WARN 记录剩余数量。空转进程立即关闭——没有在飞调用就没有等待；
  - 网格客户端停机时先发 WebSocket `1001 going away`（最多等 200ms，对端已消失则由 abort 兜底），
    对端据此区分"节点要走了"与"连接坏了"（服务端 session 本就发 close 帧）。
  - 破坏性：无（只新增两个高级档配置键）。

- **`@Local` 去掉类级位置（freeway-cloud，编译期收紧）** — 与 `@Marker`/`@Primary`/`@Builtin` 同一
  判据：`@Local` 的 `TYPE` 没有任何读取方（类上的框架 marker 由 ioc 的 `MarkerIndex` 读取，而它
  不能认识 cloud 的注解），写在实现类上会**编译通过却静默无效**。现只保留注入点位置
  （`PARAMETER`/`FIELD`，经 marker 索引读取）；标记实现请用绑定侧
  `.marker(Local.class)` 或模块级 `@Marker(Local.class)`。加测试钉住位置集合。
- **文档：区分"声明键"与"选择键"（freeway-cloud）** — `freeway.cloud.*.type` 四个键保留，但
  文档此前没有说清它们**不选择实现**：框架不做类名反射加载、不做 classpath 扫描，真正决定实现的
  是 `.primary()` 绑定 / `@Local` 标记 / 适配器模块；`*.type` 只是"我期望用哪个后端"的声明，由
  适配器消费，并在"声明了外部后端而本地实现仍活跃"时启动告警一次（`BackendTypeGuard`）。
  `freeway-config.md`（两类键对照表）、DEVELOPER-GUIDE、cloud 设计文档三处同步。
- **文档：静态文件的校验器契约（freeway-http）** — 缓存头取自请求开始时的探测、正文在其后打开，
  因此并发替换的窗口里可能"正文与 ETag 不同代"。这是 stat-then-sendfile 的固有 TOCTOU，本框架
  **有意保留**（路径在使用时重新解析以守住挂载根，防符号链接换链）；它会自洽收敛：下一次
  `If-None-Match`/`If-Range` 对照的是当时的文件。契约现已写在 `serve(...)` 的 javadoc 里，
  不再是一句无法兑现的注释。

- **远程调用收敛为一条显式通道（freeway-cloud，破坏性）** — provider 声明导出、consumer
  在组合根绑类型化客户端，位置（本地实现还是远端）是**组合**的事实而不是运行期的猜测：
  - 导出是一条数据贡献：`binder.contribute(RpcExport.class).add(RpcExport.of("user",
    UserHandlers.class))`（可选 `.propagateMessages()`）；框架贡献**一条**
    `/rpc/{mapping}/{method}` 通配路由与一个 `.before(HTTP_SERVER)` 装配 hook，hook 从容器解析
    handler（注入、单例、生命周期归容器）并建方法派发表，请求直接调用它——中间没有注册表，
    也就没有"端点查一条、客户端查另一条"的可能；
  - 启动期能查的全查并**点名失败**：重复 mapping、导出类型未绑定（消息给出要补的
    `binder.bind(...)`）、handler 方法重载（位置参数无法区分重载）；
  - consumer 侧：`RemoteProxyFactory.of(caller).serviceId(..).mapping(..).build(Api.class)`
    绑到接口上，业务代码照旧 `@Inject Api`；`RemoteCaller` 仍由框架绑定，直接调用与每调用
    预算走它的 `invoke` 重载；
  - 线协议不变（路径、位置参数数组、版本头、异常头全不动），幂等门/错误映射/韧性/传播不变。
  - 破坏性：`RpcEndpoint.of(mapping, bus, codec[, propagateMessage])` 删除（v2 起）；
    `RemoteProxyFactory.of(callBus, caller)` → `of(caller)`，`Mode`/`localFirst()`/`remoteOnly()`
    删除（只剩远端一种模式）；独立组装（ext 引擎的 `RouteIndex`、自定义挂载）改用容器无关的
    `RpcEndpoint.route(RpcExport, handler, JsonCodec)`——它接收已拿到的 handler 实例，不再需要
    假容器或总线。freeway-ext 的 Undertow/Jetty RPC 集成测试同步迁移；应用迁移见 DEVELOPER-GUIDE。
- **路由重复规则统一（freeway-http，行为变更）** — WebSocket 路由此前在 `WebSocketIndex` 里按 path 去重，
  规则是"显式路由覆盖组展开的同名路由"，而 HTTP 路由在 `RouteIndex` 里对同 method+path 直接
  **启动失败**——同一个框架、同一棵 trie，两条相反的规则。现统一为**重复即失败**：组展开的与显式声明的
  路由撞 method+path 时抛 `IllegalStateException`，不再有一方静默胜出（与"启动失败而不是静默跳过"的
  既有原则、以及 RPC 导出重复 mapping 的处理一致）。`WebSocketIndex` 因此退化为薄适配——组先展开、
  个体随后，一起进同一个 trie——少一个概念、少一处特例；两侧各加一个测试钉住碰撞行为。
- **注解 `@Target` 只声明有读取方的位置（freeway-ioc，编译期收紧）** — `@Marker`/`@Primary` 的 METHOD、
  `@Builtin` 的 TYPE 没有任何读取方（容器没有 producer-method 绑定；`@Builtin` 的落点是模块级
  `@Marker(Builtin.class)` 或 `.marker(...)`）：写在方法上、类上此前**编译通过但静默无效**。现从
  `@Target` 移除，误用变成编译错误；新增测试把三个注解的位置集合钉住，避免再长出无读取方的位置。
- **`StaticAsset` 去掉只写不读的 meta 组件（freeway-http）** — 缓存头来自服务前的 `ResourceSource.meta()`
  探测，`load()` 返回的 meta 从未被读取；`DirectoryResourceSource.load` 里"文件在探测与读取之间变了就
  刷新元数据，好让 ETag/Last-Modified 与所发字节一致"的注释因此是假的（刷新出的 meta 立刻被丢弃）。
  删掉该组件与刷新逻辑，代码不再承诺做不到的事。
- **`CloudEventEnvelope.translate` 补齐参数文档（freeway-cloud）** — `topic` 在 CLASS 通道不参与组帧
  （CloudEvents `type` 是事件类名），此前只被 `requireNonNull` 检查而没有任何说明；现按通道写明它的角色，
  `@param` 覆盖全部参数。
- **`CorsFilter` 收敛为单一构造器（freeway-http，破坏性）** — 两个 7 参构造器只差元素类型
  （`List<String>` 与逗号字符串），既是"同一条规则的两种说法"，也让传 `null` 的调用点直接编译不过
  （`new CorsFilter(false, null, null, null, null, null, false)` 两个重载都匹配——freeway-ext 的引擎
  测试正是这样被卡住的）。保留列表形态这一个：它是精确形态，模块装配把配置的列表原样传入，
  逗号拼写只在确实持有字符串的边界解码（`CorsFilter.DEFAULT` 与 `Builder.build()` 显式调用
  `SymbolSpec.splitList`）。迁移：字符串形态改用 `CorsFilter.builder().allowAllOrigins()/.allowedOrigins(s)
  .allowCredentials(b).build()`，或把逗号串换成 `SymbolSpec.splitList(s)` / `List.of(...)`。
- **应用面 API 补钉测试 + 状态码词汇表统一（freeway-http）** — 新增两组端到端测试，钉住此前零覆盖、
  但**应用开发者会用**的公共面（仓库内没有调用者只说明用它的应用在仓库之外）：
  `TypedRequestApiTest` 覆盖 `HttpRequest` 的类型化读取族（`queryParam/header/pathVar` 带 `Class`：
  正常解析、缺省为空、非法值报错而非放行垃圾）、类型化 body 工厂（`post/put/patch(path, type, handler)`
  的反序列化与路由顺序）、**bean 校验失败 → 400**（`Route.wrapBody` 的校验分支此前被判定"仓库内不可达"），
  以及 `Route.head/options` 的动词语义；`WebSocketReadLoopTest` 新增用例钉住 `WebSocketSession.ping`
  与 `sendTextBatch` 的帧序列（PING 载荷原样、批量保持顺序、恰好发送给定条数）。
  同时把四处硬编码状态码改用 `HttpStatus` 常量（`StaticResourceMount` 的 416/206、`CorsFilter` 的 204、
  `HealthFilter` 的 200）——常量是给用户的状态码词汇表，**保留**，缺的是模块自己用它。
- **删除 cloud 侧同类成员（freeway-cloud）** — 判据同上一批（"定义处 + 调用处"逐个看，两仓库确认）：
  `PeerConnector` 的两个委托构造器、无参 `start()`，以及**永远为空的 `staticPeers`**（唯一的构造点传
  `List.of()`，配置里的 peers 走 `start(peers)`/`setPeers`）——peers 因此只剩一条入口；
  `PeerConnection` 的 3 参构造器与 `remotePrefixes()`；`CloudEventEnvelope` 的 6 参 `translate`
  （自带铸造 UUID 的第二条翻译路径）；`RemoteProxyFactory` 里 catch 后原样重抛的空操作；
  `ActiveBindingProbe.hasMarker`（唯一调用者只是转发，内联进 `isLocal`）；
  `SecretStoreDefault.reload()` 降为 private（只在构造器调用）；
  `MetricsDefault.TimerData` 降为 private（实现内部持有结构）。
  另修一处**文档过度承诺**：`CloudEventEnvelope` 的 javadoc 写 `subject` = `Keyed#key()` 而未限定通道，
  实际只在 CLASS 通道写入（topic 载荷对总线不透明，没有排序键）——新增测试
  `keyedEventCarriesItsKeyAsTheWireSubject` 把两个通道的行为都钉住（CLASS 带 subject 与类名 type、
  TOPIC 无 subject 且 type 即 topic），并同步收窄 javadoc。这条测试在写的时候就先失败了一次，
  正是"未钉住的线上契约"的实证。
- **删除引擎内触达不到的成员（freeway-http）** — 逐项判别"定义处 + 调用处"的分布后才删，
  并在 workspace 与 `freeway-ext` 两处确认零引用：
  - 只写不读的状态：`HttpContextImpl` 的 `http10` 字段与 `isHttp10()`（HTTP/1.0 keep-alive 由
    `Http1xParser` 的 `keepAlive = !isHttp10` 决定，上下文里的副本从未被读）——`reset(...)` 随之
    少一个参数；`ParsedRequest.httpVersion` 组件（解析用局部变量仍在，组件无读者）；
    `WebSocketFrame.closeCode` 字段与访问器（`CloseCode` 枚举**保留**：它是用户传给
    `session.close(int, String)` 的协议词汇表，与 `HttpStatus` 同理）。
  - 无调用者的成员：`Headers.size()`、`Http1xParser` 的 `CR`/`LF` 常量（代码用 `'\r'`/`'\n'` 字面量）、
    `FrameHeader` 实例 `encode()`（静态重载才是真源）、`WebServer.notFound`（并入 `ErrorResponses` 调用）、
    `DataFrame.padLength()`、`MultipartForm.part(String)`、`PathPattern.template()`、
    `StaticResourceMount` 的三个无调用 getter（setter 是文档化 API，保留）。
  - 无调用者的重载构造器：`HttpSession` 7 参、`HttpConnection` 1/2 参、`SessionBufferedOutputStream`
    1 参、`SettingsFrame()`/`HeadersFrame()` 无参、`WebSocketException` 3 参；以及
    `HttpConnection`/`Http2Connection` 各一对地址访问器（地址一律经
    `HttpSession.remoteAddress(connection.socket())` 取得）。
- **模块组合改为数据（freeway-ioc）** — 容器在绑定任何模块之前一次性解析整棵模块树：父模块
  先于其子模块、同级按声明顺序；同一实例被到达两次（共享子模块或互相引用）只绑定一次，
  同 class 的两个实例启动即失败。`subModules()` 必须是稳定视图，被读取多次。
- **SPI 发现只补空缺（freeway-boot）** — 类已在模块树中声明（含作为子模块）时不再被自动发现
  重复加入，因此"`subModules()` 里声明 `new HttpModule()`"与"开启 autoDiscovery"不再冲突。
- **bootstrap 键通道统一（freeway-boot，行为变更）** — `freeway.env.prefix` /
  `freeway.config.file` 统一为 `-D<键>` 或 `FREEWAY_<键>`；写进配置文件
  不再静默忽略，启动时 WARN 点名。
- **配置文件读取上限统一（freeway-boot，行为变更）** — 16 MiB 上限从"仅类路径资源"扩展到
  所有来源：工作目录覆盖文件、`freeway.config.file` 附加文件与热重载重读一视同仁。
- **`AppConfigDefault` 构造方式变更（freeway-boot，破坏性）** — 静态形态改用
  `AppConfigDefault.of(Map<String,String>, List<String>)`；级联形态改为
  `AppConfigDefault(ConfigSources, List<Path>)`。
- **`AppConfigModule` → `BootModule`（freeway-boot）** — 改名并收窄职责：该模块只做一件事，
  把加载好的 `AppConfig`（及其声明的 symbol source）接入容器。runtime hook 生命周期与配置
  释放归 `AppRuntimeDefault` 自己的协作者所有，`HookLifecycle` 不再注册为容器服务，
  `"freeway.config"` 这个 hook id 随之消失（以它为 `before`/`after` 锚点的代码会在启动时
  报到未知 hook id）。（位于 `boot.internal`，无稳定性承诺）
- **CloudEventBus 命名统一为单数（freeway-cloud，破坏性）** — 包名 `cloud.events` →
  `cloud.event`、WS 端点 `/cloud/events` → `/cloud/event`、配置键
  `freeway.cloud.events.*` → `freeway.cloud.event.*`、常量 `CloudConfigKeys.EVENTS_*` →
  `EVENT_*` 与 `CloudHooks.EVENTS` → `EVENT`，boot 的事件类移入 `boot.event`。配置语义
  不变，仅命名；引用常量的代码与配置文件/env 中的键名需同步改名。

### Removed

- **`CLAUDE.md` 删除（仓库根）** — 它此前只剩一行 `@AGENTS.md`（Claude Code 的原生 import，因为 Claude
  Code 不原生读 `AGENTS.md`）。删掉的理由：仓库里"每个约定只有一个家"已经成立，再留一个只为某个工具
  存在的转发文件，就是同一份规则的第二条入口。用 Claude Code 的话，二选一即可——放一个一行的
  `@AGENTS.md` 文件，或用读 `AGENTS.md` 的插件；其他 agent（Codex、DSH 等）本就读 `AGENTS.md`，行为不变。

- **`CallBus` 及其卫星类型删除（freeway-ioc，破坏性）** — 删除 `CallBus`、`CallTargetRegistry`、
  `CallAdviceChain`、`CallProxyFactory`、`CallStats`、`DeadCallException` 六个公开类型（core 主体
  718 行、测试 1076 行），容器不再注册 `CallBus` 内置服务。理由：同进程调用能力与 IoC 注入重合
  （互相依赖的服务容器能直接解析），"可选能力缺席即降级"在 IoC 里就是绑一个默认实现，
  运行期热替换与"组合是数据、启动静态"的框架立场冲突；它独有的"结构化接口"（零编译期边）
  用组合根的一个适配器即可达成且换回编译期检查。跨进程调用改为显式：位置由组合决定、
  导出由 `RpcExport` 声明、地址由 `serviceId` 给出。同进程调用改用 `@Inject`（含互相依赖），
  跨进程调用用 `RemoteProxyFactory`（见上条）。`EventBus` 的消息域描述随之从三通道改为两通道。
  设计文档 `freeway-remote-callbus-design.md` 更名为 `freeway-cloud-rpc-design.md` 并重写立场章节。
- **`Binder.install(ModuleEx)` 移除（freeway-ioc，破坏性）** — 模块组合不再通过 `bind()`
  内的命令式安装表达。动机：安装式组合让模块图成为 `bind()` 的副作用，框架的其余部分
  （装配校验、SPI 去重、启动日志、测试）都看不到真实的模块集合。期间一度改为
  `ModuleEx.subModules()`，最终定为**入口构建的 `ModuleNode` 树**（见下一条）。
- **`ModuleEx.subModules()` 与 `ModuleTree` 移除：模块组合成为入口构建的树（freeway-ioc，
  破坏性）** — `ModuleEx` 只剩 `bind(Binder)`（外加展示用的 `name()` 默认方法），模块只声明绑定；
  组合由入口代码构建：`ModuleNode.app("order-service", ModuleNode.of(new
  OrderModule()), CloudModule.class)`，容器绑定它的前序并持有它。迁移：
  `b.install(new HttpModule())` / `subModules()` → `ModuleNode.app("app",
  ModuleNode.of(new HttpModule()))`（`FreewayApp.run(new HttpModule())` 是同一件事的扁平
  写法：应用根的直接孩子）。动机：`subModules()` 让组合成为框架**回调用户代码**的方法
  （每次启动被读多次，于是要靠"必须是稳定视图、请用字段 + getter"的契约去约束），而且伞
  模块因此拥有自己的子模块、应用无法替换其中一个；树是值——构建一次、装配处可见、片段可
  复用、校验落在构建它的那行代码上。同一实例重复到达仍折叠（共享片段是正常用法），同
  class 两个实例仍启动失败，但错误现在点名两条路径（`app → web → HttpModule`）。绑定序
  ＝前序，确定性但**不再是契约**：需要时序请用 `RuntimeHook` 锚点或贡献自己的 `order()`。
  启动日志与 `Container.moduleTree()` 读的是同一份值，`Container.modules()`（同轮引入，未
  发布）随之删除。
- **`CloudModule` 改为 bundle 模块（freeway-cloud，破坏性）** — 伞模块 → 片段工厂
  `CloudModules.standard()` → `CloudModule` + `@SubModule`：整包用
  `ModuleNode.of(CloudModule.class)`，只取其中一个模块就单独
  `ModuleNode.of(CloudRpcModule.class)`（这是伞模块做不到的）。迁移：
  `new CloudModule()` / `CloudModules.standard()` → `ModuleNode.of(CloudModule.class)`
  （或 `FreewayApp.of(...).add(CloudModule.class)`）。
- **`AppConfig.snapshot()` 移除（freeway-boot，破坏性）** — 级联不再对外暴露 map 形态：
  `SymbolSource` 是唯一读取入口，`AppConfig` 收窄为 `profiles()` / `providers()` /
  `close()`。迁移：`config.snapshot().get(k)` →
  `container.get(SymbolSource.class).resolve(k, null)`（或按声明 `resolve(spec)`）。
- **`PutResult.versionId`（freeway-cloud，破坏性）** — 接口没有按版本寻址的读/删
  （`get`/`delete` 只按 key，`ObjectEntry` 也没有版本字段），这个每次写入新铸的 UUID
  调用方除了打印什么也做不了：契约不成立。版本能力与其可用的操作一起排期，而不是先留一个
  拿不到东西的返回值。同步更新设计文档；`ObjectStorageDefault.put` 的 javadoc 写明本地
  后端**刻意忽略** `ObjectMetadata`（content-type/用户元数据是给 ext 后端的契约），
  而不是半兑现。
- **`EventSubscriber` 的排序 id（freeway-ioc，破坏性）** — `id` 字段、`of(String id, Class,
  Consumer)`、`of(String id, String topic, Consumer)` 与包内 `id()` 全部删除：该 id 从未被
  任何读取方使用（订阅索引只读 topic/eventType/handler），排序 id 属于 contribution ——
  `Contributions.add(id, value)` 才是唯一定义处。原文档示例写着
  `.add(EventSubscriber.of("notify", …)).after("index")`，既编译不过（`add(T)` 返回的
  `Contributions` 没有 `after`）又会静默排错序；两个示例已改为
  `.add("notify", EventSubscriber.of(…)).after("index")`。
- **`SymbolSpec.description` 与两个零调用者工厂（freeway-ioc，破坏性）** — `description`
  组件只写不读（javadoc 承诺的 "docs/registry use" 并不存在），随之删除
  `of(key, type, default, description)` 与 `required(key, type, parser, description)`；
  唯一传过它的调用点（`CloudResilienceModule`）把那句说明改为注释。记录现在只剩机制真正
  使用的输入：key、type、default、parser、required。

### Fixed

- **"声称与实现脱钩"清扫（全仓）** — 本轮审计把每一处"注释/报错/文档承诺了实现没有的东西"补成真的，或把话改对：
  flow 的驱动报错与 javadoc 指向早已删除的 `newInstance`（且被测试断言钉死）→ 改指 `FlowEngine.create`，断言反向
  钉住"不得指向已删 API"；`GraphSpec` 的 `System.Logger` → SLF4J，`@author noear`/上游 `@since` 移植残留清除，
  `PlantUmlOptions.DEFAULT` 可变静态全局按 FlowOptions 自家原则改为 record 值 + wither；`JsonUtils` javadoc 承诺的
  `JsonException` 类不存在 → 文档改为写明真实契约（`IllegalArgumentException` 带位置）；`HPackContext` 编码器 javadoc
  声称"编码器动态表逐块推进"而表根本不存在 → 删谎，锁要求的真实理由（帧序连续）写进注释；`ServiceRuntime` 的
  "cached lookups are lock-free"注释由下方实现兑现。`@NotThreadSafe` 的错误建议"改用接口（代理）"治不了它展示的病
  （代理共享一个 target）→ 改为给出真正可行的三个修法。
- **接口单例热路径的 JVM 级锁（freeway-ioc）** — 接口单例代理每次方法调用都重进 `realize()` 抢静态 `REALIZE_LOCK`，
  即使命中缓存——一个容器的慢构造可阻塞无关容器已建单例的调用（实测 1.2s），且与"锁只护首次构造"的注释相反。
  现在目标完整构造后才发布进 CHM，缓存读走锁前快路径；关闭密封协议（sealed-then-clear 不得留下孤儿单例）不变。
  同轮：`@NotThreadSafe` 隔着接口绑定不被执法（校验对接口目标直接早退）→ 校验所选绑定的 impl 标记，THREAD 作用域
  的接口代理豁免（每线程各得实例）；provider 返回 null 以绑定标识的明确报错出现，不再是缓存 put 里的匿名 NPE。
- **HEADERS+立即 RST 冻结连接读循环（freeway-http）** — 活跃的 RST_STREAM 路径只 `close()` 流而不标记输入结束：
  reader 线程自身落入 `DataIn.close()` 的 drain 分支时在空队列上永久 park（无唤醒源），一条连接两帧即可打停；
  dispatch 里真正设 `peerReset/halfClosed` 的 RST 分支是死代码。现在复位只经 `resetByPeer()`（标记→结束→拆除一条
  路径），`wakeupReader` 补 null 守卫（unpark(null) 会在 reader 线程上抛 NPE）。回归测试钉住"复位后连接还能接下一
  个请求"。
- **枚举写路径与池的迟到释放（freeway-db）** — DDL 侧把 enum 映射为 VARCHAR、读侧 `valueOf`，写侧却把原始 enum 交给
  `setObject`：H2/PostgreSQL 抛转换错误，枚举"可读不可写"且零往返测试。现在所有绑定点（位置/命名/展开集合/批处理）
  统一过 `StatementValues.bindValue`。连接池复用同一个 wrapper 对象，跨线程迟到的 `release()`/`invalidate()` 会把
  别人正在用的连接拿走/销毁（实测复现）：现在每次 borrow 给新句柄，已消费句柄上的迟到调用是无害 no-op。
- **RPC 参数绑定、重连风暴、权重（freeway-cloud）** — `decodeArgs` 注释声称容器参数"由 handler 自己的强制转换重绑"，
  而派发并不做：DTO 参数一律以 `ClassCastException` 出现并被伪装成业务失败。现在按声明参数类型经 codec 重绑
  （`JsonCodec` 新增 `convert` 缝：节点直转，默认实现文本往返，第三方实现不破坏），varargs 尾部真正组装数组，参数
  个数在派发前判定（调用方错误 = `Kind.REJECTED`，不再是 handler 异常）。mesh 重拨的退避计数从"socket 打开"移到
  "hello ack"：token 配错的两个节点不再全速互拨；`LoadBalancerDefault` 读自家 `ServiceInstance` 暴露的 `weight()`
  （权重=周期份额，0/负按 1）——此前"配置了但不读"。
- **数字守卫与 cause 环（freeway-commons）** — `MAX_NUMBER_LENGTH` 抄自字符串上限（10MiB）而非成本界：10M 位数
  token 合法通过而 BigDecimal 解析超线性（实测 1M 位 ≈11s，10M 位 60s+ 未归）。上限改为 1000 字符并给出理由，
  测试同时钉住"拒绝快"与"边界内能解析"。`JULFileHandler.estimateThrowableSize` 对 cause/suppressed 环无访问集
  （同库另三处都有）→ 补上：它运行在 handler monitor 内，一个中毒记录即可锁死整个 logger。
- **hook 启动中的重入 close（freeway-boot）** — 第一个 hook 调 `close()` 时嵌套停止只看得见"已登记"的 hook：closer
  自己（登记在 start 返回之后）永不被停，其后的 hook 继续在已关容器上启动且无人再停。现在 start 循环逐 hook 检查
  封存标记，closer 在自己的 start 返回后补一次 stop，其余 hook 不再启动。
- **入站请求有 span 了，traceId 也进日志（freeway-cloud）** — 此前模块内唯一的 span 是出站调用：
  入站请求只做上下文提取，于是被调方在链路里是一段平线，且**入站日志没有任何 traceId** 可关联。
  现在 `TracingFilter`（由 `CloudObserveModule` 贡献，因为它拥有 `Tracer`）为每个应用请求开一个
  server span，并把它**绑定为请求作用域**——这点是关键：`InvocationContext.current()` 优先
  scoped 层，只写 ambient 的 span 处理器看不见，于是出站传播会跳过本跳。为此 `Tracer.Span`
  新增 `context()`（span 建立的调用上下文，默认 null = 该 tracer 不拥有上下文），
  `TracerDefault` 返回它已算好的子上下文（含继承的 principal/baggage）。框架自身的探针与
  `/metrics` 不计入追踪。同时补上 W3C `tracestate` 的透传（`TraceContext` 增加 `traceState` 组件，
  构造时严格校验——CR/LF 注入与超长值直接拒绝；入站侧宽容：非法即丢弃并 debug 记录）。
- **readiness 不再恒真，且丢失的注册会自愈（freeway-cloud）** — 本地注册表的 readiness
  contributor 此前无条件返回健康，`/health/ready` 在默认安装下恒 200；而 `RegistryStore.renew`
  在条目已不存在时静默无操作，被淘汰的实例会一直"运行但不可见"直到重启。现在心跳除了续租还会
  用**消费者同款的发现查询**校验自身条目是否仍在册，不在则重新注册（WARN 记录），并把结果发布到
  `RegistryRenewal`：连续 {@code 3} 次（默认约 30s）心跳失败后 `/health/ready` 返回 503 并给出
  "heartbeat failed N times in a row"。未注册任何实例的进程报 `not registered`，不再给空洞的
  all-clear。
- **密钥文件轮换无需重启（freeway-cloud）** — `SecretStoreDefault` 此前只在构造时读一次
  （`reload()` 私有），k8s 换卷或改写 properties 后进程一直用旧密钥。现在按 size + **全精度
  mtime** 变化重读，节流 1s；读失败或文件瞬时消失（projected volume 的原子换卷）**保留已加载的
  值**并告警，绝不会把在用密钥清空。设计文档此前声称的"显式 reload()"并不存在，已同步为真实语义。
- **网格 token 走明文 ws:// 时启动告警（freeway-cloud）** — `event.token` 在 hello 里明文过线，
  其保护来自传输；`registry.service-scheme` 非 https 时拨号是 `ws://`。sidecar/mesh 终止 mTLS
  的部署里这是正常拓扑，因此**不拒绝**，而是启动时响亮告警一次：要么改成 https（wss://），
  要么确认它在 mesh/可信网络内。

- **topic 通道的 DeadEvent 不再自我触发（freeway-ioc）** — `dispatchEvent`（CLASS 通道）有"`DeadEvent`
  本身不再产生 DeadEvent"的守卫，`dispatchTopic`（TOPIC 通道）没有：把一条 `DeadEvent` 当 topic 载荷发布
  时，会先为它报一次"零订阅者"诊断，再为那条诊断报第二次。现在两个通道说同一条规则；新增测试同时钉住
  "零订阅者的 topic 发布报一次诊断"与"发布诊断本身不再产生第二条"。
- **框架扩展面的两处契约表述（freeway-cloud）** — `LoadBalancer` 的接口 javadoc 原写
  "Reads routing inputs (zone/weight/canary) from `ServiceInstance#metadata()`"，作为对
  **默认实现**的描述是假的（`LoadBalancerDefault` 是纯轮询，不读任何实例属性），作为对
  **角色**的描述才是真的。现写明：默认轮询不读属性，weighted/zone-aware/canary 是应用或
  适配器 `.primary()` 的活（设计文档 §263-266 的既定分工），而
  `ServiceInstance.weight()/zone()/version()/isCanary()` 是这些策略的**输入词汇表**
  （键名与解析的定义处，应用不应自己读 metadata 键）。这些访问器**保留**——仓库内没有
  调用者只说明"那个 CanaryLb 在仓库之外"。
  `RegistryStore.liveReady` 此前把 `Health.isStale` 的阈值判断内联重写了一遍，现调用
  `Health.isStale(maxAge)`，规则回到它自己的 owner（`Health` 的 javadoc 本就声明了这条淘汰
  语义），访问器因此不再是"没人读的成员"。
- **响应头块超过对端帧上限时不再发出超限 HEADERS 帧（freeway-http，协议修复）** —
  `HPackContext.encodeResponseHeaders` 把整个头块编成**一个** HEADERS 帧，且从不比较对端的
  `SETTINGS_MAX_FRAME_SIZE`（初值 16384，`Http2Connection.peerMaxFrameSize`），而本地预算却是
  64 KiB；同时该上限此前只用于 DATA 分片。于是 16 KiB–64 KiB 的响应头块（多个 `Set-Cookie`、
  长 CSP/Link、或单纯头多）会以超限帧发出，一致的对端必须按 FRAME_SIZE_ERROR 判定为**连接
  错误**。现按对端上限切分为 HEADERS + CONTINUATION（RFC 9113 §6.10），`END_STREAM` 留在
  HEADERS、`END_HEADERS` 只在末帧，整串由 `Http2FrameWriter` 一次加锁连续写出（不得插入其他
  帧）；`ContinuationFrame` 由"仅解析"变为真实写出路径，`FrameHeader.DEFAULT_MAX_FRAME_SIZE`
  成为该协议常量的唯一定义处（原先 `Http2Connection` 里另有一个同值字面量）。
  `HeadersFrame.writeTo` 改为按自身解析出的 flags 序列化——`BaseFrame.writeTo` 是抽象方法，
  该类必须实现它，此前它硬编码 `END_HEADERS`、丢弃 `END_STREAM`，是个只会产出错帧的陷阱。
  新增 `H2WireFormatTest.oversizedHeaderBlockIsSplitIntoContinuationFrames`：按 512 字节
  切分 2 KiB 头块，逐帧校验类型/flags/上限，并用引擎自身解码器重组回同一头块。
- **节点身份统一为一处推导（freeway-cloud）** — 此前注册表的实例 id 是
  `service-instance-id` 或 `service-id@host:port`（HTTP server 起来后推导），而事件网格的
  origin 是 `service-id@<随机 UUID>`（网格在 HTTP server 之前接线，端口未知），注释与设计
  文档却声称二者相同。现在 `HttpServiceDeclaration.of(container)` 是唯一定义处，注册与网格
  都消费它返回的同一个 `ServiceInstance`，网格改用 `.after(HTTP_SERVER)` 接线以获得真实
  端口；代价是接线前到达的 hello 会收到 1013 并按既有退避重连（网格本就以此为准）。
  `PeerHub` 不再自造 UUID 回退。
- **HTTP 引擎三处行为缺陷（freeway-http）** — 均由审计以具体输入复现：
  - **RST 之后的响应帧泄漏**：`markAborted()` 只置 `responseAborted` 而未置
    `streamOutputClosed`，且写出前的检查排在 `writeResponseHeaders` 之后——收到
    `HEADERS[END_STREAM]` 后再来一个 DATA 帧时，服务器发完 RST_STREAM 仍会继续发
    HEADERS/DATA/END_STREAM（RFC 9113 §5.1）。现两处都修，该输入下不再发帧。
  - **带引号的 charset 导致乱码**：`Content-Type: text/plain; charset="ISO-8859-1"` 时
    `bodyText()` 因 `Charset.forName("\"ISO-8859-1\"")` 失败而静默回退 UTF-8；同一个头
    `Part.text()` 却解码正确。现去掉引号后再查表，两条路径一致。
  - **`isMultipart()` 是子串判断**：`text/plain; note=multipart/form-data` 被当作上传，
    `multipart()` 抛错、默认处理器回 400。现按媒体类型精确比较。
  - 另修：`CompressionConfig` 负值静默改成 256（同记录其他分量都抛），现抛
    `IllegalArgumentException`；HTTP/1.1 补齐 415/416 的 reason phrase（此前线上出现
    `HTTP/1.1 415 ` 空原因短语）；`WebServer` 的 null sink 现在走 noop 哨兵；
    `cors.max-age` 由未校验的 String 改为 Integer（`=abc` 不再被原样写进响应头，改为启动即失败）。
- **配置面分档与文档纠错（freeway-http / freeway-cloud / docs）** — `HttpConfigKeys` 与
  `CloudConfigKeys` 按「决策键 / 默认最优 / 高级（小概率）」重组并写明理由，高级簇各自
  标出管辖它的聚合键（`rpc.resilience=auto|off`、`event.enabled` presence、
  `event.dedup.enabled`）；`docs/freeway-config.md` 的「必填配置速查」换成「决策键速查」。
  纠错：文档四处用 `freeway.http.port` 当例子，但该键**不存在**（真实键
  `freeway.http.server.port` ↔ `FREEWAY_HTTP_SERVER_PORT`）；`secret.file`/`secret.keys`
  标注为仅 `-D`；`rpc.connect-timeout`/`request-timeout` 明确**不受** `resilience=off`
  管辖；WS 端点 `/cloud/events` → `/cloud/event`（6 处）；`CloudEventModule` 的
  "enabled default false" 改为 presence 驱动的真实规则；`ExceptionMapper` →
  `ErrorHandler`（1.3.8 改名后的残留）；DEVELOPER-GUIDE 的 RPC 导出示例在
  `bind(Binder)` 里引用 `container`（编译不过），改为模块持有 `CallBus`。
- **`freeway-ioc` 内部收敛（无 API 变化）** — 按"概念要挣到自己的位置"清理：
  - 总线协作者与宿主同包同可见性：`EventStats`/`EventSinkRegistry`/`EventExecutorSupport`/
    `CallStats`/`CallTargetRegistry`/`CallAdviceChain`/`CallProxyFactory` 由 `ioc.internal`
    （public）移入 `ioc`（包私有），与既有的 `EventDispatcher`/`EventStreams`/
    `EventSubscriptionIndex` 一致；`ioc.internal` 现只剩 `ContainerImpl` 一个 public 类型。
  - `ProxyFactory`（包私有接口 + 唯一实现，无外部可代换点）并入 `ProxyFactoryImpl`。
  - `@Inject Logger` 改走 `container.get(LoggerSource.class)`（与 `SymbolSource`/`Coercer`
    同一原则）：此前走硬连线字段，绑定一个 primary `LoggerSource` 替代实现对其无效。
  - `ContainerImpl`：删除零调用者的纯转发 `resolveArguments`；模块上下文的 save/restore
    永远恢复 null（树先展平、不递归）改为 set/clear；单用处 `Scoping` 字段内联；
    `close()` 补 `markerIndex.clear()`（此前标记索引长期持有全部 binding）。
  - `BindingIndex`：`updateId` 里写回同一引用的"更新类型索引"空操作块、`scanBindings`
    未使用的 `type` 参数删除。`MarkerIndex.register`/`sync` 合一（marker 校验已在 binding
    入口完成，不再三处重复）。
  - `Shutdown` 两个 catch 体相同者合一；`EventExecutorSupport` 恒真且返回值被丢弃的
    `BooleanSupplier` 改为 `Runnable`；`EventBus` 删除提取 `EventDispatcher` 后遗留的未使用
    `LOG` 字段、修正 `publishOrdered` 里描述已删参数 `key` 的 javadoc；`ContainerImpl`
    线程作用域登记表去掉从未读取的 owner 值（`Map<Object,ContainerImpl>` → 恒等 `Set`）。
- **文件系统覆盖文件的 profile 段顺序（freeway-boot）** — 工作目录与
  `freeway.config.file` 的 profile 变体此前按「每个 profile 的 properties+json」排列，
  与类路径段的「先全部 properties、再全部 json」不一致：多 profile 时同一份文件内容在两侧
  会得出不同的赢家（`application-dev.json` 在类路径赢过 `application-prod.properties`，
  在文件系统侧却输给它）。两侧现统一为格式优先。
- **bootstrap-only 键的 WARN 覆盖全部配置文件渠道（freeway-boot）** — 此前只检查类路径
  文件；工作目录的 profile 变体与 `freeway.config.file` 附加文件中声明
  `freeway.env.prefix`/`freeway.config.file` 仍被静默忽略。现由
  `AppConfigDefault` 统一在读取每个文件系统文件时点名（含文件名），CLI 参数
  （`--freeway.config.file=...`）同样点名。

## [1.5.1] — 2026-09-06

### Added

- **HTTP/2 入站 RST 突发熔断（freeway-http）** — 滑动窗口内"未响应即取消"超过阈值即
  `GOAWAY(ENHANCE_YOUR_CALM)` 并拆除连接（CVE-2023-44487 家族）。仅存活且未提交响应
  的流参与计数：响应后取消（正常客户端行为）与已回收死流的 RST 永不触发熔断。
  经 `freeway.http.h2.reset-burst-limit`（默认 200，`0` 禁用）/
  `freeway.http.h2.reset-window`（默认 10s）可配，全链路 `HttpConfigKeys` →
  `HttpServerConfig`（校验 + Builder）→ `Http2Session` 落到新建的重载构造器。
- **TLS 证书重载事件驱动（freeway-http）** — `WatchService` 即时触发 + 防抖重调度：
  watcher 线程只做信号（零 I/O、零睡眠），快照/digest/重载全部收敛到单 scheduler
  线程，`check()` 的公开 monitor 换成私有锁；轮询保留为 NFS 等不可靠文件系统的
  兜底，任一层单独即可驱动同一快照比较。
- **回归覆盖** — RST 熔断与自定义限额、取消提交语义、GOAWAY last-id、watcher
  60s 轮询下数秒重载、迁移 owner-token 接管、池外来连接拒绝、`Defer` Error 保形、
  `CallBus` null 实参透传。1879 用例全绿。

### Changed

- **GOAWAY last-stream-id 改报 `lastHandledStreamId`（freeway-http）** — 被拒流只推进
  `lastSeenStreamId`（重放校验不变），不再谎称"可能已处理"而误导对端放弃重试
  （RFC 7540 §6.8）。
- **公开 monitor 收敛为私有锁** — `Extension`（含内部类 `Entry.before/after` 同锁）、
  `LazyValue`、`ScopedCache.Session`、`Defer.DeferredSupplier`、`SslReloader`。
  全仓确认无 `wait/notify` 配对后落地，行为不变。
- **现代惯用法收敛（全模块，无行为变更）** — pattern switch（`JsonLeaves` 18 分支、
  shutdown 三路分发、`PeerHub` 前缀；编译器强制 arm 序）、`equals` 模式匹配、
  immutable 工厂（`copyOf`/`of`——保序 Map 与 live-view 按三条保留规则排除）、
  `Thread.ofPlatform` 线程构建器、字符集显式化、raw-type 清理、FQ/import 整洁。
- **`Extension.asMap` 文档写明快照语义** — 返回不可变 point-in-time 快照，后续贡献
  仅新调用可见；不改名（`asMap` 描述形态，时间性归文档层，见评估）。

### Fixed

- **迁移锁双竞态（freeway-db）** — stale 接管由"先查后无条件删"改为
  `executed_at` 条件删除（0 行即退让）；`release` 按写入 `description` 列的 owner
  token 条件删除，迁移超时被接管后慢 owner 的晚释放不再误删新锁。
- **连接池释放外来连接（freeway-db）** — 硬转 `PooledConnectionImpl` 的
  `ClassCastException` 改为指引型 `SqlException`（释连接到错池）。
- **`Defer.supply` 的 Error 包装（freeway-commons）** — `Error` 原类型透传并缓存，
  checked/exception 仍保持既有包装形状（首版改动曾打破既有测试，已收窄回兼容）。
- **`CallBus` 代理 null 实参（freeway-ioc）** — `List.of(args)` 改与
  `RemoteProxyFactory` 一致的 null 容忍视图，接口方法 null 实参正常分发。
- **`Node` 构造不再原地排序调用方列表（freeway-flow）** — 改为拷贝后排序。
- **`WebSocket` accept-key 编码（freeway-http）** — 固定 UTF-8：原平台编码 +
  `length()` 当字节数，非 ASCII 下 digest 截断。
- **配置热重载 WatchKey 重挂（freeway-boot）** — `reset()` 进 finally，失败迭代
  不再静默退订目录。
- **H2 preface 解码（freeway-http）** — 按 US-ASCII，消除平台默认编码依赖。

## [1.5.0] — 2026-09-05

### Changed

- **CLASS 通道入站默认拒绝（freeway-cloud，安全，破坏性）** — `PeerHub.receive()`
  的 CLASS 通道 deserialization 改为 deny-by-default：`allowed-types` 未配置时
  一律丢弃 CLASS 事件，不再回退到"接受任意类"。此前空 allowlist 语义为"放行任意
  类"，配合未认证端点构成任意类加载 + 反射反序列化面；TOPIC 通道（通用 JSON、
  无类解析）保留"空 = 全放行"的既有文档语义不变。`allowed-types` 为空的启动告警
  文案同步更新为"CLASS 事件将被丢弃"。E2E 测试 `keyedEventsPreserveTheOrderingSubject`
  相应改为显式白名单其类型。
- **freeway-cloud 内部结构收敛（无行为变更）** — 按结构审计收口
  落地一组机械重构：`PeerConnector` 的地址
  解析（`toUri`/`parsePort`/`sameEndpoint`）抽为 `PeerAddress` record（解析 + 渲染 +
  端点相等，顺带消除 `URI.getHost()` 的 IPv6 括号歧义）；`PeerHub.ServerSessionHandler`
  的握手准入（origin + token + subscribe 解析）抽为纯方法 `validateHello` +
  `HelloAdmission` 结果类型，与已独立的 `receive` 入站门对称；`CloudHttpClientDefault`
  的 3 个 telescoping 构造器收敛为 `Wiring` record + 单一构造器；逗号切分列表解析抽为
  `internal/ConfigLists`（`splitAndTrim` + 列表 `spec`）；`CloudEventLifecycleHook`
  剩余裸 `resolve` 改走 `SymbolSpec`；`freeway-boot` 依赖声明降为 test 作用域（main
  零引用）。
- **`SymbolSource` 新增 `resolve(SymbolSpec<T>)` 默认方法（ioc 公共接口，向后兼容）** —
  取代全项目 32 处 `spec.parse(symbols.resolve(spec.key(), null))` 二段式样板，改为
  一步 `symbols.resolve(spec)`。默认方法向后兼容（现有 `SymbolSource` 实现类无需改动），
  键名、默认值、解析器仍由 `SymbolSpec` 单点声明。`freeway-db` / `freeway-cloud` /
  `freeway-boot` / `freeway-commons` 的全部调用点与 javadoc 示例已同步。纯机械收口，
  零行为变更。
- **`XDefault`/`XImpl` 命名定稿（freeway-commons/ioc/db/http/flow/cloud，破坏性更名）** —
  命名判据定为「外部的可代换性」：角色实现可被外部替换（`.primary()` 绑定 / 构造选择 /
  配置激活）时，框架的默认实现定名 `XDefault`，与功能包归属无关——cloud 的可替换默认
  全部安置于各自功能包（discovery / resilience / observe / storage / secret / rpc，
  `internal` 不再容纳可替换默认），即便留在 `internal` 也不改变定名（如 `db/internal`
  的 `PoolDefault` 仍可被外部 `.primary()` 替换）；无外部可代换性的实现件定名 `XImpl`。
  定稿的净更名（相对 1.4.0）：
  - 定名 `XImpl`：`HttpContextDefault`→`HttpContextImpl`（内建引擎的上下文契约，ext
    引擎自带各自实现）、`LoggerSourceDefault`→`LoggerSourceImpl`（注入路径硬连线单例，
    从不查询绑定）、`PooledConnectionDefault`→`PooledConnectionImpl`（逐池工件类型，
    Hikari 适配器自带自有连接类型）、`ProxyFactoryDefault`→`ProxyFactoryImpl`（包私有
    接口 + 唯一实现，无外部可代换点）；
  - 定名 `XDefault`：`FlowEngineImpl`→`FlowEngineDefault`（默认流程引擎）；
    `TransportSecurityDefault` 维持可代换默认定名、自 `cloud.internal` 归入 `cloud.rpc`
    （按 `freeway.cloud.rpc.tls.*` 键经 `fromKeyStore` 装配，ext 可绑替代实现）；
    `CoercerDefault` / `ExchangeMetaDefault` 维持定名不变；
  - `MetricsDefault` 的私有内部类 `DefaultCounter` / `DefaultTimer` → `CounterImpl` /
    `TimerImpl`（清除 `DefaultX` 前缀残留，无 API 影响）。
  判据与 `internal` 语义（不承诺稳定的实现细节，如 `RegistryStore` / `ConfigLists`）
  写入 `CLAUDE.md` / `AGENTS.md` / `DEVELOPER-GUIDE.md`（AGENTS.md 同步「归置与后缀
  正交」口径），各文档中的改名类引用统一为现行类名；freeway-ext 引用同锁步更名。
- **cloud 配置键单源化与 `Wiring` withers（freeway-cloud，无行为变更）** — 七个配置键
  的默认值在 `CloudConfigKeys` 以 `*_DEFAULT` 常量单点声明（RPC TLS keystore/truststore
  四键空串默认、storage base-path、registry service-scheme），配置层与库内兜底不再双写；
  RPC 请求/连接超时与 peer 网络超时常量在 config spec 与 `CloudHttpClientDefault.Wiring`
  的库兜底间共享同一来源（未装模块时直接复用常量，两层不可能漂移）。`Wiring` 增 7 个
  wither（`withPropagators` / `withRetryer` / `withBreaker` / `withRateLimiter` /
  `withTransport` / `withRequestTimeout` / `withConnectTimeout`）供逐步定制。

- **包结构收紧与文档口径同步（freeway-commons/ioc/boot/http/db/flow/cloud，无行为变更）** —
  按 2026-09-05 包体结构审计的 B/C 清单落地：
  - http 引擎体系可见性收紧：21 个包外零引用的 public 类型降 package-private
    （engine 根 `SessionBufferedInput/OutputStream`；engine/ws `WebSocketFrame` /
    `OpCode` / `CloseCode` / `WebSocketException`；engine/http2 `BaseFrame` 帧族 /
    `FrameSerializer` / `Settings` 族），engine/ 剩余 public 只是引擎子包间契约
    （Java 无子包可见性），已在 CLAUDE.md 写明；engine/ws 的静态读循环工具
    `WebSocket` 更名 `WebSocketReadLoop`（消除"以协议命名工具"）。
  - commons logging：`JULLoggerFactory` / `JULLoggerAdapter` / `JULMDCAdapter`
    收 package-private（SPI 与 LogManager 按名加载的类型保持 public）。
  - flow：引擎件 `Stepper` / `FlowContextImpl` 移入新建 `flow.internal`
    （类仍为 public，仅供根包装配引用，无稳定性承诺）；根 package-info
    增补 Stability 段。
  - db：`RowMapperTest` 归位 internal 镜像测试包；新增 `DialectSyntaxTest`
    直接单测四方言语法面（引号/保留字/DDL 片段/DML 子句）；`DatabaseHub.of(Map)`
    根工厂取代 javadoc 引导构造 internal 类。
  - `internal` 包（ioc/boot/http/db/cloud/flow）与 commons.util 补 package-info
    （"no stability promise" / 包边界）；ioc `Container` 类 javadoc 从 import
    之前归位（此前从不附着到接口）。
  - `ContextExecutor` 定案：保留为公开 API（显式 `ScopedValue` 传播执行器），
    util 包边界随之写入 package-info。
  - 文档口径同步：CLAUDE.md 的 dialect 替换路径（内建默认 `PostgresDialect`
    本身 `.primary()`；自定义方言 = id 绑定 + `freeway.db.dialect` 键）、
    engine 公开面表述、`@Primary` 与 binding DSL 等价、`ExprEvaluator` ~600 行、
    模块依赖图（db 的 ioc 实为 compile 且仅 DbModule 引用、cloud→boot 为
    test scope、http 显式依赖 commons）；AGENTS.md 模块表同步；flow README
    的 "GraphSpec2" 措辞修正（v2 由 `GraphSpec` 承载，`version=2` 标记）。

### Removed

- **清理死依赖声明（根 `pom.xml`）** — 移除 `<dependencyManagement>` 中
  `freeway-starter` / `freeway-starter-boot` / `freeway-starter-web` /
  `freeway-starter-db` 四个条目。这些 artifact 在 `<modules>` 中无对应模块、
  目录不存在、全仓无任何代码或模块引用（仅有 pom 自身声明），属早期规划的
  残留。移除后 `dependencyManagement` 中的 7 个内部模块与 `<modules>` 完全
  一一对应。对已发布 artifact 的坐标解析无影响（从未发布过这些 artifact）。

### Fixed

- **一个进程可导出多个 RPC mapping（freeway-cloud）** — `RpcEndpoint.of()` 此前恒
  返回同一路由模式 `Route.post("/rpc/{mapping}/{method}")`，第二次导出与第一次争夺
  同一 trie 节点，启动即 `IllegalStateException: Duplicate route detected: POST
  /rpc/{mapping}/{method}`，与类 javadoc「each mapping you hand to `of()` becomes
  reachable」相悖，而报错信息完全不指向真因。现把 mapping 编入路径字面量
  （`/rpc/<mapping>/{method}`），并在导出时校验 mapping 名（复用
  `RpcPaths.validateSegment`，非法名当场失败而非请求期静默遮蔽兄弟 mapping）。
  对外 URL 形状与调用侧代码均不变。新增回归：两 mapping 并存可达、跨前缀仍 404、
  非法名导出即抛。
- **RPC 拒绝详情不再把 4xx 放大成 500** — `X-RPC-Reject-Reason` 此前直写原文，而
  `method` 段经路径 URL 解码可以携带 CR/LF；freeway-http 拒绝头值中的控制字符，于是
  `POST /rpc/user/greet%0d%0aX-Injected%3a...` 得到 500 加一行 SEVERE 日志，精心
  区分的 400/404 契约被替换成服务端故障。现按 wire 文档既有约定对该头 form-encode
  （与 `X-RPC-Exception` / `X-RPC-Message` 一致，消费侧本就解码），错误体改由
  `JsonCodec` 序列化并删除手写 `escape()`（只处理 `\\` 与 `"`，控制字符会产出非法
  JSON）。CRLF 响应头注入经实证本就被 HTTP 层拦下，修的是被它顺带毁掉的状态码。
- **网格入站分片文本帧不再丢事件** — client 侧 `PeerConnector` 用的是 JDK
  `WebSocket.Listener`，按**帧**回调；此前非末帧直接忽略，后续 CONTINUATION 帧单独
  解析失败还会 `abort()` 连接。对端服务端在 4MiB 以上即分片发送（入站上限 16MB），
  因此 4–16MiB 的事件必然丢帧并反复断链，而服务端入站经引擎已合并、两侧语义不对称。
  新增 `TextMessageAssembler` 负责重组，并按与服务端同源的上限（16MiB）拒绝超长
  消息——"对端永不置 FIN"不能换成无界内存。
- **`callAsync` 与 `close()` 的登记竞态** — future 此前在任务提交**之后**才加入
  `inFlight`；若 `close()` 恰在两步之间完成遍历，被 `shutdownNow` 丢弃的任务就没人
  结算。现改为在 `close()` 的同一监视器下"先登记再提交"，关闭窗口内的调用同步抛
  `IllegalStateException`（与 `requireUsable` 同型）。
- **注册钩子停止期不再掩盖真因** — `RegistryLifecycleHook.stop()` 在 try 之外执行
  `container.get(ServiceRegistry.class)`，容器半拆时该查找抛出的异常会顶替正在发生
  的失败。现按 best-effort 处理：注册表不可得即一条 WARN 加清理跟踪实例，租约自然
  过期。
- **RPC 未装 resilience 模块时重试策略不再被静默置空（freeway-cloud）** —
  `CloudHttpClientDefault` 此前把解析后的默认 retryer 存入 `this.retryer` 字段（死字段，
  只写不读），却把可能为 null 的原始参数直接传给 `ResiliencePolicy`——未装 resilience
  模块（retryer 为 null）时，一旦遇到可重试失败，`retryer.shouldRetry` 即 NPE。现删除
  死字段，改用解析后的非 null retryer 构造策略，缺省时回退
  `RetryerDefault.withDefaults()`。
- **HTTP/2 流被 RST 后不再向该流写响应帧（freeway-http，含偶发 flaky 修复）** —
  此前 `Http2Stream.close()` 在流因 RST 结束时仍执行 `outputStream.close()`，向一个已结束
  的流发送 `DATA END_STREAM`；被唤醒的 handler 还会继续执行 `ctx.send(200,...)` 写 stray 帧，
  与同一连接上的后续请求响应交错。该竞态违反 RFC 7540 §8.1（流被 RST 后不得再发送该流帧），
  真实客户端会据此发 GOAWAY 关闭连接，导致 `Http2ProtocolTest.h2cResetStreamWithNoErrorReleasesHandler`
  偶发失败。现统一收敛：为流加 `peerReset`（对端 RST）与 `responseAborted`（服务端主动 RST）
  两标记，`close()` 在任一标记下不写任何响应帧；handler 后续的 `ctx.send`/`write` 在
  `peerReset` 下直接干净抛 `IOException` 收尾而非边写边错。覆盖全部发 RST 的路径——
  对端 `RST_STREAM`、`abortResponse()`（PROTOCOL_ERROR）、`sendReset()`（异常路径）、
  `dispatchToStream` 捕获的流错误、`WINDOW_UPDATE` 发送窗溢出（FLOW_CONTROL_ERROR）——以上
  路径发完 RST 后均不再补 END_STREAM。`sendReset()` 改为基于 `responseAborted` 的幂等
  （重复调用只发一次 RST）。正常完成路径（handler 主动 `close`）因 `outputStream.closed`
  守卫不重复写帧，行为不变。
- **`SslContextFactory` 空 keystore 密码不再 NPE（freeway-http，P3 卫生）** —
  `keyStorePassword` 为 `null` 时 `password.toCharArray()` 直接抛 NPE（混淆的栈而非配置错误）。
  无密码 keystore（如空密码 PKCS#12）本就合法，`KeyStore.load` 期望 `null` 字符数组而非空数组。
  新增 `keyStorePasswordChars` 把 `null` 原样透传，消除 NPE 且不破坏无密码 keystore 场景
  （`loadKeyStore` / `defaultKeyManagers` / SNI `SniKeyManager` 三处调用点统一走该 helper）。
- **批量收口 cloud 模块 P3 卫生项（freeway-cloud / freeway-http，无行为变更）** —
  - `ReadyHandler`：`/health/ready` 的每个 contributor 检查加 2s 超时预算（虚拟线程
    + `Future.get(timeout)`），某 contributor 阻塞即判不健康，而非拖挂探针端点并堆积探针线程（P3-8）。
  - `TracerDefault`：span 构造期捕获**所属线程**的 `Frame`，`restoreThreadState` 用其操作
    span 栈——修复跨线程 `close` 后所属线程的 span 栈残留 stale 项、后续 MDC/`diagId` 错乱（P3-11）。
  - `CloudHttpClientDefault`：breakers/rateLimiters 按 `serviceId` 分片，加 1024 容量上限兜底
    （超限驱逐一个），防 churning 注册表来源下无界增长（P3-1）。
  - `PeerConnector`：握手超时(10s)/重连退避(1s/30s)由硬编码私有常量改为经 `SymbolSpec` 可配
    （`freeway.cloud.events.handshake-timeout-ms` / `backoff-base-ms` / `backoff-max-ms` /
    `connect-timeout-ms`，默认值与原有硬编码一致，行为不变）；`CloudEventLifecycleHook` 透传（P3-5）。
  - 注：`Baggage` 上限（P3-10）经核对已在 `BaggagePropagator` 传播层落地（inject/extract 截断
    到 `MAX_ENTRIES` / `MAX_ENCODED_LENGTH`），故不在构造器加帽，以免破坏 oversized 传播回归测试。
- **网格会话强制 hello-first 握手（freeway-cloud，安全）** — `PeerHub` / `PeerConnector`
  两端各持逐会话握手状态机：hello/ack 之前到达的 CE 帧一律按协议错误处理（服务端关
  1002、client 侧 abort），不再可能落入 `hub.receive()`——此前任何能建立 WebSocket 的
  客户端都可跳过 token 门禁的 hello 准入直接注入 TOPIC 事件（`allowed-topics` 默认放行
  任意主题）。hello 每次会话仅一次，重复 hello 按协议错误处理。新增
  `PeerHubHandshakeStateTest` 等回归（先于修复验证为红）。
- **网格重连语义修正（freeway-cloud）** — 是否重连不再由"谁调用 close()"决定：出站侧
  `close()` 只负责拆除传输，`handleDisconnect` 一律重拨，除非 hub 注册表仍服务该
  origin（重复解析残留 twin 的抑制场景保留）；sink 发送失败导致的摘除
  （unregister + close）现在会重拨，不再静默丢失对端直到重启。新增
  `PeerConnectorReconnectTest` 回归。
- **出站调用异常头净化与事件源身份回退链（freeway-cloud）** — `RemoteCaller` 对 peer
  异常头只解码净化一次，同一份干净值同时供外层消息与 `RemoteInvocationException`
  （其构造器仍自净化）使用，CRLF 伪造与超长值无法经 cause 链进入日志；
  `CloudEventLifecycleHook` 解析网格自身份改走与 `HttpServiceDeclaration` 相同的
  registry service-id → `freeway.app.name` → freeway-app 回退链，注册身份与事件源
  身份保持一致。
- **TLS 热重载跟随生效引擎（freeway-http）** — `SslReloader` 此前无条件装配到内建
  `FreewayHttpEngine`：ext 引擎（Undertow/Jetty）成为 primary `HttpEngine` 后，
  reloader 监视并重载的是一个从未启动的引擎，TLS 热重载静默失效。`HttpModule` 现在
  先探测当前生效的 HttpEngine 绑定是否仍是本模块的内建绑定
  （`container.isActiveBinding(HttpEngine.class, Builtin.class)`，只查绑定元数据、
  不实例化引擎），是才装配 reloader；否则记日志说明证书轮换属生效引擎模块的职责。
  内建引擎路径行为不变。新增 fake primary 引擎 + 不存在 keystore 的启动回归。
- **`/metrics` 三角色同实例绑定（freeway-cloud）** — observe 模块把同一个
  `MetricsDefault` 实例以三个角色注册（具体类、`Metrics` primary、`MetricsSnapshot`），
  `/metrics` 导出视图不再对解析出的 JDK 代理做 instanceof 判定（代理只实现被请求的
  接口，首请求即抛）；替换 metrics 后端 = 不装 `CloudObserveModule` 自行装配。
  `CloudObserveModule` / `MetricsSnapshot` / `MetricsHandler` 上"自动跟随"的 javadoc
  声明随之下修。新增标准装配 200 导出、ext 式 primary 替换无歧义、双 primary 响亮
  失败（`AmbiguousBindingException`）回归。

### Documentation

- **事件网格入站安全与生产 token 配置** — `docs/freeway-cloud-events-design.md`
  新增 §4.3「入站门禁：token 与双白名单（生产部署必读）」：厘清三道入站门
  （`token` 对等认证 / `allowed-types` CLASS 反序列化 / `allowed-topics`
  TOPIC 注入）各自不同的空值语义，并给出多节点生产部署配置 token 的四条
  约束（全节点取值一致、经 `FREEWAY_CLOUD_EVENTS_TOKEN` 注入而非写进配置
  文件、常量时间比较防探测、轮换需滚动重启）；同时记录"不内置默认 token"
  的决策理由——硬编码默认值等于公开密码、制造安全错觉；随机默认值会让
  mesh 因 token 不一致而直接断连。
- **修正过期文档描述** — `DEVELOPER-GUIDE.md` 与 `freeway-config.md` 中
  "空 allowlist = 放行全部"的表述已随上述行为变更同步：CLASS 通道为
  deny-by-default，仅 TOPIC 通道保留"空 = 放行全部"。生产样例配置
  `application-prod.properties.sample` / `.json.sample` 补上 events 段与
  token 注入提示。
- **`secret.file` / `secret.keys` 的真实生效方式** — 这两个键直读 JVM 系统属性
  （密钥提供方参与符号解析，不能经该链读取自身配置），因此写进 `application.*`
  或 `FREEWAY_*` 环境变量**静默无效**，而白名单失效正好意味着"任意符号名先查环境
  变量"的锋利默认继续生效。`docs/freeway-config.md` 的密钥表与环境变量映射小节
  补上"仅 `-D`"标注与例外说明。
- **熔断结果上报的线程亲和契约** — `CircuitBreaker` javadoc 写明：半开探针由
  `allowRequest()` 的调用线程持有，`onSuccess` / `onFailure` 必须同线程结算，
  否则探针不计账、电路停在 HALF_OPEN 直到 open window 重新 arm。
- **RPC 导出面文档同步** — `DEVELOPER-GUIDE.md` 与
  `docs/freeway-remote-callbus-design.md` §3.2 改为描述每次导出各自的
  `/rpc/<mapping>/{method}` 路由（多 mapping 并存）与导出期 mapping 名校验。

## [1.4.0] — 2026-09-02

### Added

- **统一配置文件解析器（freeway-boot）** — 新增 `ConfigFileReader`（boot internal）：
  `.json` 解析为 JSON 并展平为点号键，其余按 properties 解析，全部 UTF-8。
  classpath 级联与热重载文件层共用同一实现——文件无论位于 classpath、工作目录
  还是 `freeway.config.file`，启动与每次重载的解析结果完全一致。

### Changed

- **配置读取统一归框架（freeway-boot，破坏性）** — `ConfigLoaderDefault` 现在返回
  `AppConfigDefault`（唯一实现，静态/动态两种构造形态）：文件层 = classpath 基线
  （打包的 `application*.properties/json`，静态）+ 文件系统覆盖（工作目录同名标准文件
  + `freeway.config.file` 指定的文件，逗号分隔，文件系统压过 classpath、后者压过
  前者），WatchService 热重载，无覆盖文件时行为与静态配置完全一致。热重载是 pull
  语义：配置模型直接声明自己的 `SymbolProvider`（`AppConfig.symbolProviders()`），
  文件源每次 lookup 读实时快照，`@Value`/`@Symbol` 重新解析即见新值。
  自定义 loader 的静态配置从"顶层（TIER_CLI）"改为"文件层（TIER_FILES）"——
  env/CLI 与模块源（如 secret）现在正确地压过它。
- **cloud 配置机制删除（freeway-cloud，破坏性）** — `CloudConfigModule` /
  `CloudConfigDefault` / `CloudConfigSymbolProvider` / `CloudConfig` / `ConfigRef` /
  `ConfigSubscription` / `ConfigChangedEvent` 及 `freeway.cloud.config.*` 键全部移除；
  配置中心文件改用框架键 `freeway.config.file`（旧键 `freeway.cloud.config.file`
  废弃）；`CloudModule` 不再安装配置模块。
- **读取入口收敛：SymbolSource（freeway-ioc/boot，破坏性）** — `SymbolSource` 回归
  纯解析基底（`resolve`/`expand`，String in / String out），不再认识 `SymbolSpec`/
  `Coercer`；类型化读取改为显式两步——`SPEC.parse(symbols.resolve(SPEC.key(), null)[, coercer])`，
  键与默认值只在 spec 声明一次。`AppConfig` 不再是读取门面：删除 `get(String)` /
  `get(SymbolSpec)` / `DefaultCoercer`，仅保留 `profiles()` / `asMap()`（级联快照，
  永不含 secret）/ `symbolProviders()` / `close()`。`freeway-ioc` 从此零依赖
  `commons.config`。
- **类型化解析收敛（freeway-cloud/db/http，破坏性）** — `ConfigValues`（int/long/
  double 静态工具）删除；`HttpConfig` 的反射式取值 helper、DbModule 的键/默认值
  双写、cloud 各模块的裸 `Boolean.parseBoolean(symbols.resolve(...))` 全部收敛为
  声明式 `SymbolSpec`。cloud 的 `*_DEFAULT` 常量升级为类型化值，配置层与库内
  兜底共享同一来源。
- **配置层级显式化为 4 个框架层（freeway-ioc，破坏性）** — `SymbolProvider` 常量
  收拢为 `TIER_CLI(0)` / `TIER_SYS_PROPS(5)` / `TIER_ENV(10)` / `TIER_FILES(20)`；
  模块槽位由模块自持（cloud secret 声明 order 15，介于 env 与 files 之间）。全部
  provider 进入单一定序列表：JVM `-D` 升至文件层之上（进程级运维覆盖，对齐
  Spring Boot 惯例）；raw-env 兜底层删除——环境变量只经声明式前缀映射进入链条，
  未知符号快速失败而不是静默命中无关变量（Windows 大小写不敏感下的 `path`→`PATH`
  事故面随之消除）。裸容器（无 boot）的内建来源只剩 system properties。

### Removed

- **`EventBus.hasSubscribers(String/Class)`（freeway-ioc，破坏性）** — 全库零消费
  的查询 API；DeadEvent 诊断与 `EventDispatcher` 内部判断已覆盖需求。
- **`EventBus.publishOrdered(Object key, Object event)`（freeway-ioc，破坏性）** —
  key 参数被实现忽略的占位 API；单参 `publishOrdered(event)` 为唯一形态，
  per-key 排序在未来按真语义实现。
- **`@RoundRobin` 注解（freeway-cloud，破坏性）** — 单一实现却有策略标记的伪 SPI
  概念；负载均衡扩展机制是 `LoadBalancer`（@FunctionalInterface）+ primary 绑定。
  `LoadBalancerDefault` 改标 `@Local`，与全部内置默认实现的选择子一致。

### Fixed

- **JSON 覆盖文件按 properties 解析（freeway-boot）** — 热重载文件层此前对所有
  override 无差别 `Properties.load()`，`application.json` 或 `freeway.config.file`
  中的 `.json` 文件被解析成 `{"app.name"="..."}` 式垃圾键、真实键全部丢失；
  统一解析器按扩展名（大小写不敏感）分派后，JSON 覆盖文件启动与热重载均正确。
- **测试桩契约偏差（freeway-cloud）** — `SecurityTest` 的 `SymbolSource` 桩改为
  抛出 `UnknownSymbolException`（此前抛普通 `IllegalArgumentException`，
  `resolve(name, default)` 按类型无法识别 miss）。

## [1.3.11] — 2026-08-30

### Added

- **`Defer.within` 结果形态（freeway-commons）** — 新增 `within(Supplier<T>)` 与
  `within(Function<DeferScope, T>)`：作用域产出返回值，提交语义不变（正常
  返回 drain、rollback/异常 discard 后不产出）。与 `ScopedCache.within` 的
  三形态对称，另含 `Consumer<DeferScope>` 无返回值形态。
- **观测 SPI 统一（freeway-commons/cloud）** — `Metrics.Timer` 新增
  `record(Duration)` 与 `record(Supplier)` 默认重载（nanos 为规范单位），
  commons 成为全框架唯一观测 SPI。`CloudObserveModule` 的注册表现在以
  primary 绑定覆盖容器 Noop 内置：安装即把 EventBus/HTTP 引擎等全部
  框架计数器汇入 `/metrics`（此前 http/ioc 与 cloud 各有一套不兼容的
  registry，框架计数器默认不可见）。计数器为整数语义
  （LongAdder），Prometheus 文本中计数值不再带小数点。
  `/metrics` 经新能力接口 `MetricsSnapshot` 服务于 primary 注册表——
  ext 后端替换 `Metrics` 且能自行渲染时导出自动跟随，不能则启动期
  路由解析即失败。
- **结构化绑定异常（freeway-ioc）** — 新增 `AmbiguousBindingException` 与
  `UnknownSymbolException`：多命中/双 primary 与符号未命中现在可按类型捕获，
  无需匹配异常消息文本。两者均继承 `IllegalArgumentException`，既有 catch
  站点不受影响。`Container.get` 的 javadoc 同步标注两类抛出。
- **EventBus 订阅查询（freeway-ioc）** — `hasSubscribers(String)` /
  `hasSubscribers(Class)` 补齐与 `CallBus.handles` 对称的查询面。
- **迁移锁 TTL（freeway-db）** — `freeway.db.migration.lock-ttl`（默认 PT1H）：
  进程崩溃残留的锁行超时后自动接管，无需手工 DELETE；设为 0 显式禁用接管。
- **连接拨号登录超时（freeway-db）** — `PoolDefault` 建连前设置
  `DriverManager.setLoginTimeout`（取 `freeway.db.pool.health-check-timeout`）：
  拨号挂死不再无限期占用池许可并阻塞后续借出者。
- **迁移锁过期判定去时区化（freeway-db）** — 过期检查改为在同一查询内读取
  锁行 `executed_at` 与数据库自身 `current_timestamp`，两者经同一 JDBC 读
  路径换算，DB 会话时区 ≠ JVM 时区时的整段时差偏移不再可能提前抢占活锁。
- **DatabaseHub 重名快速失败（freeway-db）** — `NamedDatabase` 贡献中出现重复
  名称现在抛 `IllegalStateException`，不再静默后者覆盖前者。
- **表达式乘除模（freeway-flow）** — 条件表达式支持 `*` `/` `%`
  （优先级介于加减与一元之间；除零/模零显式报错，不产生 Infinity/NaN）。
- **跨传输事件身份与入站去重（freeway-ioc/cloud）** — 一次 `publish` 现在由
  `EventBus` 铸造**一个**事件 id 并交给每一个 bridge，扇出到 N 个传输的同一
  事件因此携带同一身份。此前每个 bridge 各铸一个 id，同一事件的两份副本无法
  被任何消费端关联——跨传输去重在结构上是**不可能**的，而非"尚未实现"。
  `EventBridge` 新增 `send(topic, event, channel, eventId)` 默认方法（默认
  委托三参形态，既有实现不受影响）；`CloudEventEnvelope.translate` 新增带
  `eventId` 的重载。入站侧新增 `publishInboundWithId(event, eventId)` /
  `(topic, payload, eventId)`，配合 `enableInboundDeduplication(capacity)`
  开启有界窗口后，经两个传输抵达本节点的同一事件只投递一次。去重默认**关闭**：
  它改变投递语义且占用内存，不应是安装第二个传输的副作用。配置
  `freeway.cloud.events.dedup.enabled` / `.dedup.capacity`（默认 4096）。
  该组方法刻意**不**实现为 `publishInbound` 的重载——`(String, String)` 调用
  无法在三参 topic 形态与泛型两参形态之间消歧。事件 id 为 null/空白时一律
  投递，旧版生产者不带 id 头不会被丢事件。
- **`MethodHandleUtils.defaultMethodHandle`（freeway-commons）** — 缓存的
  非虚派发方法句柄（`findSpecial`）：在代理接收者上调用接口 default 方法
  的正确句柄形态（`methodHandle` 的虚派发会命中代理自身，无限递归）。
  按 Method 缓存、Lock-free 读取，与既有句柄缓存同构。
- **`ConfigValues`（freeway-ioc，`symbol` 包）** — bind 期类型化配置解析：
  `intValue`/`longValue`/`doubleValue` 以键名+原值报错（如
  `freeway.cloud.rpc.connect-timeout must be an integer: 'soon'`），取代
  散落的裸 `Integer.parseInt`。cloud 四个模块与 ext `freeway-mq-kafka`
  已接入；紧邻其消费的 `SymbolSource`，所有解析配置的模块均可直接使用。

### Changed

- **跨线程事务守卫报错澄清（freeway-db）** — `checkNoForeignTransaction` 的
  异常信息现在明确"另一线程持有本 Database 的事务"并给出两条出路（在事务
  线程上执行、或用独立 Database 实例并发），不再让无关线程的调用者误读为
  自身用法错误。
- **`Orm.of(db)` 补齐 JDBC 强制规则（freeway-db）** — 默认 Coercer 现在与
  `DatabaseBuilder`/IoC 路径一致地携带 Date/Timestamp/Time → java.time 规则，
  generated-key 回写等 Orm 内部转换行为不再因构建路径而异。
- **命名清晰化（freeway-commons/ioc）** — `MethodHandleUtils` 的
  `invoke(handle, receiver, args)` 更名 `invokeOn(...)`：与位置参数形态
  `invoke(handle, args...)` 在调用点不可区分，Lifecycle 曾以
  `invoke(handle, instance)` 表达接收者语义、靠无参句柄的巧合才正确；
  `JULLoggerAdapter.fixCallerInfo` → `applyCallerInfo`（"fix" 预设破损，
  实为设置）；`JsonLeaves.leaf` → `stringForm`。
- **CallBus 注册为容器内置服务（freeway-ioc）** — 开箱即用，与 EventBus 同样
  延迟到全部 @PreDestroy 之后关闭。此前手动 `bind(CallBus.class).to(CallBus::new)`
  的代码会与之形成二义性，需删除该手动绑定或改为 `.primary()`。
- **熔断器/限流器按服务分片（freeway-cloud）** — 注入型实例现作为配置模板：
  每个 serviceId 经 `newShard()` 获得同策略独立状态；一个服务的失败不再污染
  其他服务。自定义实现经接口默认方法保持共享语义。HALF_OPEN 探针超时重置
  收敛到单探针不变量（probeLock）。
- **Tracer span 时长入指标（freeway-cloud）** — span 关闭时冻结时长并写入
  `tracer.span.duration` timer，`/metrics` 可见；`Span.elapsedNanos()` 可编程读取。
- **响应侧 header 校验对齐解析侧（freeway-http）** — `validateHeaderValue`
  拒绝除 HTAB 外的全部 CTL 与 DEL，与 Http1xParser 入站规则完全一致。
- **性能冒烟断言改为地板阈值（freeway-cloud）** — CloudPerformanceTest 各场景
  统一为可通过 `-Dcloud.bench.floor` 调整的数量级守门线（默认 1k ops/s），
  实测数值照常打印，慢 CI 不再误报。
- **`EventBridge.send(topic, event)` 默认通道统一（freeway-cloud/ext）** —
  `CloudEventBridge` 默认 `Channel.CLASS`、`KafkaEventBridge` 默认
  `Channel.TOPIC`，两者不一致；统一为 `CLASS`（两参形态收到的是具体事件对象、
  topic 由类型推导，本就是 class 通道）。总线始终传显式通道，故仅影响直接
  调用 SPI 的代码。
- **CallBus 派发走缓存方法句柄（freeway-ioc）** — 热路径从裸
  `Method.invoke` 切换为注册期解析的 `MethodHandleUtils.invokeOn`，与
  AOP/Lifecycle 的既有惯例一致；业务异常不再经
  `InvocationTargetException` 拆包（方法句柄直接抛原异常），DeadCall
  时的 default 方法降级改用共享缓存的非虚句柄。行为差异仅一处：直接
  `call(topic, payload)` 参数个数/类型不匹配时失败类型由
  `IllegalArgumentException` 变为 `WrongMethodTypeException`/
  `ClassCastException`（同为 RuntimeException，无契约依赖）。

### Removed

- **设计冗余清理（freeway-db）** — 删除 `Orm` 的 `insert/save/update/delete(T, Class<T>)`
  五个 typed 重载与 `BatchQuery.rows(List<Object[]>)`（均无使用点；varargs
  形态已是超集）；删除 `DatabaseStats.averageBorrowWaitNanos()/averageBorrowWait()`。
  同批性能微优化：`Schema.ensure` 每个实体只反射解析一次，命名参数批量执行
  的结构校验只做首行一次。
- **`EventBus.setEventBridge`（freeway-ioc，破坏性）** — 单槽 setter 与多
  bridge 扇出不可共存：后启动的模块会静默卸载先前模块已安装的通道（正是文档
  所述"静默分区"的成因）。`addEventBridge`/`removeEventBridge` 现为唯一的
  通道 API，安全性质由 API 形状结构性保证，不再依赖运行期拒绝。调用方改用
  `addEventBridge`。
- **`EventBus(Container, EventBridge)` 双参构造函数（freeway-ioc）** — 最后
  一个单槽接缝，两仓库零调用点（容器用 `new EventBus(this)`）；与新定的
  多 bridge API 自相矛盾，一并删除。
- **`fwtimes` 信封扩展（freeway-cloud，线格式）** — 恒为 1、两仓库零读取方，
  且它要承担的两个用途已被取代：mesh 内不存在转发（入站走
  `publishInbound`、不回桥），跨传输的两份副本代数同为 1，故它无法用于幂等
  判断；死信诊断实际走 Kafka 的 `X-DLQ-Original-Offset`/`X-DLQ-Reason`。
  幂等现由总线铸造的共享事件 id 加 `EventBus` 入站去重窗口承担。
  **线上兼容**：`parse` 只按需读取已知键、忽略其余扩展属性（必填为
  `specversion`/`id`/`source`/`type`/`fwchannel`），因此未升级节点发来的帧
  仍带 `fwtimes` 也能正常解析，新旧节点可混跑。

### Fixed

- **freeway-db** — 删除 `DatabaseImpl.startsWithInsert` 单行包装：其名字
  描述朴素前缀匹配，实际语义是顶层 INSERT 检测（跳过 CTE/注释），调用点
  直接使用名字准确的 `SqlTextParser.hasTopLevelInsert`；query/execute/
  batch 三处重复的事务绑定表达式提取为 `currentTx()`。
- **freeway-ioc** — 注入点同时携带服务注入与配置注解时的错误消息改带
  owner 类型名（此前输出匿名对象 `InjectResolver$1@...`，诊断信息为零）；
  `normalizedId` 签名收窄为 `Inject`（原 `Annotation` 形态的 else 分支按
  构造不可达）；`ContainerImpl` 三个 get 重载的 closed 双查与缺失绑定
  报错提取为 `requireOpen()`/`missingOrClosed()`（命中路径保持零分配）；
  `BindingIndex.duplicateMessage` 更名 `duplicateBinding`（返回的是异常
  不是消息）。
- **freeway-boot** — `AppConfig.get(SymbolSpec)` 不再每次调用分配新
  `CoercerDefault`（嵌套 holder 共享单例）；`loadJson` 复用已解码文本直接
  解析，去掉二次字节流包装；负数 CLI 值识别扩展到指数形式
  （`--port -1e5`）；`openBoundedStream` 更名 `findBoundedStream`（可空
  语义）、`putAllIgnoringProfileActivationKey` 缩名并提取
  `PROFILE_KEY` 常量；FreewayApp 头部格式对齐。新增
  `HookLifecycleTest` 覆盖启动失败回滚、Error 透传与抑制链。
- **freeway-commons** — `SymbolSpec.parse(raw, coercer)` 在无 parser 且
  coercer 为 null 时不再裸 NPE，改为带键名的 `IllegalStateException`
  （附首个 config 包专属测试类 SymbolSpecTest）；`Defer` 延迟供给的
  compute/get 双份执行逻辑合并（失败缓存与恰好一次语义不变）；
  `ScopedCache.onClose` 清理叠置的残留 javadoc。
- **freeway-commons（logging）** — `LogBootstrap.ensureProvider()` 在 SLF4J 已被
  过早的静态 logger 绑定为 JUL 兜底时，不再打印误导性的 "pinning so it wins"，
  而是绑定后核验实际生效的 provider 并给出可操作的 WARN（指认检测到的外部
  provider、说明成因、给出 `-Dslf4j.provider=` 修复指引）；
  `freeway.log.console.enabled=false` 不再移除用户定制的 ConsoleHandler
  （formatter 非原版 SimpleFormatter 即视为用户配置），只移除 freeway 自有与
  JVM 原生态的 handler——所有权契约三级化并文档化（自有/原生态/定制）；
  env 反解对带连字符键的折叠失配修复（`FREEWAY_LOG_FILE_MAX_SIZE` 现能对账回
  `freeway.log.file.max-size`）；`configure()` 中强制 LogManager 初始化失败
  不再中断整个日志配置。
- **freeway-cloud**：注入路径的熔断/限流分片失效（容器代理导致 instanceof
  失败，所有服务共享同一实例——一个服务打熔断即拒答健康服务）；HALF_OPEN
  探针丢失后的并发重置竞态。
- **freeway-ioc**：EventBus 流桥在"取 publisher 后、首次订阅前总线关闭"
  的窗口内挂死订阅者或让 ISE 冲出 subscribe()（Flow 规范要求不抛）；桥关闭
  后迟到订阅者现在按 onError 结算。
- **freeway-flow**：PARALLEL 分支在 executor 已 shutdown 时的 RejectedExecution
  使 join latch 泄漏（await 永挂）；现记录首个错误并释放对应槽位，后续分支
  快速跳过。嵌套 PARALLEL + 定长池的死锁风险写入 FlowDriverDefault 文档。
- **freeway-db**：同一 Database 实例上并行事务互相覆盖线程守卫注册表；
  BatchQuery autoCommit 恢复失败现销毁物理连接而非归还脏连接；迁移锁重复键
  判定从 "23xxx 前缀 + 宽泛关键词" 收紧为精确状态码（23505/23000/40001）+
  窄关键词，check/not-null 违规不再误报为锁竞争（附表驱动回归测试）。
- **freeway-http**：HPACK header 名校验改用 Locale.ROOT（土耳其语环境不再误判）。
- **freeway-commons**：AOP 热路径的全局同步 MethodHandle 缓存改为 ClassValue
  分层并发缓存（读无锁），AdvisedHandler 另加每代理句柄缓存；Coercer 重注册
  在 assignable-source 索引中留下重复条目；JSON 解析器 BOM 判定的裸 U+FEFF
  字符改为转义写法。
- **freeway-ioc**：EventBus/CallBus 从容器构造期急切实例化改为**首次解析时
  惰性 realize**（标准单例路径）——此前构造期 `container.get(Metrics)`
  冻结预加载的 NoopMetrics，模块供给的 primary 注册表永远收不到
  `eventbus.*`/`callbus.*` 计数（与观测模块的文档承诺相悖）；Shutdown
  语义不变（未解析的总线无需关闭，close 期间解析的仍走延迟关闭）。
  新增 `BuiltinMetricsWiringTest` 固化行为。
- **freeway-cloud**（审计修复批）——`ObjectStorageDefault`：`delete` 仅在
  真实移除对象时发 `ObjectDeletedEvent`（此前对不存在的键也发幽灵删除
  事件，违背接口 no-op 契约）；`put` 改为临时文件 + 原子替换
  （ATOMIC_MOVE 降级 REPLACE_EXISTING），目标处 symlink 被替换链接本身
  而非被跟随，检查与写入间不再有 TOCTOU 窗口；`list` 跳过指向根外的
  symlink（与 get 的拒绝语义对齐，不再泄露外部文件名）。
  `BaggagePropagator`：键值百分号编码（RFC 3986 unreserved 之外的
  UTF-8 字节全转义），含 `,`/`=`/空格/非 ASCII 的 baggage 无损往返，
  不再损坏线上结构；提取对畸形转义容忍降级。`CloudHttpClientDefault`：
  派发期非预期本地异常（坏 URL/头、discovery 后端缺陷）统一映射为
  `CloudException.dispatch`（retryable=false）——半开探针不再因异常
  逃逸而丢失结局；限流/熔断拒绝计入 `cloud.rpc.failures` 与 duration。
  `Endpoint`：构造期校验 URI 可渲染性并规范化 basePath（补 `/` 前缀、
  去尾斜杠、IPv6 括号容忍），client 侧相应移除尾斜杠兼容拼接。
  `PeerConnector`：peer 地址解析支持 IPv6（方括号/裸字面量）并校验端口，
  新增握手看门狗（socket 打开 10s 未完成 hello/ack 即中止重连）。
  `CloudConfigDefault`：变更通知改为专用单线程按序投递——`reload()`
  换快照不再被慢监听器阻塞。`CloudObserveModule`：primary `Metrics`
  不实现 `MetricsSnapshot` 时启动期报命名错误（原为裸 ClassCastException）。
  `ReadyHandler`：贡献者重名改为构造期失败（原先每次健康探测 500）。
  `HttpServiceDeclaration`：注册 bind-all 地址（`0.0.0.0`/`::`）时启动
  告警提示 service-host 覆盖；WebServer 缺绑定判定精确捕获
  `MissingBindingException`。

### Removed

- **freeway-cloud**：`observe.MeterRegistry` 接口删除（与 commons `Metrics`
  双轨并存、类型语义漂移 long/double 与 nanos/Duration）；实现类
  `MeterRegistryDefault` 更名 `MetricsDefault` 并改为实现 commons
  `Metrics`，快照访问器与 Prometheus 文本导出不变。自定义观测后端请直接
  实现 `com.jujin.freeway.commons.metrics.Metrics`。
- **freeway-ioc**：`Extension.of(...)`（全仓零引用）；`Contributions.add(Class)`
  的 default-throw 实现改为抽象方法（唯一实现方 BinderImpl 本就重写）；
  internal 的 InstanceFactory 类折叠进 ContainerImpl。

### Test

- **freeway-cloud**：`PrincipalPropagationTest` 的 `@AfterEach` 未清除
  `freeway.cloud.auth.extract.enabled`，系统属性泄漏到后续测试类，
  `SecurityTest.authPropagatorIgnoresInboundIdentityByDefault` 在全量套件
  中按类顺序稳定误报（单独跑通过）——补齐清理。
- **freeway-ioc 测试重组** — 2900 行的 FreewayTest 单文件按域拆分为 9 个测试
  类 + FreewayFixtures/FreewayTestSupport；1000 行的 EventBusTest 拆分为 6 个；
  流式视图的关闭竞态、SSE 泵的取消竞态各补确定性回归测试。


## [1.3.9] — 2026-08-24

### Added

- **EventBus 流式视图（freeway-ioc）** — 新增 `stream(Class)` / `stream(String)` 返回 JDK
  `Flow.Publisher`（零外部依赖，规范即 `java.util.concurrent.Flow`）。桥接器基于
  `SubmissionPublisher`：冷启动懒挂载（首次下游 subscribe 才向总线注册，未消费零泄漏）、
  背压溢出即弃（不阻塞总线派发线程）、任一下游 cancel 即整体摘除、close 时全部流收到
  onComplete。活跃流是真实订阅者——被流的主题不再发 DeadEvent，delivered 按桥接计一次。
- **SSE 流泵接（freeway-http）** — `SseEmitter.from(Flow.Publisher)` /
  `from(publisher, mapper)` 把事件流直通 SSE 响应：request(1) 单飞背压沿 TCP 逐级上传，
  阻塞当前虚拟线程直至源结束（配合 handler 的 try-with-resources），源完成关响应、源失败
  debug 日志后关闭、客户端断开经 latch 事件驱动唤醒并取消上游订阅。心跳在空闲期照常保活。
- **CallBus 请求-应答总线（freeway-ioc）** — topic 寻址的本地 RPC：提供者
  `register(mapping, target)`（public 方法即 `mapping.methodName` 主题，槽位式热交换、重载
  拒绝）；消费方 `consumer(mapping, api)` JDK 动态代理或 `call(topic, List args[, Duration])`
  直接调用；位置参数编码（不依赖 `-parameters`）；异常统一经 stage 传递（RuntimeException
  原样、检查型按 join/get 惯例包装）；无监听抛 `DeadCallException`（DeadEvent 的应答侧对
  应物），代理自动降级到接口 default 方法。事务内调用内联派发、语义等同本地方法调用——
  提交后才该发生的副作用是事实，发布到 EventBus 的 Defer 缓冲（避免阻塞取答死锁）。
  Metrics 四计数器（callbus.called/served/failed/dead）镜像 eventbus 风格。
- **CallBus 调用链切面（freeway-ioc）** — `advise([selector,] advice)` 环绕切面：值空间
  短路（返回即缓存命中应答 / 抛出即熔断快速失败）、注册序分层次、advice 可见业务异常
  （反射 ITE 已在终端链节解包）。与 freeway-ioc AOP 的 `MethodAdvice` 同构。

### Changed

- **EventBus 入站通道（freeway-ioc）** — 新增 `publishInbound(Object)` /
  `publishInbound(String, Object)` 发布外部来源事件（如 MQ 订阅者回灌）:本地分发语义与
  `publish` 完全一致（含 Defer 缓冲/DeadEvent）,但**绝不回桥接 MQ**——入站事件再桥接会
  无限循环回队列。`EventBridge` 新增 `Channel` 枚举（CLASS/TOPIC）与三参
  `send(topic, event, channel)` 默认方法（二参实现不受影响）,桥接适配器可据此在线上信封
  标记分发通道,入站侧按标记对称分发。配套的 Kafka 适配器改动见 freeway-ext。
- **`EventBus.Keyed` 分区键（freeway-ioc）** — 事件类可选实现嵌套契约
  `EventBus.Keyed { String key(); }`（与 `EventBus.Stoppable` 同构）,桥接适配器以其
  返回值作为消息 key（Kafka record key）:同一聚合的事件跨 JVM 保持有序、消费端可按 key
  并行处理。未实现的事件仍以 null key 桥接（无跨 JVM 排序保证）。事件与框架零耦合。
- **cloud type 键校验（freeway-cloud）** — `BackendTypeGuard`:本地后端装配时校验
  `freeway.cloud.{config,secret,storage,discovery,registry}.type` 键,值为非 `local`
  且未装对应 ext 适配器时 warn（不再静默忽略）,对齐 marker 回退的"非完全静默"原则。
- **观测接线（freeway-cloud）** — 安装 `CloudObserveModule` 后 `CloudHttpClient` 调用
  打 span（`cloud.rpc.<service>`）并记指标（`cloud.rpc.calls`/`failures`/`duration`）;
  `freeway.cloud.rpc.trace.enabled`（默认 true）控制 span 创建,指标始终记录。
- **Baggage 跨进程传播（freeway-cloud）** — 实现 `BaggagePropagator`（W3C `baggage`
  头,k=v 逗号分隔）,应用自有 KV 随 HTTP 请求双向跨越服务边界,兑现 `Baggage` 的
  "propagated across service boundaries" 承诺;trace/auth propagator 的 extract 空值
  语义修正（未设置返回 null,不再用空值覆盖后续传播的 baggage）。
- **cloud 结构清理（freeway-cloud）** — `TransportSecurity` 绑定去掉静态 `@None`
  marker（能力由运行时配置决定,静态 marker 表达不了条件能力;`@Mtls` 保留为 ext
  契约面）;hook 名集中到 `CloudHooks` 常量并引用 `HttpModule.SERVER_HOOK`;删除唯一
  冗余 `.scope(SINGLETON)`（默认即单例）;`PropagationFilter` 下沉 `internal`;
  `/health/ready` 内置 `RegistryHealthContributor`（registry 连通性 + 实例数,ext
  后端连通性由适配器提供）;修复 4 处空 `{@code }` javadoc 块。

- **SLF4J provider 选择确定性化（freeway-commons）** — `LogBootstrap.ensureProvider()` 在 SLF4J
  初始化前探测 classpath 上的外部 provider 并固定 `slf4j.provider` 系统属性（优先序
  logback > log4j > slf4j-simple），外部 provider 存在时 JUL 回退不再启用。此前 provider
  由 classpath 顺序决定，freeway-commons 作为基础依赖通常先出现，可能静默顶掉 Logback 使其
  配置（logback.xml）失效。用户显式设置的 `-Dslf4j.provider` 始终优先、绝不覆盖；JUL 增强
  只在 JUL 实际生效时配置。
- **`@Inject("id")` 限定注入（freeway-ioc）** — `List<V>`/`Map<String, V>` 注入点带显式 id 时
  优先解析同 id 的绑定服务（此前绑定为 `List<X>` 的服务会被贡献集合永久遮蔽），无绑定才回退
  贡献视图。构造参数仍隐式消费贡献（无注解 `List`/`Map` 参数即贡献集合），字段注入仍需显式
  `@Inject`；注入 `Extension<V>` 显式拒绝，提示改用
  `@Inject List<V>` / `@Inject Map<String, V>`。
- **`${name:-default}` 默认值语义修正（freeway-ioc，行为变更）** — 默认值剥离单个前导 `-`，
  `${port:-8080}` 解析为 `"8080"` 而非 `"-8080"`，与文档宣传的 shell 语义一致。
- **CLI 解析严格化（freeway-boot，行为变更）** — 空键参数（裸 `--` / `-D`）与含 `=` 的键
  （`--=x`）直接拒绝并报错，不再静默产生 `freeway.` / `freeway.=x` 垃圾键；位置参数 WARN
  忽略并提示使用 `--key=value` / `--key value` / `-Dkey=value` 形式。
- **空 `application.json` 视为无配置（freeway-boot，行为变更）** — 空/空白 `application.json`
  不再启动即崩，与空 properties 一致按"无配置"跳过，四层配置文件行为统一。
- **重复模块 fail-fast（freeway-boot，行为变更）** — 显式传入同一模块类的两个不同实例（如
  `new DbModule("ds1")` + `new DbModule("ds2")`）启动即失败并给出指引，不再静默丢弃后者的
  配置；同一实例重复传入与匿名/lambda 模块仍宽容去重；显式实例优先于 SPI 发现。
- **H2 伪头校验（freeway-http，行为变更）** — HTTP/2 请求 `:path` 必须为 origin-form（以 `/`
  开头）、`:authority` 按 HTTP/1.1 Host 规则校验（拒 `@`/空白/控制字符）、非 CONNECT 请求
  `:authority` 可选；非法 `:path`/`:authority` 被拒，与 HTTP/1.1 行为对齐，堵住代理混淆/走私。
- **HTTP/1.1 控制字符拒绝（freeway-http，行为变更）** — 请求头值中的控制字符/非 token 字节被
  拒（此前裸 CR 可流入 `X-Request-Id` 回显路径导致整个会话 500，也是弱响应拆分原语）；
  `Content-Length` 严格 `1*DIGIT`，`+5` 之类不再被接受。
- **`bodyAsJson` 媒体类型校验（freeway-http，行为变更）** — 类型化 body 读取要求 `Content-Type`
  为 `application/json` 或 `application/*+json`；缺失或类型不符抛
  `UnsupportedMediaTypeException`，由内置异常映射为 **415**（此前为非法状态 → 500）。
- **MySQL DDL 迁移守卫（freeway-db，行为变更）** — 方言不支持事务性 DDL（MySQL/MariaDB）时，
  含 DDL 语句的迁移在事务前 fail-fast 并给出修复指引（拆分迁移、语句幂等化）；此前 DDL 隐式
  提交落库而校验和行丢失，下次启动重跑 DDL 直接失败、启动永久卡死。
- **跨线程事务拒绝（freeway-db，行为变更）** — 事务内从其他线程执行 DB 工作被显式拒绝并抛
  `SqlException`；此前 `ScopedValue` 不随线程传播，子线程写入跑在独立连接上，父事务回滚后
  依然提交，原子性静默破裂。
- **未知 JDBC URL fail-fast（freeway-db，行为变更）** — 无法识别的 JDBC URL scheme 启动即失败
  并列出受支持的 scheme（mysql/mariadb/sqlite/h2/postgresql），不再静默回退 PostgreSQL 方言
  （此前 Oracle/SQL Server/DB2 URL 会生成 PG 语法）；普通 `jdbc:h2`（无 MODE）改用原生
  `H2Dialect`。
- **方言能力扩展（freeway-db）** — `Dialect` 新增 `backslashEscapesStrings()`（MySQL `\'`
  反斜杠转义串正确 lex）与 `supportsTransactionalDdl()`（MySQL false）能力；
  `Schema.ensure()` 在非事务 DDL 方言上于用户事务内显式拒绝；`execute(Sql)`/`query(Sql)`
  过方言校验，RETURNING 语义文档化。
- **网关死路抛 `FlowException`（freeway-flow，行为变更）** — EXCLUSIVE 无匹配且无默认分支、
  join 缺少到达分支时，eval 完成即抛 `FlowException` 指明图与节点；此前仅 WARN、任务静默
  丢失。拦截器阻断的运行豁免。
- **表达式求值语义修正（freeway-flow，行为变更）** — `&&`/`||` 短路求值（此前两操作数总是先
  求值，`false && (x - 1)` 抛异常）；一元负号完整实现（`-x`、`-(a+b)`、`--x`，类型保持）；
  Number/String 混合比较按数值进行（`"10" > 9` 为 true，此前按字典序失真）。
- **Graph v2 版本门统一（freeway-flow，行为变更）** — `Graph.fromText` 与 `GraphSpec.fromText`
  共用同一版本校验，仅 canonical v2 文档（`version: 2` + `nodes`/`links`）可加载；此前 v1
  文档经主 API 静默加载。
- **Bean 序列化 getter 读路径（freeway-commons）** — JSON 写出优先走 getter（getter-only/
  计算属性不再从输出静默消失），支持 `isX()` 布尔约定，getter-only 属性按只读处理。
- **内部重构（全模块）** — 大量内部去重与死代码清理（共享 helper 抽取、JSON 写容器骨架统一
  等），行为不变。
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

- **keep-alive 状态隔离（freeway-http，安全）** — 连接复用前 `ExchangeMetaDefault.reset()` 清空
  principal/attributes、轮换 correlationId、刷新 startTime，`HttpContextDefault.reset()`
  先清后应用 `X-Request-Id`；此前同 socket 上请求 N 的认证上下文对请求 N+1 可见（未认证请求
  "被认证"）、correlationId 跨请求复用。
- **HPACK Huffman 解码重写（freeway-http）** — 静态 `CODE_BY_LENGTH` 前缀码表取代每符号全表
  扫描，单符号解码降为 O(1) 查找；此前 64KB 恶意头部块约 13 万符号、数十亿次循环，单连接读
  线程被钉死（未认证攻击面）。
- **accept 循环自愈（freeway-http）** — 瞬时错误（EMFILE/ENOBUFS/EINTR）50ms 有界退避重试，
  仅"关闭"条件 break；首错 ERROR、后续 DEBUG 降噪。此前任何瞬时 IOException 永久退出，监听器
  死透而引擎仍报 started，新连接在 OS backlog 层被静默拒绝。
- **`maxBodySize` 流式强制（freeway-http）** — `LimitedInputStream` 在流式读取路径按
  `maxBodySize` 计数过滤，`bodyStream()` 边读边写不再可绕过限制（此前限制只落在
  `readAll()`/排空路径）。
- **WebSocket 空闲连接（freeway-http）** — WS 升级后清除 `SO_TIMEOUT`，长空闲连接不再被无
  close 帧的 1006 强拆（此前空闲超过 readTimeout 即被杀，与 H2 路径行为不一致）。
- **静态文件与路由修复（freeway-http）** — 子目录 `index.html` 正常服务、无资产的挂载不再阻断
  后续挂载（hasResource 探测）；路由正则匹配段长上限 1024（ReDoS 缓解）；If-Range 按秒截断
  比较，修复断点续传被答成完整 200；HEAD + sendfile 只写响应头（RFC 9110）；
  `WebServerBuilder` 自定义异常映射器改为追加而非整体替换内置映射；`status()` 校验 100-599；
  注册百分号编码字面段的路由可匹配（编码斜杠防碰撞）；非 ISO-8859-1 响应头值显式拒绝（此前
  静默变 `?`）。
- **HTTP/2 协议加固（freeway-http）** — 截断的 HPACK 整数抛 `COMPRESSION_ERROR`（此前
  `ArrayIndexOutOfBoundsException`，对端收不到错误码）；`SETTINGS_HEADER_TABLE_SIZE` 做
  uint32 范围校验、解码器负值 clamp；字面头名做 token 校验（伪头豁免）；204/205/304 响应
  丢弃 `Content-Length`（HEAD 保留）。
- **`Orm.save()` 原始类型主键（freeway-db）** — 原始类型 `@Generated` 主键的零值视为"未设置"，
  走 insert 拿自增键并回写；此前 `long` 主键显式写 0 绕过序列（upsert 路径），二次 save 更新
  0 行。
- **迁移校验和双轨（freeway-db）** — 校验和以原始字节为准，另按 CRLF→LF 归一化复检，向后
  兼容；此前文件在 Windows 检出换行即误报 checksum mismatch。`one()` 多行截断、空集合
  `IN (:ids)` 错误指引、跨库事务无 XA 均文档化并测试固化。
- **连接池竞态（freeway-db）** — `PoolDefault` borrow/close 竞态修复（`handOut()` 锁内复检），
  配套确定性竞态测试；introspection 失败 fail-fast，不再被 `Dialect.querySet` 吞成空集后
  重复建索引。
- **IoC 容器生命周期（freeway-ioc）** — close/realize 竞态再加固：锁内原子置 closed + 末轮
  排水，`get()` 统一报 closed（并发慢构造的实例不再成为无 `@PreDestroy` 的孤儿）；THREAD
  作用域代理 close 后拒绝调用（与单例路径契约一致）；advised PROTOTYPE 每代理懒缓存（同一
  proxy 上多次调用同一实例，此前每次调用新建目标）；`Binding.id()` 变更迁移缓存（晚变更 id
  不再实例化出第二个单例）；final 字段携带注入注解 → 构造期 fail-fast（此前静默跳过）。
- **EventBus 隔离与排空（freeway-ioc）** — 订阅者抛 `Error` 不再逃逸（catch Throwable 继续
  派发，与此前异常隔离一致）；`@PreDestroy` 抛 Error 不中断 drain；Defer 内 async/ordered
  发布在总线关闭后静默排空（此前打出虚假告警）；`publish(String)` 是类事件而非 topic 的语义
  文档化并测试固化。
- **启动装配（freeway-boot）** — start() 期间重入 close() 状态机正确（hook 完成后复检
  `shutdownAttempted`，不再覆盖 RUNNING 或补发 `AppStartedEvent`）；`AppBuilder.start()` 单次
  使用 AtomicBoolean 守卫（并发 start 不再建两个容器两个 shutdown hook）；hook 排序引用未知
  id → 启动失败（`Extension.validateOrdering()` opt-in，落实 AGENTS.md 回归要求）；
  `AppConfig.get(SymbolSpec)` 默认以 CoercerDefault 解析（此前无 parser 形式的 spec 抛
  IllegalStateException）；profile 层剥离 `freeway.profile`（config 读取与 `profiles()` 不再
  分叉）。
- **JSON/反射/Defer（freeway-commons）** — 自引用泛型界（`Node<T extends Comparable<T>>`）
  反序列化以 visited-set 回退 `Object`，不再 StackOverflowError；JSON 数字 token 10MB 上限、
  `parse(String)` 32MB 输入上限（此前超长数字串经 BigInteger/BigDecimal 造成 CPU/内存尖峰）；
  `DeferScope` 重复 id 注册期校验，排序失败时按注册序执行全部动作并重抛（此前静默跳过全部
  延迟动作）；字符串 `"NaN"` 与 Infinity 一致拒绝（此前不对称，NaN 转 boolean 得 false）；
  JSON 重复键 last-wins 语义文档化并测试固化。
- **日志文件处理（freeway-commons）** — 同一路径日志文件全局 handler 去重（规范化绝对路径
  注册表，reset 后自动换新；此前两个 handler 共享文件轮转互踩，记录静默进归档/丢失）；purge
  排除 `.gz.tmp` 与压缩中源文件（此前压缩原子改名失败、归档静默丢失）；
  `freeway.log.console.level` 只作用于 freeway 自有 handler（不再覆盖用户自配的
  ConsoleHandler）。
- **Flow 执行修复（freeway-flow）** — `$for` LOOP 原子抢占（并发重入不再双执行）；子图继承
  调用方 per-eval 拦截器（不重复执行）；全新运行清 EventBus 订阅（子图豁免）；`onNodeStart`
  抛异常时 `onNodeEnd` 仍配对；INCLUSIVE join 计数按迭代重置；`putAll` 过滤 null 与 `put`
  一致；`IocContainerAdapter` 仅"无绑定"回退 null，真实错误重抛。
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
  双检）；`Metrics` 观测 SPI（counter/gauge，零依赖，
  容器 builtin 默认 NoopMetrics、可 primary 覆盖）——EventBus 接入
  （published/delivered/subscriber_failures/dead_events 镜像计数）；
  `SymbolSpec<T>` 类型化配置（解析 + 默认 + 含 key 上下文的错误消息，
  `AppConfig.get(SymbolSpec)` 统一入口——替代分散 parseInt；**无 parser
  形式（Coercer 默认解析）**：`of(key, type, default)` + `parse(raw, Coercer)`
  支持 Duration/"2s"/用户 CoerceRule——DbModule 池配置全量适配（URL/USERNAME
  required、池大小 parseInt、6 个 Duration 键走 Coercer，手写 helper 删除）。
  **移至 commons.config**（http/db 等模块不依赖 boot 也可声明类型化键）；
  新增 `required()` 工厂（命名经评估：ConfigKey → SymbolSpec，区分裸 key 常量族）（缺失/空白 fail-fast，不再静默回默认）。+10 测试。

- **WebSocket 空闲保活（freeway-http，修复）** — 升级成功的 WebSocket 在 101 响应后
  清除 socket 读超时：空闲连接不再被默认 30s readTimeout 以 1006 异常关闭（此前
  无帧交换的连接约 30s 后被强制断开）。死连接仍由 TCP keepalive 探针回收，不发送
  服务端 ping。`WebSocketIdleTimeoutTest` 修正为真实绑定 1s readTimeout 覆盖该路径。
- **`HttpServerConfig.builder()`（freeway-http，新增）** — 具名 setter 的配置构造，
  避免长位置参数把 `Duration` 绑错槽位（如 readTimeout 落到 shutdownGrace）；
  默认值与 canonical 构造一致，`build()` 走同一套校验。
- **`AbstractHttpContext.readBodyLimited` 改名（freeway-http）** — 请求体限流读取助手
  更名为 `readBody`（语义不变），旧名已移除，调用方请改用新名。
- **`WebSocketRoute` record 形状简化（freeway-http）** — 移除从未参与匹配的 `pattern`
  组件（匹配由路由 trie 按 path 完成）；3 参构造器一并移除，请用
  `WebSocketRoute.of(path, endpoint)`。

- **JSON 媒体类型判断补齐 `+json` 后缀（freeway-http，修复）** — `MediaTypes.isJson`
  现在接受 `application/*+json` 结构化语法后缀（如 `application/vnd.api+json`、
  `application/json-patch+json`），与 `bodyAsJson` 415 校验的文档承诺一致。
- **容器关闭时 EventBus 最后关闭（freeway-ioc，修复）** — `@PreDestroy`/`close()`
  回调里 publish 事件不再因 EventBus 先于其他服务被关闭而抛
  `IllegalStateException`、把良性关闭放大成 shutdown 失败；关闭期间发布的事件
  正常投递。
- **继承层级 lifecycle 方法冲突 fail-fast（freeway-ioc，行为变更）** — 子类与父类
  声明**不同名**的 `@PostConstruct`/`@PreDestroy` 时启动即报错并点名两个方法
  （此前静默只执行子类的、丢弃父类的 init/cleanup）；同名重写仍只执行子类一次
  （Java 重写语义）。
- **非可写属性注入报错区分（freeway-ioc，改进）** — `@Inject` 命中不可写属性时，
  final 字段与 getter-only 派生属性给出各自的准确报错与修复指引（此前统一报
  "Cannot inject into final field"）。

### Docs

- **代码审查报告** — 新增 `docs/CODE-REVIEW.md`：三轮修复（S1/S2/S3，共 68 项）的修复状态
  清单、总体评价、跨模块一致性观察与测试覆盖分析；三轮修复后 **1517 个测试全绿**
  （commons 374 / ioc 182 / boot 60 / http 349 / db 453 / flow 99）。
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

[Unreleased]: https://github.com/dzb/freeway/compare/v1.5.1...HEAD
[1.5.1]: https://github.com/dzb/freeway/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/dzb/freeway/compare/v1.4.0...v1.5.0
[1.3.1]: https://github.com/dzb/freeway/compare/v1.2.2...v1.3.1
[1.1.1]: https://github.com/dzb/freeway/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/dzb/freeway/compare/v1.0.0...v1.1.0
