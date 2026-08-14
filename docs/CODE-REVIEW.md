# Freeway Framework 代码审查报告

- 日期:2026-08-14
- 范围:`freeway-commons` / `freeway-ioc` / `freeway-boot` / `freeway-http`(路由层 + 引擎层)/ `freeway-db` / `freeway-flow` 全部 `src/main/java`(约 404 个文件),及对应测试
- 方法:7 路并行深度静态审查(逐文件通读 + 行号核对),主审查者对所有 Critical/High 发现做了字节码/源码级复核;`mvn -B test` 全量构建验证
- 构建基线:`BUILD SUCCESS` — 1303 个测试、88 个测试类全部通过(commons 342 / ioc 157 / boot 35 / http 272 / db 417 / flow 80)
- 严重级别:S1 必须修复 / S2 应当修复 / S3 建议修复 / S4 锦上添花

## 修复状态(2026-08-14,第一轮:S1 清单 10 项)

| # | 模块 | 问题 | 状态 |
|---|------|------|------|
| 1 | commons | SLF4J JUL provider 抢占 Logback | ✅ 已修:`LogBootstrap.ensureProvider()` 探测外部 provider 并 pin `slf4j.provider`(logback>log4j>simple,用户显式值绝不覆盖),外部 provider 存在时跳过 JULEnhancer;javadoc 全面校正;8 个新测试(自定义 ClassLoader 伪造 provider 类) |
| 2 | http | keep-alive 泄漏 principal/attributes | ✅ 已修:`ExchangeMetaDefault.reset()`(清 principal/attributes、轮换 correlationId、刷新 startTime)+ `HttpContextDefault.reset()` 先清后应用 X-Request-Id;3 个新测试 |
| 3 | http | Huffman 解码器 O(257²) CPU DoS | ✅ 已修:静态 `CODE_BY_LENGTH` 表(码长 5..30 → 前缀码值 → 符号),最长优先扫描每符号 ≤26 次 O(1) 查找;100KB 块实测毫秒级;4 个新测试(性能预算/全符号往返/坏填充/EOS) |
| 4 | http | accept 循环瞬时异常永久死亡 | ✅ 已修:仅关闭条件 break,瞬时错误 50ms 有界退避重试,首错 ERROR 后续 DEBUG 降噪;package-private `AcceptSource` 注入缝 + 故障注入测试 |
| 5 | boot | start() 重入 close() 状态机错乱 | ✅ 已修:hook 完成后复检 `shutdownAttempted` 直接返回,不再覆盖 RUNNING/发 AppStartedEvent;3 个新测试 |
| 6 | db | `Orm.save()` 原始类型 @Generated 主键写 0 | ✅ 已修:`hasIdValue()` 对 @Generated 原始类型零值视为未设置,走 insert() 拿自增键;1 个新测试(两次 save 两个不同 id、无 0 行) |
| 7 | db | MySQL 迁移原子性(DDL 隐式提交) | ✅ 已修:`Dialect.supportsTransactionalDdl()`(MySQL false),MigrationRunner 对含 DDL 迁移在事务前 fail-fast 并给出修复指引;3 个新测试 |
| 8 | flow | 网关死路静默"成功" | ✅ 已修:`ExecState` 死路标记(EXCLUSIVE 无匹配无默认 / join 缺到达,激活即清除),eval 完成时未清标记即抛 FlowException(子图传播、拦截器阻断豁免);5 个新测试 |
| 9 | flow | 混合类型数值比较错误 | ✅ 已修:`parseNumericString()`(Long→Double),Number/String 混合按值比较,非数值回退字典序;2 个新测试 |
| 10 | db | 跨线程事务逃逸 | ✅ 已修:`activeTxThread` 注册表 + `checkNoForeignTransaction()`,无绑定路径借用前守卫,抛清晰 SqlException;2 个新测试 |

修复后测试量:commons 350 / ioc 157 / boot 38 / http 280 / db 423 / flow 87 ≈ **1335 个测试全绿**(`mvn test -DuseIncrementalCompilation=false`)。未提交 git、未加依赖、未改公共 API。

## 修复状态(第二轮:S2 批次,2026-08-14)

