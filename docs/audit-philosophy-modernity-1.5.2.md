# freeway 核心审计：理念一致性 + 现代性（1.5.2-SNAPSHOT）

审计对象：七个核心模块（commons / ioc / boot / http / db / flow / cloud）的 `src/main`。
方向：**整体理念 → 实现的一致性**，以及**现代性**（链式 API、方法与变量命名、JDK 25 惯用法）。
`freeway-ext` 不在本轮范围（它的结构与一致性审计见该仓库 `docs/audit-structure-consistency-1.5.2.md`）。

## 0. 结论速览

0. **全轮唯一一处"静默错值"的正确性问题**（其余发现都是契约、文档与形态层面）：`CoercerDefault.java:281` 的 Number→boolean 先 `intValue()` 再判真假，`0.5`、`0.9`、`4294967296L`（2³²）全部悄悄变成 `false`；而同一个文件 420-425 行的注释恰好把这个 `intValue()` 模式列为"corrupting data"明令禁止。测试只钉了 `1/42/-1/0/0.0/1.5`（`1.5` 的 `intValue()==1` 恰好为真，掩盖了截断）。这是本轮唯一应当**立刻**修的一条。
1. **理念的四条硬骨架全部成立**：main 树无第三方依赖（非 JDK import 只有 `org.slf4j.*` 与一处 `jdk.net.ExtendedSocketOptions`）；无 classpath 扫描、无字节码织入；12 个 cloud `XDefault` 全在功能包；全仓 `@Deprecated` = 0、无别名配置键、无旧 arity 兼容残留（`Wiring` 的 9 参构造器上一轮已删）。
2. **最普遍的一类问题不是设计错误，而是"产物说假话"**：javadoc / README 与实现相反的条目共 **17 处**（§3.7 有清单），分布 http(5)、cloud(3)、boot(2)、flow(3)、ioc(2)、commons(1)、db(1)。其中最危险的三处：`README` 的 flow 快速开始照抄必失败；cloud 的事件网格 hook 排序被三份注释说反；readiness 的 javadoc 与实现相反。
3. **第二普遍的是"同一件事两套策略/两个名字"**：commons 的 `lenient` 双策略（同一个 `freeway.log.file.*` 键一边静默回退、一边抛错）、http 的 `HttpServerConfig` 三套构造方式、ioc 的 null 策略三态、db 的 `Sql.set` 一名两语法、cloud 的 `Propagator.extract` 未定义"没提取到"的约定。
4. **一处真正的资源泄漏形状**：`BeanIntrospector` 用 `WeakHashMap<Constructor, BeanConstructor>`，而 value 强引用 key，条目永不回收，Class 与其 ClassLoader 被静态 map 钉住（commons）。
5. **一处"机制混入内容"**：日志 MDC 的默认显示优先级写死为某个应用的字段名 `code,market,diagId`，并被 `docs/freeway-config.md` 固化为框架契约（commons，理念第 4 条的教科书反例）。
6. **现代性达标项（正向）**：`getXxx()` 访问器声明全仓 **0**（bare accessor 489 个）；旧式 `instanceof` + 强转 **0**；`Optional` 只用作返回值、参数位置 0；switch 现代化 139 个箭头 case vs 4 个 colon case（全在 flow `Graph.java`）；316 个公共类型在 core+ext 中零引用数为 **0**；`internal` 的 33 个 public 类型里 28 个确有跨包装配引用。
7. **现代性缺口**：`withX` wither **只存在于 cloud**（14 个），http/db 的大配置记录仍用位置参数或构造器阶梯；`sealed` 全仓 0（唯一候选收益有限，见 §3.9）；真实方法中 ≥5 参的有 8 个，其中 `ResponseFraming.shouldGzip*` 三兄弟带 3 个连排 boolean 且有一个是纯别名。

## 1. 判据与方法

**判据来源**（不引入审计者私货）：`AGENTS.md` 的九条理念（JDK 优先 / 显式优先 / 可替换性写在名字上 / 机制不懂键名 / 抽象当场自证 / 产物包含理由 / 兼容不是目标 / 稳定=语义 / 品味不设门槛）与 `docs/ARCHITECTURE.md` 的边界。现代性五维度：A 链式与参数形态、B 方法命名、C 变量与参数命名、D JDK 25 惯用法、E 空值·异常·不可变策略。

**方法**：先做全仓量化普查（口径与命令见 §7），再按模块逐条落到 `文件:行`。量化只用于指路，不用于下结论——本轮踩到并纠正了三处量化假阳性：

- 宽松正则报"2 处旧式 `instanceof` 强转"，逐条核对后为 **0 处**；
- 同一脚本报 `Sql.Condition` 有 21 个公开构造器，实际为 **0 个**（正则命中的是调用点与嵌套名）；
- 我一度把 `Dialect` 的能力谓词命名（8 个 bare vs 4 个 `supportsX`）判为不一致，**db 子审计给出反例**：`Dialect.java:192-203` 明文写了两条约定——"SQL 能力用 `supportsX`，语法形态用名词短语"。我核对该处 javadoc 后**撤回该条**，转入 §5"有意保留"。

**复核口径**：分模块深审由七个独立子审计完成（各自只读、不给结论之外的修改）。全部 P1（29 条）中我本人**逐条回代码复核了 22 条**（§4 中标"已复核"），其余标"子审计"并在修复前需再确认一次。十二处"有意保留"的结论我也各自抽查了调用者或设计文档。

## 2. 量化普查

计数范围：`*/src/main/java`，正则口径见 §7。

### A. 公共面与访问器风格

| 指标 | commons | ioc | boot | http | db | flow | cloud |
|---|---|---|---|---|---|---|---|
| 文件 / 行 | 56 / 8921 | 65 / 6493 | 18 / 1970 | 121 / 14144 | 51 / 8147 | 38 / 5481 | 94 / 8590 |
| 公共类型 | 43 | 42 | 15 | 81 | 43 | 36 | 84 |
| `getXxx()` 访问器声明 | **0** | **0** | **0** | **0** | **0** | **0** | **0** |
| bare accessor 声明 | 63 | 41 | 12 | 91 | 69 | 144 | 69 |
| `withX` wither 声明 | 0 | 0 | 0 | 0 | 0 | 0 | 14 |
| `Optional` 返回 | 1 | 1 | 0 | 13 | 2 | 0 | 5 |
| record / sealed | 11 / 0 | 11 / 0 | 4 / 0 | 32 / 0 | 18 / 0 | 8 / 0 | 21 / 0 |

