# 事件平面整改方案 —— 各归其位

> **批次 0 正式对账（2026-09-24 开工日）**：main 版 **17 文件**、test 版 **10 文件**、
> ext 版（main 4 + test 5）**全数命中归位表处置行，零落进无注明"留（原样）"行——封板成立**。
> 基线注记：`§3.1` 各路径以 **`ioc.event` 包移动后**为准（`ioc/` 根包 13 事件类型 →
> `ioc/event/`，纯 import 移动，含 `EventExecutorSupportTest` 拆位与新 `EventSink` 契约段、
> `deferOrRun` 孤儿 javadoc 清理）——本方案归位表与批次 0 记录时即读自该工作树，路径天然一致；
> **批次 1 须待包移动提交落地后开工**（重叠文件近全量，提交边界须干净）。

---

> 状态：**定稿 v7 · 已实施（1.5.5 收口）**——§5 四批全部落地：批次 1 `72f513a2`、
> 批次 2 ext `d0bc985`、批次 3 文档 `ea6ba00d`、发布 `4454a404` + tag `v1.5.5`
> （ext 同车 `2d40ab5`/`v1.5.5`，point-bbs 锁版 `d2a1efd`）。拆除门两仓零输出，
> 2077/154/21 全绿。（2026-09-24 六审修订，吸收 E1–E2：批次 0 封板扩至 ext 仓
> 同令两版（§3.3 自此受对账约束，预跑当场推翻 `KafkaHeaders` "原样"判决——第四处
> F1 同型案实锤）、批次 1/2 验收增"拆除门"（对账复跑输出为零 = 桥符号全灭的形式化
> 定义，封板与验拆同一工具闭环）。前序：v2←F/S，v3←R，v4←M，v5←N+批次 0，v6←P。
> **七轮审计收敛：main/ext 双侧、代码/测试两侧、开工/完工两端全部纳入机械对账。**
> 原目标（`[Unreleased]` 内完成、1.5.5 切版前落地）已达成。
> 结论先行：**拆除 ioc↔cloud 的总线桥**（`EventSink` 扇出模型），事件按平面归位：
> 本地事实归 `EventBus`（ioc），云生广播归 `CloudEventBus` 门面（cloud），持久有序流归
> Kafka（ext），跨 JVM 问话归 `RemoteCaller`/`RpcEndpoint`（已有，不动）。
> **外审修正要点**：cloud 事件文件是"改线不动 wire"而非"原样"（F1）；`Keyed` 随桥处决
> 而非迁存（F2）；hello 前缀单一事实源=门面订阅贡献（F3）；入站白名单=订阅表升为守则
> （F4）；混版本分层——**topic 帧在途兼容、CLASS 帧随桥亡即弃、路由要求同版本舰队**
> （F5/R1）；ext 与 1.5.5 同车为前置条件（F6）。

---

## 1. 问题陈述与裁决依据

### 1.1 执念在代码中的现形

本地 `EventBus.publish` 会扇出给全部已贡献的 `EventSink`（cloud mesh、ext kafka）。
事件是否跨 JVM，由「装载了哪个模块 + peer 订阅声明 + bridgeTopics 配置」决定，
**调用点一字不可见**。`CloudEventModule` 自己的注释写得最坦白：

> "a loaded module turns `EventBus.publish` into cross-node broadcast **with no bus calls**"

### 1.2 三条裁决依据

**(a) 自家判例已裁过一次——RPC 平面。** 2026-09-12 `358bbf42`（delete CallBus）：

> "A call is not a broadcast: in-process it is a method call through the binding table,
> across processes it is the explicit remote invocation … all of them hiding the one
> fact that matters — **where the service lives**."

同轮删除了 "the EventBus description of a three-channel message domain"。
`freeway-cloud-design.md` §7 把它固化为结构性排除项：

> "**透明的本地优先调用**（'本地没人接就悄悄上网'）……因概念膨胀且把'调用去哪儿'
> 藏进运行期而整体删除，**`freeway-ioc` 里也没有为它准备的桥接缝**。"

v3 的 `CallBridge`（让 CallBus 本地未命中自动跨进程）**定稿未实施即撤回**。
事件桥是这条演变线上**最后一个幸存的同型接缝**——它把"事实去哪儿"藏进运行期，
把 RPC 域已经处决的概念在广播域原样保留。

