# Freeway 包体结构审计（2026-09-05）

> 范围:核心仓 8 个 Maven 模块的 `src/main/java` 包结构与跨模块边界;
> 判断口径以 `CLAUDE.md` 的 Architecture Boundaries / Naming Rules 为准。
> 本报告只做分析与建议,不含代码改动。所有结论均可按给出的文件路径复核。

## 1. 总体库存

| 模块 | main 文件 | 包数 | 测试文件 | 依赖(compile) |
|------|----------:|-----:|---------:|---------------|
| freeway-commons | 54 | 8 | 24 | 无 |
| freeway-ioc | 69 | 6 | 31 | commons |
| freeway-boot | 15 | 2 | 7 | ioc, commons |
| freeway-http | 118 | 13 | 45 | ioc, commons (+boot test) |
| freeway-db | 50 | 6 | 25 | commons, ioc(仅 DbModule 引用) |
| freeway-flow | 37 | 1 | 4 | ioc, commons |
| freeway-cloud | 87 | 12 | 32 | ioc, commons, http (+boot test) |
| **合计** | **430** | **48** | **168** | — |

包数含模块根包;测试方法总数 1864,全绿。

## 2. 模块级事实与观察(已实证)

### 2.1 依赖图与文档图一致(一处修正)
- pom 实测依赖与 CLAUDE.md 模块图一致,仅两处细节:
  - `freeway-http → freeway-boot` 与 `freeway-cloud → freeway-boot` 均为 **test scope**;
    cloud 的 main 源码对 boot **零 import**(CLAUDE.md 模块图里 cloud 标了 boot,建议图注或文字同步为 test-only,或删去该边)。
  - `freeway-db → freeway-ioc` 为 compile 且非 optional,但 db 的 main 源码里 ioc 引用**只有 DbModule 一个文件**(Library → DbModule 模式成立)。
- 所有模块 **main+test 对其它模块 `*.internal` 包的 import 数为 0** —— 跨模块不触碰 internal,边界干净。

### 2.2 ServiceLoader 模块发现不对称
- `AppBuilder` 默认开启 `ServiceLoader.load(ModuleEx)`(boot,on by default);
  但全仓只有 `freeway-db`(DbModule)与 `freeway-http`(HttpModule)注册了
  `META-INF/services/com.jujin.freeway.ioc.ModuleEx`。flow/cloud(及 boot 自身)不注册。
- 观察:db/http 出现在 classpath 即被自动装配;cloud 的各功能模块与 FlowModule 则必须显式传入。
  行为上也许刻意(cloud 默认不开),但"谁自动装配、谁显式装配"的边界没有文档说明,
  建议在 CLAUDE.md/模块 README 写明依据;若 db/http 自动装配是历史遗留,可评估改为显式。

### 2.3 ext 仓包名约定
- ext 各模块包名与 artifact 同名段对应(`http-undertow` → `http.undertow`、`mq-kafka` → `mq.kafka`、
  `db-hikari` → `db.hikari`),与核心 `XDefault` 由 ext 以 `.primary()` 替换的机制自洽。无问题。

### 2.4 测试树镜像
- 各模块 `src/test/java` 目录树与 main 一一镜像(cloud 测试按功能包展开;http 测试覆盖到 engine/http2、engine/ws)。
- flow 是唯一例外形态:**37 个 main 文件只有 4 个测试文件**(112 个测试方法集中),且 main 全部挤在单包,
  详见 §5。

## 3. 命名规则符合度(全仓扫)

