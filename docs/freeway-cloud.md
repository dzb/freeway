# freeway-cloud 开发指南

本文是 `freeway-cloud` 的开发方法总览：一个应用如何从单机走向云原生，注册发现、远程调用、
事件网格分别怎么用、怎么部署、边界在哪。设计取舍见
[freeway-cloud-design.md](freeway-cloud-design.md)，全部配置键见
[freeway-config.md](freeway-config.md)。

核心承诺只有一句：**单机和云原生是同一套代码的两种放置方式**。业务代码不写注册、不写寻址、
不写重试；"有邻居"这件事由组合根（放置哪些模块、配哪些键）表达。

## 模块与组合

`CloudModule` 是云能力 bundle：用 `@SubModule` 声明 8 个标准模块——上下文传播、密钥、
**服务注册与发现**、远程调用、可观测、弹性、健康、对象存储。放置 bundle 即全部安装；
每个子模块也是普通模块，**要子集就直接放子集**，没有排除清单：

```java
FreewayApp.run(
    new OrderModule(),
    CloudModule.class);                                // 整个 bundle

FreewayApp.run(
    new OrderModule(),
    new CloudRpcModule());                             // 只要远程调用
```

`CloudEventModule` 刻意**不在** bundle 里：它会打开自己的网络监听并主动拨号，所以由应用
显式放置、显式进入（`@Inject CloudEventBus`、订阅贡献）。

应用接触这些模块的方式只有三层：

| 层 | 应用写什么 | 覆盖什么 |
|------|-----------------------------|--------------|
| **环境层** | 什么都不写，装模块即得 | 实例自动注册（心跳续约、停机注销）、`GET /health/live`、`/health/ready`、`/metrics`，每个出站调用自带指标/追踪/弹性/上下文传播 |
| **声明层** | 数据贡献：`binder.bind(Handlers.class)` + `contribute(RpcExport.class)`、`contribute(CloudEventSubscription.class)`（网格）、`contribute(CloudHealthContributor.class)` | 本服务对外提供什么 |
| **消费层** | `@Inject ServiceDiscovery` / `ObjectStorage` / `SecretStore` / `Metrics` / `Tracer`，及 `binder.bind(Api.class).to(...)` | 本服务从别人那里用什么 |

## 服务注册与发现

同一 seam（`ServiceRegistry` / `ServiceDiscovery`），三种后端，切换 = 换模块 + 换键，
业务代码零改动：

| 后端 | 放置 | 基础设施 |
|---|---|---|
| 进程内（默认） | `CloudModule`（或 `CloudDiscoveryModule`） | 无——单实例 / 固定拓扑 |
| **freeway 注册节点** | `CloudModule` + `RegistryFleetModule` | 自部署的注册节点（1~N 个 freeway 应用） |
| Consul | `CloudModule` + ext `ConsulModule` | Consul 集群 |

### 部署注册节点（freeway 原生）

节点就是一个普通 freeway 应用，装配即全部：

```java
public static void main(String[] args) {
    FreewayApp.run(args,
        new HttpModule(),              // HTTP 面：注册/心跳/发现四条路由
        new CloudDiscoveryModule(),    // 实例表（RegistryStore）
        new RegistryServerModule());   // 把实例表发布成 HTTP 面
}
```

```properties
freeway.cloud.server-port=8060
freeway.cloud.registry.token=s3cret      # 出了本机必设：读写都要这个头
```

**每个业务服务**（注册方 = 调用方 = 监听方）：

```java
FreewayApp.run(args,
    new HttpModule(),
    new CloudModule(),
    new RegistryFleetModule());    // 把节点舰队绑定为本进程的注册与发现
```

```properties
freeway.cloud.registry.nodes=http://n1:8060,http://n2:8060
freeway.cloud.registry.token=s3cret
freeway.cloud.registry.service-id=order-service
```

**自动发生的事**（业务代码零注册逻辑）：

| 时机 | 自动发生 |
|---|---|
| 启动 | 解析身份（service-id + host:port）→ POST 注册到**每个**节点；任一节点拒收 → 启动响亮失败 |
| 每 10 秒 | 心跳扇出；某节点 404（它重启过）→ 用本进程注册过的实例**就地重灌**该节点 |
| 每 `discovery-feed-ms` | 拉一次同服务实例列表，整份喂给事件网格——新实例自动拨通，离开的自动断开 |
| 调用别人 | `RemoteProxyFactory` 按发现结果 + 加权轮询选实例 |
| `GET /health/ready` | 报真实注册状态：已注册 / 排空中 / 连续心跳失败 |
| 停机 | 先从全部节点注销（drain 窗口）再关进程 |