| 模块 | 修复项 | 状态 |
|------|--------|------|
| commons | ① `TypeContext.resolve` 自引用泛型界递归→visited-set 回退 Object ② 数字 token 10MB 上限 + parse(String) 32MB 输入上限 ③ DeferScope 重复 id 注册期校验 + 排序失败时按注册序执行全部动作并重抛 ④ Bean 序列化支持 getter 读路径(方案 B:getter 优先、getter-only 只读属性、isX() 识别;7 测试) | ✅ |
| ioc | ⑤ `Binding.id()` 变更迁移缓存(同实例) ⑥ close/realize 竞态:锁内原子封口 + 末轮排水 + get 统一报 closed ⑦ advised PROTOTYPE 每代理懒缓存 ⑧ THREAD 代理 close 后拒绝 ⑨ 贡献注入改为显式 `@Inject`(参数/字段),`@Inject("id")` 优先绑定服务 | ✅ |
| boot+Extension | ⑩ hook 排序引用未知 id → 启动失败(`Extension.validateOrdering()` opt-in) ⑪ 显式重复模块 fail-fast(同实例宽容去重) ⑫ CLI 拒绝空键/`--=x`,位置参数 WARN ⑬ 空/空白 application.json 视为无配置 ⑭ `AppConfig.get(ConfigSpec)` 用 CoercerDefault 解析 | ✅ |
| http 路由 | ⑮ 正则匹配段长上限 1024(ReDoS 缓解) ⑯ If-Range 秒级截断 ⑰ HEAD+sendfile 只写头 ⑱ WebServerBuilder 始终追加内置 mapper ⑲ bodyAsJson 415 + `*+json` 接受 ⑳ >50MB 目录源流式服务 | ✅ |
| http 引擎 | ㉑ maxBodySize 流式读取计数过滤(LimitedInputStream) ㉒ WS 升级后清 SO_TIMEOUT ㉓ HTTP/1.1 拒 CTL + CL 严格 1*DIGIT ㉔ H2 伪头校验(origin-form `:path`、Host 规则 `:authority`、非 CONNECT 可选) | ✅ |
| db | ㉕ 未知 JDBC URL fail-fast ㉖ execute/query(Sql) 过方言校验 + RETURNING 文档 ㉗ MySQL `\'` 反斜杠转义(方言能力) ㉘ Schema.ensure 事务守卫 + 文档 ㉙ introspection 失败 fail-fast + Schema 按点跳过 | ✅ |
| flow | ㉚ `$for` LOOP 原子抢占 ㉛ 子图继承 per-eval 拦截器(不重复) ㉜ 全新运行清 EventBus 订阅(子图豁免) ㉝ onNodeStart 异常时 onNodeEnd 仍配对 ㉞ INCLUSIVE join 计数按迭代重置 | ✅ |

第二轮修复后测试量:commons 364 / ioc 165 / boot 53 / http 322 / db 446 / flow 93 ≈ **1443 个测试**。跨模块集成:http 的 RouteIndex/WebSocketIndex 构造器补参数级 `@Inject`(ioc 贡献注入显式化的连带);`Http2ProtocolTest` 一用例改用合法 `:path`(原用例依赖被修复的"无前导 /"缺陷)。未提交 git、未加依赖。

## 修复状态(第三轮:S3 批次,2026-08-14)

| 模块 | 修复项 | 状态 |
|------|--------|------|
| commons logging | ① 同路径日志文件全局 handler 去重(规范化绝对路径注册表,reset 后自动换新) ② purge 排除 `.gz.tmp` 与压缩中源文件 ③ `freeway.log.console.level` 只作用于 freeway 自有 handler ④ StackWalker 调用者解析:论证后文档化(lazy walker 已短路,缓存会产出错误调用者),markers 忽略已注明 | ✅ |
| commons json/coercion | ⑤ JSON 重复键 last-wins 文档化+测试固化 ⑥ 字符串 `"NaN"` 与 Infinity 一致拒绝(值判断,大小写不敏感) | ✅ |
| ioc | ⑦ final 字段携带注入注解 → 构造期 fail-fast ⑧ `${name:-default}` 剥离单个前导 `-` ⑨ Defer 内 async/ordered 发布在总线关闭后排空静默 ⑩ 订阅者抛 Error 不再逃逸(catch Throwable 继续派发) ⑪ @PreDestroy 抛 Error 不中断 drain ⑫ `publish(String)` 类事件语义文档化+测试固化 | ✅ |
| boot | ⑬ AppBuilder 单次守卫 AtomicBoolean ⑭ profile 层剥离 `freeway.profile`(get 与 profiles() 不再分叉) ⑮ 生命周期事件失败不对称文档化 ⑯ `freeway.env.prefix` 仅 JVM 级文档化+测试固化 | ✅ |
| http 路由 | ⑰ status() 校验 100-599 ⑱ 子目录 index.html + 无资产挂载不阻断后续挂载(hasResource 探测) ⑲ 路由字面段注册时百分号解码(编码斜杠防碰撞) ⑳ 非 ISO-8859-1 头值拒绝(防静默 `?`) | ✅ |
| http 引擎 | ㉑ 截断 HPACK 整数 → COMPRESSION_ERROR(不再 AIOOBE) ㉒ SETTINGS_HEADER_TABLE_SIZE uint32 范围校验 + 解码器负值 clamp(防状态污染) ㉓ 字面头名 token 校验(伪头豁免) ㉔ 204/205/304 丢弃 Content-Length(HEAD 保留) | ✅ |
| db | ㉕ 迁移校验和双轨(原始字节 + CRLF→LF 归一化,向后兼容) ㉖ `one()` 多行截断文档化+测试固化 ㉗ 空集合 `IN (:ids)` 错误信息补指引 ㉘ PoolDefault borrow/close 竞态:`handOut()` 锁内复检 + 确定性竞态测试 ㉙ 跨库事务无 XA 文档化 | ✅ |
| flow | ㉚ 一元负号完整实现(`-x`/`-(a+b)`/`--x`,类型保持) ㉛ Graph.fromText 与 GraphSpec 同一版本门 ㉜ `putAll` 过滤 null 与 `put` 一致 ㉝ IocContainerAdapter 仅"无绑定"回退 null,真实错误重抛 | ✅ |