### B. JDK 25 惯用法

| 指标 | commons | ioc | boot | http | db | flow | cloud |
|---|---|---|---|---|---|---|---|
| 箭头 `case` / colon `case` | 49 / 0 | 5 / 1 | 0 / 0 | 46 / 0 | 0 / 0 | 33 / 3 | 9 / 0 |
| `instanceof` 模式 / 旧式强转 | 94 / 0 | 9 / 0 | 2 / 0 | 9 / 0 | 13 / 0 | 53 / 0 | 6 / 0 |
| `getFirst()/getLast()` | 0 | 2 | 0 | 14 | 3 | 1 | 1 |
| `Objects.requireNonNull` | 43 | 54 | 20 | 55 | 45 | 15 | 38 |
| `var` 局部变量 | 4 | 8 | 1 | 99 | 29 | 1 | 18 |
| text block | 0 | 0 | 0 | 0 | 2 | 0 | 0 |
| `@Deprecated` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `synchronized` / `ConcurrentHashMap` | 12 / 23 | 27 / 19 | 4 / 2 | 23 / 12 | 6 / 5 | 6 / 16 | 13 / 21 |

### C. 公共面无死代码与 internal 收口

- **316 个 core 公共类型，在整个 core+ext 源码中零引用的数量为 0。**
- `internal` 包内 public 类型 33 个：**28 个有跨包 main 引用**（合规）；`AppLogSource` 无跨包引用但由 `META-INF/services/...LogConfigSource` 注册（**必须 public**，只看引用数会误判）；剩 4 个只被测试引用（§3.6）。

### D. 单文件规模 Top 6（职责溢出候选）

| 行数 | 文件 | 分节/职责 |
|---|---|---|
| 920 | `commons/logging/JULEnhancer.java` | 8 节：配置加载、级别、console handler 所有权、formatter 安装、文件日志激活、具名文件持久化、附加文件、属性助手 |
| 854 | `db/migration/MigrationRunner.java` | 扫描（文件/JAR）、版本比较与归一化、校验和（含 CRLF 双轨）、锁与接管、DDL 应用、多方言路径归一 |
| 848 | `db/Sql.java` | SQL 构建（45 个公共方法：WHERE/JOIN/CTE/DML/UNION/条件组） |
| 793 | `http/staticfile/StaticResourceMount.java` | 挂载范围解析、条件请求、RFC 7233 范围、Content-Type 表、两条安全打开路径、发送 |
| 779 | `flow/FlowEngineDefault.java` | 引擎生命周期、节点分派、表达式求值、死路/深度/步数上限 |
| 685 | `http/engine/http2/Http2Connection.java` | 连接状态机（子审计核查为内聚，不计） |

## 3. 跨模块发现

### 3.1 `HttpServerConfig` 是"同一件事的三种写法"（P1，已复核）

13 组件记录同时提供三套构造方式：

| 方式 | 位置 | 谁在用 |
|---|---|---|
| 规范构造器（13 参） | `HttpServerConfig.java:31` | `HttpModuleConfig.java:116`、`WebServerBuilder.java:54` |
| **4 级委托构造器阶梯** | `HttpServerConfig.java:108/116/125/133` | ext testkit `EngineFixture.java:75`、ext 各引擎测试 |
| 内嵌 `Builder`（13 个 setter + `build()`） | `HttpServerConfig.java:147-241` | core 测试（`HttpServerConfigTest`、`WebSocketIdleTimeoutTest`） |

这是全 core **唯一**一个多级记录构造器阶梯（全仓扫描确认），违背仓库自己的两条规则：三个以上可选输入用记录 + `defaults()` + 每字段 wither；不为已编译调用点保留旧 arity 构造器。

**附带一处默认值分叉**（子审计发现、我已复核）：`Builder` 的 `port` 隐含 0、`shutdownGrace = Duration.ZERO`（`:152-156`），而 `WebServerBuilder.java:54-55` 与配置文档的默认是 8080 / 2s；`builder()` 的 javadoc 却写"Defaults match the canonical constructor's"——record 的规范构造器并没有默认值，这句话是空的。

**修法**：保留规范构造器 + `defaults()` + 每字段 wither，删掉 4 个委托构造器与内嵌 `Builder`，调用点（core 3 + ext ~10）按编译错误迁移。

### 3.2 大配置记录的可选输入写法不统一（P1）

| 记录 | 组件数 | 可选输入写法 |
|---|---|---|
| `CloudHttpClientDefault.Wiring` | 10 | `defaults()` + 每字段 `withX` ✅ 规则样板 |
| `PoolConfig` | 12 | `defaults(url, user, pass)` 三参工厂，无 wither |
| `HttpServerConfig` | 13 | 4 级构造器阶梯 + Builder（§3.1） |
| `PeerConnector`（cloud） | 7 位置参数 | 两个相邻 `Duration`、两个相邻 `long`，装配点 `CloudEventLifecycleHook.java:136-142` 顺序摆开 |

`AGENTS.md` 的规则没有模块区分，实现却只有 cloud 的 `Wiring` 做全。这正是该规则要防的"同类型相邻参数静默串位"。

**修法**：把"记录 + `defaults()` + 每字段 wither"写成全局形状，`PoolConfig`、`PeerConnector` 对齐；`HttpServerConfig` 按 §3.1 处理。

### 3.3 `ResponseFraming` 三兄弟：一个纯别名 + 3 个连排 boolean（P2，已复核）

```java
// http/engine/ResponseFraming.java:39-43
public static boolean shouldGzipFile(
        HttpServerConfig.CompressionConfig compression,
        int status, boolean bodyAllowed,
        boolean acceptsGzip, boolean compressible) {
    return shouldGzipStream(compression, status, bodyAllowed, acceptsGzip, compressible);
}
```