### 3.1 `*Default` / `*Impl` 落点普查(public 类)
| 位置 | 类 | 判据复核 |
|------|-----|---------|
| commons 功能包 | CoercerDefault, JsonCodecDefault | 可代换默认,功能包 ✓ |
| flow 根包 | FlowDriverDefault, FlowEngineDefault | 可代换默认,根包 ✓ |
| http 根/engine | ExchangeMetaDefault(根), HttpServerHandleDefault(engine) | 前者 ✓;后者见 §7(P2-1 引擎公开面)。|
| db/internal | PoolDefault | CLAUDE.md 明示的 sanctioned 例外(经 `.primary()` 替换,永不引用类本身)✓ |
| boot/internal | ConfigLoaderDefault | 同款"装配从不引用类本身"(AppBuilder.config(loader) 即替换点)✓;与 ioc 的 SymbolSourceDefault 同款,归置问题见 §9(P2-1)。|
| ioc/internal | SymbolSourceDefault | 替换路径是贡献 SymbolProvider 而非换 SymbolSource ✓ |
| cloud 功能包 ×11 | discovery 3/resilience 3/observe 2/storage/secret/rpc | 与 cloud 定稿口径一致 ✓ |
| http engine/ws | WebSocketSessionImpl | 引擎内建件 → XImpl ✓(接口在 websocket 包) |
| db/internal | DatabaseImpl/QueryImpl/BatchQueryImpl/DatabaseHubImpl/PooledConnectionImpl | 容器装配件/逐属主工件 → XImpl ✓ |
| flow 根包 | FlowContextImpl | XImpl 在根包(flow 无 internal 包,见 §5) |
| boot/cloud internal | TransportSecurityImpl(cloud), HookLifecycle(boot) | 模块内部件 ✓ |

### 3.2 跨模块 internal 引用:0(见 2.1)—— 边界强项,保持。

> §4–§9 为审计时点快照(裁决/执行前);与最终代码的偏差以 §11/§12 执行记录为准。

## 4. freeway-commons(8 包 / 54 文件 / 无 P0 P1)

依赖图谱实测:**无环**。json 与 validation 消费叶子包(bean/coercion/util),
其余(bean、coercion、util、scoped、logging、metrics、validation)为叶子;
模块 pom 仅 slf4j-api(与"零依赖"声明一致)。外部模块只 import commons 的
public 类型(198 条 import 线全查)。无 internal 包,但非 API 类一律
package-private —— 比"public-in-internal"更强的隐藏,且两个真 XDefault
(CoercerDefault / JsonCodecDefault)都紧邻接口,与定稿口径一致。

发现(全部非 API 破坏或最小):
- [P2] `util` 是三类不同概念的混合袋:纯静态助手(Types/Strings/Maps/Digests/
  ByteStreams)旁坐着 `LazyValue`(生命周期/并发原语,ioc/LazySymbolProvider 消费)
  与 `ContextExecutor`(ScopedValue 传播执行器,**全仓零生产调用**,仅自身测试)。
  建议:要么新建 `commons.concurrent` 或把 ContextExecutor 归 `scoped`(均 API
  破坏),要么最廉价 —— 包 javadoc 声明 util 边界,并给 ContextExecutor 定案
  (公开 API 还是删除)。
- [P2] `logging` 公开面过重:12 个类里 7 个 public,其中 `JULLoggerFactory` /
  `JULLoggerAdapter` / `JULMDCAdapter` 全仓除本包外零引用 —— 应收为
  package-private(纯内部改动);`LogBootstrap` / `JULLoggerServiceProvider` /
  两个 Formatter / `JULFileHandler` 保持 public 有据(SPI 加载、LogManager
  按名实例化、用户入口)。
- [P3] `JsonUtils.normalize → JsonCoercions.normalize → JsonNormalizer.normalize`
  中间一跳无意义(JsonCoercions.normalize 可删或直连)。
- [P3] `bean/BeanConstructor` 私有嵌套 record `BeanParameterDefault`:
  不可代换的私有类型顶着 XDefault 后缀 —— 应名 `BeanParameterImpl`
  (上一轮 Default→Impl 卫生遗漏)。
- [P3] `metrics/NoopMetrics` 是可代换角色(容器 Metrics 可被
  cloud/MetricsDefault 覆盖)的默认,却用行为命名而非 `MetricsDefault`
  —— 若刻意(no-op 策略名)请在 javadoc 说明,若从规则则更名(API 破坏,
  ioc/http 3 处引用)。
- [P3] `MethodHandleUtils` 实际是框架级反射缓存中心(ioc 4 类消费),顶着
  bean 的名字 —— javadoc 说明或更名即可,搬迁属 API 破坏不划算。
- [P3] 测试深度不匀:util 的 Maps/ByteStreams/Types 无独立测试(但都被
  boot/http/db/commons 生产消费);bean 缺 BeanIntrospector 独立测试;
  `util/UtilRegressionTest` 是唯一不带 javadoc 的非 `<Class>Test` 命名。

