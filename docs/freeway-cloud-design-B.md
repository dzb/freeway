# freeway Cloud 能力方案

> 状态：方案设计阶段（未落地）。目标：为 freeway 增加云/微服务能力，全程基于现有扩展点**增量构建**，不改动任何现有 core 模块。

## 1. 目标与范围

新增核心模块 **`freeway-cloud`**（零依赖，仅 JDK 25 + SLF4J），遵循 freeway 既有的 core/ext 分层：

- **云无关 SPI**：抽象接口 + in-memory/local 默认实现全部留在 core；具体后端（Nacos/Consul/etcd/Kubernetes）后续放到独立的 `freeway-ext` 仓库。
- 覆盖四项能力（用户已确认全选）：
  1. 服务注册发现 + RPC 调用
  2. 配置中心 + 热刷新
  3. 可观测性（分布式追踪 + Metrics）
  4. 韧性（熔断 / 限流 / 重试）

## 设计内核（概念优先）

> freeway-cloud 不是一张「能力清单」，而是少数不可约概念在进程边界上的延伸。能力（注册发现/配置/可观测/韧性/安全）都是这些内核的*派生*。先立内核，再谈能力。

### 内核 ① `InvocationContext`：能跨越网络的 `ScopedValue`
- freeway 已有 `Defer`/`ScopedCache`（基于 JDK `ScopedValue`）作为边界作用域原语；云把它推过进程边界。
- `InvocationContext` = **统一的 `ScopedValue` 载体**：进程内承载 + 边界（反）序列化管线，一个 `ScopedValue` 槽。
- 载体内含**三个类型化、分属各子系统的子上下文**（universal 到值得显式）：`TraceContext`（traceId/spanId，基础设施拥有）、`Principal`（身份，安全子系统拥有、不可被任意代码伪造）、`Baggage`（KV，应用拥有）。概念可分、类型分明、信任规则不同。
- **`Propagator`**（贡献点，沿用 `Route`/`HttpFilter` 的 `Contribution` 模式）= 一种关注点如何跨线的策略：`extract(headers, ic)` / `inject(ic, headers)`。内置 `TracePropagator`/`AuthPropagator`/`BaggagePropagator`；加新关注点 = 贡献一个 `Propagator`，不改 core。
- 两个边界应用点：`PropagationFilter`（`HttpFilter`，入站 `extract`→`enter(ic)`）与 `CloudHttpClient`（出站 `inject` 当前 `ic`）。多跳调用经此链式传播。
- 合一边界：**载体合一、载荷三分、Propagator 可插拔**——既得「一个概念、一个过滤器」之简，又避「context 大杂烩」反模式与安全问题。

### 内核 ② `ServiceId`：通用服务标识，网络形态 `ServiceId @ Location`
- `ServiceId` 本质是**服务标识**，不是「同接口多实现」的专属；绑定 `id` 只是它在*容器作用域*的一个应用。
- 网络作用域下自然演化为 `ServiceId @ Location`（`ServiceInstance` = id + 地址 + metadata）。
- 目的始终如一：**被发现、被定位**。
- `ServiceInstance` = `ServiceId @ Location` + metadata（version/zone/weight/canary/health）。
- **发现** = `ServiceId → {ServiceInstance}`；**解析** = 从中选一实例定位到地址。
- `serviceId` 即**普通字符串身份**——freeway 内部 `ServiceIds.normalize` 经核实目前只是「非空 + trim」守卫（非真正的归一化，不产生规范形态），但它是 freeway 为 serviceId 归一化**预留的接缝**。故本地绑定 id 与远程服务名**共用同一名字空间**并非靠某层归一化统一，而是因为它们本就是**同一个字符串**：绑定 id 经 IoC 守卫后即是该串，`@CloudClient("id")` 名亦取该串。cloud 让本地绑定 id 与 `@CloudClient` 名都走同一个 `ServiceIds.normalize` 守卫——未来若在该接缝加入规范形态，两作用域会自动一致，无需 cloud 另造归一化逻辑；cloud 不自行引入独立归一化。绑定 id 与远程服务名是同一概念在两作用域的视图，非转换、也非归一化产物。

#### `ServiceInstance` 数据模型（内核 ② 的货币）
发现层的货币、解析层的对象；discovery / 负载均衡 / 健康 / canary 都围着它转。

- **形态（强类型核心 + 可扩展 metadata 袋）**：
  ```
  record Endpoint(String scheme, String host, String port, String basePath) {  // 结构化定位符，可转 URI
      URI uri();
  }
  record ServiceInstance(
      ServiceId           serviceId,    // 逻辑身份（归一化后的 id）
      String              instanceId,   // 本实例稳定身份（区别于 serviceId；同 id 多实例各不同；与位置解耦）
      Endpoint            endpoint,     // 结构化定位符（非裸 IP）
      Map<String,String>  metadata      // zone / version / weight / canary / region / ...
  )
  ```
  - `serviceId@(scheme://host:port)` 只是 `ServiceInstance` 的**派生显示键**，不是存储/寻址形式；`Endpoint` 用结构化定位符取代裸 `ip-address`——覆盖 scheme/port/path、DNS 名、K8s Service FQDN、mesh sidecar，甚至非 IP（Unix socket / 消息队列名）。
  - `instanceId` 是**实例级稳定身份**（注册中心用它做增删改与心跳），与 `endpoint` 解耦：容器重调度换了 IP/端口，仍是同一 `instanceId`、仅更新 `endpoint`——身份不随位置漂。
  - **调用方永远只认 `serviceId`**（`@CloudClient("orders")` / `Container.get(type, id)`）；`@location` 仅存在于被发现出的 `ServiceInstance` 内部，永不出现在调用语法里。发现 = `ServiceId → List<ServiceInstance>`；解析 = 从集合按策略选一 `endpoint`。