javadoc 说"same gates as the streaming path, **plus body-allowed**"，但 `shouldGzipStream`（`:29`）本来就检查 `bodyAllowed`——**行为完全相同，注释描述了一个不存在的差异**。调用点 5 处（core 3 + ext 2）。同时 `shouldGzip` 的 3 个连排 boolean 就是规则里"参数记录"的适用对象。

### 3.4 入口工厂命名分叉：`create` 与 `of`（P2）

`Freeway.create(...)`（ioc `Freeway.java:31/35/49/55`）与 `FreewayApp.of()`（boot `FreewayApp.java:88`）是框架的两个并列入口，却用了两个动词。全仓 `of` 36 处、`create` 10 处（ioc 4 + flow 6：`Graph.create`、`GraphSpec.create`）。**修法**：择一并一次改掉（形态变化，编译期会响）。

### 3.5 位置参数/旧 arity 构造器残留清单（P1）

除 §3.1 外，全仓扫描（≥3 个公开构造器的公共类型）结果为：

| 类型 | 公开构造器 | 判定 |
|---|---|---|
| `HttpServerConfig` | 4 + 规范 | **残留**（§3.1） |
| `MigrationRunner`（db） | 4 参与 5 参（后者仅多 `lockTtl`） | **残留**：默认值同时在 `MigrationRunner:73` 与 `DbModule.java:202-203` 声明，位置布尔 `enabled` 20+ 处不可读（已复核） |
| `FreewayHttpEngine`（http） | 5 个位置构造器 | **残留**：不是"每步加一项"的梯子而是可选参数矩阵（第 3 步加 metrics，第 4 步又把 metrics 重置为 `NoopMetrics`） |
| `JULFileHandler`（commons） | 4 参与 5 参 | **残留**：4 参唯一调用点是测试（`JULFileHandlerTest.java:268`） |
| `GraphSpec`（flow） | `(id)` / `(id,title)` / `(id,title,driver)` | **合规**：2 个可选输入，规则允许重载阶梯（需补 javadoc 说明每级增加了什么） |
| `PingFrame` / `WebSocketFrame` / `FlowException` / `JULFileHandler` | 3 个 | 前两者是载荷变体便利构造、`FlowException` 是异常标准形态，均非 arity 阶梯 |

### 3.6 `internal` 里只被测试引用的 public 类型（P2，已复核）

| 类型 | 模块 | 同包 main 引用 | 跨包 main | 测试 | 结论 |
|---|---|---|---|---|---|
| `ServiceIdentity` | cloud | 1（`HttpServiceDeclaration`） | 0 | 同包测试 | 可降 package-private |
| `ConfigFileReader` | boot | 2 | 0 | 同包测试 ×2 | 可降 |
| `HookLifecycle` | boot | 1 | 0 | 同包 + `boot.AppRuntimeDefaultTest` | 需同批改测试 |
| `ConfigSources` | boot | 3 | 0 | 同包 ×2 + **cloud** 测试 | 需同批改测试（跨模块测试在用） |
| `AppLogSource` | boot | 1 | 0 | 1 | **保留**：`META-INF/services` 注册的 provider |

### 3.7 文档与实现不符清单（P1/P2）

| 位置 | 文档说什么 | 实现是什么 | 已复核 |
|---|---|---|---|
| `flow/README.md:68-98` | 快速开始 JSON（无 `version`）；"v1 layout 仍兼容" | 门禁要求 `version=2`，v1 已删（`GraphSpec.java:290-303`，测试钉住拒绝） | ✅ |
| `cloud/event/CloudEventModule.java:17-18` | hook "ordered **before** `freeway.http.server`" | 代码 `.after(CloudHooks.HTTP_SERVER)`（`:58`），理由（mesh origin 需要真实端口）写在代码注释里 | ✅ |
| `cloud/health/CloudHealthModule.java:17` | registry contributor "in-process store: **always healthy**" | `RegistryHealthContributor.java:53-64`：draining 不健康、连续 3 次心跳失败不健康、未注册报 `not registered` | ✅ |
| `http/HttpResponse.java:66-76`、`:82-91` | 悬空 doc 块："`contentLength` must be known" | 实现允许 `-1`（走 chunked，`StaticResourceMount.java:214` 就这么调） | ✅ |
| `http/RouteHandler.java:6-7`、`HttpResponse.java:135,143` | 引用 `request()`/`response()`/`status(s)` | 这些方法不存在（应为 `HttpRequest`/`HttpResponse` 与 `setStatus`） | 子审计 |
| `boot/internal/ConfigLoaderImpl.java:18-31` | "标准级联"只列五层 | 真实链含 `TIER_SYS_PROPS`（`-D`，order 5） | ✅ |
| `boot/AppRuntime.java:3-23` | 接口零 javadoc | 契约（幂等 start/close、事件可靠性差异）只在 internal 实现里 | ✅ |
| `ioc/extension/Contributions.java:30` | id = 类简单名 camel→snake | 真实 id = `snake@package`（`BinderImpl.java:116-117`） | 子审计 |
| `ioc/internal/ServiceRuntime.java:12` | "lock striping (64 stripes)" | 单一锁（同文件 `:21-22`、`:32` 自述相反） | 子审计 |
| `commons/json/JsonUtils.java:19` | 门面 15 个静态方法零 javadoc | `parse(InputStream)` 会关掉调用方的流（`JsonParser.java:108 try (input)`） | ✅ |
| `db/DatabaseHub.java:145` | "Returns an unmodifiable **view**" | 构造期快照（`DatabaseHubImpl.java:65 Map.copyOf`） | 子审计 |
| `cloud/storage/ObjectStorage.java:18` | put "returns its etag **and version id**" | `PutResult(String etag)`，设计明确不返回 versionId | 子审计 |
| `AGENTS.md`（构建章节） | SLF4J "declared by commons, http and cloud; the rest inherit transitively" | 实际 **6/7 模块显式声明，只有 ioc 继承**（七个 pom 实测） | ✅ |

### 3.8 ioc 的"可替换性写在名字上"两处失真（P1）

