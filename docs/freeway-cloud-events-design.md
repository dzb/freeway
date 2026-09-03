# CloudEventBus 设计（freeway-cloud 事件网格）

> 状态：**已实现（E1–E3 完成，见文末状态注）** — 协议与组件形态均已
> 落地；与早期草案的偏差以正文与修订记录为准。
> 目标：装载 `freeway-cloud` 后，`EventBus` 获得"跨 JVM 事件通道"：
> A 节点 publish 的事件，以 CloudEvents 1.0 格式实时推送到所有订阅的
> 对端节点并触发本地订阅者。
> 前置阅读：`EventBus` javadoc（消息域三通道）、
> `freeway-cloud-unified-design.md`（§5.3 discovery/registry）。
> 关联：`freeway-remote-callbus-design.md`（question 通道的跨进程形态，
> 本文是 fact 通道的对应物）。

## 0. 定位与原则

1. **本地派发不变**：`publish` → 本地订阅者仍是对象引用直递、零序列化。
   跨 JVM 是 publish 的**附加效果**（出栈），不是它的前置路径。
2. **通道只有 WebSocket 一种**（v1）。理由：
   - 节点是常驻服务 → 长连接是天然形态；
   - 连接即订阅、连接即寻址 → 不需要 webhook 的订阅注册 API、
     不需要投递队列、不需要 URL 管理；
   - freeway-http 的 WS 面（endpoint/group/session/subprotocol）现成；
   - 双向全双工：一条连接承载 A→B 与 B→A 两个方向的事件流，
     mesh 不需要双方各存对方的地址。
3. **投递语义诚实化**：at-most-once。对端离线窗口内的事件不补投——
   要 at-least-once 用 ext 的 Kafka 桥（broker 持久化）。两个通道并存
   不迁移：**cloud-WS = 轻量实时面；Kafka = 持久可靠面**；共享同一套
   CE 信封翻译器。
4. **CloudEvents 1.0 作为线上格式**：`type`/`source`/`id`/`subject`/
   `time`/`data` + 扩展属性。非 Freeway 的消费者（Knative、EventGrid、
   任何 CE 客户端）可以直接解读消息。
5. **与节点注册能力结合**（connection-as-fact, registry-as-projection，
   见 §3）：`@Local` 内置 RegistryStore 支撑零外部依赖起步；Nacos 等
   ext 后端提供规模化发现。两种后端 CloudEventBus 都吃。

## 1. 消息域全景（本设计后）

```
fact 通道:
  同 JVM   → publish → 本地订阅者（对象直递, Stoppable 短路有效）
  跨 JVM   → CloudEventSink(WS) → 对端 /cloud/events → publishInbound
           → KafkaEventSink（ext, 并存）→ broker → 订阅者
question 通道:
  同 JVM   → CallBus → 本地槽位
  跨 JVM   → RemoteCaller → /rpc/*（RemoteProxyFactory.localFirst）
stream:
  本地     → EventBus.stream → Flow.Publisher（SSE 泵）
```

## 2. 线上协议

### 2.1 连接与握手

- 对端地址：`ws(s)://{host}:{port}/cloud/events`（复用 HTTP 端口与
  TLS 配置；`REGISTRY_SERVICE_SCHEME` 推导 ws/wss）。
- 握手即 HTTP upgrade——既有 WS 面（subprotocol `freeway.events.v1`）
  完成协议协商，无需新机制。