**(b) 现状违反自家 §7 两条**。§7 另有一条：

> "**注解路由**……导出、调用与**事件路由都是显式声明，不往业务类型上挂路由注解**"

而 `ioc/annotation/Topic.java` 的存在理由就是路由注解——其 javadoc 自证：
"Maps an event type to a cross-JVM topic name **for MQ bridging**"。
同理 `EventBus.Keyed`：让业务类型 implements 它，等于**领域类型依赖分区键/适配器知识**
——桥拆除后若把该类型迁存进 ext，就是把同一个错误从 `@Topic` 换个宿主重犯（外审 F2）。

**(c) 桥在代码里处处谈判例外——模型错误的自供状**。

| 证据 | 出处 |
|---|---|
| 包名字符串否决名单 | `KafkaEventSink.send`：`event.getClass().getName().startsWith("com.jujin.freeway.boot.")` → "inherently JVM-local, never send" |
| 全量序列化税 | `CloudEventSink.send`：先 `CloudEventEnvelope.translate`（JSON）**再**遍历 peer 问兴趣——连上后零兴趣的 publish 也照付 |
| 本地重构 = 线上断约 | mesh CLASS 通道 type = `event.getClass().getName()`（FQCN 上 wire） |
| 整套「一事件多副本」机器 | 总线铸造共享 id、`IdWindow` 入站去重、`EventBridgePolicy`、claim-入-延迟动作 —— **本轮 P0（回滚重投被当重复丢弃）正是这套机器自己产生的 bug** |
| 词汇殖民 | `Keyed`（分区键）、`@Topic`（线上名）、`publishInbound`（适配器门）、`sinkFailures`（混进本地 stats）全部住在 ioc |

### 1.3 执念的三项资产——不是废掉，是搬家

1. **Defer 提交耦合**（事务提交后才出站）→ **必须**长进 `CloudEventBus` 门面（§4 守则①）；
2. **CE 1.0 信封 / `EventTrace` / PeerHub / origin 自环 / 兴趣过滤** → 保留在 cloud，
   但注意它们是**改线不动 wire**（§3.2——这些文件 import 着将死类型，不是"原样"）；
3. **共享 id + 窗口去重** → **整体处决**（§4 守则②）——其存在理由（同一 publish 走
   N 个传输）在新模型里不存在。

---

## 2. 终态：四平面，各一职

```
                ┌─ 问一句话 ────────→  RemoteCaller / RpcEndpoint     （cloud.rpc，已有）
一次交互 ───────┼─ 本地广播事实 ──────→  EventBus                     （ioc.event，纯进程内）
                ├─ 跨节点广播事实 ────→  CloudEventBus 门面（新）      （cloud.event，显式）
                └─ 持久有序事件流 ────→  Kafka producer/subscriber    （ext mq-kafka，显式）
```

| 平面 | 消息语义 | 交付保证 | 进程边界 | 入口 |
|---|---|---|---|---|
| ioc EventBus | 本地事实（class/topic 双通道、defer、ordered、streams） | at-most-once，本地隔离 | **从不**（类型系统层面零传输词汇） | `bus.publish` |
| cloud `CloudEventBus` | 云生事实（CE 1.0，topic+payload，peer 兴趣过滤，trace） | at-most-once 易失织物 | 显式 | `@Inject CloudEventBus` |
| ext Kafka | 持久事件流（per-key 有序、DLQ、legacy header 读兼容） | at-least-once（幂等归业务键） | 显式 | `@Inject KafkaEvents` |
| cloud RPC | 请求-应答 | 调用语义 | 显式 | `RemoteCaller`（不动） |

`CloudEventBus` 的 javadoc **首句必须写死**（防"复合门面"误读，名字本身最容易被读成
"publish = 本地+出站"）：

> "Publish leaves the JVM and nothing else — the facade owns its subscription table,
> local facts still travel on the in-process `EventBus`; remote facts are remote facts."

**回流（远端→本地）是 app 的显式选择，不是框架默认**——且门面订阅**只有申报形，
没有运行时 `subscribe`**：hello 前缀与入站闸门共享同一张组合期订阅表（Q8 的单一事实源），
运行时订阅无法回头更新 peer 侧的发送过滤，只会造出"收了 handler 收不到帧"的半死面。
镜像因此写在模块的 bind 里（与 point-bbs 批次 C 的贡献工厂形同款）：