1. **`SymbolSourceImpl` / `LoggerSourceImpl` 命名与替换能力矛盾**：两者都在 `internal`，但 `InjectionResolver.java:242-244` 的注释明说"a module that binds its own primary LoggerSource must be honored here too"，即它们**可被外部 `.primary()` 替换**——按 `AGENTS.md` 的判据（外部能否替换）应为 `XDefault`（`PoolDefault` 有先例）。**已复核**。
2. **替换 `SymbolSource` 会静默丢掉整条贡献链**：`ContainerImpl.java:119` 的 wiring 把 `SymbolProvider` 贡献注册进**容器内部的 `symbolSource` 实例**，而注入端走 `container.get(SymbolSource.class)`（`InjectionResolver.java:269-270`）。boot（CLI/env/files 全链）与 cloud（`CloudSecretModule.java:38`）都走 contribute；cloud 测试确实用 `.primary()` 替换过 `SymbolSource`。**已复核**。

### 3.9 现代性观察：`sealed` 全仓为 0

唯一有说服力的候选是 `flow/ExprEvaluator.AstNode`（`private interface` + 4 个 `private record` 实现，`ExprEvaluator.java:88/92/96/124/150`），但它用**多态 `eval()`** 分派而非 switch——sealed 只带来"封闭性自证"，收益有限，列为观察项而非缺陷。`Dialect`/`NodeType` 用 enum + 穷尽 switch 是正确选择。

## 4. 分模块发现

级别口径：**P1** = 契约/语义/安全面与声明不符，或同一件事存在两套相反策略；**P2** = 命名、文档、形态与规则不一致，修复是机械的。

### 4.1 commons（56 文件 / 8921 行）

**P1**

1. **Number→boolean 静默错值**（已复核，全轮唯一正确性问题）：`CoercerDefault.java:281` 用 `n.intValue() != 0` 判真假 → `0.5`/`0.9`/`4294967296L` 都变成 `false`（2³² 的 `intValue()` 为 0）。同文件 420-425 行写着"Long/Double/Float sources silently wrap via `intValue()`/`shortValue()`/`byteValue()`, corrupting data (e.g. 3_000_000_000L → int must fail loudly)"——整数目标路径严格执行了这条，谓词路径恰好是它禁止的模式。可达路径：JSON `{"enabled": 0.5}` → `JsonCoercions.coerceStructured` → `coercer.coerce` → 本方法。测试缺口：`CoercerDefaultTest:511-518` 只覆盖 `1/42/-1/0/0.0/1.5`。**修法**：`return coerceToBigDecimal(n).signum() != 0;`（复用同文件 176-193 的精确十进制路径，非有限值按 453-462 拒绝），补 `0.5`/`0.9`/`4294967296L` 三个用例。
2. **构造器缓存泄漏**（已复核）：`BeanIntrospector.java:41-42` 用 `WeakHashMap<Constructor<?>, BeanConstructor>`，而 `BeanConstructor.java:21` 强引用同一个 `Constructor`——value 钉住 key，条目永不回收，Class 与 ClassLoader 被静态 map 钉住。同文件 javadoc（`:12-16`）却自称"cached via `ClassValue` and `ConcurrentHashMap`"；同包 `JsonCoercions.java:478-483` 已示范 `ClassValue` 的无泄漏写法。**修法**：缓存挂到既有 `ClassValue<BeanPlan>` 上（`BeanPlan` 本就持有 `BeanConstructor`）。
3. **机制层写死了应用字段名**（已复核）：`JULLogFormatterSupport.java:55` 的 MDC 默认优先级是 `{"code","market","diagId"}`，并被 `docs/freeway-config.md:160`（与 `docs/freeway-logging.properties.reference:75`）当作框架契约发布。**修法**：默认返回空数组（字母序兜底已存在），顺序交回应用；docs 同步删除。
4. **同一个键、两条解析路径、两种相反的错误策略**（已复核）：`JULEnhancer.propertyValue(..., lenient=true)`（`:859` 的 `freeway.log.file.flush-interval`）非法值静默换默认值；`JULFileHandler.java:166` 对**同一个键**用 `lenient=false` 抛错。**修法**：删 `lenient` 形参，统一"非法值 → 一行 `logEarly`：键名 + 原值 + 期望形式 + 采用的默认值"。
5. **隐藏的静态 `Coercer`**（已复核）：`JsonCoercions.java:53` 的 `DEFAULT_COERCER` 让 `JsonUtils.coerce(...)`（生产调用点 `FlowContextImpl.java:65`）与 `new JsonCodecDefault()`（`WebServerBuilder.java:62`）绕过容器，容器里 `register()` 的 `CoerceRule` 到不了这两条路。**修法**：删无参构造器与静态实例，coerce 入口显式要求 `Coercer`。
6. **公共 JSON 门面零 javadoc，且会关掉调用方的流**（已复核）：`JsonUtils.java` 全文件 0 个 `/**`；`parse(InputStream)` 经 `JsonParser.java:108 try (input)` 关闭入参流。**修法**：给 6 个入口补契约（谁拥有流、null 策略、抛什么），或去掉 `try (input)`。
7. **日志键表缺失**（已复核）：`freeway.log*` 字面量 23 处散在 5 个类、0 个常量（`"freeway.log.level"` ×4、`"freeway.log.file"` ×3……），改键名不会在编译期响。**修法**：加 package-private `LogKeys` 常量表。

**P2**：`JULFileHandler` 4 参委托构造器（§3.5）；`JULConsoleFormatter.java:28` 的 `FormatConfig(TIMESTAMP, 7, useColor, true, showMDC)` 位置参数（应为静态工厂或 `defaults()` + wither）；`JsonObject.object(key)`/`array(key)`（`:40`）是**写**操作却与读方法 `getObject/getArray` 只差一个 `get`，`JsonArray.java:20-30` 的无参同名版又是 append——建议 `newObject/newArray`；`JsonAccessors.java:172/195` 的 `object/array` 类型不符时静默返回 `null`，与同文件 `:61-65` 抛 `IllegalArgumentException` 的策略相反。