第三轮修复后测试量(最终全量 `mvn test` 验证):commons 374 / ioc 182 / boot 60 / http 349 / db 453 / flow 99 = **1517 个测试全绿**。未提交 git、未加依赖。

---

## 总体评价

代码质量明显高于同类自研框架的平均水平:核心热路径(单例并发发布、连接池释放、HTTP/1.1 防走私、事务资源释放)写得很认真,AGENTS.md 里列的回归项(静态文件穿越、连接恰好释放一次、SQL 参数解析)大多已落实并有测试。**没有发现"框架核心逻辑是错的"这类问题**。

但存在三类系统性风险:

1. **网络面 DoS 向量**(S1):HPACK Huffman 解码器 O(n·257²)、Huffman 无长度上限、accept 循环瞬时异常即永久死亡、路由正则无超时——这些是未认证攻击面。
2. **跨请求/跨模块状态泄漏**(S1):keep-alive 复用泄漏 `principal`/`attributes`(认证上下文);SLF4J JUL provider 无条件注册可静默顶掉 Logback。
3. **静默错误**(S1/S2):flow 引擎死路"成功"、`Orm.save()` 原始类型主键写 0、DB 工作跨线程逃逸事务、未知 JDBC URL 静默按 PostgreSQL 处理——都是"不报错但结果错"。

测试覆盖健康(1303 个),但**集成测试全部跑 H2-in-PostgreSQL 模式,没有任何真实 MySQL/PostgreSQL/SQLite 驱动测试**,所有跨方言问题(MySQL DDL 隐式提交、`\'` 转义、SQLite 批量主键)在 CI 中不可见。

---

## 一、必须优先修复(S1,按影响排序)

| # | 模块 | 问题 | 位置 |
|---|------|------|------|
| 1 | commons | **JUL SLF4J provider 无条件注册,可静默顶掉 Logback/Log4j**。javadoc 声称"SLF4J 自动选 Logback"与 SLF4J 2.0.18 实际行为相反。三重证据:(a) 源码:`JULLoggerServiceProvider.java:44-52` 的 `initialize()` 无条件建 JUL factory + `JULEnhancer.configure()`,零检测逻辑,而 javadoc 声称"无外部 provider 时才安装";(b) 字节码:SLF4J `bind()` 对多 provider 仅告警后取 `list.get(0)`(ServiceLoader 即 classpath 顺序),`findServiceProviders()` 无任何过滤;(c) 实测(2026-08-14,slf4j-api 2.0.18 + logback-classic 1.5.6 + freeway-commons,tmp-slf4j-test 可复现):commons 在前 → `Actual provider is of type [JULLoggerServiceProvider]`,logback 配置失效;logback 在前 → logback 胜出。修正措辞:不是"必然顶掉",而是**取决于 classpath 顺序**——freeway-commons 作为基础依赖通常先出现,失败模式常见。SLF4J 会打 W 级告警("multiple SLF4J providers"),但 logback.xml 被无视的后果是实际可见的。修复方向:把 provider 移出核心 jar(可选模块/opt-in),或按 `slf4j.provider` 系统属性显式选择;provider 内部无法自检(选择发生在任何 provider 代码运行之前)。 | `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`;`logging/LogBootstrap.java:7-11`;`JULLoggerServiceProvider.java:44-52` |
| 2 | http | **keep-alive 连接复用泄漏 `principal` 与 `attributes`**:`reset()` 不清共享的 `ExchangeMetaDefault`,请求 N 设置的认证上下文对请求 N+1 可见(同 socket 上未认证请求"被认证")。correlationId 对空输入保留旧值,同连接请求共用一个 id。 | `engine/HttpContextDefault.java:124-147`;`ExchangeMetaDefault.java:17-34`;`engine/HttpSession.java:255` |
| 3 | http(引擎) | **HPACK Huffman 解码器 O(257²)/符号 CPU DoS**:每个符号扫描全部 257 个表项、且再扫描 257 个验证"无更长匹配"。64KB 攻击性头部块 ≈ 13 万符号 ≈ 数十亿次循环,单连接读线程被钉住,同连接所有流停摆。 | `engine/http2/hpack/Huffman.java:334-384`(热循环 347-369) |
| 4 | http(引擎) | **accept 循环对任何瞬时 IOException 永久退出**(EMFILE/ENOBUFS/EINTR),监听器死透但引擎仍报"started",新连接在 OS backlog 层被静默拒绝。 | `engine/FreewayHttpEngine.java:120-142` |
| 5 | boot | **start() 期间重入 close() 导致状态机错乱**:`synchronized` 可重入,hook 内调用 `app.close()` 完整执行到 STOPPED 后,`start()` 仍无条件置 RUNNING——应用报运行中而容器已关闭,后续 `get()` 全抛。 | `boot/AppRuntimeDefault.java:46-67,79-120` |
| 6 | db | **`Orm.save()` 对原始类型 `@Generated` 主键显式写 0**:`idProp.read(entity) == null` 对 `long` 永不成立,新实体走 upsert 路径 `INSERT ... id=0 ON CONFLICT`,绕过序列,二次 save 更新 0 行。 | `db/Orm.java:135-147,156-169` |
| 7 | db | **MySQL/MariaDB 迁移原子性破裂**:所有语句+校验和行包在一个事务里,但 MySQL DDL 隐式提交——DDL 落库而校验和行丢失,下次启动重跑 DDL 直接失败,启动永久卡死。 | `db/migration/MigrationRunner.java:322-335` |
| 8 | flow | **网关死路静默"成功"**:EXCLUSIVE 无匹配且无默认分支、INCLUSIVE/PARALLEL join 缺到达时仅 `LOG.warn`,首次 eval 永不报错(完成态检查只在 `isReverting()` 时触发)——编排器最坏的一类失败:任务静默丢失。 | `flow/FlowEngineImpl.java:387-411,421-442,461-464,163-172` |
| 9 | flow | **混合类型数值比较静默错误**:`"10" > 9` 按字典序比较得 false、`"10" == 10` 为 false;JSON 来的上下文数据几乎必然触发,条件分支静默走错。 | `flow/ExprEvaluator.java:395-406,408-418` |
| 10 | db | **DB 工作跨线程逃逸事务**:`ScopedValue` 不随线程传播,事务内 lambda 起线程做的写入跑在独立连接上,父事务回滚后依然提交——原子性静默破裂。 | `db/internal/DatabaseImpl.java:114-163,259-267` |