```java
binder.contribute(CloudEventSubscription.class)
      .add("mirror-to-local", c -> {
          EventBus bus = c.get(EventBus.class);
          return CloudEventSubscription.of("user.created", UserCreated.class,
              e -> bus.publish(e));   // 回流：显式一行，写在 app 里
      });
```

**「远端传来的是远端事实，不是刚发生的本地事实」**——这正是拆桥要换来的那一句话。

### 2.1 定址随边界：本地平面**保留** class 通道（回答"要不要也只剩 topic"）

拆桥的刀落在**边界**上，不在**定址风格**上。判据一句话：

> **跨进程边界的消息，必须用进程无关的名字（字符串）定址；不跨边界的，让类型自己说话。**

按此，本地 `EventBus` 保留 class+topic 双通道，云生/kafka 门面只 topic+payload：

- **编译期契约 vs 字符串注册表**：class 通道 `Consumer<CommentCreatedEvent>` 静态定型，
  改名/拼错由编译器当场拦下；topic 拼错 `"comment.creataed"` = 静默 DeadEvent。
  本地砍 class 通道等于**主动放弃 JVM 内的编译期安全**，换不来任何东西。
- **层级分发是 class 独有能力**：订阅父类型收全族（`EventSubscriptionIndex` 的
  supertype-hierarchy 匹配）。实证：point-bbs 的 `PointDomainEvent` 是 sealed interface、
  9 事件实现之、带 `timestamp()` 契约——topic-only 把这份类型资产逼到发布边界作废。
- **事实自描述，命名是多余仪式**：CallBus 判例原文 "A call is not a broadcast"——
  对称地，广播也不必装成带名字的调用。topic 通道的存在理由是类型做不到的事：
  null payload 信号语义、无共享类型依赖的协作、flow audit 式自由串
  （`publish("audit", "→ " + node.id())`）。两通道互补，不冗余。
- **用量数据**：point-bbs class 通道 10:0；core 生命周期（`AppStoppingEvent`）、SSE 桥
  全走 class。砍掉 = 本地生态全额迁移、零收益。
- **"跨平面统一命名"的诱惑已随桥死亡**：拆桥后镜像是显式一行，名字对称买不到东西。

---

## 3. 类型归位表

### 3.1 `freeway-ioc`

| 现类型 | 判决 | 去向 / 理由 |
|---|---|---|
| `EventBus` | **留（瘦身）** | 删 `bridge` 字段、`publishInbound` 实现、`Keyed`；javadoc 删 bridge/inbound/跨 JVM 段落；`Stoppable` 保留（"withheld from the outbound EventSink" 条款随桥消失） |
| `EventDispatcher` | **留（瘦身）** | 删 `fanOut` 调用与 `TOPIC_OF` 缓存（@Topic 解析的唯一消费者） |
| `EventSink` | **删** | 桥的接缝本体；cloud 出站改门面私有方法 |
| `EventBridge` | **删** | 扇出循环 + IdWindow + 共享 id 机器整体退役 |
| `EventBridgePolicy` | **删** | 去重对象（多副本同事件）已不存在（守则②） |
| `EventBusInbound` | **删** | 唯一门退役：入站不再进本地总线 |
| `EventStats` / `EventBusStats` | **留（收窄）** | 删 `sinkFailures` 组件——**record 形状破坏**：仓内除 ioc 测试外零消费（已核实），外部读者改 pattern，迁移表点名 |
| `EventSubscriptionIndex` / `EventSubscriber` / `Subscription` / `EventStreams` / `EventExecutorSupport` / `DeadEvent` | **留（原样）** | 纯本地词汇 |
| `annotation/Topic` | **删** | 路由注解违反 §7；云生 topic 在门面调用点，kafka topic 在 producer 调用点。残留引用（`PeerConnection:73`、envelope/`EventSink` javadoc、DEVELOPER-GUIDE）列入批次 3 清扫 |
| `EventBus.Keyed` | **删（溶解，外审 F2 改判）** | 原判"迁 ext"错误：类型活着 = 领域 implements mq 适配器接口，与 `@Topic` 同型。分区键语义以 `KafkaEvents.send(topic, payload, key)` 调用点参数回归。附带效果写明：新发送方不再置 subject（`parse:144` 对缺 subject 容忍已核——在途旧帧仍可读，wire 兼容不破坏） |