- **首帧（hello，连接发起方发送）**：
  ```json
  { "proto": 1,
    "origin": "freeway-app@127.0.0.1:39535",
    "serviceId": "order-service",
    "subscribe": ["order.", "user.created"] }
  ```
  - `origin`：全局唯一节点身份（复用 `REGISTRY_SERVICE_INSTANCE_ID`
    缺省生成规则）。mesh 全互联去重（双方同时发起时，`origin` 字典序
    大的一方主动关闭自己发起的那条——**保留对端发起的连接**，其
    hello 已携带对端订阅）。
  - `subscribe`：订阅声明数组，元素为
    `{ "prefix": "order.", "group": "order-workers" }`（点分段前缀，
    如 `order.` 匹配 `order.created` / `order.paid`）。空数组 =
    不收事件（单向发布者）。
    **`group` 声明投递拓扑**（吸收 solon EventLevel+group 的语义）：
    - 无 `group` = **广播**：每个收到消息的节点都触发本地订阅者
      （刷缓存、配置刷新类场景）；
    - 有 `group` = **竞争**：相同 group 的节点间每条消息只由一个处理
      （订单处理、发邮件类场景）。v1 中竞争语义由 Kafka 桥兑现
      （group 直映射 consumerGroup）；WS mesh 通道上竞争消费需要
      协调机制，记为待定协议扩展——WS 订阅声明现在就携带 group，
      协议设计期留下最便宜的落位。
  - 对端 ack（服务方回）：
    `{ "proto": 1, "origin": "...", "accept": true, "subscribe": [...] }`
    （ack 携带服务方自己的 hello）。拒绝不发 accept:false 帧——直接以
    WS 关闭码表达：`1008`（token 校验失败/未授权）、`1002`（协议错误：
    origin 缺失或未知帧），随后关闭。重复连接按 origin 字典序决断关闭。

### 2.2 事件帧（CE 1.0 JSON 格式，Json content mode）

```json
{ "specversion": "1.0",
  "id": "…",                 ← 事件身份：总线每次分发铸一个、交给所有 bridge；
                               跨传输去重依据（可选启用，见下）
  "source": "freeway://order-service",
  "type": "com.acme.OrderCreated",   ← 入站反序列化路由键（= 事件类名）
  "subject": "order-42",             ← EventBus.Keyed.key()（分区键）
  "time": "2026-08-27T12:00:00Z",
  "datacontenttype": "application/json",
  "fwchannel": "class",              ← 扩展属性：CLASS|TOPIC 回灌路由
  "fworigin": "freeway-app@…",       ← 扩展属性：出站节点身份（回环防护）
  "data": { …事件对象 JSON… } }
```

- `fwchannel` / `fworigin` 为 Freeway 扩展属性，不注册到 CNCF registry
  （内部概念）。（`fwtimes` 投递代数曾在此列，2026-08-29 移除——见 §8
  修订记录。）
- `TOPIC` 通道：`type` 为字符串 topic 本身（`"user.created"`），
  `data` 为 payload；`CLASS` 通道：`type` 为类全名。
- 不满足 CE 约束（id/type/source 缺失）的事件出站即失败并记日志，
  不静默丢弃。

### 2.3 心跳与保活

v1 未实现应用层心跳（`freeway.cloud.events.keepalive` 键未实现）：
连接活性依赖 TCP/WS 层行为、发送失败检测（出站 send 失败即摘除连接）
与对端 close 即时感知。客户端侧有**握手看门狗**：socket 打开后 10s 内
未完成 hello/ack 即中止并走退避重连，半开连接不会悬挂。应用层心跳
列为待定扩展。

## 3. 节点发现与连接生命周期（connection-as-fact）

**原则：连接状态是事实，注册表是它的投影。注册永不反向驱动连接。**

```
启动序列（每节点对称）:
1. HTTP server 启动（承载 /cloud/events WS 端点）——已有
2. ServiceDeclaration → RegistryStore / 外部注册表（已有, 零改动）
3. PeerConnector: 解析 peers → 逐个发起 WS 连接（握手 + hello）
4. 服务侧接受连接 → PeerHub 连接表登记（key = hello.origin，
   ConcurrentHashMap 形状；不写 RegistryStore——早期草案的"虚拟实例"
   方案未采用，连接状态即事实本身）
5. 双向可用。对端关闭/握手失败 → unregister → "下线"

peers 解析（双源）:
  a. freeway.cloud.events.peers=host:port,…（静态配置，@Local 后端的引导输入；
     IPv6 字面量支持方括号与裸写两种形态）
  b. 有外部 registry 后端（Nacos…）时: 经 setPeers 动态喂入
     （外部后端自带的 push 能力由适配器接，core 不做 watch）
```