---

## 二、分模块发现

### freeway-commons(12)

**S1 — JUL SLF4J provider 无条件注册**(上表 #1,三重证据:源码零检测逻辑 + SLF4J bind() 字节码取首个 provider + 双顺序实测复现)。

**S2 — `TypeContext.resolve` 自引用泛型界无限递归 → StackOverflowError**:`class Node<T extends Comparable<T>>` 反序列化时 `resolve(T)→resolve(Comparable<T>)→resolve(T)→…`,`StackOverflowError` 无任何捕获。建议 visited-set 或深度计数,回退 `Object.class`。`json/JsonCoercions.java:555-558,593-600`。

**S2 — JSON 数字 token 无长度上限**:字符串有 10MB 上限,数字没有;16-32MB 数字串经 `new BigInteger/BigDecimal`(超线性)造成 CPU/内存尖峰,`parse(String)` 本身也无输入大小上限。`json/JsonParser.java:300-351,28,34`。

**S2 — `DeferScope.sort` 失败只在 commit 时暴露且静默跳过全部延迟动作**:重复 id/环在 `drain` 时才检测,`sort()` 抛异常后零个动作执行(commit、缓存清理、锁释放静默丢失)。建议 `add()` 时即校验。`scoped/DeferScope.java:96-151,42-53`。

**S2 — Bean 序列化只读字段、从不调用 getter**:getter-only/计算属性从 JSON 静默消失,`isX()` 约定完全不识别。建议补 getter 读路径或明确文档化字段语义。`bean/BeanPlan.java:166-212`;`json/JsonWriter.java:328-371`。

**S3 — 同一日志文件挂两个 logger → 两个独立 `JULFileHandler` 共享文件轮转互踩**:A 轮转 `Files.move` 后,B 的 FileOutputStream 继续写已移动的 inode,记录静默进归档/丢失。去重只查目标 logger 自身 handler。`logging/JULEnhancer.java:491-531`;`JULFileHandler.java:352-403`。

**S3 — `purgeOldFiles` 可能删除正在 GZIP 的 `.gz.tmp` 暂存文件**:压缩线程写已删除 inode,原子改名失败,压缩静默丢失。建议 purge 过滤 `.tmp` 后缀。`JULFileHandler.java:460-500,510-542`。

**S3 — `freeway.log.console.level` 覆盖用户自配的 `ConsoleHandler` 级别**,与"不动用户 handler"的 formatter 约定矛盾。`JULEnhancer.java:330-345 vs 365-400`。

**S3 — JSON 重复键静默 last-wins**、**coercion 接受 `"NaN"` 但拒绝 Infinity(不对称,NaN 转 boolean 得 false)**、**每条日志记录做全量 `StackWalker.walk`(可用 call-site 缓存)**。`JsonParser.java:136-162`;`CoercerDefault.java:514-521,273-296`;`JULLoggerAdapter.java:96-111`。

**S4 — `ConfigSpec.parse(String)` 对无 parser 规格抛运行时 IllegalStateException**(与 boot 的 `AppConfig.get(spec)` 联动,见 boot 节)。

### freeway-ioc(12)

核心解析/注入/事件总线无 S1 问题;单例发布并发正确性扎实(全局重入锁 + 每线程 realize 栈 + 锁内发布)。

**S2 — 绑定 `id()` 变更后缓存未迁移**:`Binding.id()` 改索引键但 `serviceCache/targetCache` 不动,晚变更的 id 会静默实例化出第二个单例,两个实例都收 `@PreDestroy`。`BindingImpl.java:136-142`;`BindingIndex.java:59-103`。

**S2 — realize() 与 close() 竞态孤儿实例**:close 在 drain 后才置 `closed=true`,并发慢构造的实例插入 targetCache 后无人 `@PreDestroy`。建议锁内原子置 closed + 清缓存。`ContainerImpl.java:273-274`;`Shutdown.java:103-105`。

**S2 — 被代理的 PROTOTYPE 每次方法调用新建目标**:同一 proxy 上两次调用是两个实例,状态不跨调用;未代理原型却是每次 `get()` 一个实例。行为不一致。`ServiceRuntime.java:156-170`。

**S2 — close 后 THREAD 作用域代理调用仍会实例化**(单例路径会抛,线程路径不会——关闭契约不一致)。`ServiceRuntime.java:124-144 vs 94-122`。

**S2 — 绑定的 `List<X>`/`Map<String,X>` 服务被贡献(contribution)机制永久遮蔽**,`@Inject("id")` 也被无视。建议仅 `@Inject` 标注时走贡献解析。`InjectResolver.java:104-145,230-279`。

**S3 — `final` 字段上的注入注解被静默跳过**(无 setter 的 final 字段保持默认值,零报错);**`${name:-default}` 保留前导 `-`**(文档宣传 `:-` 语法);**Defer 中 async/ordered 发布在总线关闭后排空打出虚假告警**;**`publish(String)` 是类事件不是 topic**(单参字符串订阅者静默收不到);**订阅者/`@PreDestroy` 抛 `Error` 会中断整个 drain**(应 catch Throwable,与 Defer/ScopedCache 一致);**SPI 测试资源引用已不存在的类**(`META-INF/services/com.jujin.freeway.ioc.ModuleEx` 指向 `FreewayTest$BootModule`)。`InjectResolver.java:49-75`;`SymbolSourceDefault.java:167-177`;`EventBus.java:304-364,102-113,136-168`;`Lifecycle.java:30-41`。

### freeway-boot(12)

**S1 — start() 重入 close() 状态机错乱**(上表 #5,已复核:`start()` 无 `shutdownAttempted` 复检,只 catch RuntimeException)。

**S2 — `Error`(非 RuntimeException)绕过全部失败/回滚路径**:hook 抛 Error 时 `HookLifecycle` 不回滚已启动 hook、状态卡在 STARTING、`AppBuilder` 不 close 不移除 shutdown hook,`shutdownHook(false)` 时永久泄漏。三处都是 `catch (Exception/RuntimeException)`。`HookLifecycle.java:43`;`AppRuntimeDefault.java:62`;`AppBuilder.java:153`。

**S2 — "hook 配置无效必须启动失败"回归只落实一半**:before/after 引用不存在的 id 只是 WARN 并按插入序执行——正是 AGENTS.md 要求失败的场景。`Extension.java:193-209,250-261`。

**S2 — 重复模块类静默去重(WARN)**:两个显式传入的同类型不同配置实例,后者被丢弃;与 `Freeway.create` 的 fail-fast 契约不一致。`AppBuilder.java:107-125,173-187`。

**S2 — CLI 解析静默接受垃圾**:`--` → 键 `"freeway."`;`--=x` → `freeway.=x`;拼错的 `--profle=dev` 变成无害未知配置项,应用不带 profile 启动。`ConfigLoaderDefault.java:174-218,235-237`。

**S2 — 空 `application.json` 启动即崩,空 properties 却静默接受**:四层文件行为不一致(缺失→跳过、空 properties→无操作、空 JSON→硬失败)。`ConfigLoaderDefault.java:140-154`。

**S2 — `AppConfig.get(ConfigSpec)` 对 coercer 解析形式的 spec 抛 IllegalStateException**:最方便的工厂形式通过自然 API 不可用,框架自己的 DbModule 都要绕。建议默认 CoercerDefault。`AppConfig.java:19-21`;`ConfigSpec.java:146-153`。

**S3 — env 前缀只读 `System.getProperty`**:配置级联里的 `freeway.env.prefix`(文件/env/CLI)被静默忽略;`FREEWAY__X` 产生双点键;含下划线的配置键无法经 env 设置。`ConfigLoaderDefault.java:85-118`。

**S3 — `AppBuilder.start()` 单次使用守卫非原子**(并发 start 可建两个容器两个 shutdown hook);**`Freeway.create` 绑定期失败泄漏半成品容器**;**生命周期事件失败语义不对称**(AppStarted 订阅者失败静默吞,AppStopping 失败则 FAILED);**profile 文件里写 `freeway.profile` 会使 `config().get(...)` 与 `profiles()` 分叉**。`AppBuilder.java:38,91-98,125-128`;`AppRuntimeDefault.java:61,91-132`;`ConfigLoaderDefault.java:66-72,280-289`。

### freeway-http — 路由与请求层(14)

**S1 — keep-alive 状态泄漏**(上表 #2,已复核:`reset()` 不清 principal/attributes,`setCorrelationId` 空值保留旧 id)。

**S1 — multipart 全内存解析,3-5× 内存放大**:body→ISO-8859-1 String→substring→byte[] 多份拷贝,无临时文件溢出路径;每 part 10MB 硬编码上限与 `maxBodySize` 脱钩且在全量缓冲后才检查(恰好 10MB+CRLF 差 2 字节误伤)。`body/MultipartForm.java:42-56,94-128,169,295-306`。

**S2 — 路由正则约束无超时(ReDoS)**:64 字符上限挡不住 `(a+)+$` 类灾难性回溯,攻击者可拿 ~8KB 段钉死虚拟线程。建议匹配前截断段长/拒绝嵌套量词。`route/RouteIndex.java:210`;`PathPattern.java:18`。

**S2 — If-Range 用全毫秒 mtime 比较,Last-Modified/If-Modified-Since 截断到秒**:客户端回显自己收到的 Last-Modified 时 `lastModified.isAfter()==true`,Range+If-Range 请求被答成完整 200,断点续传失效。`staticfile/StaticResourceMount.java:313-329 vs 404-420`。

**S2 — sendfile 路径对 HEAD 请求写完整 body**(绕过 `suppressBodyBytes`;RFC 9110 违反;静态文件调用方自挡,公共 API 裸奔)。`engine/HttpContextDefault.java:359-389`;`Http11ResponseWriter.java:47-50`。

**S2 — `WebServerBuilder` 加自定义异常映射器会整体替换内置映射**:413/400 变 500;HttpModule 入口却是追加语义,两个入口行为不一致。`WebServerBuilder.java:191-193`。

**S2 — `bodyAsJson` 对非 `application/json` Content-Type 抛 IllegalStateException → 500**,且拒绝 `application/*+json` 合法媒体类型。`AbstractHttpContext.java:162-166,294-299`。

**S2 — SSE 生命周期缺口**:心跳失败只置 closed,不关流不通知 handler;`complete()` 可能阻塞在心跳持有的锁上;未 complete 的 emitter 泄漏。`sse/SseEmitter.java:49-51,101-153`。

**S3 — 静态文件 >50MB 抛 IOException → 500**(流式/sendfile 本可服务);**`status(int)` 不校验、204 上 handler 自设的 Content-Length 原样上线路**(RFC 9110 禁止);**根挂载遮蔽全部路由、子目录 index.html 不服务、非 fallthrough 404 阻断后续挂载**;**注册含百分号编码字面段的路由永远不可匹配**(注册存原始、匹配先解码,且精确缓存跳过含 %/+ 的路径);**correlationId 跨 keep-alive 请求复用**;**非 Latin-1 响应头值经 ISO-8859-1 序列化静默变 `?`**。`StaticResourceMount.java:549-551,577-580,678-681,104-112,269-298`;`HttpContextDefault.java:235-238`;`Http11ResponseWriter.java:129-139,134-139`;`RouteIndex.java:81,129,175`;`HttpSession.java:243-257`。

**S3(主审查者补充)— 同形不同名参数路由静默遮蔽**:`/users/{id}` 与 `/users/{name}` 共存不报错,注册顺序决定唯一胜者,后者是死路由。`route/RouteIndex.java:184-188,266-273`。

### freeway-http — 引擎与传输层(9)

**S1 — Huffman 解码器 CPU DoS**(上表 #3,已复核:347-369 双重 257 扫描)。

**S1 — accept 循环瞬时异常即永久死亡**(上表 #4,EMFILE 可达:默认 `maxConnections=0` 不限)。

**S2 — HTTP/2 `:path`/`:authority` 校验与 HTTP/1.1 不对称**:`:path` 不要求以 `/` 开头、不拒 `//`/绝对形式,`:authority` 不查 `@`/空白/CTL(H1 的 Host 全查);同时 `:authority` 被强制要求(RFC 7540 为可选)。代理混淆/走私的经典温床。`hpack/HeaderFields.java:36-56`;`HttpSession.java:602-631`。

**S2 — `maxBodySize` 可被流式读取绕过**:限制只落在 `readAll()`/未读 body 的 `drain()`;handler 用 `ctx.bodyStream()` 边读边写可无限量接收(HTTP/2 流控只限在途 ~64KB)。`engine/RequestBody.java:57-106`。

**S2 — WebSocket 空闲超过 `readTimeout`(默认 30s)被 1006 杀掉,服务器从不发 ping**:RFC 6455 不要求客户端 ping,长空闲连接被无 close 帧强拆(H2 路径有 `updateReadTimeout()` 规避,WS 没有)。`HttpSession.java:134`;`engine/ws/WebSocket.java:39-41,134-137`。

**S3 — HTTP/1.1 解析器接受控制字符/非 token 字节**(值里的裸 CR 能流入 `X-Request-Id` 回显路径导致整个会话 500 死掉;弱响应拆分原语);`Content-Length: +5` 被接受。`HttpParser.java:293-341,135-138`;`HttpSession.java:258,317-318`。

**S3 — 截断 HPACK 整数抛 `ArrayIndexOutOfBoundsException` 而非 COMPRESSION_ERROR**(对端收不到错误码);**`SETTINGS_HEADER_TABLE_SIZE` 接受负数**(状态被污染且后续所有 in-band 更新被拒);**H2 字面头名字只查小写不查 token 字符**(非 ASCII/CTL 名进入应用)。`HPackContext.java:71,43-46,312-322,357`;`SettingIdentifier.java:16-25`。

### freeway-db(15)

**S1 — MySQL 迁移原子性**(上表 #7);**S1 — `Orm.save()` 原始类型主键写 0**(上表 #6,已复核)。**S1 — 跨线程事务逃逸**(上表 #10)。

**S2 — 未知 JDBC URL 静默回退 PostgreSQL 方言**:Oracle/SQL Server/DB2 URL 生成 PG 语法(identity/ON CONFLICT/pg_indexes),IoC 路径连警告都没有。建议未知 scheme 启动失败。`DatabaseBuilder.java:97-121`;`DbModule.java:248-265`。

**S2 — `Database.execute(Sql)` 绕过 `Sql.sql(Dialect)` 校验并丢弃 RETURNING 行**:MySQL 上 raw 语法错误,PG 上 `executeUpdate+getGeneratedKeys` 把 RETURNING 列值静默丢掉。`Database.java:42-44,60-62`;`Sql.java:416-428`;`QueryImpl.java:186-204`。

**S2 — MySQL 反斜杠转义字符串误 lex**:`'it\'s'` 在 `\'` 处提前闭合,后续 `:p` 参数被吞(方言只声明了 PG 的 `E'...'`)。建议加 `backslashEscapesStrings()` 能力。`util/SqlTextParser.java:571-586`。

**S2 — `Schema.ensure()` 非原子**:中途失败半套 schema 无回滚;MySQL 上包在用户事务里会隐式提交调用方全部 DML。`schema/Schema.java:98-190`。

**S2 — `Dialect.querySet` 吞掉 introspection 失败返回空集**:索引查询失败 → ensure 重复建索引(MySQL 无 IF NOT EXISTS)→ 启动失败,真因被掩盖。`dialect/Dialect.java:320-332`。

**S3 — `Row`/记录映射重复列标签静默互相覆盖**(未别名 join 的 `SELECT *` 数据损坏);**批量生成主键按位置配对,SQLite 驱动只回最后一个 rowid**(第 0 行拿到键,其余全 null);**迁移校验和 = 原始字节无换行归一化**(CRLF/LF 检出差异即误报 checksum mismatch);**`one()` 多行静默截断**;**空集合 `IN (:ids)` 抛异常**(每个调用点都要特判);**`PoolDefault.close()` 与在途 `borrow()` 竞态可遗留物理连接**(active.add 不在 lifecycleLock 内);**DatabaseHub 跨库事务无任何文档/守卫**(db1 回滚后 audit 写已提交)。`RowMapperResolver.java:143-157,249-280`;`BatchQueryImpl.java:122-129,163-178`;`MigrationRunner.java:146-148`;`QueryImpl.java:94-109,445-496`;`PoolDefault.java:69-158,216-279`;`DatabaseHubImpl.java:13-61`。

### freeway-flow(15)

**S1 — 网关死路静默成功**(上表 #8,已复核);**S1 — 混合类型数值比较错误**(上表 #9)。

**S1 — `&&`/`||` 不短路**:两操作数总是先求值,`false && (x - 1)` 抛异常而非 false,条件侧效果被无条件执行。`ExprEvaluator.java:110-127`。

**S1 — PARALLEL 分支共享同一 `FlowContext`**:文档(freeway-flow-parallel-context-isolation.md)描述的 fork/overlay/join 隔离未实现;并发读改写丢更新、stepCount/跟踪点被跨分支竞争。`FlowEngineImpl.java:466-505`;`FlowContextImpl.java:38`。

**S2 — `$for` LOOP 并发重入可双执行**:`loop_run_in` 检查与 `loop_run_out` 压栈非原子;无 `$for` 的 LOOP 栈机制是死代码。`FlowEngineImpl.java:509-534,563-567`。

**S2 — 子图 eval 绕过调用方提供的 per-eval 拦截器**(`runGraph` 传 null options);**复用 FlowContext 再次 eval 会从 trace 静默续跑**(无"全新运行"API);**暂停/中断的运行订阅泄漏**(EventBus 只在正常完成时 clear);**`onNodeStart` 抛异常时 `onNodeEnd` 不配对**。`FlowExchanger.java:95-114,37`;`FlowEngineImpl.java:135-155,241,263-278`;`FlowEventBus.java:77-79`。

**S2 — 并行扇出 `CountDownLatch.await()` 无超时**;INCLUSIVE join 计数器跨循环迭代残留。`FlowEngineImpl.java:488-493,432-439`。

**S3 — 一元负号只对字面量有效**(`-x`/`-(a+b)` 报"Invalid number"——语法根本没有算术负号);**`Graph.fromText` 与 `GraphSpec.fromText` 版本校验不一致**(v1 文档经主 API 静默加载);**`put` 忽略 null 而 `putAll` 存储 null**(不对称);**`IocContainerAdapter` 把容器真实的 IllegalArgumentException 也当"组件不存在"吞掉**。`ExprEvaluator.java:266,291-309`;`Graph.java:76-79`;`GraphSpec.java:290-298`;`FlowContextImpl.java:216-231`;`FlowModule.java:84-94`。

---

## 三、跨模块/文档一致性(主审查者独立发现)

1. **CLAUDE.md 版本漂移**:写 "JUnit 5.12, SLF4J 2.0.17",pom 实际 `junit-jupiter 6.1.3`、`slf4j 2.0.18`(CHANGELOG 已更新,CLAUDE.md 漏改)。
2. **groupId ≠ 包名**:Maven `com.jujin8.freeway` vs Java 包 `com.jujin.freeway`。发布物坐标与代码命名空间不一致,建议统一(牵涉 freeway-ext 及已发布版本,需评估)。
3. **README 与代码的小出入**:README 示例用 `com.jujin8.freeway` 依赖坐标(与 groupId 一致,但与包名不符);"core modules have zero external dependencies" 与 CLAUDE.md "SLF4J API only" 表述需要统一口径。
4. **未跟踪文件**:`.reasonix/` 与 `reasonix.toml` 未入 git(若是本地工具配置,建议加入 .gitignore 或提交)。
5. **无空 catch 块、无 printStackTrace、无 TODO 残留**(grep 全库确认)——工程卫生好。

---

## 四、测试覆盖缺口(按风险排序)

1. **跨方言集成测试为零**:全部跑 H2-PG 模式,MySQL 隐式提交/`\'` 转义、SQLite 批量主键、`pg_indexes` 在 H2 上不存在等全部不可见。建议至少加 SQLite(纯 Java、CI 友好)矩阵,并给 MySQL 路径加"拒绝/警告"守卫测试。
2. **SLF4J 多 provider 场景无测试**(JUL provider 抢占 Logback 完全未覆盖)。
3. **Huffman 大输入/恶意输入无测试**(只测了 RFC 向量与小回环)。
4. **accept 循环故障注入无测试**;重入/并发 `close()` 无测试;hook 抛 `Error` 无测试。
5. **keep-alive 状态隔离、multipart 边界注入/内存放大、正则灾难回溯、If-Range 亚秒 mtime、HEAD+sendfile、mapper 替换**均无回归测试。
6. **flow**:短路语义、`"10" > 9`、死路断言、并行分支复合写、`$for` 并发重入均无测试。
7. **db**:原始类型生成主键、事务内换线程、`execute(Sql)`+RETURNING、未知 URL 方言回退、重复列标签、空 `IN ()`、多行 `one()`、close 与 borrow 竞态均无测试。

---

## 五、值得肯定的设计(供后续开发保持)

- **HTTP/1.1 防走私是教科书级**:重复 Content-Length(含逗号列表)、CL/TE 冲突、非末位 chunked、未知编码、空 TE token 全拒;未读 body 按 maxBodySize 有界排空后才复用 keep-alive;4KB 缓冲的所有权在管道化下保持精确。
- **HTTP/2 流控完整正确**:连接+流双窗口、padding-only DATA 信用恢复、半窗 WINDOW_UPDATE、31 位溢出检查、SETTINGS_INITIAL_WINDOW_SIZE 对活流的增量、无丢失唤醒的 park/unpark。
- **静态文件安全真正加固**:每访问路径 realpath 包含性复检(TOCTOU)、SecureDirectoryStream 符号链接竞态防护、NOFOLLOW_LINKS 重开、channel 所有权"恰好一次关闭"。
- **资源释放纪律**:ExecuteContext 双重关闭守卫、事务 finally 在 checked/unchecked/Error 全路径恢复隔离级别并释放连接、PoolDefault 的 lifecycleLock 串行化 release/close 排水。
- **SQL 词法器**:方言声明的词法画像(`::`、`$tag$`、`E'...'`、`#>` 豁免、双引号、方括号标识符)加上重复命名参数回归套件,精确覆盖 AGENTS.md 回归清单。
- **单例并发发布**:全局重入锁 + 每线程 realize 栈 + 锁内发布,无不安全发布,跨线程循环依赖报清晰错误而非死锁。
- **flow 的防御工程**:三色 DFS 环检测、深度/迭代/嵌套预算全有上限、StackOverflowError 包装、512 项 AST LRU + 双重检查锁、无可执行面(无反射无函数调用)。
- **日志文件 handler 工程**:轮转前 flush、`.gz.tmp→.gz` 原子压缩、openFailed 重试恢复、保留期清理、溢出防护的逐记录大小估算。
- **WebSocket 一致性严格**:掩码强制、RSV/FIN/控制帧规则、125 字节控制载荷、最小长度编码强制、close 码白名单、跨分片 UTF-8、锁定的多帧发送。
- **关闭设计**:GOAWAY 预告、HTTP/1.1 在途请求宽限窗、写看门狗防阻塞 socket 写钉死虚拟线程。