### 4.2 ioc（65 文件 / 6493 行）

**P1**

1. `SymbolSource`/`LoggerSource` 命名与替换能力矛盾（§3.8.1，已复核）。
2. 替换 `SymbolSource` 丢掉贡献链（§3.8.2，已复核）。
3. **`.advise()` 的接口约束延迟到首次 `get()` 才失败**（已复核）：`Binding.java:74`/`BindingImpl.java:164` 不校验，`ServiceRuntime.java:157-165` 才抛；`ScopeProxyAdvisorTest.java:596-608` 把延迟固化成契约。bind 期完全可知（`type.isInterface()`），属"启动期发现问题不静默"的反例。
4. `Contributions.java:30` 的 id 形态 javadoc 与实现不符（§3.7，子审计）。

**P2**：`ServiceRuntime.java:12` 的"64 stripes"与实现相反；`ModuleNode` 8 个工厂中 `app(String)` 只为消解歧义（子审计）；`Symbol`/`Value`/`IntermediateType` 三个公共注解零 javadoc，`IntermediateType` 在 `docs/` 中不存在；`EventBus.java:241` 与 `:303` 命名风格分裂（`inboundDeduplication(int)` vs `setAsyncExecutor(...)`）；`EventBus.EventBusStats` 与包私有 `EventStats` 同概念两名；`Extension.get(String)` 与同类型 bare accessor 混用；`InjectionResolver.java:268` 用 `isPresent()/get()` 而非 `map(...)`；`Freeway.java:36`（null varargs → 空容器）、`ContainerImpl.java:337`（null markers → 按类型）、`:319`（null id → 抛）三处空值策略不一致。

**A 组 P1（现代性）**：`Scoping.java:28` 只有 `<T> T within(Supplier<T>)`，纯副作用的 scope 块被迫 `return null`（测试里 7 处）。JDK `ScopedValue` 本身是 run/call 两形，建议补 `default void within(Runnable)`。

### 4.3 boot（18 文件 / 1970 行）

**P1**

1. **文件层读失败三种口径、都不给修法**（已复核）：`AppConfigDefault.java:169-183` 对"文件不存在"零日志返回空、对 `IOException` 只 WARN 后让整份文件的键消失；而同类错误在 `ConfigLoaderImpl.java:163-165` 是硬失败 `IllegalStateException("Unable to load " + file, e)`。后果：`-Dfreeway.config.file` 写错路径无声、权限/超大文件让 profile 变体整份失效、热重载期 JSON 解析错保留旧快照而读失败却换上空快照。
2. **profile 变体剥离只做了一半**（已复核）：类路径侧剥离（`ConfigLoaderImpl.java:214-218 withoutActivationKey`），文件系统侧不剥离（`AppConfigDefault.java:123-129`）——工作目录 `application-dev.properties` 里写 `freeway.profile=prod` 会让 `profiles()` 与解析值分叉，推翻 `AppConfig.java:41-43` 的绝对承诺（"cannot disagree"）。
3. **组合期异常不清理 config，泄漏热重载 watcher**（已复核）：`AppBuilder.java:168-176` 的 `ModuleNode.app(...)`/`discover(...)` 在 `try` 之外，`:180` 才进入清理分支；重复模块类（`ModuleNode.normalize`）或坏 SPI provider 抛出时，每次 `start()` 泄漏一个守护线程 + 一个 `WatchService`。

**P2**：`ConfigFileWatcher.java:153-155` 目录消失时静默 `return`，与自身 javadoc"the reason is logged"相反（K8s ConfigMap 换目录即触发）；`AppRuntime`/`AppState` 零 javadoc，契约只在 internal；`-X value` 是第四种 CLI 形式（`ConfigLoaderImpl.java:358-361`）而 `docs/DEVELOPER-GUIDE.md:772` 说"Three CLI styles"；`HookLifecycle`/`ConfigFileReader`（§3.6）；`ConfigLoaderImpl.java:18-31` 级联 javadoc 缺 `-D` 层（§3.7）；`FreewayApp.java:49-53` 悬空 javadoc + 11 个入口工厂无阶梯说明；`FreewayApp.java:93` 静默容忍 null 模块数组而 `args==null` 会 NPE；`AppRuntimeDefault.java:162-167` `close()` 只 catch `RuntimeException`，一个 `Error` 会把状态永久钉在 `STOPPING`。

### 4.4 http（121 文件 / 14144 行）

**P1**

1. `HttpServerConfig` 三套构造 + 默认值分叉（§3.1，已复核）。
2. **`WebServer` 的 public 4 参构造器写死 `secure=false`**（已复核）：`:56-63` 委托时传 `(host, port) -> port > 0, false`；而唯一能表达 TLS 判据的 6 参构造器是包私有。core 内该构造器零调用者，但 **ext 有 7+ 处使用**（`UndertowHttpContractTest`、**`UndertowTlsTest:73/103`** 等）——即 ext 的 TLS 测试建出来的 `WebServer.secure()` 恒为 false。生产路径走 `WebServerBuilder`（6 参）所以未受影响。**修法**：把 6 参转正/删 4 参，并把 ext 测试迁到 `WebServerBuilder`（正好是 ext 审计 §9.4 的待办）。
3. **同名方法上叠置两段 javadoc，悬空那段与实现相反**（已复核，§3.7）。
4. **javadoc 指向不存在的方法**（子审计，§3.7）。
5. **SPI 收口残留**：`engine/http2/hpack/Huffman.java:17`、`StaticHeaderTable.java:8` 在 main 树里只有同包引用却 public，与 `internal/package-info.java:10-12` 自述规则相抵（CHANGELOG 记录过同类收紧 21 个类型，漏了这两个）。

**P2**：`StaticResourceMount.java` 793 行承担 6 种策略，且内建 Content-Type 表与 `MediaTypes.java:5-8` 的自述（"单一来源"）相抵；`WebServer.stop()`（`:164`）与 `close()`（`:185`）字节级同义、文档只教 `stop()`；`SseEmitter.complete()` 与 `close()` 同义；`FreewayHttpEngine` 5 个位置构造器应是参数记录；全模块 `withX` wither 0 个，而 `StaticResourceMount` 的 `cacheMaxAgeSeconds(n)/immutable(b)` 返回**新实例**、`WebServerBuilder` 的同名形态返回 **this**，调用点无法从名字分辨；`RequestComponents`（public record）不拷贝三个 List，`WebServer.java:77-78` 按引用保存。