- **typed vs free-form（freeway 口味）**：必类型化 `serviceId`/`instanceId`/`endpoint`（发现与解析每跳要）；其余（zone/version/weight/canary/health…）放 `metadata` 自由袋（随后端与场景增长，不该固定成字段，否则加一个就改类，违背 additive）。但 freeway 不喜「裸 String 到处取」——提供**类型化 accessor + `Coercer`**：`instance.weight()` → `Coercer.coerce(metadata.get("weight"), double.class, 1.0)`；`zone()`/`version()`/`isCanary()` 同理，带默认值。metadata 是袋，读取类型安全、有默认——与 `@Value("${k:default}")` 风格一致。
- **health 不放进 `ServiceInstance`**：健康是会变的探测状态，不是地址的一部分，由发现层/注册中心维护 `Health(live, ready, lastSeen)`。负载均衡只选 `live && ready`；`lastSeen` 超阈值（心跳丢失）的实例被驱逐（stale eviction）。`ServiceInstance`=地址+属性；`Health`=探测状态；`LoadBalancer`=选谁——三件事职责分清。
- **canary / zone / weight 驱动路由**：纯 `LoadBalancer` + metadata 的事（内核 ③ 策略），非 `ServiceInstance` 职责。`choose(instances, ctx)` 读 metadata 做 zone-aware / weighted / canary；`ServiceInstance` 只给数据，不给策略。
- **注册 / 续约 / 驱逐（生命周期）**：`register(instance)` 在 `RuntimeHook.start`；`renew()` 周期心跳刷新 `lastSeen`；`deregister()` 在 `stop`；注册中心对 `lastSeen` 超时者驱逐。本地默认用内存 map + 定时清理；ext（Nacos 心跳 / K8s watch Endpoints）差异在*如何*感知变更，但 `ServiceInstance` 模型不变。

### 内核 ③ 云边界 = freeway 组合原语延伸到进程边界
- 「调一个服务」不引入新注解动物园，而用既有组合原语在边界重组：
  - `Contribution`/`Extension`：RPC 边界贡献点（类 `Route`/`HttpFilter`；如 `Propagator`、`/rpc/*` 的 `Route`）
  - `Advisor`：韧性/传播作顾问链织入（类 `@bean` AOP）
  - `@Marker`：后端/策略选择（类 dialect/pool 的 `.primary()`）
  - `RuntimeHook`：生命周期注册/反注册（类 http server hook）
- 一句话：**云能力 = freeway 的边界作用域原语（`ScopedValue`/`Defer`/`ScopedCache`）+ 组合模型（`Contribution`/`Advisor`/`Marker`/`RuntimeHook`）延伸到进程边界**，而非搬 Spring Cloud。

### 能力是内核的派生（映射）
- 注册发现 + RPC ← 内核 ②（`ServiceId`/`ServiceInstance`）+ 内核 ③（`CloudHttpClient`/`CloudExporter` 走 `Contribution`）+ 内核 ①（调用经 `InvocationContext` 传播）
- 配置中心 + 热刷新 ← 内核 ③（`ConfigLoader`/`SymbolProvider` 贡献，本地 `WatchService` 默认）
- 可观测性 ← 内核 ①（`TraceContext`/`MeterRegistry` 经 `InvocationContext` 与 `Propagator`）
- 韧性 ← 内核 ③（`Advisor` 织入）
- 安全 ← 内核 ①（`AuthPropagator` 是 `Propagator` 的一种；mTLS 走 `TransportSecurity`）+ 内核 ③（`SecurityFilter` 经 `Contribution`）

## 2. 设计约束（来自 CLAUDE.md，决定方案形状）

- **core 零外部依赖**：任何集成 Consul/Nacos/K8s 的代码必须进 `freeway-ext`。纯 JDK 部分（如 `java.net.http.HttpClient`、`ScopedValue`）可留在 core。
- **无类路径扫描、无字节码织入**：模块用显式 `ModuleEx` + `install` 组合；仅 `ServiceLoader` 自动发现（可关）。
- **`.primary()` 选择模式**：覆盖默认引擎/客户端/方言时，绑 alternative 并 `.primary()`。
- **小而显式的 API**：概念少（Module/Service/Extension/Scope/Runtime）；优先显式而非过度抽象。
- **命名规则**：公共接口用裸领域名（`Container`、`EventBus`）；框架默认实现用 `XDefault`（`AppRuntimeDefault`、`JsonCodecDefault`）；`Impl` 留给非策略性具体类；内部助手保持 internal。
- 配置是**启动期不可变快照**（`AppConfig` 为 immutable record），无热刷新 —— 分布式配置需要自己的模型。

## 3. 现有可复用地基（证明零侵入可行）