### 3.2 `freeway-cloud/event` —— 改线不动 wire（外审 F1 改判）

> "留（原样）"是错的：`CloudEventEnvelope`/`PeerHub`/`CloudEventInterceptor` 都
> **引用着**将死类型（P3 措辞：Envelope 真 `import EventSink`、PeerHub import+FQCN 双形，
> Interceptor 仅 javadoc `:12`——同表内处置行已各按实情写明）。wire 格式确实
> 一字不动，但**线必须重接**。`fwchannel` 是 wire 扩展字段——`Channel` 枚举必须
> **cloud 自持**（收编进 envelope 作内部枚举），唯一职责 = 解析在途旧帧 + 出站标注；
> 否则旧帧解析断，"跨版本互通"卖点不成立。

| 现类型 | 判决 | 说明 |
|---|---|---|
| `CloudEventBus`（**新**，名字已被 mesh 日志自用："CloudEventBus wired: …"） | 新增 | 门面：`publish(topic, payload)`（Defer 感知，守则①）+ 订阅贡献 + 自有订阅表入站投递。javadoc 首句防复合误读（§2） |
| `CloudEventEnvelope` | **改** | `EventSink.Channel` → cloud 内部 `Channel`；删 `EventBus.Keyed` 读取（subject 由门面 `publish(topic, payload, subject?)` 的可选形参显式置——Q2 已拍，不再由事件类型隐式派生）；translate/parse/Parsed 签名随换 |
| `CloudEventSink` | **改造** | 摘 `implements EventSink` → 门面出站组件；**translate-then-filter 倒转为 filter-then-translate**（消灭零兴趣序列化税）；Stoppable 条款删 |
| `CloudEventSubscription`（**新**，M6 补行） | 新增 | 订阅申报值类型：`(prefix, Class<T>, handler-factory)`——**同一张表兼任三重身份**：入站闸门（守则④的"已声明类型"就是它）、hello 前缀派生源（Q8）、本地投递路由。这是门面申报-only 设计的承重件 |
| `PeerHub` | **改** | 入站 CLASS 分支删——**CLASS 帧到新节点无条件丢弃**（wire 上 CLASS 帧只带 FQCN 作 type、无 topic 兜底可路由，见 Q9），丢弃**计数 + debug 日志留观测**（rollout 期排查"为什么收不到"，M5）；TOPIC 投递不再 `publishInbound`，改投门面订阅表；`warnWhenInboundIsUngated` **重构而非整删**（M3）：type/topic 两条 warn 分支随键退役，**mesh token 缺失告警必须存活**（token 准入与白名单正交），方法开头的 `subscriptions.isEmpty()` 早退随键重排；**hello 前缀 = 门面订阅贡献派生**（外审 F3，见 §6-Q4/Q8） |
| `CloudEventInterceptor` | **改** | javadoc 的 `publishInbound(..., eventId)` 路径表述改为门面投递 |
| `CloudEventModule` | **改** | 删 `contribute(EventSink)`、`contribute(EventBridgePolicy)` 两条总线贡献；bind 出 `CloudEventBus` 单例；WS route、hook、TLS 原样；`subscriptions` 配置键删（= 订阅贡献派生） |
| `CloudEventLifecycleHook` | **改（瘦身）**（v4 判"留"是 N1 误判，本轮纠正——它是 Q8 的落地文件） | 删 `DEDUP_CAPACITY:35`/`DEDUP_ENABLED:43` 两个 SymbolSpec（policy 贡献死后成**无引用死常量——编译器不报、常规验收抓不到**，靠批次 0 对账）；`:49` 的 `EVENT_SUBSCRIPTIONS` 读取改为**接收门面订阅表的派生前缀**（hello 申报路径保留，来源换轴）；`:25-28`/`:183` javadoc"sealed contributions/needs no detach"随两条贡献死亡改述。TOKEN、EVENT_ENABLED、超时/退避族**不动**——键活着 |
| `CloudConfigKeys` | **改（瘦身）** | 删五常量：`EVENT_DEDUP_ENABLED:87`/`EVENT_DEDUP_CAPACITY:243(+DEFAULT)`/`EVENT_SUBSCRIPTIONS:99`/`EVENT_ALLOWED_TYPES:102`/`EVENT_ALLOWED_TOPICS:104`；类级 javadoc 的 dedup 姿态段（`:23-32`）随删。**键退役是代码+文档双侧动作**（文档侧见批次 3）；`peers`/`token`/`enabled`/超时族/`EVENT_PATH_DEFAULT` 存活 |
| `PeerConnection` / `PeerConnector` / `TextMessageAssembler` / `EventOrigin` / `EventTrace` | **留** | 网络机器原封（PeerConnection:73 javadoc 清扫除外）。**wire（CE frames、`fwchannel`/`fworigin` 扩展、trace 头）一字不动** |