### 4.5 db（51 文件 / 8147 行）

**P1**

1. **`orWhere` 作为首个条件生成非法 SQL**（已复核）：`Sql.java:174` 的 `where` 有 `conditions.isEmpty() ? "" : "AND"` 兜底、`whereNot` 走 `notConnector`（空→`NOT`），只有 `orWhere`（`:179`）硬写 `"OR"`，`renderConditions:718` 对首条件原样输出连接词 → `SELECT * FROM users WHERE OR role = ?`，到驱动才报语法错；`Sql.Group.orWhere` 同样漏。测试只在已有 where 之后测过 orWhere。
2. **`Sql.set` 一名两语法**（已复核）：`Sql.java:358-394` 用"INSERT 模式 `set("col", value)` / UPDATE 模式 `set("col = ?", value)`"两套语义，模式由 `Sql.insert/update` 隐式决定，守卫靠字符启发（含空格或括号的合法列名如 `` set("`my col`", v) `` 被拒）。
3. **3+ 可选输入仍是位置参数**：`Orm.findAll(type, orderBy, limit, offset)`（`:78`，两个相邻 `int` 可互换且编译通过，用 `""`/`0` 当"未设置"哨兵）；`PoolConfig` 12 组件只有三参 `defaults(...)`、无 wither（已复核前两项）。
4. **`MigrationRunner` 旧 arity 构造器 + 默认值两处声明**（§3.5，已复核）。

**P2**：`QueryImpl.java:249` 用 `instanceof PostgresDialect` 判定 jsonb 提示，而 `H2Dialect extends PostgresDialect` → 给 H2 用户错误修法；`Sql.with(...)`（CTE）占用 wither 前缀，`orWhere`/`whereNot` 词缀不对称，`isInsert()` 一公四私（全仓零调用者）；`PoolDefault`/`MigrationRunner`/`Query` 三个核心公共类型无类级 javadoc；`Sql.equals/hashCode/toString` 走 `sql()`，半成品构建器会从 `Object` 方法抛异常（`log.debug("q={}", Sql.insert("users"))` 即触发）；`MigrationRunner` 同类错误两条消息质量不一、校验和不给修法；`RowMapperResolver` 每行重建缓存签名（`Signature.of(meta)` 逐列 `getColumnLabel` + `List.copyOf`）；`DatabaseHub.get` 返回 null 与 `primary()` 抛异常两种未命中策略并存。

### 4.6 flow（38 文件 / 5481 行）

**P1**

1. **README 快速开始照抄必失败**（已复核）：`:68-98` 的示例 JSON 没有 `"version": 2`，门禁 `GraphSpec.fromText`（`:290-303`）只在 `version==2` 且有 `nodes`/`links` 时放行；README 还写着"v1 (`layout`) 格式仍兼容"，而 v1 已删除并有测试钉住拒绝。
2. **无 `@FlowMarker` 的组件被静默丢弃**（已复核）：`FlowMarkerIndex.java:29-32` 对空 markers 直接 `return`；README 教的 `engine.register((TaskComponent) (ctx, node) -> …)` 正好走这条路，失败推迟到 eval。
3. **组件解析错误信息不成句、不给修法**（子审计）：`FlowDriverDefault.java:218/221/155-157`，而同文件 `:230-238` 已示范好信息。
4. **v1 兼容残留**（子审计）：`NodeType.java:38-41` 的兜底分支经 `GraphSpec.java:325` 的 `requireString` 后不可达；`docs/migration-notes.md:50-55`、`docs/graph-v2.md:5` 仍把已删除的 v1 双轨写成现存事实。

**P2**：`GraphSpec.fromDom` public 且绕过版本门禁（已复核）；`FlowEngineDefault.graphs()` 返回活视图（已复核），与同模块 `FlowTrace`/`FlowMarkerIndex` 的 `List.copyOf`/`Set.copyOf` 快照约定相反；`FlowContextImpl` 保留 trace 旧字符串格式读取；`docs/freeway-flow-design-decisions.md:46` 的 AST 缓存描述已过期（实现早改为 `Collections.synchronizedMap`）；`_meta_<key>` 保留键未文档化；`FlowTrace.setRootGraphId` 全模块唯一 `setX`、`enable(boolean)` 与 `FlowContext.stopped(boolean)` 命名不成对；`NodeType.code()` 无消费者也无逆向入口；`ExprEvaluator` 同文件 `context`/`ctx` 混用；`FlowContextImpl` 用 VarHandle + CAS 手写惰性初始化（未见收益说明）；`ExecState` 用 `java.util.Stack`（Vector 子类）而引擎另有显式 `synchronized`。

### 4.7 cloud（94 文件 / 8590 行）

**P1**

1. **导出面按实例类而非申报类型**（已复核）：`RpcTarget.java:38` 用 `handler.getClass().getMethods()`；`RpcExport.java:7` 说"the methods of `type` become reachable"，同文件 `:28` 又说"exactly the resolved instance's public methods"——**javadoc 自相矛盾，实现取了更宽的一侧**；设计 §5.2 的姿态是"只导出显式声明的面"。`binder.bind(UserApi.class).to(UserApiImpl.class)` 且实现类多出 public 方法时，那些方法直接可达且启动期无提示。**修法**：改用 `export.type().getMethods()`，或在 `RpcTarget.of` 检测"实例类比 type 多出的 public 方法"并启动失败。
2. **事件网格 hook 排序被三份注释说反**（已复核 `CloudEventModule.java:17-18` vs `:58`）：注释说 before `freeway.http.server`，代码 `.after(...)`，理由（mesh origin 需要服务器实际端口）写在代码里；`CloudHooks`/`CloudEventLifecycleHook` 的注释同向说反。按注释改装配会把 mesh origin 推导错。
3. **readiness javadoc 与实现相反**（已复核）：`CloudHealthModule.java:17` 说 in-process store "always healthy"，实现有三种非健康/非空洞答案（draining、连续 3 次心跳失败、未注册）。