**发现结果的复用**：`ServiceDiscovery` 的实现在内部缓存答案（Eureka/Ribbon/Spring Cloud LB
都是同一形状；K8s informer 这类推送式不需要 TTL）。默认
`freeway.cloud.registry.cache-ttl=5s`（半个心跳，远在 30s 驱逐窗口内），可按服务覆盖
（`…cache-ttl.checkout=1s`，`0` = 该服务不缓存）。后端失败时，窗口内继续用最旧的答案
（grace = max(10×ttl, 30s)），超出窗口才抛。

**政策**（框架提供的默认手段，不是强制）：走网络的发现实现用 `CachingServiceDiscovery.around(delegate, ttl, metrics)`
包一层——fleet 就是这么做的；进程内 store **不包**（查表本身已是 0 成本，缓存只会让它变慢）；
自带推送新鲜度的适配器（K8s informer）也不包，把 `TtlPolicy` 答成 `0`。换句话说：**缓存的发现实现是
框架给的默认能力，套不套由后端决定**。

**协议与凭证轮转**：注册面有协议版本——客户端每个请求带 `X-Registry-Proto`，不兼容时节点答
`426`（并附带支持的版本），节点永远接受更旧的协议；`GET /registry` 在无凭证时可读，自述协议、
路由与认证方式。凭证轮转不需要停节点：把当前 `token` 的值填进
`freeway.cloud.registry.token-previous`、新值写进 `token` 即可两个并存，然后滚动切换
写入方，最后清空旧值键——密钥按请求解析，配置热重载即生效。

**就绪门（readiness）**：注册发生在应用能服务之前，所以带 `CloudHealthContributor` 的进程会先以
"未就绪"注册（`Health.starting()`：在注册表里、不在发现结果里），等**所有**贡献者通过后才翻成
就绪——慢启动的连接池/迁移不再吃它处理不了的流量。没有贡献者的应用**注册即就绪**（保持旧行为：
不给不存在信号的应用加等待）。翻转是即时的（`POST …/instances/{id}/ready`），同时也随下一次心跳
携带，所以老节点只是延迟一拍，不会分叉。

**节点的运维语义**（v1 的明确选择）：无持久化——表是可重建状态，节点重启后表为空，每个实例
的下一次心跳收到 404 会**就地把自己重新注册到该节点**，一个心跳周期内恢复满员；无共识复制——
HA 靠客户端扇出（写全部节点、读并集），任一节点存活即整队可用，节点之间互不相识、没有主从。
容量按轮询模型估算：每实例每 10s 一次心跳 + 每 feed 间隔一次发现查询。

### Consul

ext 的 `freeway-cloud-consul`：同一 seam，`renew` 自愈、`drainWindow()`、`BackendTypeGuard`
告警全部由 core 驱动，适配器只实现传输。Nacos / K8s endpoints 按同一模式接。

## 远程调用

跨进程方法调用：提供方声明哪些 mapping 可被外部调用，消费方把类型化客户端绑到该 mapping。
分发走 `CloudHttpClient`，因此发现、负载均衡、重试、熔断、上下文传播全部生效。

**同一个接口服务两种形态**——业务代码永远 `@Inject UserApi`，实现是本地还是 HTTP 之后，
只是组合根的决定：

```java
// 单体：本地实现
binder.bind(UserApi.class).to(UserApiImpl.class);

// 拆分：类型化远程客户端
binder.bind(UserApi.class).to(container -> RemoteProxyFactory
    .of(container.get(RemoteCaller.class))
    .serviceId("user-service")      // 提供方的发现 id
    .mapping("user")                // 提供方导出的 call-topic 前缀
    .build(UserApi.class));
```

**提供方——声明导出**：

```java
binder.bind(UserHandlers.class);                                   // 容器管理
binder.contribute(RpcExport.class)
    .add(RpcExport.of("user", UserHandlers.class));               // 整个导出声明
```

导出是**声明**而非装配：框架从容器解析 handler，只服务一条通配路由
`POST /rpc/{mapping}/{method}`。未声明的 mapping 在进入任何 handler 之前就被拒绝；重复导出
同一 mapping、导出未 bind 的类型、handler 含重载方法——三类装配错误**启动即失败**，而不是
日后 404。

**消费方——直调或类型化客户端**：

```java
@Inject RemoteCaller caller;

// 1. 直调
Greeting g = caller.invoke("user-service", "user", "greet", List.of("bob"), Greeting.class);

// 2. 类型化客户端（组合根绑定一次）；这里也能给单次调用设预算
UserApi api = RemoteProxyFactory.of(caller)
    .serviceId("user-service").mapping("user")
    .timeout(Duration.ofSeconds(5))          // 端到端，含重试
    .build(UserApi.class);
```

**错误模型——两类失败两种直觉**：