### 3.3 `freeway-ext/freeway-mq-kafka`

| 现类型 | 判决 | 说明 |
|---|---|---|
| `KafkaEventSink` | **改造→`KafkaEvents`**（producer 门面） | 桥形（全量扇出→bridgeTopics.getFirst 覆写）废除；显式 `send(topic, payload[, key])`——key 即调用点参数（`Keyed` 类型不迁存，§3.1）；包名否决名单**整个删除**（本地事件不再出现在这个入口）；DLQ、限时关停、ce- headers 原样 |
| `KafkaSubscriber` | **改造** | 入站投递 kafka 自己的订阅表（`KafkaEvents.subscribe(topicPrefix, type, handler)` 形），不再 `publishInbound`；需要本地反应 → app 显式镜像一行。**与 cloud 门面订阅形不对称是有意的（M4）**：cloud 必须申报式，因 hello 前缀需要在组合期把兴趣捎给 peer（运行时注册改不了对端过滤）；kafka 的 poll 集本来就是 broker 侧 consumer-group 订阅 + 配置 `topics`，handler 是本地路由细节，运行时登记不破坏任何对端知识——勿当矛盾去"统一" |
| `KafkaModule` | **改造** | 删 `contribute(EventSink)`；bind 出 producer/subscriber |
| `KafkaHeaders` | **改（瘦身）**（v6 判"留（原样）"是第四处 F1 同型误判——ext 侧批次 0 实跑抓出） | 去 `EventSink` 引用：`:3` import、`:107` `channelToken(EventSink.Channel)`、`:115-120` `classChannel` 改字符串比对或 kafka 内部常量（`ce-channel`/`X-Event-Channel` 的**线格式与读兼容语义不变**）；`:26` javadoc 的 subject 来源由 `Keyed` 改述为 `send` 的 key 形参。`LEGACY_*` 族读兼容原样（wire 不破坏） |

---

## 4. 四守则（缺一必复发）

① **云生门面的 `publish` 必须 Defer 耦合**——事务内缓冲、提交后出站、回滚即弃。
缺这条的代价方向是**"幻影出境"**：未提交的事实已经上线——多发比丢失更难赎（fabric
不可撤回），故契约测试成对钉死："事务内 publish → 回滚 → peer 无帧"与"提交 → peer
有帧"。（史证：Defer 耦合做错的代价有真实先例——本轮 P0 正是它的入站方向变体，
其机器虽随守则②处决，教训不随之。）

② **跨传输身份/耦合概念整体处决，不留尸**：共享 eventId、窗口去重、policy、claim、
**`Keyed`、`@Topic`** 一个都不许以"实用工具"名义回归。kafka at-least-once 的幂等按
既有 javadoc 归业务键；mesh at-most-once 本无重投。"以后觉得少了什么"是预期戒断反应。

③ **入站无自动镜像**。远端事件只进门面/kafka 自有订阅表；镜像是 app 显式一行。

④ **入站白名单 = 订阅表（外审 F4 新增，守则级安全条款）**：门面/kafka 入站 payload
只反序列化进**订阅声明的类型**（`Class<T>` 形参），未声明的 type/topic 一律丢弃且
**不触发反射类加载**。现状 TOPIC 通道 accept-any（`PeerHub.warnWhenInboundIsUngated`
为此专设启动 WARN）——本守则落地后 `allowed-types`、`allowed-topics` 两键才有资格
退役：订阅表即白名单，比配置键更紧（声明 handler 才能收，无"准入了没人接"的悬空面）。

---

## 5. 实施批次（一刀切齐，无过渡兼容）

**批次 0（开工前置，机械对账定稿封板）**——归位表的封板工序，人眼已漏三轮（v1 四件、v4 hook、
v5 测试手列漏半），自此用命令代替目检。**跑两遍（P1）**——main 版封归位表，test 版产出
测试处置全集（v5 的 `grep -v Test` 恰恰复刻了它要消灭的人肉枚举病）：