**P2**：`ResiliencePolicy.java:190` 把 `"backoff"` 当 serviceId 传进错误信息（`Request interrupted for service 'backoff'`）；`ObjectStorage.java:18` 的 put javadoc 承诺返回 version id 而 `PutResult` 只有一个字段；`Propagator.java:11` 指向不存在的 `InvocationContext.enter`；`CloudException.of(...)`（`:92`）全仓零调用者且 `Kind.OTHER` 自己写着"prefer a named factory"；`PeerConnector` 7 个位置参数（§3.2）；`Baggage` 对 null 抛 NPE 而 `ServiceInstance`/`CloudRequest` 把 null 当空集合；`Propagator.extract` 的 SPI 未定义"什么都没提取到"（`BaggagePropagator` 返回 `null`、`TracePropagator`/`AuthPropagator` 返回空上下文）；四个构造器缺 `requireNonNull`（`MetricsHandler`/`ReadyHandler`/`ObjectStorageDefault`/`PeerConnector`）。

## 5. 有意保留（核查过调用者或设计文档，不要顺手改）

1. **`Dialect` 的两种谓词命名**（db）：`Dialect.java:192-203` 明文区分"能力 `supportsX`"与"语法形态名词短语"，调用者只在 `schema` 包内——自证抽象。
2. **连接恰好释放一次（db）**：`PoolDefault.release/invalidate` 以 `if (!active.remove(pc)) return;` 为幂等闸门；事务内查询根本不持池句柄（`QueryImpl.borrow` 绑定命中时返回 `connection=null` 的上下文），double-release 在形状上写不错。
3. **`FlowContext` 的 `get/getAs/getOrDefault`**（flow）：CHANGELOG 明确"keyed lookup 保持 Map 词汇"，`package-info` 也写明；不是 `getXxx` 残留。
4. **flow 的 `null` + `XOrThrow` 成对查找**、`NodeType` 用 enum + 穷尽 switch、引擎 24 个 `protected` 扩展点：分别是声明式策略、封闭集合的正确建模、ext 装配面。
5. **`WebServer`/`SseEmitter` 的能力探测式默认实现**（http）：`HttpResponse.outputFile` 默认抛 `UnsupportedOperationException`，唯一调用点捕获后回退——能力探测，不是遗漏。
6. **`JsonNormalizer` 与 `JsonWriter` 两份逐字相同的环检测 `Context`**（commons）：javadoc 写明理由（写路径 lambda 不能捕获、避免每次分配），叶子映射已由 `JsonLeaves` 单点共享。
7. **`LogBootstrap` 的 classpath provider 探测**（commons）：唯一被许可的探测，javadoc 给了固定优先级与"用户 `-D` 永不覆盖"，失配警告带 "Fix:"。
8. **四个日志键只能从 `-D`/env 读**（commons）：类加载期读取、早于文件解析，理由在 javadoc 与 `docs/freeway-config.md:139`。
9. **`CloudHttpClientDefault(discovery, loadBalancer)` 与 `PeerHub.Wiring` 不设 wither**（cloud）：前者是 1 个可选输入的两级阶梯（规则允许）；后者 8 字段全必填、单一装配点、包内类型。
10. **仓库内零调用者的公共成员**（cloud：`Endpoint.withHost/withPort`、`ServiceInstance.weight/zone/version/isCanary`、`ObjectStorage.presignedUrl` 默认实现、`Health.starting()` 等）：角色在 ext/应用侧，按 `AGENTS.md`"先找角色"保留。
11. **`AppLogSource` 在 internal 中 public**（boot）：`META-INF/services` provider，必须 public。
12. **`AppConfigDefault.of` 跳过 null 键值**、**重复键只比覆盖文件**（boot）：javadoc + 测试钉住，文档（`ARCHITECTURE.md:146-148`）一致。

## 6. 分级修复清单

### 批次 A：一处改到位（破坏性小、收益最大，建议下一轮就做）

| # | 修复 | 模块 | 牵连 |
|---|---|---|---|
| **A0** | **`CoercerDefault` 的 Number→boolean 走精确十进制路径（`signum() != 0`），补 `0.5`/`0.9`/`4294967296L` 用例** | commons | 无——全轮唯一正确性缺陷，建议单独一个提交先落地 |
| A1 | `HttpServerConfig` 收成规范构造器 + `defaults()` + wither，删 4 个委托构造器与内嵌 `Builder`，默认值只声明一处 | http | ext ~10 处调用点（`EngineFixture` 等） |
| A2 | `WebServer` 删 4 参构造器（或把 6 参转正），ext 测试迁到 `WebServerBuilder` | http | ext 7+ 处（含 `UndertowTlsTest`） |
| A3 | `Sql.orWhere` 首条件修正 + 补测试；`Sql.set` 拆成 `setColumn`/`setExpression` | db | 无（core 内 + 测试） |
| A4 | MDC 默认优先级改为空数组，docs 同步；`JULEnhancer` 的 23 个键字面量收进 `LogKeys` | commons | docs 1 处 |
| A5 | 日志读失败统一策略：删 `lenient`，非法值统一"键名 + 原值 + 期望 + 默认值"一行提示 | commons | 无 |
| A6 | boot 三条 P1：读失败升级为硬失败（重载期保留旧快照）、override 文件剥离 `freeway.profile`、组合段移进 `try` | boot | 无 |
| A7 | 文档说假话的 17 处（§3.7）一次性改正，含 flow README、cloud 三处 javadoc、`AGENTS.md` 的 SLF4J 声明 | 全仓 | 无 |
| A8 | `AGENTS.md` 补"稳定=语义"落地细则：`XDefault`/`XImpl` 判据以"外部能否 `.primary()` 替换"为准（ioc 的两处据此改名） | 全仓 | ioc 两处改名 |

### 批次 B：形态统一（一次改一类，不做零敲碎打）