| 失败 | 抛出 | 可重放？ |
|---------|-----------|---------|
| 连接拒绝 / 连接超时 | `CloudException`，`retryable()==true`、`outcomeUnknown()==false` | 是——请求没离开本进程 |
| 超时、中途 I/O、5xx | `CloudException`，`retryable()==true`、`outcomeUnknown()==true` | **仅幂等操作**——对端可能已生效 |
| 远端 handler 抛异常 | `CloudException`（4xx），cause 为 `RemoteInvocationException` | **否**——确定性失败 |

重试前要过两道门：失败类别 + 幂等性。`CloudHttpClient` 按动词推导幂等（GET/PUT/DELETE 等
重放，POST/PATCH 不），可用 `CloudRequest.idempotentWith(true)` 覆盖；远程调用走 POST，所以
在消费接口的方法或整个接口上标 `@Idempotent` 声明可安全重放。远端异常只按类名 + 消息重建，
不反射还原原始类型。跨进程调用**不在任何本地事务内**——提交后的副作用放 EventBus（Defer
缓冲），不放 RPC。

## 事件网格

云原生广播面：以 CloudEvents 1.0 帧在 WebSocket 网格上发布与接收——**显式**。进程内
`EventBus` 保持进程内；装载事件模块不改变任何本地发布行为。

配置（`freeway.cloud.event.*`）——presence 驱动的激活：

```properties
# 静态 peers：非空即激活网格——不需要 enabled
freeway.cloud.event.peers=10.0.0.11:8080,10.0.0.12:8080
# freeway.cloud.event.enabled=true            # discovery-fed mesh：无静态 peers
# freeway.cloud.event.discovery-feed-ms=10000 # 按间隔向已绑定的发现查同伴（整份替换）
# freeway.cloud.event.enabled=false           # 总闸——连已配置的 peers 也压制
freeway.cloud.event.token=mesh-secret         # 空 = 无对等认证（有告警）；生产必配
```

- `peers`——要拨的节点；非空列表**本身就是激活**。端点挂在既有 HTTP 服务的 `/cloud/event`
  上；IPv6 字面量带方括号或裸写均可。
- `discovery-feed-ms`——用发现后端喂网格：按间隔向绑定的 `ServiceDiscovery` 查询本服务实例，
  整份交给网格（本节点排除）：出现的实例被拨通，离开的被断开并停止重拨，**一次查询失败保留
  原集合**。静态 `peers` 与发现集合是并集，空轮询不取消静态列表；设了键却没有 discovery 可问
  = 启动失败并点名。
- `token`——hello 帧里的共享密钥，常量时间比较；不一致以 WS `1008` 断开。多节点生产必配，
  经 `FREEWAY_CLOUD_EVENT_TOKEN` 注入，轮换走滚动重启。

**兴趣不是配置**：订阅贡献同时驱动 hello 的拉取前缀、入站门禁和投递——一处声明三处生效。

**发布跨边界——按名字**：路由键是调用点选择的 topic 字符串，Java 类名不上线：

```java
@Inject CloudEventBus mesh;

mesh.publish("order.created", new OrderCreated("order-42"));
mesh.publish("metrics.tick", report, "tenant-7");   // 可选 subject（顺序提示）
```

`publish` 是 Defer 感知的：事务内缓冲到提交才出境，回滚即弃——未提交的事实不出 JVM。

**接收声明类型——声明即白名单**：

```java
binder.contribute(CloudEventSubscription.class)
      .add("orders",
          CloudEventSubscription.of("order.", OrderCreated.class, this::handle));
```

topic 匹配不上任何订阅的帧**不反序列化即弃**——未声明的类不会被实例化。刻意没有运行时
subscribe：对端在握手时看不见的兴趣，是无法被兑现的兴趣。

**本地与远程刻意镜像**：两个平面互不喂——远程事实不会自动变成本地事实。需要时在 handler
里镜像：`CloudEventSubscription.of("order.", OrderCreated.class, event -> bus.publish(event))`。

**谁收到什么**：只看节点自己声明的订阅前缀，没有第二个过滤器。每节点部署清单：模块放置 +
（peers 或 `enabled=true`）+ 订阅声明 + `token`——缺任何一项都是静默分区而非错误；
`stats().droppedNoSubscriber()` 与 `framesQueued()` / `framesDropped()` 让发送侧事后可观测。

**半开连接**：设 `freeway.cloud.event.keepalive-ms` 后，mesh 每拍向 peer 发 WebSocket ping，要求
pong 在下一拍前回来；没回就关连接，交给既有重连（出站）与注销（入站）路径。抓的是"TCP 还活着、
对端已经不读"——NAT/LB 静默丢连接正是这种形状，靠发送失败要等到下一次发布才发现。默认关：
活性探测是流量，已用 TCP keepalive 或 service mesh 的部署可以不为第二个意见付费。