```
pat='EventSink|EventBusInbound|publishInbound|EventBridgePolicy|DEDUP_|EVENT_SUBSCRIPTIONS|EVENT_ALLOWED|@Topic|annotation\.Topic|EventBus\.Keyed'
# main 版（归位表封板）
grep -rnE "$pat" --include='*.java' freeway-ioc/src/main freeway-cloud/src/main demo
# test 版（批次 1/2 测试处置全集，取代手抄枚举）
grep -rlE "$pat" --include='*.java' freeway-ioc/src/test freeway-cloud/src/test demo
```

（模式含 `annotation\.Topic`：`CloudEventBusTest:32` 的 FQ 注解形 `@com.jujin…annotation.Topic`
会漏过裸 `@Topic`——预跑当场实证了这种脆弱性，P3 括注转正为必需。）

**§3.3（ext）的封板不在本仓可达路径内（E1）**——批次 2 开工前在 `freeway-ext` 仓跑同一
`pat` 两遍（main `-rn` / test `-rl`，对 `freeway-mq-kafka/src`），§3.3 判决自此生效：

> （2026-09-24 已在 ext@a67f3fa 实测预跑：main 版 4 文件全有处置——`KafkaEventSink`/
> `KafkaModule`/`KafkaSubscriber` 判"改造"覆盖，`KafkaHeaders` 原判"原样"被 `:3` 真
> import 推翻、已改判（见 §3.3）；test 版 5 文件——`KafkaEventSinkTest`/
> `KafkaEventSinkIntegrationTest` 重写 producer 形、`KafkaModuleContainerTest` 门面形、
> `CrossJvmRole`/`CrossJvmEventTest` 双端门面重写，**`KafkaSubscriberTest` 不在命中内**
> ——不碰死词汇，以编译通过为准保留。这是三次复发病的第四处同形案：
> 判决出本仓视野，"原样"就没人对账。）

**封板判据（P2 修正措辞）**：每个持有者必须有**明确处置**（删 / 改 / 留但注明瘦身或清扫），
**不得落进无任何注明的"留（原样）"行**——原句"必须落在删/改行"对 EventBus、EventDispatcher
（留-瘦身）与 PeerConnection（留+清扫注记）字面误报，已纠。

（2026-09-24 复跑实测：main 版 17 文件全数有处置、封板为真；test 版 10 文件 = v5 已列 5
+ 新出 5——手抄清单漏掉的正是 `CloudEventBusTest`、`EventBusOrderedDeferredTest` 与三个
`EVENT_SUBSCRIPTIONS` 夹具文件。两版对账自此是批次 1/2 测试处置的**权威来源**。）

AGENTS 判例支持此刀：1.5.5 自身已含结构性删除（`WebServerBuilder`、`HttpModuleConfig`、
`DatabaseBuilder`、CallBus）。**且必须在 1.5.5 切版前。**
**前置条件（外审 F6）：ext 与 core 同车发布**——批次 1 删 `EventSink` 当场断 ext 编译
（`KafkaEventSink implements EventSink`、`KafkaModule` 贡献它、`KafkaSubscriber` 调
`publishInbound`），1.5.5 对外发布必须带 ext 兼容版，否则用户升 core 即坏 ext。