良好项:json 引擎 6 类全包私有且带 DoS 上限/重复键契约文档;scoped 的
Defer/ScopedCache 互嵌套契约双向注明并 warn-once;SLF4J provider 单点注册 +
按类名探测外部 provider;validation 注解面干净(无内部消费者也不碍事)。

## 5. freeway-flow(单包 36 public 类型 + package-info / 5.4k 行 / 无 P0 P1)

**判据:当前规模下单包扁平是成立的,拆分尚不划算。** 理由:(i) 依赖图是围绕
Node/Graph/FlowContext 的稠密团(簇),分层拆分是虚构;(ii) 多数"看似 internal"
的类型已被公开签名钉在根包(FlowContext.trace()/eventBus()、FlowEngine.markerIndex()、
eval(FlowOptions)、FlowDriver 回调面含 ExecState 等),真正自由移动的只有
`Stepper` 与 `FlowContextImpl` 两个;(iii) 搬走只减约 300 行/2 类。再评估触发条件:
根包超 ~50 类型/8k 行、出现第二引擎/驱动族、上 JPMS 或停售 solon 兼容面。
flow 无 internal 包是既定设计(plan.md §模块结构设计 明示单包 + 角色分组),
与兄弟模块惯例的偏差集中在 FlowContextImpl(他模块的 XImpl 在 internal)。

建议(全部低风险):
- [P2] `ExprEvaluator` 规模表述过时:CLAUDE.md:60、freeway-flow/README.md:27、
  AUDIT-2026-09-03.md:176 均写"~280 行",实际 591 行(实测)——文档漂移,顺手修正。
- [P2] flat 包内缺少"internal 词汇":全包所有类型 public,连 `Stepper`/被
  FlowContext @hidden 标注的 3 个引擎方法都没有稳定性提示。建议 package-info 加
  一段"稳定面 = 所列核心入口;其余无稳定性承诺"或给引擎件加 internal 标注(javadoc 级)。
- [P3] 可选:建 flow/internal 移入 Stepper + FlowContextImpl(内部改动;对外部直接
  使用者属 API 破坏,但符合 internal 语义——若模块决定开始画这条线)。
- [P3] `FlowDriverDefault` / `FlowEngineDefault` **不要**动:可被外部替换/子类化
  (FlowEngineTest 子类化、package-info 列为核心入口、CLAUDE.md 列为 XDefault 范例)。
- [P3] PlantUML 簇(3 类 + Graph 内 4 个 toPlantuml)保持:自成一体,搬包会制造
  包环(DisplayContext→Node/Link 反向依赖);要精简时是 4 成员一刀切,无文档要求删除。
- [P3] 文档残留:README 提的 "GraphSpec2" 属 1.3.x 历史类名(GraphBlueprint→
  GraphSpec2),1.3.6 起并入 GraphSpec 以 version=2 标记承载——README 现行措辞已修正。

真正短板是测试集中(112 方法/4 文件)+ 文档陈旧,不是包结构。

## 6. freeway-db(6 包 / 50 文件 / 无 P0 P1)

internal 纪律是**全仓最佳**:7 类中 QueryImpl/BatchQueryImpl/PooledConnectionImpl
为包私有(只经根接口可达);4 个 public(DatabaseImpl/DatabaseHubImpl/PoolDefault/
RowMapperResolver)仅因兄弟装配者(DatabaseBuilder/DbModule)需要;根包 22 类无一个
import internal(只有两个装配者);ioc 引用仅 DbModule 一个文件(Library→Module
模式),http 尚有 3 个、flow 1 个。schema 包 = 共享实体元数据词表(@Table/@Column/
@Id/@Generated/@Transient + SqlTypeMapping) + DDL 引擎,被 Orm/RowMapperResolver
共同消费,拆 db/annotation 现在不划算。migration 单类 797 行成包,粒度诚实,类的
规模才是观察点。dialect 包与 DbModule 通过 id 绑定 + freeway.db.dialect 配置键选择。

发现:
- [P2/文档] CLAUDE.md ".primary() … DB dialect (PostgresDialect vs custom)" 与代码
  矛盾:**内建默认 PostgresDialect 本身就是 .primary() 绑定**(DbModule.java:73),
  ext 再绑 .primary() 会触发 AmbiguousBindingException;实际扩展路径是 id 绑定 +
  `freeway.db.dialect` 配置键(DbModule.java:262-279)。改文档或把回退改为
  `get(Dialect.class,"postgresql")`。
