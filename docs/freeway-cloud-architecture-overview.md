# freeway-cloud 架构总览

## 目标

freeway-cloud 的目标不是复制一套 Spring Cloud，而是把 Freeway 现有的显式装配、零扫描、core/ext 分层、Marker/primary 选择、RuntimeHook 生命周期这些理念延伸到云原生边界。

## 一句话定义

freeway-cloud 是 Freeway 的云原生基础层，负责：

- 服务身份与实例定位
- 跨边界调用
- 动态配置
- 可观测性
- 韧性控制
- 服务间安全
- 健康检查
- 可选对象存储

## 核心对象

freeway-cloud 只围绕 5 个核心对象展开：

```text
ServiceId -> 逻辑服务身份
ServiceInstance -> 可用实例集合中的一员
Endpoint -> 结构化网络定位信息
InvocationContext -> 跨边界传播载体
CloudConfig -> 运行期动态配置源
```

职责边界固定如下：

- `ServiceId` 负责“是谁”
- `ServiceInstance` 负责“有哪些实例”
- `Endpoint` 负责“怎么到达”
- `InvocationContext` 负责“跨边界传什么”
- `CloudConfig` 负责“运行时配置从哪里来”

## 分层结构

```text
freeway-cloud
├── context      传播上下文
├── config       动态配置
├── discovery    注册发现与调用侧负载均衡
├── rpc          出站客户端与服务端导出
├── observe      追踪与指标
├── resilience   重试 / 熔断 / 限流
├── security     mTLS / 身份传播 / 密钥源
├── health       live / ready
├── storage      对象存储
└── internal     默认实现与内部细节
```

## 明确采用的设计

### 1. 零核心依赖

`freeway-cloud` 只依赖 JDK 标准库、SLF4J 和 Freeway 核心模块，不引入第三方云 SDK。

### 2. 显式装配

每个子系统一个 `*Module`，总模块 `CloudModule` 只负责聚合安装，不负责扫描。

### 3. 默认实现优先

所有抽象都必须提供可脱离云环境运行的本地默认实现，并以 `.primary()` 作为默认选择。

### 3.1 独立可运行

freeway-cloud 的 core 先保证“装上就能跑”，再考虑接入外部云平台。

- core 不依赖任何第三方云 SDK
- 云原生能力的基础语义必须在 core 中成立
- `freeway-ext` 只做第三方适配和增强，不定义 core 语义
- 任何特定云平台能力都应该通过 ext 插件化接入
- 任何影响 core 可运行性的能力都必须有 fallback

### 3.2 三层分工

| 层级 | 责任 | 典型内容 |
|---|---|---|
| `freeway-cloud core` | 框架内核能力 | `ServiceDiscovery`、`LoadBalancer`、`CloudHttpClient`、`CloudConfig`、`Tracer`、`RateLimiter`、`/health/*` |
| `freeway-ext` | 云后端与增强 | Nacos、Consul、Kubernetes、Otel、S3、Secrets、gRPC 等适配 |
| 基础设施 | 平台流量与运行环境 | K8s Service、Ingress、网关、DNS、证书系统、监控平台 |

### 4. `@Marker` 只做静态选择

`@Marker` 只用于实现、后端、策略选择，不用于运行时实例属性。

### 5. `RuntimeHook` 负责生命周期

注册、注销、连接初始化、连接释放都通过 `RuntimeHook` 显式管理。

### 6. 不做透明远程 bean

RPC 保持显式：

- `@CloudClient("orders")`
- `CloudHttpClient.call(...)`
- `CloudExporter.publish(...)`

## 能力映射

```text
发现 / 注册 -> ServiceRegistry / ServiceDiscovery / LoadBalancer
调用侧负载均衡只负责 RPC 出站实例选择，不负责 K8s Service / Ingress 入口调度
RPC          -> CloudHttpClient / @CloudClient / CloudExporter
配置中心      -> CloudConfig / ConfigRef / ConfigStore
可观测性      -> Tracer / MeterRegistry / Propagator
韧性          -> Retryer / CircuitBreaker / RateLimiter
安全          -> mTLS / Principal propagation / Secret source
健康检查      -> /health/live / /health/ready
对象存储      -> ObjectStorage
```

## 明确不做

- 透明远程 bean
- classpath 扫描式自动注册
- 把业务数据塞进 `InvocationContext`
- 把实例级属性塞进 `@Marker`
- 把第三方 SDK 直接放进 core
- 用“能力清单”代替统一的内核模型

## 设计判断

freeway-cloud 的正确形态是：

- 内核对象少
- 边界显式
- 默认实现完整
- 扩展实现下沉到 `freeway-ext`
- 用户可以只装需要的子模块

这会让它更像 Freeway 自己的云原生延伸，而不是另起一套云框架。