- 断线重连：指数退避（1s 起、30s 封顶），重连成功重走握手
  （订阅状态在连接里，天然重置）。
- 节点关闭：deregister（既有 hook）+ 主动 close 所有 WS（对端立即
  感知，不等心跳超时）。
- **不做**分布式 RegistryStore：每节点只维护"连到我"的连接视图，
  这正是 `RegistryStore` 的 ConcurrentHashMap 形状；全网视图是每个
  节点投影的并集，事件网格不需要一个全局一致的成员列表。

## 4. 出站与入站管道

### 4.1 出站（publish → 桥）

```
publish(event)
  ├─ 本地派发（不变, 对象直递）
  └─ 出站钩子（等价 EventSink.send 的位置, 见 §5）:
       POJO → CloudEventEnvelope.translate(event, channel, origin)
            → 遍历活跃连接: 按 hello.subscribe 前缀过滤 type
            → session.sendText(CE-JSON)
```

- 过滤在**发送方**执行（省带宽）；`fwchannel` 与 hello 匹配规则：
  CLASS 订阅者用 type 前缀匹配类名，TOPIC 订阅者精确/前缀匹配 topic。
- `Stoppable.isStopped()` 的事件**不出栈**（被本地订阅者否决的事实
  不广播——与现状一致）。
- Defer 事务缓冲照常：缓冲的是"出栈动作"，commit 后 drain 出栈，
  rollback 丢弃——语义与本地派发对称。

### 4.2 入站（对端 → publishInbound）

```
onText → CloudEventEnvelope.parse(json) → {type, channel, payload}
       → 拦截器链（全部放行才继续）
       → CLASS: 类型白名单放行后反序列化 → publishInboundWithId(event, id)
         TOPIC: topic 白名单放行 → publishInboundWithId(topic, payload, id)
```

- **allowed-types / allowed-topics 双白名单继续生效**，两道门独立、
  语义不同（见 §4.3）：`allowed-types` 为 **deny-by-default**（空列表 =
  CLASS 通道入站全部丢弃，永不回退到"放行任意类"）；`allowed-topics`
  空列表 = TOPIC 放行全部。wire 时对未收紧的配置打启动告警。
- **拦截器位**（吸收 solon `CloudEventInterceptor`）：入站管道经
  `contribute(CloudEventInterceptor.class)` 贡献的拦截器链——审计、
  租户检查、自定义过滤的统一挂点，所有通道共用。
- 跨传输幂等去重**不在拦截器**（拦截器只看得到 mesh 帧，会漏掉经
  broker 到达的同一事件）：落在 `publishInboundWithId` 这一所有传输
  共用的漏斗上，`EventBus.enableInboundDeduplication(capacity)` 显式
  开启（`events.dedup.enabled`，窗口容量 `events.dedup.capacity`，
  默认 4096），依据是总线铸造的共享事件 id。
- 回环防护：`fworigin == 本节点 origin` 的入站帧丢弃（自身经 mesh
  环回的事件；配合 publishInbound 的"不回桥"语义双保险）。

### 4.3 入站门禁：token 与双白名单（生产部署必读）

mesh 端点 `/cloud/events` 一开就有**三道独立的入站门**，各守一件事：

| 门 | 配置键 | 守住的东西 | 空值语义 |
|---|---|---|---|
| 对等认证 | `events.token` | 谁可以连进来（握手身份） | **空 = 不校验**（文档化默认，仅适用可信内网） |
| CLASS 反序列化 | `events.allowed-types` | 入站帧可以要求本节点实例化哪些类 | **空 = 全部拒绝**（deny-by-default） |
| TOPIC 注入 | `events.allowed-topics` | 对端可以往哪些 topic 投递 | 空 = 放行全部 |

**token 是"门锁"，白名单是"门后的第二道防线"**——两者不互相替代：
token 决定"谁能连上"，白名单决定"连上之后能要求本节点做什么"。

#### 多节点生产环境必须配置 token