- [P3] pom 与文档不符:db 的 ioc 依赖是普通 compile(非 optional),CLAUDE.md 却写
  "ioc optional"、AGENTS.md 模块表写 "commons only"。http/flow 同款——建议文档
  对齐 pom(全仓都不做 Maven optional,靠代码围栏),不要改 pom。
- [P3] DatabaseHub.java:9 javadoc 让独立用户直接 new DatabaseHubImpl(internal 类,
  无稳定性承诺)——建议加根工厂 DatabaseHub.of()/create() 并改文档。
- [P3] 测试落位:RowMapperTest 在根测试包却 import internal.RowMapperResolver,移到
  internal 镜像测试包;dialect 无直接单测(经 schema/DatabaseBuilder 间接覆盖)。
- [P3] util/Names 仅 SqlTextParser 单消费者,可收窄。

良好项:三个真隐藏 + 两装配者唯一入口;PoolDefault-in-internal + Pool-.primary()
替换是 CLAUDE.md 钦定范型(ext 绑 primary,唯一 primary 解析在 ioc BindingIndex);
分层严格无环且 subpackage 只 import 根 API。

## 7. freeway-http(13 包 / 118 文件 / 无 P0 P1)

三层单向:engine→orchestration→integration,零违规。engine 的对外消费仅 4 个文件
且只 import FreewayHttpEngine;cloud/boot/demo 只碰公开 websocket/body/sse API。
engine 22 类中 16 个包私有;public 的 6 个里 HttpContextImpl/HttpResponseWriter/
ResponseFraming 是 Java 无子包可见性被迫公开(engine/http2 消费),SessionBuffered*
则连理由都没有(仅 engine/HttpConnection 同包使用,可收)。http2 28 类 26 public,
约 15 个(BaseFrame 族 + FrameSerializer + Settings 族)包外零引用;hpack 4 类自成
子层,与 http2 的双向引用同属协议层,良性。websocket 与 engine/ws 双层职责清晰
(公开会话视图 vs 帧协议),"pattern" 残留为零——精简是实史。

发现:
- [P2/文档] CLAUDE.md "only FreewayHttpEngine is public" 在 Java 层不成立:引擎体系
  39 个 public(engine 6 + http2 26 + ws 7),其中 ~21 个包外零引用,只因无子包可见性
  被迫公开。建议:文档写明约定("engine/ 内 public = 引擎子包间契约,非 API"),
  可选把 ~21 个降为包私有(对非官方用户 API 破坏,但 internal 语义允许)。
- [P2] engine/ws/WebSocket.java 以协议名命名一个静态读循环工具(自己的测试都叫
  WebSocketReadLoopTest)——更名 WebSocketReadLoop,零外部引用,非破坏。
- [P2/P3] internal/SslReloader 持有具体 FreewayHttpEngine 字段(internal→engine 上
  行耦合,HttpModule 有引擎替换守卫);可迁 engine/ 或文档承认其为引擎特化。
- [P3] SslReloaderTest/HttpsSniReloadTest 在测试 engine/ 包测 internal 类,测试树
  落位小瑕;staticfile 单文件 797 行内部分解良好(public 面 = 1 类),可不动;
  http2 平铺 28 类不拆 frames/ 子包(加了第 4 层,收益低);event 3 记录自成包可留。

良好项:引擎层实现 root API 契约但不回指编排层;SslReloader→FreewayHttpEngine 是
唯一上行耦合且有守卫;FrameSerializer(in)/Http2FrameWriter(out) 分工干净;
websocket 复用 RouteIndex trie 且 route 不依赖引擎;WebServer NOOP_SINK 短路。

## 8. freeway-cloud(12 包 / 87 文件 / 无 P0 P1)

整合后一致性验证通过:11 个 *Default 全部在功能包、internal 15 类全为非 Default
装配件(3 类分类:容器按类实例化的 handler/filter 必须 public、模块构造的 hook、
纯 helper/状态)。外部对 cloud.internal 的引用:全仓 44 处,全部在 cloud 模块内部
(main 装配 + 定向测试),demo 只 import 公开 API。CloudHooks/CloudConfigKeys 中央
目录零字面量漂移;context/observe/internal/rpc 的传播域切分无环且方向正确。