| 批 | 仓库 | 内容 | 验收 |
|---|---|---|---|
| 1 | core | cloud 门面 + mesh 入站改道 + ioc 桥拆除（§3 全部删除/改造项，含 hook 与 `CloudConfigKeys` 两个瘦身件——批次 0 对账抓出的最后两处）**+ `demo/cloud-event-mesh` 按新门面改写（M1）**——它不在根 reactor（`pom.xml` modules 仅七核心模块），批次验收永远不会编译它，而它是全仓唯一的桥词汇真实用户（`Events.java:14 @Topic`、`:15 EventBus.Keyed`、NodeA class-channel publish）；重写后恰好成为"门面 + 镜像一行"的活演示 | `mvn clean test` 全绿；**demo 单列验收：`mvn -f demo/cloud-event-mesh/pom.xml package`**；**拆除门（E2）：批次 0 两版对账在本仓复跑，输出必须为零**——桥符号全灭的形式化定义（死词汇只准活在 CHANGELOG，代码注释不留尸——与守则②同构，封板用它、验拆也用它）。测试迁移（P1：**处置全集以批次 0 test 版输出为准**，10 文件逐一）：ioc——`EventBusSinkTest` 桥专属例随桥亡（`closedBusRejectsPublish` 迁本地总线测试；`stoppedEventIsNotSentToSink` 仅出网半死，本地短路半已独立钉在 `EventBusDispatchTest.stoppableEventShortCircuits`，无需工序）；`EventBusInboundDedupTest` 删；`EventBusStatsTest` 去 sinkFailures；`EventBusOrderedDeferredTest:266/:312` 两用例**换本地 fixture 贡献类型**（applied-flag 命题无罪，死的是 EventSink 词汇——勿整例陪葬）。cloud——`CloudEventBusTest` **主改写对象**（三毒齐全：`:13` import、`:32-33` FQ-@Topic+Keyed、`:51-70` 死键夹具，重写为门面形即成守则①④契约测试的载体）；`PeerHubInboundGateTest`/`EventTraceTest` 改门面形；`CloudEventMeshActivationTest`/`PeerHubHandshakeStateTest`/`PeerConnectorReconnectTest` 三夹具 `EVENT_SUBSCRIPTIONS` set/clear 改**订阅贡献申报形**（键死、hello 换源）。新增门面测试：Defer 一对（守则①）、订阅表闸门（守则④，钉 `Class.forName` 不发生）、**CLASS 帧丢弃观测（M5：计数/日志可断言）**、hello 派生、filter-then-translate、自环、trace |
| 2 | ext | kafka 门面化（§3.3），测试重写（处置全集=批次 0 **ext test 版**输出，E1）：`KafkaEventSinkTest`/`KafkaEventSinkIntegrationTest`→producer 形、`KafkaModuleContainerTest`→门面形、`CrossJvmRole`/`CrossJvmEventTest`→双端门面形；`KafkaSubscriberTest` 无死词汇命中、保留；`KafkaHeaders` 按 §3.3 改判瘦身 | `mvn install` 核心后 ext 全绿；**拆除门（E2）：ext 仓批次 0 两版对账复跑，输出为零** |
| 3 | docs | `DEVELOPER-GUIDE`：564-567 表、627、1686-1712（kafka 章）、1729-1748、1778、1936-2030（mesh 章）重写为四平面；迁移表**点名两行**（外审 S1/S3）：①框架生命周期事件（`AppStoppingEvent`、`HttpModule:186`/`CloudStorageModule:44` SSE 桥）默认不再出网，需要即显式镜像；②`EventBusStats` record 形状破坏；键表**代码+文档双侧退役**（N1 连带）：代码侧删 `CloudConfigKeys` 五常量（见 §3.2 行），文档侧 `freeway-config.md` 删 `subscriptions`/`allowed-types`/`allowed-topics`/`dedup.*` 四条（**`event.token`/`peers`/`enabled`/超时族不在列，必须存活**——M3）；`@Topic`/`Keyed` 残留引用全扫（外审 S4，**含 demo `README.md`/`run.sh` 随新门面形同步**——M1 尾巴）；`freeway-cloud-design.md` 的任务是**全文改写不是追加一行**（N2）：§7 判例表追加广播行 + 正文现在时叙述将死模型的四处按四平面重写（`:356` "网格只是它的一个 EventSink 与入站漏斗"立场段、`:390-395` 出站管道图、`:410` "唯一漏斗"论、`:661` 分层表"Kafka 事件桥 → EventSink"行），**带日期的历史时间线条目（`:694+`）不动**；CHANGELOG Migration 新行（§98/99 两行随实施作废改写）；混版本声明（§6-Q9）进 guide | 文档-代码零漂移 |
| 4 | 回归 | point-bbs | **预期零改动**（`@Topic`/`Keyed`/`EventSink`/`publishInbound` 全仓零使用已核）；21 测试全绿即证本地平面未受连累；批次 4 的编译就是 @Topic/Keyed 零使用的终审 |

---

## 6. 拍板记录（原开放问题，外审后定案）