| 能力 | 钩入点 | 位置 |
|---|---|---|
| 生命周期注册/反注册、心跳 | `RuntimeHook` + `Contribution.before/after("freeway.http.server")` | `freeway-ioc` `RuntimeHook.java`；`freeway-boot` `HookLifecycle.java` |
| 动态配置注入 | 贡献 `SymbolProvider`（`@Value`/`@Symbol` 解析时读取）或替换 `ConfigLoader` | `SymbolSourceDefault`、`freeway-boot` `ConfigLoader.java` |
| 追踪 / Metrics 端点 | `HttpFilter` / `Route` 扩展点、`HealthFilter` 模式 | `freeway-http` `filter/` |
| 分布式事件 | `EventBus.setEventBridge(EventBridge)` | `freeway-ioc` `EventBridge.java` |
| 韧性织入 | `Advisor`/`MethodAdvice`（接口→实现绑定）或 `HttpFilter` | `freeway-ioc` `advisor/` |
| 出站 RPC | core 无客户端 → 新模块包 JDK `HttpClient`（零依赖，允许在 core） | `freeway-http` 仅服务端 |
| 后端/策略/变体选择 | `@Marker`（见 §4） | `freeway-ioc` `MarkerIndex.java` |

**所有项都是 additive**，不碰 `freeway-boot`/`freeway-ioc` 内部。

> 上述扩展点名、钩子签名、`ConfigLoader` 替换入口（`AppBuilder.config(loader)`）、HTTP 配置键（`freeway.http.server.host/port/shutdown-grace`、`freeway.http.health.*`）均已对照 `freeway-dev` 技能 reference（ioc/boot/http）逐条校验，无偏差。

**已具备的优雅关停基础**（无需 cloud 模块新建）：`freeway.http.server.shutdown-grace` 配置项控制关停宽限；`RuntimeHook.stop(Container)` + `AppStoppingEvent`（shutdown 前发布）提供有序反注册时机；JVM shutdown hook 默认开启。cloud 模块只需在其 `RuntimeHook.stop` 里做注册中心反注册，并补 `/health/ready`、`/health/live` 拆分即可，不必重造关停机制。

## 4. `@Marker` 机制及其在 cloud 中的用法（用户指定纳入）

### 4.1 机制回顾（freeway-ioc）
- `@Marker` 携带 `Class<?>[] value()`（marker 注解类型），要求 `@Retention(RUNTIME)`、空注解。`@Target({TYPE, METHOD})`。
- 三种挂载：(a) 实现类上的 `@Marker(X.class)`；(b) 绑定 DSL `.marker(X.class)`；(c) **模块类上的 `@Marker` 传播给该模块每个绑定**（`BinderImpl.java:46-52`）。
- 解析 `Container.get(Class, Class<? extends Annotation>...)` → `MarkerIndex.findByMarker`（`:69-133`），用 **`containsAll`（AND）** 语义：绑定必须携带所请求的全部 marker；多个命中则 `.primary()` 胜出，否则报错。
- 注入点读取：`InjectResolver.resolveMarkers`（`:285-302`）扫描字段/参数上的所有注解，只要 `markerIndex().isKnownMarker(annType)` 即当 marker；构造器参数可**不带 `@Inject`** 仅靠 marker 解析（`:232`）。

### 4.2 语义边界（决定怎么用、避什么坑）
- **静态、无值**：marker 是注解*类型*，`zone=us-east` 无法表达，只能建 `@UsEast`/`@UsWest` 等独立注解。它分类的是**实现/模块**，不是运行时实例。
- **`containsAll` 严格**：请求 marker 无单一绑定全携带则报错，没有「最接近胜出」打分（那种打分只在 Flow `@FlowMarker`，是另一套）。
- **「已知 marker」静默回退**：注入点用了某注解但没有任何绑定携带它 → 静默回退 `get(type)`，不报错。拼写错/漏引后端适配器时会**悄悄用默认实现**，极难排查。
- **模块级传播是叠加**的，不能对单个绑定排除。
- **marker vs `.primary()`**：`.primary()` 全局只选一个；marker 允许多个实现共存、按使用点挑选。两者互补。

### 4.3 在 cloud 中的具体落点
1. **每个 SPI 的后端选择**（替代纯 `.primary()`）：给每个云抽象的多实现打后端标记——
   `ServiceRegistry`：`@Local`/`@Nacos`/`@Consul`/`@Kubernetes`；`CloudConfig`：`@Local`/`@Nacos`/`@Consul`；`Tracer`/`MeterRegistry`：`@Local`/`@Otel`。
   应用侧 `@Inject @Nacos ServiceRegistry registry;` 显式选；`.primary()` 仍作默认。本地默认与 ext 后端可**共存**——这正是 `.primary()` 做不到的。
2. **策略标记**：`LoadBalancer` 的 `@RoundRobin`/`@Random`/`@Weighted`、`ClientTransport` 的 `@Http`/`@Grpc`、`CircuitBreaker` 的 `@Semaphore`/`@Threadpool`——`CloudHttpClient` 可 `@Inject @RoundRobin LoadBalancer` 按需选。
3. **模块级能力标签**：`CloudModule` 或子模块 `@Marker(Cloud.class)`，使其所有 bean 带 `@Cloud`；canary/edge 部署用 `@Marker(Canary.class)` 整模块切变体实现（相当于 Spring 的 `@Profile`/条件装配，但用 marker 表达）。
4. **不做的事**：运行时实例级属性（具体实例、region、version、health、weight）用 `ServiceInstance.metadata` + `LoadBalancer`/谓词选，不用 `@Marker`。