**投递语义**：at-most-once、实时。发布时离线的 peer 错过该事件（无重放队列）——需要持久、
按键有序的投递用 Kafka 平面（`freeway-mq-kafka`）。入站帧可挂拦截器
（`contribute(CloudEventInterceptor.class)`）；抛异常的 handler 被隔离并计数；网格从设计上
无重投，历史 class 通道帧解析后按原因丢弃并计入 `stats().droppedLegacy()`。

## 健康与就绪

`contribute(CloudHealthContributor.class)` 为自己的依赖加就绪检查。内置注册检查报的是心跳
**验证过**的状态——已注册 / 排空中 / 连续心跳失败——不是常量；`/health/live` 与
`/health/ready` 由框架服务。外部后端适配器可以**追加**自己的连通性检查，不替换核心的注册
状态检查。

## 可观测、弹性、密钥、存储、上下文

| 能力 | 应用侧 | 键 / 备注 |
|------------|------------------|--------------|
| 负载均衡 | 默认加权轮询；`.primary()` 换策略 | `ServiceInstance.weight()/zone()/version()/isCanary()` 是策略的输入 |
| 可观测 | `@Inject Metrics`（计数/计时/仪表）、`@Inject Tracer`（`start(name)`）、`@Inject MetricsSnapshot`；每个请求运行在服务端 span 里，日志带 `traceId`；探针与 `/metrics` 不追踪 | `GET /metrics` |
| 弹性 | 默认即绑定且 RPC 客户端使用；`@Inject Retryer` / `CircuitBreaker` / `RateLimiter`，或 `.primary()` 替换 | `freeway.cloud.rpc.resilience=auto\|off` 与细分键 |
| 上下文传播 | `InvocationContext.current()` 读 baggage/principal/trace；零接线 | 入站过滤器 + 出站头注入 |
| 密钥 | `@Inject SecretStore`；密钥也可被符号解析（`@Symbol("db.password")`）。文件按 size/mtime 变化重读（不重启轮换） | `freeway.cloud.secret.*`（仅系统属性） |
| 对象存储 | `@Inject ObjectStorage`；`ObjectStoredEvent` / `ObjectDeletedEvent` 发到总线 | `freeway.cloud.storage.*` |

每种能力都有进程内默认（本地注册表、env/文件密钥、文件系统存储、内置指标注册表）——**单节点
部署不需要任何后端**；外部后端以适配器到达，绑 `.primary()` 即接管。

**`freeway.cloud.*.type` 声明意图，不选择实现。** 框架从不按类名加载（无扫描、无配置反射），
选择器是绑定：适配器以 `.primary()` 绑定角色，内置默认让位。`type` 键是给人与适配器看的
声明；声明了外部后端却没有适配器接管时，本地实现启动告警一次（`BackendTypeGuard`），不让
声明看起来像已生效。

## 出站 TLS 的在线轮换

默认情况下 `TransportSecurity` 的 `SSLContext` 解析一次，换 keystore/truststore 需重启；设
`freeway.cloud.rpc.tls.reload-interval` 后，KeyManager/TrustManager 每次握手委托给**可轮换材料**
（size/mtime + 节流，读失败保留旧材料），下一次握手即生效——**不重建客户端**，因为 JDK
`HttpClient` 在构建时就捕获了 context。入站侧是另一个机制（引擎每次握手读当前 context，
`freeway.http.ssl.reload-interval`），两侧合起来才是完整的证书轮换。

## 键的读取时机与停机顺序

**Cloud 键在拥有它的组件被构建时读取一次。** 热更新改的是符号链此后解析到的值，不是已构建
客户端捕获的值——改 `freeway.cloud.rpc.request-timeout` 要重启生效（传给
`RemoteCaller`/`RemoteProxyFactory` 的单次调用值是例外；密钥文件按 size/mtime 变化重读）。
只在真正需要"活值"的地方按使用点读取。

**停机是有序的，且刻意如此。** 注册 hook 先于 HTTP 服务器停止：置 readiness 为 `draining`、
先注销，再等 `freeway.cloud.registry.shutdown-drain`（默认 `auto`，由注册表后端回答自己的
传播窗口——进程内答 0，适配器答自己的数），然后服务器才停止接收。在途的出站调用有
`freeway.cloud.rpc.shutdown-grace`（默认 `5s`）收尾；事件网格 peer 收到 `1001 going away`
关闭帧而非 RST。

## 相关文档

- 设计取舍与边界：[freeway-cloud-design.md](freeway-cloud-design.md)
- 全部配置键：[freeway-config.md](freeway-config.md)
- 三方适配器（Consul、Kafka 平面等）：[freeway-ext](https://github.com/dzb/freeway-ext)