- B1 `PoolConfig`、`PeerConnector` 补 `defaults()` + 每字段 wither，`Orm.findAll` 的位置哨兵改参数记录（`FindOptions`）。
- B2 `MigrationRunner`、`FreewayHttpEngine`、`JULFileHandler` 的旧 arity 构造器删除，默认值归一处。
- B3 入口工厂动词统一（`create` vs `of`，34 处）。
- B4 `internal` 中 4 个只被测试引用的 public 类型收窄（`ServiceIdentity`、`ConfigFileReader`、`HookLifecycle`、`ConfigSources`），同步改测试。
- B5 `ResponseFraming` 三兄弟合并为一个（或让 `shouldGzipFile` 真的不同），3 个 boolean 收进参数记录。
- B6 ioc：`SymbolSource`/`LoggerSource` 改名 `XDefault`；让 `SymbolProvider` 贡献走被解析的实例（或启动期拒绝替换并给可行动提示）。
- B7 ioc：`.advise()` 在 bind 期校验接口；`Scoping` 补 `within(Runnable)`。
- B8 cloud：`RpcTarget` 按 `export.type()` 取导出面（安全面，建议优先于其它 B 项）。
- B9 commons：`BeanIntrospector` 的 `WeakHashMap` 换成 `ClassValue`；`JsonUtils`/`JsonObject.object()` 命名与 javadoc 补齐；`JsonAccessors` 的静默 null 改为与兄弟一致的抛出。

### 批次 C：观察与择机

- C1 `sealed`（唯一候选 `ExprEvaluator.AstNode`，收益有限）。
- C2 大文件职责：`JULEnhancer` 8 节、`StaticResourceMount` 6 策略、`MigrationRunner`、`Sql` 的拆分——按"一次改到位"的原则整文件拆，不做局部挪动。
- C3 `DbModule` 与 `MigrationRunner` 的默认值归属统一到一处。

## 6.1 实施状态（滚动更新）

| 项 | 状态 | 提交 |
|---|---|---|
| A0 `Coercer` Number→boolean 静默错值 | 已落地 | core `961987be` |
| A1 `HttpServerConfig` 三种构造收敛 | 已落地 | core `361efd4c` + ext `1325338` |
| A2 `WebServer` 4 参构造器（`secure` 写死 false） | 已落地 | 同上 |
| A3 `Sql.orWhere` 首条件 + `set` 拆名 | 已落地 | core `eac5551a` |
| A4 日志键集中 + MDC 默认值清空 | 已落地 | core `c51a9bb1` |
| A5 日志非法值统一"报出并回落" | 已落地 | 同上 |
| A6 boot 三条 P1（读失败口径、profile 变体、组合期清理） | 已落地 | core `dc23a6e5` |
| A7 文档 17 处 + flow v1 残留 | 已落地 | core `f278117e` |
| A8 `AGENTS.md` 判据 + ioc 改名与贡献链回放 | 已落地 | core `f910701f` |
| B5 `ResponseFraming` 纯别名 | 已落地 | 本轮 |
| B7 `advise` bind 期校验 + `Scoping.within(Runnable)` | 已落地 | 本轮 |
| B8 `RpcTarget` 导出面按申报类型 | 已落地 | 本轮 |
| B9 `BeanIntrospector` 缓存泄漏、JSON 写方法命名、类型不符不再静默 | 已落地 | 本轮 |
| B1 `PoolConfig`/`PeerConnector` wither、`Orm.findAll` 位置哨兵 | 待做 | — |
| B2 `MigrationRunner`/`FreewayHttpEngine`/`JULFileHandler` 旧 arity 构造器 | 待做 | — |
| B4 四个只被测试引用的 internal public 类型收窄 | 待做 | — |
| B3 入口工厂 `create` vs `of` | **待用户定调** | — |

审计方法上的两次自我纠正也留在正文：`instanceof` 强转计数与 `Sql.Condition` 构造器计数的假阳性（§1），
以及一条被模块反例撤回的结论（`Dialect` 命名，§5 第 1 条）。

## 7. 附：复现命令与口径

```bash
# 访问器风格：getXxx() 声明 vs bare accessor 声明（分别统计 7 个模块）
grep -rnE '^\s*public\s+(?!static)[\w.<>\[\], ?]+\s+get[A-Z]\w*\s*\(\s*\)\s*[{;]' */src/main/java | wc -l   # 期望 0
grep -rnE '^\s*public\s+(?!static)(?!get|is|has|can|to|with|as|of)[\w.<>\[\], ?]+\s+[a-z]\w*\s*\(\s*\)\s*[{;]' */src/main/java | wc -l

# 旧式 instanceof + 强转（严格多行口径；宽松口径会给出假阳性）
grep -rnzE 'instanceof\s+[A-Z][\w.]*\s*\)\s*\{\s*\n\s*[\w.<>\[\]]+\s+\w+\s*=\s*\(' */src/main/java | wc -l   # 期望 0

# 多级委托构造器阶梯（记录或类上有 ≥3 个公开构造器）
python3 - <<'PY'
import re, pathlib
for p in pathlib.Path('.').glob('*/src/main/java/**/*.java'):
    t = p.read_text(encoding='utf-8', errors='replace')
    for name in set(re.findall(r'^(?:public\s+)?(?:final\s+|abstract\s+)?(?:class|record)\s+(\w+)', t, re.M)):
        n = len(re.findall(r'^\s{4,8}(?:public|protected)\s+' + name + r'\s*\(', t, re.M))
        if n >= 3:
            print(f'{p}: {name} → {n}')
PY

# internal 里 public 类型的跨包引用判定（把 name 换成待查类型）
grep -rn "\bServiceIdentity\b" --include=*.java */src | grep -v '/internal/'

# 公共类型零引用扫描：见本轮方法（类型名在 core+ext 全源码中是否出现）
```

口径说明：所有计数只含 `src/main/java`（除标注"含测试"者）；`getXxx`/bare accessor 只统计**声明**，不统计调用点；`Optional` 只统计返回类型位置。**量化用于指路，结论一律回代码**——本轮三处假阳性（§1）都是靠逐条回读纠正的。