### 4.4 多后端共存 vs canary（关键区分）
- **多后端共存**：同一 SPI 绑多个实现，各打后端标记，按点选。`ServiceRegistryLocal`+`ServiceRegistryNacos` 共存，测试用 Local、生产用 Nacos，或双注册迁移。`@Primary` 定默认、`@Marker` 定变体。
- **canary 分两种，别混淆**：
  - *接线期 canary*（marker 的活）：canary 部署实例内，某些服务用 canary 变体——模块级 `@Marker(Canary.class)` 整模块切。
  - *运行时 canary 路由*（**不是** marker 的活）：部分流量导到 canary 实例——同一服务多运行时实例按 `metadata{canary:true}` 选，归 `LoadBalancer`+`ServiceInstance.metadata`。`@Marker` 无值、静态，干不了这个。

### 4.5 静默回退的规避约定（写进设计决策）
- 本地默认实现统一打 `@Local` 且 `.primary()`，确保「不指定 marker 必有默认」。
- 后端 marker 注解集中放 `cloud/annotation/`，文档明确「用了 `@Nacos` 就必须引入对应 ext 适配器，否则静默回退」。
- 关键处用 `container.get(ServiceRegistry.class, Nacos.class)` 显式取，拿到异常比静默回退更易暴露问题。

## 5. 模块结构

单 Maven 模块 `freeway-cloud`，内部 **4 个 sub-`ModuleEx` + 1 个伞模块 `CloudModule`**。用户按需 `install` 子集：

```
freeway-cloud
 ├ discovery   ServiceRegistry / ServiceDiscovery / ServiceInstance / LoadBalancer / CloudHttpClient
 ├ config      CloudConfig / ConfigListener / ConfigRef（热刷新）
 ├ observe     Tracer / TraceContext（W3C traceparent）/ MeterRegistry
 ├ resilience  CircuitBreaker / RateLimiter / Retryer（+ @CircuitBreak/@RateLimit/@Retry 注解 + Advisor）
 └ CloudModule （install 上述四个，绑定全部 local 默认）
```

每个后端件都是 `bind(...).to(...).primary()` 可覆盖的（贴合 HTTP engine / DB pool / dialect 模式）。包布局：`com.jujin.freeway.cloud.{discovery,config,observe,resilience,annotation}`。

## 6. 各能力设计要点

> 以下能力是「设计内核」三概念的*派生*，按内核组织而非并列模块：注册发现/RPC ← 内核 ②③①；配置 ← 内核 ③；可观测 ← 内核 ①；韧性 ← 内核 ③；安全 ← 内核 ①③。