`token` 为空时，任何能连到 `/cloud/events` 的对等方都被当作合法节点，
握手不做身份校验。这在**可信内网**是合理的默认（省去密钥分发成本，也
是双节点契约测试所覆盖的形态），但**多节点生产部署必须显式配置**。

token 是**全节点共享**的握手密钥，双向生效：本节点出站时在 hello 帧
携带 token（`PeerConnector`），入站时校验对端 hello 中的 token
（`PeerHub`，不匹配即以 WS 关闭码 `1008` 断开）。因此：

1. **所有节点必须配同一个值**——任一节点不一致，该连接被 `1008` 拒绝
   （日志：`Peer xxx failed the mesh token check — closing`）；
2. **经环境变量注入，不要写进配置文件**（与数据库口令、keystore 口令
   同一处理方式）：
   ```bash
   export FREEWAY_CLOUD_EVENTS_TOKEN='<随机长密钥>'
   ```
   properties / JSON 写法见 `docs/application-prod.*.sample`；
3. **比较是常量时间的**（`MessageDigest.isEqual`），token 无法被计时
   侧信道逐字节探测；
4. **轮换需要滚动重启**：token 在 `wire()` 时读入、连接握手时校验，
   不支持热更新——轮换期间新旧节点互相拒绝，需按批次滚动。

生产建议同时收紧 `allowed-topics`（TOPIC 通道空列表 = 放行任意 topic），
把入站面收窄到实际使用的前缀。

> **为什么不内置一个默认 token**：硬编码默认值等于公开密码——日志干净、
> 无告警，部署者以为"已启用认证"，实则钥匙是公开的。这比"空 = 明确地
> 无认证 + 启动告警"更危险，后者至少是显式、可审计的失败。同理也不做
> "启动时随机生成默认 token"：token 必须全节点共享，各自随机会让 mesh
> 直接断连。安全姿态的加强方向是"危险场景下让忘配这件事 fail-fast"，
> 而不是塞一个假秘密。

## 5. 与 EventSink SPI 的关系

**`CloudEventSink` 实现 `EventSink` SPI**（经
`EventBus.addEventSink` 安装，`EventBus` 核心零改动）：

- `EventSink` 是 broker 语义（send-and-forget 到持久通道），由 ext
  适配器实现；CE-WS 桥与 Kafka 桥**可以并存**——总线对每个 sink 都
  派发（fan-out），同一事件经两条通道到达同一节点时由共享事件 id +
  入站去重窗口收敛（`events.dedup.enabled`）。双通道并存属用户显式
  选择，框架支持。
- CE 翻译器（envelope）作为独立纯函数类放在 `cloud.events` 子包；
  将来 Kafka 桥想发 CE 格式，可直接复用翻译器（ext 可选依赖 cloud）。
- 出站派发在发布线程上同步执行（JDK WS `sendText` 为排队式非阻塞），
  at-most-once、best-effort：发送失败的连接被摘除，重连是 connector
  的职责。

## 6. 组件清单

| 组件 | 包 | 职责 |
|---|---|---|
| `CloudEventEnvelope` | cloud.events | CE 1.0 翻译器：translate/parse，属性映射表见 §2.2 |
| `PeerHub` | cloud.events | WS 端点（`WebSocketEndpoint`）：握手/hello/token 门禁/订阅声明/入站管道 + 连接表（按 origin 去重） |
| `PeerConnector` | cloud.events | peers 解析（config/discovery 双源）+ 连接生命周期 + 退避重连 + 握手看门狗 |
| `PeerConnection` | cloud.events | 一条活跃连接：对端身份、订阅前缀、发送器（close 恰好一次） |
| `CloudEventSink` | cloud.events | 出站钩子（实现 `EventSink`）：遍历活跃连接、前缀过滤、发送 |
| `CloudEventInterceptor` | cloud.events | 入站拦截器位（contribution）：审计/租户/自定义过滤 |
| `CloudEventModule` | cloud.events | 装配：endpoint route + connector hook + sink 安装 + 去重开关 |