| # | 问题 | 决定 | 依据 |
|---|---|---|---|
| Q1 | 门面命名 | **`CloudEventBus`** | 与 `EventBus` 对称、"Bus" 词在 RPC 域已腾空；**且 cloud 日志已自用此名**（`PeerHub:94 "CloudEventBus wired"`）——名从主便。javadoc 首句写死防复合误读（§2） |
| Q2 | 门面 publish 形状 | **只 topic+payload**（subject 可选形参） | class 上 wire 正是被裁决的病灶；本地 class 通道的安全收益已由 §2.1 在正确平面保住 |
| Q3 | `allowed-types`/`allowed-topics` | **删** | 前提 = 守则④（订阅表即白名单）；`Class<T>` 订阅形参从"即可"升为**必须**（入站反序列化的唯一入口，F4/F5 的共同地基） |
| Q4 | 门面订阅申报形 | **`contribute(CloudEventSubscription.class)`，申报-only（门面不开运行时 subscribe）** | 与框架"贡献=申报"一致；且 hello 前缀与入站闸门的单一事实源要求运行时不可变（Q8）——开了运行时口子就自毁单一源。kafka 侧保留运行时订阅，不对称理由见 §3.3 M4 注 |
| Q5 | stats 归属 | 各平面各账 | 跨平面合并视图无可信语义（三套交付保证加权平均是伪指标） |
| Q6 | point-bbs 零改动 | **基本成立，措辞修正** | "一个字节不用改"→"除 `EventBusStats` record 形状外不用改"（S3）；point 现读 stats 与否批次 4 编译即证 |
| Q7 | `Keyed` 归属（外审） | **删（溶解为 `send(…, key)` 调用点参数）** | 迁存 = 领域→适配器耦合，与 `@Topic` 同型错误（F2）；§3.1/§3.3 本条已统一 |
| Q8 | hello 前缀来源（外审 F3） | **门面订阅贡献派生；`subscriptions` 配置键删** | 否则双头漂移（声明 handler 无线准入 / 准入了没 handler）。单一事实源，与 Q4 连体。`freeway.cloud.event.subscriptions` 退役 |
| Q9 | 混版本策略（外审 F5，复评 R1 定死） | **分层：帧解析层 topic 帧在途兼容；CLASS 帧随旧桥亡、到新节点即弃；路由要求同版本舰队** | CLASS 帧在 wire 上只带 FQCN 作 type、无 topic 兜底（`CloudEventEnvelope` javadoc 自证："the derived topic is not used there"），新平面路由表已无 CLASS 概念——条件式"无人声明即弃"不成立，定死为**无条件丢弃**。"在途不丢"的承诺范围仅限 **topic 帧**（在途照读照投）与 **kafka 面**（DLQ 积压与 legacy header 各自读兼容，批次 2 独立成立）。此结论写死进批次 3 guide：fabric 路由升级按舰队同步 |

---

## 7. 风险清单

| 风险 | 等级 | 缓解 |
|---|---|---|
| 现有 fabric 用户"本地 subscribe 收远端事件"失效 | **中——且这就是目的本身** | 镜像一行入迁移文档示例；`CrossJvm*` 测试演示新写法 |
| 新门面忘接 Defer → 重演回滚外泄 | 中 | 守则① + 批次 1 契约测试（成对钉死） |
| 有人日后把共享 id/去重/Keyed 缝回来 | 中 | 守则②写进 CHANGELOG 行与 §7 判例表："多副本同一性"是桥遗骸，非缺失特性 |
| 混版本路由静默丢事件（rolling upgrade 期） | 中 | Q9 声明写死进 guide：同版本舰队；在途帧兼容不覆盖跨版本路由 |
| ext 发布不同车 → 用户升 core 坏 ext | 高→中 | §5 前置条件：1.5.5 与 ext 兼容版同批发版 |
| 入站闸门误配（守则④实施走样，重蹈 TOPIC accept-any） | 中 | 批次 1 测试：未声明类型入站**不触发反射加载**（钉 `Class.forName` 不发生） |
| 测试重写量被低估 | 中 | 批次 1/2 独立成轮，各自全绿再进下一轮 |

---

## 8. 一句话总结

**RPC 域在两周前已经把"悄悄跨进程"关进了笼子；本方案把同一道闸门装回广播域**：
`EventBus` 回到它名字的含义——**进程内**的事件总线；跨 JVM 的事实，要么点名找
`RemoteCaller`（问话），要么点名登 `CloudEventBus`（云生广播），要么点名进 Kafka
（持久流）。问话有门牌，广播有门牌，本地有本地——上帝的归上帝，凯撒的归凯撒。