### 6.1 注册发现 + RPC（最地基，其余依赖服务调用）
- `ServiceInstance(name, host, port, scheme, metadata, id)`（record）。
- `ServiceRegistry`：`register(ServiceInstance)` / `deregister()` / `renew()`（renew 走 `RuntimeHook` 心跳）。
- `ServiceDiscovery`：`instances(serviceName)` / `subscribe(name, listener)`。
- local 默认：`ServiceRegistryLocal`/`ServiceDiscoveryLocal` 基于进程内/静态注册表（生产可用；单进程与静态拓扑直接可用，跨进程动态发现由 ext 后端提供）。
- **出站传输层 `CloudHttpClient`**（底层，人人可用）：包一个 SINGLETON 的 JDK `HttpClient`（虚拟线程友好、复用连接）。核心 `CloudHttpResponse call(String serviceName, CloudHttpRequest req)`；流程：`discovery.instances(serviceName)` → `loadBalancer.choose(instances, ctx)` 选 `ServiceInstance` → 拼 `scheme://host:port + path` → 从 `Tracer.current()` 取上下文写 W3C `traceparent` 头 → 套 `CircuitBreaker`/`RateLimiter`/`Retryer` 发请求 → 响应体用 `JsonCodec`/`Coercer` 映射回返回类型。选择：`bind(CloudHttpClient.class).to(CloudHttpClientJdk.class).primary()`；ext 可换 WebClient/异步实现。`LoadBalancer` 用 `@Marker` 选（`@RoundRobin`/`@Random`/`@Weighted`）。
- **声明式客户端 `@CloudClient`**（上层，最佳 DX，最 freeway 原生）：`@CloudClient("orders") interface OrderClient { @Get("/orders/:id") Order findById(@Path("id") String id); ... }`，使用 `@Inject @CloudClient("orders") OrderClient client;`。框架用 **JDK 动态代理**（freeway 的 AOP/Advisor 同一套 `Proxy` 机制，零新增依赖）生成实现：每次方法调用 → 读 `@Get/@Post` + 参数 `@Path/@Body` → 构造 `CloudHttpRequest` → 委托 `CloudHttpClient.call("orders", req)`。客户端必须是**接口**（契合 AOP 仅接口→实现约束）。绑定用 freeway-cloud 提供的注册助手显式注册，**不扫描类路径**（freeway 无扫描原则）：`CloudBinder.bindClient(binder, OrderClient.class, "orders")`——其内部用现有 `binder.bind(OrderClient.class).to(generateProxy(...))` 绑定生成的代理，**不新增 core `Binding` 接口方法**，保持对 `freeway-ioc` 零侵入。
- **两个关键细节（易错）**：(a) **重试要重新选实例**——`Retryer` 重试时重新 `choose` 一个不同实例（结合 `discovery` 刷新），不能 hammer 同一死实例；(b) **超时**——每调用 `HttpRequest.timeout(Duration)`，可配置，配合虚拟线程中断。
- **与 `@Marker` 的边界**：`@CloudClient("orders")` 的 `"orders"` 是**服务名（字符串）**定位实例；传输实现/负载均衡策略才用 `@Marker` 选（`@Grpc`/`@Http`、`@RoundRobin`）。两者职责不同，不混。
- **不做的**：透明远程 bean（`c.get(SomeService.class)` 解析成远程代理）——太魔幻、与本地绑定语义冲突，RPC 保持显式（`@CloudClient` 接口或 `CloudHttpClient.call`）；gRPC/Thrift 进 core——留给 ext。
- **服务端发布（Server-side publishing）**——把本地 bean 变为可被远端调用的服务，**复用 binding `id` 作为逻辑服务名**（用户提议：id 即云化标识，统一服务端发布与客户端消费于一名）。必须**显式 opt-in**（id 也用于纯本地区分，如 `stripe`/`paypal`，不对所有 id 自动云化）。

  **`CloudExporter` 签名**（freeway-cloud 助手，不扫描类路径）：
  ```java
  final class CloudExporter {
      // 发布一个已绑定(id)的 service 为可远程调用
      static void publish(Binder binder, Class<?> serviceType, String serviceId) { ... }
      // 基于实现类上的 @CloudService 标记发布（仍由调用方显式触发，不扫描）
      static void publishAnnotated(Binder binder) { ... }
  }
  ```
  内部做两件事：(a) 贡献 `Route`（见下）；(b) 贡献 `RuntimeHook`（id `"cloud.rpc." + serviceId"`）在 `start(Container)` 时把服务注册进 `ServiceRegistry`、在 `stop` 时反注册。

  **路由生成与派发**（`/rpc/{serviceId}/{method}`）：
  - 对 `serviceType` 的每个 public 方法 `m`，生成 `Route.post("/rpc/{serviceId}/{m.name()}", handler)`。约定 RPC 走 POST、路径由 `serviceId` + 方法名构成（与客户端 `@CloudClient` 对齐，见下）。
  - handler 在**请求时**解析目标 bean：因 `bind()` 时无 `Container`，`CloudExporter` 在 `RuntimeHook.start(Container c)` 时把 `c` 捕获进 holder，`Route` handler 闭包持有该 holder，请求时 `holder.container().get(serviceType, serviceId)` 取到实例（若 `HttpContext` 直接暴露容器则更简，需实现时确认）。
  - 参数绑定：请求体 JSON（按参数名映射的对象，或按位置数组）→ 经 `Coercer`/`JsonCodec` 逐个 coercion 到参数类型。
  - 调用：`m.invoke(instance, args)`（v1 反射；后续可换 `MethodHandle`/生成的 lambda 提性能）。
  - 返回：`JsonCodec.toJson(result)` → `ctx.sendJson(200, result)`。
  - 异常跨边界：捕获受检/非受检异常，序列化 `{error: <type>, message: <msg>}` 并映射 HTTP 状态（4xx/5xx）；客户端 `@CloudClient` 侧据此重抛 `RemoteException`（携带 type+message），呼应「异常跨边界」注意点。

  **客户端/服务端路径对齐**：`@CloudClient` 方法按**方法名**映射到 `/rpc/{serviceId}/{methodName}`（verb 默认 POST，可覆盖）；`@Path`/`@Body` 改为「请求体 JSON 按参名绑定」的单一约定，避免 REST 风格 `:id` 与 RPC 方法名混用。即早前 `@Get("/orders/:id")` 示例在 RPC 模型下应理解为映射到 `/rpc/orders/findById`（verb GET/POST 仅选动词）。两端共用 `serviceType` 接口 → 类型安全、参数名一致。

  **安全闸门**：生成的 `/rpc/{serviceId}/*` 路由默认由 §6.5 的 `SecurityFilter` 看护——无安全模块时 `/rpc/*` 默认拒绝，安装安全模块后按 token 校验。`CloudExporter.publish` 不直接做鉴权，只贡献路由，鉴权交给 `SecurityFilter`（opt-in）。

  **注意**：binding id 即 `serviceId`（IoC 仅做非空/trim 守卫，非归一化），注册服务名默认取该 id，可显式覆盖（如 `publish(binder, OrderService.class, "orders")` 显式给定）；远端调用语义 ≠ 本地（序列化、异常跨边界、无引用传递、网络失败）；要求 `HttpModule` 已安装（服务需对外暴露 HTTP 面）。
- 注册用 `RuntimeHook`（`start` 注册、`stop` 反注册），排序 `before/after("freeway.http.server")`。

### 6.2 配置中心 + 热刷新
- `CloudConfig`：相比不可变 `AppConfig` 快照，新增 `watch(key, listener)` / `addListener(ConfigListener)` / `reload()`，并贡献为**动态 `SymbolProvider`**。
- **热刷新三件套**（不破坏 `AppConfig` 不可变性与现有注入模型，不引入 `@RefreshScope` 魔法）：
  1. `ConfigRef<T>` 包装：运行时 `.get()` 读最新值；
  2. 直接注入 `CloudConfig` bean 读；
  3. 发布 `ConfigChangedEvent` 到 `EventBus` 做响应式重绑。
- local 默认：`CloudConfigLocal` 用 `WatchService` 监听文件变化热重载，作为无后端时的可用实现。