发现:
- [P2/待你裁决] `TransportSecurityImpl`(cloud/internal):角色本身可代换
  (rpc.TransportSecurity javadoc 明言 Vault 式动态证书源是 ext 关注;CloudRpcModule
  的绑定无 primary 标记,ext 可 .primary())——按"外部可代换性"定稿判据它更像
  `TransportSecurityDefault` 且应回 rpc/ 功能包;但此前轮次(最终判据细化前)以
  "仅模块绑定 + fromKeyStore 配置激活引用"定为内部实现件。两条口径都通:请按现行
  判据拍板(改名 = internal 引用方源码级变动,对外部零影响)。
- [P3] 两个 Default 的 public 构造器带 internal.RegistryStore 参数
  (ServiceDiscoveryDefault/ServiceRegistryDefault)——合法(internal 类可 public),
  可留;如介意,可把构造收窄为模块装配专用。
- [P3] 三套异常层级(CloudException/RemoteInvocationException/StorageException)
  互不重叠,域名合理;CloudException 名字偏模块化,仅文档提示即可。
- [P3] Endpoint 与 PeerAddress 存在同一段 host:port→URI 解析逻辑复制(事件独立于
  discovery 的刻意解耦代价)——第三消费方出现前不抽公用。
- [P3] hook 摆放三风格并存:events 包内包私有 / 其余进 internal 公开 / secret·
  storage 在 bind() 内匿名——机制上各有道理(容器按类实例化必须 public),可选统一。
