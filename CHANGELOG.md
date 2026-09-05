# Changelog

All notable changes to Freeway 2 will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **CLASS 通道入站默认拒绝（freeway-cloud，安全，破坏性）** — `PeerHub.receive()`
  的 CLASS 通道 deserialization 改为 deny-by-default：`allowed-types` 未配置时
  一律丢弃 CLASS 事件，不再回退到"接受任意类"。此前空 allowlist 语义为"放行任意
  类"，配合未认证端点构成任意类加载 + 反射反序列化面；TOPIC 通道（通用 JSON、
  无类解析）保留"空 = 全放行"的既有文档语义不变。`allowed-types` 为空的启动告警
  文案同步更新为"CLASS 事件将被丢弃"。E2E 测试 `keyedEventsPreserveTheOrderingSubject`
  相应改为显式白名单其类型。
- **freeway-cloud 内部结构收敛（无行为变更）** — 按结构审计报告
  `AUDIT-CLOUD-STRUCTURE-2026-09-04.md` 落地一组机械重构：`PeerConnector` 的地址
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
  配置激活）时，框架的默认实现定名 `XDefault`，与功能包归属无关——可替换默认作为公开
  扩展点安置在各自功能包（cloud 的 11 个可替换默认自 `internal` 迁入 discovery /
  resilience / observe / storage / secret / rpc），即便留在 `internal` 也不改变定名
  （如 `db/internal` 的 `PoolDefault` 仍可被外部 `.primary()` 替换）；不可代换的实现件
  定名 `XImpl`。
  定稿的净更名（相对 1.4.0）：
  - `HttpContextDefault`→`HttpContextImpl`（内建引擎的上下文契约，ext 引擎自带各自实现）、
    `LoggerSourceDefault`→`LoggerSourceImpl`（注入路径硬连线单例，从不查询绑定）、
    `PooledConnectionDefault`→`PooledConnectionImpl`（逐池工件类型，Hikari 适配器自带
    自有连接类型）、`TransportSecurityDefault`→`TransportSecurityImpl`（模块内部实现件，
    经 `fromKeyStore` 配置激活）——这四类角色不再承诺外部可代换；
  - `FlowEngineImpl`→`FlowEngineDefault`（默认流程引擎）；`ExchangeMetaDefault` /
    `CoercerDefault` / `ProxyFactoryDefault` 维持可代换默认定名不变（`ExchangeMeta` 是
    ext 引擎构造的默认交换元数据；`Coercer` 规则经公开接口消费、可构造选择替换；
    `ProxyFactory` 是容器惰性/增强绑定代理的默认实现）；
  - `MetricsDefault` 的私有内部类 `DefaultCounter` / `DefaultTimer` → `CounterImpl` /
    `TimerImpl`（清除 `DefaultX` 前缀残留，无 API 影响）。
  判据与 `internal` 语义（不承诺稳定的实现细节，如 `RegistryStore` / `ConfigLists` /
  `TransportSecurityImpl`）写入 `CLAUDE.md` / `AGENTS.md` / `DEVELOPER-GUIDE.md`，各文档
  中的改名类引用统一为现行类名；freeway-ext 引用同锁步更名。
- **cloud 配置键单源化与 `Wiring` withers（freeway-cloud，无行为变更）** — 七个配置键
  的默认值在 `CloudConfigKeys` 以 `*_DEFAULT` 常量单点声明（RPC TLS keystore/truststore
  四键空串默认、storage base-path、registry service-scheme），配置层与库内兜底不再双写；
  RPC 请求/连接超时与 peer 网络超时常量在 config spec 与 `CloudHttpClientDefault.Wiring`
  的库兜底间共享同一来源（未装模块时直接复用常量，两层不可能漂移）。`Wiring` 增 7 个
  wither（`withPropagators` / `withRetryer` / `withBreaker` / `withRateLimiter` /
  `withTransport` / `withRequestTimeout` / `withConnectTimeout`）供逐步定制。

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

[1.3.1]: https://github.com/dzb/freeway/compare/v1.2.2...v1.3.1
[1.1.1]: https://github.com/dzb/freeway/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/dzb/freeway/compare/v1.0.0...v1.1.0