配置键（`freeway.cloud.events.*`，全部已在 `CloudConfigKeys` 落地）：
`enabled`（默认 false）、`peers`、`subscriptions`（本节点出站订阅
声明）、`allowed-types` / `allowed-topics`（入站双白名单，语义见 §4.3）、
`token`（mesh 握手共享密钥，常量时间比较；多节点生产部署必配，
见 §4.3）、`dedup.enabled` /
`dedup.capacity`（默认 4096）。无 `keepalive` / `idempotency` 键
（§2.3、§4.2）。

## 7. 明确不做（v1）

- webhook/HTTP 出站通道：对第三方系统的集成由用户在应用层订阅后
  自行 POST（或 v2 再评估）；节点间投递不使用 HTTP（无连接语义）。
- at-least-once / 投递队列 / 死信：Kafka 桥的领地（诚实分层）。
- CE filter 表达式完整规范（CNCF subscription spec）：前缀匹配够用。
- 分布式 RegistryStore / 全局一致成员视图。
- 发布确认 / ACK 帧：at-most-once 语义下无意义；Kafka 桥有 offset。
- **显式事件事务**（solon `EventTran` 式）：Defer 已覆盖"随 DB 事务
  缓冲"的主场景；solon 的额外能力是"无 DB 事务的多事件原子批"，
  列为远期特性，不进 v1。
- **qos(0/1/2) 透传**：WS 通道无 ack 概念，at-most-once 是通道立场，
  不让事件携带框架兑现不了的字段（solon 的 qos 依赖底层协议原生
  支持，Kafka 适配器同样忽略它）。
- **scheduled 延迟投递**：依赖 MQ 原生延迟能力（rocketmq/ons 有、
  kafka 无），适配器差异过大，不作核心模型字段。
- **@CloudEvent 注解实体 / 强类型 eventplus 层**：以 contribution
  显式路由替代注解扫描（freeway compose-first 立场）；需求真实
  存在，若将来提供，形态为 `contribute(EventRoute.class).add(
  EventRoute.of(EventClass.class, "topic"))`。

## 8. 实施切分

| 阶段 | 内容 | 依赖 |
|---|---|---|
| A（本文档） | 协议与组件契约定稿 | 无 |
| E1 | `CloudEventEnvelope` 翻译器 + 属性映射单测 | cloud 1.3.10 |
| E2 | Endpoint + PeerConnector + Bridge + 双节点契约测试（真实 WS 往返、订阅过滤、断线重连、loop 防护） | E1 |
| E3 | 文档进 DEVELOPER-GUIDE + ext 验证（Nacos 后端场景可选） | E2 |

> **状态（2026-08-28）**：E1–E3 已实施——`CloudEventEnvelope` 翻译器、
> `PeerHub`（WS 端点 + 入站管道 + 拦截器位）、
> `PeerConnector`（双源 peers + 退避重连）、`CloudEventSink`（出站
> 钩子，订阅前缀过滤）全部落地；双节点真实 WS 契约测试覆盖双向
> 往返、前缀过滤、白名单、Keyed subject、非白名单丢弃与惰性模式。

> **修订记录（2026-08-27，吸收 solon-cloud-event 评审）**：hello 帧的
> subscribe 携带 `group` 声明投递拓扑（广播/竞争）；信封增加 `fwtimes`
> 投递代数；幂等去重降为内置拦截器；新增拦截器位；显式事件事务、
> qos、scheduled、注解实体层列入不做清单（附立场）。
> （2026-08-29 后续修订：上条中的 `fwtimes` 已移除——mesh 内不存在转发
> （入站走 `publishInbound`、不回桥），跨传输的两份副本代数同为 1，该字段
> 无法承担幂等判断；幂等改由总线铸造的共享事件 id 加 `EventBus` 入站去重
> 窗口承担，见 §2.2。同条的「幂等去重降为内置拦截器」亦已改为落在
> `publishInboundWithId` 这一所有传输共用的漏斗上——拦截器只看得到 mesh
> 帧，会漏掉经 broker 到达的同一事件。）