- [P3] 构建产物陈旧:cloud/target 仍含整合前类与 apidocs(internal/*Default),下次
  clean 构建即净——发版前记得 clean,否则 javadoc 出错。

## 9. freeway-ioc + freeway-boot(6 包 / 69 文件 + 2 包 / 15 文件 / 无 P0 P1)

**internal 卫生近乎满分**:全仓对 `ioc.internal` / `boot.internal` 的跨模块引用为 0
(main+test+demo 全查),公开 API 签名不泄露任何 internal 类型。ioc/internal 25 类中
17 个包私有,8 个 public 每个都有同模块兄弟包装配者(可见性是"恰好够装配",与
CLAUDE.md internal 语义完全一致);boot/internal 5 类同理。ioc 根包 23 文件里 3 个
是包私有支撑类(EventDispatcher/EventSubscriptionIndex/EventStreams),公开面 20 类
= IoC 11 + 消息域 9;根包拥挤度 52% 属消息域,但**不建议拆包**:28+ 下游 import
点(boot/http/cloud/demo)+ ~30 测试 + 需公开 3 个包私有类的接缝,收益为零
(消息支撑类已尽数下沉 internal)。子包(annotation/advisor/extension/symbol)各一
概念、零回指;@Topic 在根包是对的(消息域注解,不属于容器注解族)。

发现:
- [P2/文档冲突] **AGENTS.md 与 CLAUDE.md 命名口径打架**:AGENTS.md 说
  "XDefault … does NOT belong [in internal]",CLAUDE.md 与代码现状
  (db/internal/PoolDefault、boot/internal/ConfigLoaderDefault、
  ioc/internal/SymbolSourceDefault)都说归置正交。AGENTS.md 是上轮更名前的残留,
  建议按 CLAUDE.md 定稿口径改 AGENTS.md(纯文档)。
- [P2/规则张力,待你裁决] `ProxyFactoryDefault`(ioc/internal,包私有接口 + 唯一
  实现):字面判据下无外部可代换性(interface 包私有),但你已两次按"角色默认
  实现"定名 XDefault(ExchangeMeta/Coercer 同理)。若维持,建议 CLAUDE.md
  XDefault 段补一句"框架内装配可换实现的角色默认亦为 XDefault"消除张力;
  若收紧,则 ProxyFactory 应回 XImpl——两者都通,请拍板(不改名也可,纯文档项)。
- [P2/信息] 事件/调用域拆 `ioc.events` / `ioc.bus`:收益小于成本,维持扁平 API。
- [P3] CLAUDE.md "Primary resolution uses binding.primary() … not an annotation"
  与代码矛盾:`annotation/Primary` 存在且被 MarkerIndex 处理,javadoc 明言
  "equivalent to calling .primary()"(DSL 内部映射到该标记)。改 CLAUDE.md 句子
  或删注解(删注解属 API 破坏,不建议)。
- [P3] Container.java 的类 javadoc 块位于 import 之前(3-24 行),**从不附着到
  接口**——孤儿文档。移到 import 之后。
- [P3] ioc/boot 及其 internal 包均无 package-info;建议至少给两个 internal 包补
  package-info 写明"no stability promise",让约定进 javadoc 而不是只在 CLAUDE.md。
- [P3] HookLifecycle(boot/internal)被 BootConfigModule 绑为容器服务并经
  AppRuntimeDefault `container.get()`——internal 类型唯一经公开 Container API
  可达的服务;零外部引用,可留可改。
- [P3] boot 的 ConfigLoaderDefault(internal)与 AppConfigDefault/AppRuntimeDefault
  (根包)的形态不对称——有理(PoolDefault 先例:替换代码从不命名该类),不改。

## 10. 汇总与建议清单

> 状态：A 类三项裁决见 §11（已执行），B/C 清单执行见 §12（已执行）；
> 下述 A/B/C 列表为审计时点建议，落地以 §11/§12 与代码为准。

### 总体结论
结构健康度**高**:48 个包、430 个 main 文件,**零 P0/P1**;跨模块 internal 引用为 0;
依赖图与文档声明基本一致(仅两处 test-scope 边);`XDefault`/`XImpl` 落点与定稿
判据一致,残余不一致集中于 3 个待裁决点(见 A)。commons/db/ioc 的隐藏纪律
(package-private 优先)甚至强于仓库惯例。真正的问题集中在**文档与代码的口径
漂移**(6 处)与**可选收紧**(可见性/命名),而非结构性缺陷。

### A. 需要你拍板的 3 个判据问题(都不动代码也能闭环)
1. **TransportSecurityImpl**(cloud/internal,§8 P2):角色可代换(ext 可 .primary()),
   按现判据更像 rpc 包的 `TransportSecurityDefault`;此前轮次按"仅配置激活的内部
   实现件"定 Impl。请按现行"外部可代换性"口径裁决。
2. **ProxyFactoryDefault**(ioc/internal,§9 P2):包私有接口 + 唯一实现,字面无外部
   可代换性,与判据文字有张力;维持 XDefault 则建议在 CLAUDE.md 补"框架内装配
   可换实现的角色默认亦为 XDefault"。
3. **XDefault 是否可居 internal**(文档层):AGENTS.md 说不可,CLAUDE.md/代码说可
   (PoolDefault 先例)。建议 AGENTS.md 对齐 CLAUDE.md。

### B. 文档修正(零代码风险,6 处)
| 位置 | 错误 | 修正 |
|------|------|------|
| CLAUDE.md | DB dialect 替换描述为 ".primary() (PostgresDialect vs custom)" | 内建 PostgresDialect 本身 .primary();ext 替换路径是 id 绑定 + `freeway.db.dialect` 键 |
| CLAUDE.md | "only FreewayHttpEngine is public" | engine 体系实际 39 个 public(~21 个包外零引用,因 Java 无子包可见性);写明"engine/ 内 public = 子包间契约" |
| CLAUDE.md | "Primary resolution … not an annotation" | annotation/Primary 存在且等价生效,句子改为两者等价 |
| CLAUDE.md/README/AUDIT-2026-09-03 | ExprEvaluator "~280 行" | 实际 591 行 |
| CLAUDE.md 模块图/AGENTS.md 模块表 | db "ioc optional"/"commons only"、cloud→boot 依赖 | 全仓无 Maven optional;cloud→boot 是 test scope |
| freeway-flow README | "GraphSpec2" | 1.3.x 历史类名,1.3.6 起并入 GraphSpec(version=2 标记);现行措辞已修正 |

### C. 低成本收紧(内部改动为主,按性价比排序)
1. [零 API 破坏] commons `logging`:JULLoggerFactory/JULLoggerAdapter/JULMDCAdapter
   3 类包外零引用 → 收 package-private(需一次编译验证)。
2. [零 API 破坏] http engine 可见性:~21 个包外零引用的 public(engine 6/ws 4/http2
   ~15)按"子包间契约"降包私有,或至少先文档化约定;`WebSocket.java` 更名
   `WebSocketReadLoop`(测试名已是如此);SessionBuffered* 2 类无理由公开可直接收。
3. [零 API 破坏] commons util 收口:util 包 javadoc 声明边界;ContextExecutor
   定案(公开 API 或删除——全仓零生产调用)。
4. [零 API 破坏] db:RowMapperTest 移 internal 镜像测试包;dialect 补直接单测;
   DatabaseHub 加根工厂 of()/create() 替代 javadoc 引导 new DatabaseHubImpl。
5. [内部] flow:package-info 补稳定性段落(flat 包内表达 internal 语义);可选建
   flow/internal 移 Stepper + FlowContextImpl。
6. [内部] ioc/boot:给 internal 包补 package-info("no stability promise");
   Container.java 孤儿 javadoc 移到 import 之后。
7. [内部] cloud hook 三风格(可选统一);Endpoint/PeerAddress 解析复制留待第三
   消费方;`Local` 单文件包维持。
8. [发版卫生] cloud target 的旧 apidocs/类需 clean 构建后再发版。

### D. 明确不动(有意的现状)
- commons 无 internal 包(package-private 是更强的隐藏);json 引擎 6 类全隐藏。
- ioc 根包扁平(消息域拆包成本 > 收益);db/http/flow 根包密度可接受。
- flow 单包扁平(团状依赖 + 签名钉住;超 50 类型/8k 行再议)。
- db schema 注解/引擎同包(共享元数据词表);MigrationRunner 单类成包(类内拆分
  留给下次触碰);staticfile 单文件 797 行(内部已分解,public 面 1 类)。
- ext 各适配模块包名与 artifact 对应,无问题。
- cloud 传播域三分(context/observe/internal)有环可查、方向正确,维持。

## 11. 裁决执行记录(2026-09-05,已落地)

1. `TransportSecurityImpl`(cloud/internal)→`TransportSecurityDefault`(cloud/rpc):
   可代换角色默认归功能包,与其余 11 个 cloud 默认同口径;`CloudRpcModule` 同包装配、
   SecurityTest 引用同步;CLAUDE.md/DEVELOPER-GUIDE 计数 11→12。
2. `ProxyFactoryDefault`(ioc/internal)→`ProxyFactoryImpl`:包私有接口 + 唯一实现,
   无外部可代换点,按字面判据归 XImpl。
3. AGENTS.md 命名段对齐 CLAUDE.md「归置与后缀正交」口径(删除"XDefault 不属于
   internal"的过期表述)。
4. CHANGELOG [Unreleased] 定稿条目改写为裁决后的净更名(相对 1.4.0)。

## 12. B/C 清单执行记录(2026-09-05,已落地)

- B 类 6 处文档口径全部修正(CLAUDE.md 五处 + AGENTS.md 模块表三行 + flow README
  三处 + skills gotchas 一处;plan.md/assessment.md 等历史快照保留)。
- C1 commons logging 三包内类收 package-private(SPI 与按名加载类保持 public)。
- C2 http 引擎 21 个包外零引用 public 类型降 package-private(engine 根 2、
  engine/ws 4、engine/http2 15);`WebSocket`→`WebSocketReadLoop`(构造器与
  3 处静态调用点同步);engine/ 余下 public 在 CLAUDE.md 标注为子包间契约。
- C3 commons.util 补 package-info;ContextExecutor 定案保留(公开 API)。
- C4 db:RowMapperTest 迁 internal 测试包;新增 DialectSyntaxTest(6 用例,
  含保留字引号、DML/DDL 片段、四方言差异);DatabaseHub.of(Map) 根工厂。
- C5 flow:Stepper/FlowContextImpl 移入新 flow.internal(各 1 引用点 + 测试
  加 import);根 package-info 增 Stability 段。
- C6 五个 internal 包 + commons.util 补 package-info;ioc Container 类 javadoc
  归位到 import 之后(此前为孤儿文档)。
- C7 cloud hook 三风格维持(机制各有依据:容器按类实例化 → internal+public;
  同包可构造 → 包私有;bind() 匿名),约定写入 cloud/internal package-info。
- C8 全仓 clean 重建验证:1864 + 6 新测试 = 1870 全绿,陈旧 apidocs/类产物清除。