### 6.3 可观测性
- `Tracer`/`TraceContext`：生成 traceId/spanId，用 **`ScopedValue`** 跨异步/虚拟线程边界传播，并写 MDC 供日志显示（建立在既有 MDC 显示之上）；outbound 端注入/接收 W3C `traceparent` 头。
- 贡献 `HttpFilter`（服务端提取/注入 trace 上下文）+ 复用 `RequestTimingFilter`/`HttpRequestEvent` 自动记延迟。
- `MeterRegistry`（counter/timer/gauge，in-memory 默认）+ 贡献 `Route` `/metrics`（Prometheus 文本格式）。
- **K8s 探针拆分**：在 `HealthCheck`/`Route` 扩展点之上补 `/health/ready`（依赖就绪：DB/注册中心连通）与 `/health/live`（进程存活）两个端点——这是云原生就绪的核心，且建立在既有 `freeway.http.health.*` 配置键之上。优雅关停复用 §3 已有的 `shutdown-grace` + `RuntimeHook.stop` + `AppStoppingEvent`，注册中心反注册放在 `RuntimeHook.stop` 内。

### 6.4 韧性
- `CircuitBreaker`（滑动窗口+半开）、`RateLimiter`（令牌桶）、`Retryer`（退避）。
- 作为 **`Advisor`** 织入 `@bean` 接口服务（`@CircuitBreak`/`@RateLimit`/`@Retry` 注解驱动，符合 AOP 仅接口→实现约束）；RPC 出站在 `CloudHttpClient` 层统一套用。

### 6.5 安全（传输加密 + 鉴权上下文传播 + 密钥源）
跨服务安全分三层，全部基于现有扩展点；core 内仅用 JDK 能力（零额外依赖），密钥库/证书管理等重后端进 ext。

- **传输加密（mTLS）**：`CloudHttpClient` 出站经 JDK `SSLContext`（core 内即可，无需第三方库）。core 提供文件型证书加载（从云配置读 keystore/truststore 路径）；证书从密钥库（Vault 等）动态获取属 ext。选择：`TransportSecurity` 实现 `.primary()`/`@Marker`（`@Mtls`/`@None`）。
- **鉴权上下文跨服务传播（核心诉求）**：入站请求携带的身份（JWT/OAuth2 token 或已验证 principal）要在下游 RPC 跳中延续。复用与追踪相同的 `ScopedValue` 上下文载体，统一为 `InvocationContext`（含 traceId + principal + baggage），由**一个 `PropagationFilter`（`HttpFilter`）**入站提取、出站由 `CloudHttpClient` 经 `AuthPropagator.inject(...)` 注入为传播头（如 `Authorization: Bearer <token>` 或 principal 头）——与 traceparent 同一机制同一过滤器，不重复造。
  - 入站 `/rpc/{id}/{method}` 路由前挂 `SecurityFilter`：校验 token、填充 `InvocationContext.principal`、拒绝未授权；无 `SecurityFilter` 时 `/rpc/*` 默认拒绝（显式 opt-in 才开放）。
  - 出站 `@CloudClient` 调用把当前 `InvocationContext.principal` 写入请求头，与 traceparent 同序注入，避免覆盖。
- **密钥源（Secrets）**：凭据/API key 不应明文落配置。复用配置中心（§6.2）的 `CloudConfig`/`SymbolProvider` 作密钥解析入口；密钥后端（Vault/KMS）属 ext。core 只定义 `SecretSource` 抽象 + 文件默认实现。
- **可选标记**：`@Mtls`/`@None`（传输）、`@Jwt`/`@Opaque`（token 方案）按 `@Marker` 选；无安全模块时默认 `@None`（明文，开发态），生产由 ext 覆盖。
- **范围边界**：这里是**服务间（service-to-service）云原生安全**——传输加密 + 身份传播 + 密钥源，**不是**应用级登录/会话框架（freeway-core 本无 auth，不在此引入完整 auth 框架）。
- **注意**：传播的应是「已验证的 principal/token」而非原始凭据；mTLS 握手有开销，按服务可配、可关。

## 7. 关键设计决策
- **热刷新**走 `ConfigRef` + `EventBus` 事件，不破坏 `AppConfig` 不可变性与现有注入模型，不引入 refresh scope。
- **trace 上下文**用 `ScopedValue` 而非仅靠 `ThreadLocal` MDC，解决虚拟线程/异步跨边界丢失。
- **出站客户端**纯 JDK 留在 core；任何第三方客户端进 ext。
- **后端件 `.primary()` 定默认，`@Marker` 定变体选择**（§4）；集中定义后端/策略标记注解于 `cloud/annotation/`。
- **不改动现有 core 模块**：全部基于 §3 的扩展点增量构建。

## 8. 实施阶段（建议顺序）
- **P0** 脚手架（产出可编译空壳 + 测试）：
  - 新建 `freeway-cloud/` Maven 模块，父 POM 设为 `freeway-parent`，依赖仅 `freeway-commons`/`freeway-ioc`/`freeway-boot`/`freeway-http`（compile）+ `slf4j-api`；JDK 25（继承父 `release` 设定，不另引插件）。在根 `pom.xml` 的 `<modules>` 追加 `freeway-cloud`。
  - `com.jujin.freeway.cloud.CloudModule`（实现 `ModuleEx`），`bind()` 内 `install` 四个 sub-`ModuleEx`：`DiscoveryModule` / `ConfigModule` / `ObserveModule` / `ResilienceModule`（各自 `bind()` 只声明，激活放 `RuntimeHook`）。
  - `cloud/annotation/`：空 `@Retention(RUNTIME)` 注解 `@Local`/`@Nacos`/`@Consul`/`@Kubernetes`/`@RoundRobin`/`@Random`/`@Weighted`/`@Cloud`/`@Canary`（供后端/策略/变体选择，见 §4）。
  - 四个能力包的**接口骨架**（`ServiceRegistry`/`ServiceDiscovery`/`ServiceInstance`、`CloudConfig`/`ConfigRef`、`Tracer`/`MeterRegistry`、`CircuitBreaker`/`RateLimiter`/`Retryer`）+ local 默认实现占位。
  - 最小测试：容器启动 `FreewayApp.run(..., new CloudModule())` 不报错；`container.get(ServiceRegistry.class, Local.class)` 解析到 local 默认；`@Local` 默认经 `.primary()` 可作为 `get(ServiceRegistry.class)` 的默认。
- **P1** 注册发现 + RPC（local 默认 + JDK 出站客户端 + LoadBalancer + trace 头注入）。
- **P2** 配置中心 + 热刷新（local 文件监听 + `ConfigRef` + `ConfigChangedEvent`）。
- **P3** 可观测性（Tracer + `ScopedValue`/MDC + W3C 传播 + `/metrics`）。
- **P4** 韧性（熔断/限流/重试 + 注解 + Advisor + RPC 集成）。
- **P5**（freeway-ext，另案）Nacos/Consul/etcd/K8s 适配器，`.primary()` 覆盖默认。

## 9. 风险 / 待确认
- **热刷新语义边界**：`ConfigRef` 是主动拉取，事件用于通知；需确认用户期望「字段自动变」还是「显式读最新」。本方案选显式，避免魔法。
- **静默回退陷阱**（§4.5）：需在文档与默认约定中明确，降低误用。
- **`@Marker` 仅静态**：运行时实例级路由务必走 `LoadBalancer`，不在 marker 上做文章。
- **AOP 仅接口→实现**：韧性 advisor 只作用于 `@bean` 接口服务；具体类服务与 RPC 出站在 client 层包装。

## 10. 不在本次范围
- `freeway-ext` 具体后端适配器（Nacos 等）——单独规划。
- 分布式事务、分布式锁、MQ bridge（已有 `EventBridge` 接缝，按需另开）。

## 11. 缺口与后续

### 11.1 已具备（非缺口，从待办划掉）
- **优雅关停**：`freeway.http.server.shutdown-grace` + `RuntimeHook.stop` + `AppStoppingEvent`（§3）已提供有序反注册与宽限，cloud 模块无需新建。
- HTTP 服务端、`ConfigLoader`/`SymbolProvider` 配置接缝、`EventBus`、`Advisor`/`AOP`、`@Marker`、MDC/ScopedValue 等均已在 core，直接复用，不重造。

### 11.2 K8s 之外还差什么（诚实清单）
本方案覆盖：注册发现/RPC（客户端+服务端）、配置热刷新、可观测性（trace+metrics）、韧性、服务间安全、K8s 探针拆分。典型云原生能力中**未覆盖**且本计划不打算做（按需另开）：
- 分布式事务（Seata 式）、分布式锁——协调型，复杂度高，超本模块。
- MQ / 事件桥接——`EventBus.setEventBridge` 已有接缝，适配器在 ext，本方案不内置。
- API 网关 / 限流网关——freeway-http 路由可近似，非完整网关。
- 分布式调度 / 任务协调、服务网格（sidecar/xDS）、Serverless/函数部署——基础设施层，不在框架内。
- **运行时金丝雀/蓝绿**（按实例 metadata 的精细流量切分）——`LoadBalancer` + `ServiceInstance.metadata` 可承载，但本方案未设计具体 canary 策略实现（仅留接口与 `@Marker`）。
- **追踪/指标后端导出**（OTel OTLP、Prometheus remote-write、Pushgateway、Datadog）——本地默认在 core，导出器进 ext。
- 服务目录 / OpenAPI 文档聚合（类 Spring Boot Admin）——可选，ext。

### 11.3 freeway-cloud（core）与 freeway-ext 的边界
**core（`freeway-cloud`，零依赖，本方案 P0–P4）**：所有抽象接口 + 本地/JDK 默认实现 + `@Marker` 后端/策略选择。

> **质量准则（关键修正）**：freeway 的一贯风格是**一站式开箱即用且足够优秀**——core 内置实现（如 `freeway-db` 的 `PoolDefault`、`freeway-commons` 的 `JsonCodec`/`JsonCoercions`，均经代码评审为生产级）是本方案的质量 bar，而非开发态桩。ext **不是**「真生产实现」的所在地，而是 freeway **选择不自建**的能力（通常需引入特定第三方服务/客户端，无法塞进零依赖 core）给出的**可选替代口子**——用户在 freeway 内置（已够好）与 ext 替代之间**选择**，并非内置不够。故 freeway-cloud 的 core 实现须达到同等质量：JDK `HttpClient` RPC、`JsonCodec` 序列化、`WatchService` 配置、自研熔断/限流/重试、自研 W3C 追踪、自研 metrics，均为**生产可用**实现，不是占位。
- `ServiceRegistryLocal` / `ServiceDiscoveryLocal`（正确的进程内/静态注册表，生产可用；单进程与静态拓扑场景直接可用，跨进程动态发现由 ext 后端提供）
- `CloudHttpClientJdk`（包 JDK `HttpClient`，生产级传输）
- `CloudConfigLocal`（WatchService 文件热重载，生产可用的文件型配置中心）
- `TracerLocal` / `MeterRegistryLocal`（生产可用的追踪/指标实现，暴露 `/metrics`）
- `CircuitBreakerDefault` / `RateLimiterDefault` / `RetryerDefault`（生产可用的韧性实现）
- `TransportSecurityNone` / `AuthPropagator` 默认（明文 / bearer 传递；生产安全由 ext 覆盖）

**ext（`freeway-ext`，独立仓库，P5 及以后）**：具体后端适配器，每个 `.primary()` 覆盖对应默认，**是可选替代而非补完**。
- Nacos：`ServiceRegistryNacos` / `CloudConfigNacos`
- Consul：`ServiceRegistryConsul` / `CloudConfigConsul`
- etcd：`ServiceRegistryEtcd`
- Kubernetes：`ServiceRegistryKubernetes`（watch Service/Endpoints）、`CloudConfigKubernetes`（ConfigMap/Secret）
- 可观测导出：OTel `TracerOtel` / `MeterRegistryOtel`（OTLP / Prometheus remote-write）
- 密钥：Vault `SecretSourceVault`
- 传输：`CloudHttpClientWebClient` / `CloudHttpClientGrpc`、密钥库证书加载（`@Mtls` 实现）
- 安全 token 校验：JWT / OAuth2 introspection 的 `@Jwt` / `@Opaque` 实现

**边界原则（沿用 CLAUDE.md）**：core 只放零依赖抽象与本地/JDK 默认（且须生产级）；任何第三方库（Nacos/Consul/etcd/K8s client/OTel/Vault/WebClient/gRPC）进 ext，不破坏 core 零依赖。ext 是上述能力的可选替代，而非 core 的「补完」。

## 12. 依赖选型

### 12.1 总约束
`CLAUDE.md` 硬规定：**core 模块除 SLF4J 外零外部依赖**。故 `freeway-cloud`（core）只能依赖 JDK 标准库 + SLF4J + 兄弟核心模块的现有能力。任何第三方库进 `freeway-ext`。

### 12.2 core 引入的依赖（均零外部依赖）
- **JDK 25** 标准库：虚拟线程、`ScopedValue`、`java.net.http.HttpClient`、`SSLContext`/`KeyStore`、`WatchService`、`ServiceLoader`、`java.lang.reflect`/`MethodHandle`、`UUID`。
- **SLF4J API**：项目全局唯一允许的外部依赖。
- **兄弟核心模块**（compile scope，非外部依赖）：`freeway-commons`（`JsonCodec`/`JsonCoercions`/`Coercer`/`Defer`/`ScopedCache`）、`freeway-ioc`（`Container`/`Binder`/`MarkerIndex`/`Advisor`/`EventBus`）、`freeway-boot`（`AppConfig`/`ConfigLoader`/`SymbolProvider`）、`freeway-http`（`Route`/`HttpFilter`/`HealthCheck`/`HttpContext`）。RPC 服务端发布依赖 http。

### 12.3 「用 JDK/自研 而非 库」的选型（均为生产级实现，非占位）
| 常见库 | core 内替代 | 说明 |
|---|---|---|
| Apache/OkHttp/WebClient | **JDK `HttpClient`** | 零依赖、虚拟线程友好、HTTP/2 |
| Jackson/Gson | **freeway `JsonCodec`** | 经代码评审为生产级（bean/record/泛型/java.time/UUID/Optional/循环检测），RPC 序列化直接用它，不需 Jackson |
| Micrometer | **自研 `MeterRegistry` + 手写 Prometheus 文本** | 暴露 `/metrics` 文本格式极简，生产可用 |
| OpenTelemetry | **自研 traceId/spanId + 手写 W3C `traceparent`** | 头格式简单可手写，生产可用 |
| Resilience4j | **自研 熔断/限流/重试** | 算法不复杂，生产可用；ext 可给 Resilience4j 替代 |
| Consul/Nacos/etcd client | **自研 进程内/静态注册表 + 文件监听** | 单进程/静态拓扑生产可用；跨进程动态后端进 ext |
| BouncyCastle | **JDK `SSLContext`** | mTLS 用 JDK 原生；BC 进 ext |

> 质量基准参照：`freeway-db` 的 `PoolDefault`（信号量容量、虚拟线程清理、泄漏检测、四阶段优雅关停、`DatabaseStats`）与 `freeway-commons` 的 `JsonCodec`/`JsonCoercions`（完整泛型、record/bean、循环检测）。freeway-cloud 的 core 实现须达到同等水准。

### 12.4 ext 才引入的库（按能力，均为可选替代）
Nacos/Consul/etcd/K8s client、OTel SDK（OTLP/Prometheus remote-write）、Vault client、WebClient/gRPC、Resilience4j、BouncyCastle（密钥库证书）、Jackson（若 ext 适配器需更强序列化）。每个库只在对应 ext 模块出现。

### 12.5 待确认
- freeway-cloud 依赖 freeway-http（RPC 服务端发布必需）是否可接受（sub-ModuleEx 仍可只装 ConfigModule，但 Maven 依赖层面 http 在）。
- 测试依赖：建议不加新测试库，用项目既有 JUnit 5.12 + freeway-http `WebServer` 测 RPC。
