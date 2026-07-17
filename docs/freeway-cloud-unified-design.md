# freeway-cloud 完整设计

> 目标：为 Freeway 增加云原生能力，但不引入 Spring Cloud 式的魔法层，不破坏 Freeway 现有的显式装配、零扫描、core/ext 分层、默认实现优先、Marker/primary 选择、RuntimeHook 生命周期这些设计原则。

## 1. 总原则

freeway-cloud 采用以下确定性原则：

- `freeway-cloud` 是核心模块，保持零第三方依赖，除 SLF4J 外不引入额外库。
- 所有云厂商 SDK、K8s Client、Otel SDK、S3 SDK、Secrets SDK 等都放到 `freeway-ext`。
- 所有能力都提供本地默认实现，脱离云环境也能完整运行。
- 模块采用“接口在 core、实现隔离在 internal、适配器在 ext、IoC 接线集中在 Module”的结构。
- 不做类路径扫描，不做字节码织入，不做透明远程 bean。
- `.primary()` 用来指定默认实现，`@Marker` 用来选择变体和后端。
- `RuntimeHook` 负责生命周期，服务注册与注销必须显式、可排序、可测试。

## 2. 设计结论

freeway-cloud 不按“能力清单”组织，而按“内核对象 + 派生能力”组织。

### 2.1 核心对象

只保留 5 个核心对象：

- `ServiceId`
- `ServiceInstance`
- `Endpoint`
- `InvocationContext`
- `CloudConfig`

它们的职责分别是：

- `ServiceId`：标识“是谁”
- `ServiceInstance`：描述“有哪些可用实例”
- `Endpoint`：描述“怎么到达”
- `InvocationContext`：描述“跨边界传播什么”
- `CloudConfig`：描述“运行时配置从哪里来”

### 2.2 能力分层

能力层按下面顺序展开：

1. 注册发现
2. RPC
3. 配置中心
4. 可观测性
5. 韧性
6. 安全
7. 健康检查
8. 对象存储

其中对象存储是可选能力，不属于云内核必选项。

## 3. 模块结构

建议模块结构如下：

```text
freeway-cloud
├── CloudConfigKeys.java
├── CloudModule.java
├── annotation/
├── context/
├── config/
├── discovery/
├── rpc/
├── observe/
├── resilience/
├── security/
├── health/
├── storage/
└── internal/
```

### 3.1 包职责

- `annotation/`
  - 后端选择、策略选择、能力标记
- `context/`
  - `InvocationContext`、传播器、入站/出站上下文绑定
- `config/`
  - 动态配置、热刷新、配置引用
- `discovery/`
  - 注册、发现、实例列表、负载均衡
- `rpc/`
  - 出站 HTTP RPC、声明式客户端、服务端导出
- `observe/`
  - 追踪、指标、日志关联
- `resilience/`
  - 重试、熔断、限流
- `security/`
  - mTLS、身份传播、密钥源
- `health/`
  - live / ready 探针
- `storage/`
  - 对象存储
- `internal/`
  - 本地默认实现和内部细节

### 3.2 装配方式

采用两个层次：

- `CloudModule` 作为总模块，负责聚合安装各子模块
- 每个子系统一个 `*Module`，只负责本子系统的 IoC 接线

示意：

```java
@Marker(Builtin.class)
public final class CloudModule implements ModuleEx {
    @Override
    public void bind(Binder b) {
        b.install(new CloudContextModule());
        b.install(new CloudConfigModule());
        b.install(new CloudDiscoveryModule());
        b.install(new CloudRpcModule());
        b.install(new CloudObserveModule());
        b.install(new CloudResilienceModule());
        b.install(new CloudSecurityModule());
        b.install(new CloudHealthModule());
        b.install(new CloudStorageModule());
    }
}
```

## 4. 核心模型

### 4.1 ServiceId

`ServiceId` 是统一服务身份。它同时用于：

- 本地容器内的服务标识
- 远程服务发现的逻辑服务名
- 声明式客户端 `@CloudClient("orders")` 的定位键

它只做身份，不做实例选择，不做网络定位。

### 4.2 Endpoint

`Endpoint` 是结构化定位器，不用裸字符串拼接。

字段固定为：

- `scheme`
- `host`
- `port`
- `basePath`

它可以表达：

- `http://host:port`
- `https://host:port`
- DNS 名
- K8s Service FQDN
- sidecar / mesh 入口

### 4.3 ServiceInstance

`ServiceInstance` 由以下部分构成：

- `serviceId`
- `instanceId`
- `endpoint`
- `metadata`

约束如下：

- `serviceId` 代表逻辑服务
- `instanceId` 代表实例稳定身份
- `endpoint` 代表位置
- `metadata` 代表可变属性

必须明确禁止把健康态塞进 `ServiceInstance`。
健康态单独由健康模型维护。

### 4.4 InvocationContext

`InvocationContext` 是跨边界传播载体，但不是万能上下文。

只包含三类内容：

- `TraceContext`
- `PrincipalContext`
- `Baggage`

它不承载：

- 业务数据
- 配置快照
- 分支上下文
- 对象缓存

传播方式统一由 `Propagator` 处理。

### 4.5 CloudConfig

`CloudConfig` 是动态配置接口。

它与 `AppConfig` 的关系明确：

- `AppConfig`：启动期不可变快照
- `CloudConfig`：运行期动态配置源

需要热刷新的地方通过 `ConfigRef<T>` 读取。

## 5. 具体能力设计

### 5.1 注册发现

注册发现拆成两个接口：

- `ServiceRegistry`
- `ServiceDiscovery`

职责固定为：

- `ServiceRegistry` 负责 register / deregister / renew
- `ServiceDiscovery` 负责 query / subscribe / refresh

实例选择由 `LoadBalancer` 负责，不放进发现接口。

#### 默认实现

- `ServiceRegistryLocal`
- `ServiceDiscoveryLocal`

本地默认实现使用内存注册表或本地配置，保证脱离云后仍可运行。

#### 负载均衡

`LoadBalancer` 是策略接口。

内置策略包括：

- round robin
- random
- weighted
- zone aware

策略使用 `@Marker` 选择默认实现，运行时参数从 `ServiceInstance.metadata` 读取。

#### 职责边界

`freeway-cloud` 的负载均衡只负责 **RPC 出站调用前的实例选择**，不负责集群入口流量调度。

- `ServiceDiscovery` 提供候选实例集合
- `LoadBalancer` 从候选实例中选出一个目标实例
- `CloudHttpClient` 或其他传输层把请求发到选中的实例

K8s `Service`、Ingress、网关、四层/七层转发属于运行时基础设施能力，不归 `freeway-cloud` 内核负责。

### 5.2 RPC

RPC 分三层：

1. `CloudHttpClient`
2. `@CloudClient`
3. `CloudExporter`

#### CloudHttpClient

`CloudHttpClient` 是底层出站客户端，基于 JDK `HttpClient` 实现，负责：

- 选实例
- 拼接 endpoint
- 注入传播上下文
- 套用重试、熔断、限流
- 处理超时
- 解析响应

#### @CloudClient

`@CloudClient("orders")` 是声明式客户端，只作用于接口。

调用链固定为：

接口方法 -> 参数绑定 -> 构造 `RpcRequest` -> `CloudHttpClient.call(...)`

不做透明远程 bean，不把远程调用伪装成本地注入。

#### CloudExporter

`CloudExporter` 负责把本地服务导出为可远程访问的 HTTP 路由。

其行为固定为：

- 显式发布
- 显式路由
- 显式注册
- 显式反注册

服务端路由以服务名和方法名构成，不扫描类路径。

### 5.3 配置中心

配置中心由以下部分组成：

- `CloudConfig`
- `ConfigStore`
- `ConfigRef<T>`
- `ConfigChangedEvent`
- `ConfigStoreLoader`

固定设计如下：

- 本地默认实现支持文件或 classpath 读取
- 热刷新通过 `ConfigRef<T>` 生效
- 配置变更通过 `EventBus` 发布
- `AppConfig` 不变，动态配置不反向污染启动快照

### 5.4 可观测性

可观测性由两个主对象构成：

- `Tracer`
- `MeterRegistry`

#### Tracer

追踪使用 `InvocationContext` 传播：

- 入站提取 trace 信息
- 出站注入 trace 信息
- 日志通过 MDC 关联 traceId/spanId

#### MeterRegistry

指标采用内存默认实现，统一暴露：

- counter
- timer
- gauge

通过 HTTP 路由导出 `/metrics`。

### 5.5 韧性

韧性能力由三个策略接口组成：

- `Retryer`
- `CircuitBreaker`
- `RateLimiter`

这些策略既可以：

- 在 `CloudHttpClient` 里对 RPC 出站统一生效
- 也可以通过 `Advisor` 织入到本地接口调用中

默认优先在 RPC 层生效，因为这是最稳定、最容易落地的路径。

### 5.6 安全

安全能力分三层：

- 传输加密：mTLS
- 身份传播：principal / token
- 密钥源：本地文件 / 云后端

设计边界固定如下：

- 做服务间安全
- 不做应用级登录系统
- 传播已验证身份，不传播原始凭据

### 5.7 健康检查

健康检查拆成两个端点：

- `/health/live`
- `/health/ready`

`live` 表示进程存活，`ready` 表示可以接流量。

注册中心注销顺序必须在 HTTP 关停之前完成。

### 5.8 对象存储

对象存储作为独立可选能力提供：

- `ObjectStorage`
- `ObjectMetadata`
- `ObjectEntry`
- `PutResult`

默认实现使用本地文件系统。

它与 discovery / rpc / config / observe / resilience 的主链路解耦。

## 6. Marker / Primary / RuntimeHook 的明确用法

### 6.1 `.primary()`

`.primary()` 用于指定默认实现。

典型场景：

- `LocalConfigStore`
- `LocalSecretStore`
- `ServiceDiscoveryLocal`
- `CloudHttpClientJdk`

### 6.2 `@Marker`

`@Marker` 用于选择变体和后端，不用于运行时实例属性。

适用场景：

- `@Local`
- `@Nacos`
- `@Consul`
- `@Kubernetes`
- `@Http`
- `@Grpc`
- `@RoundRobin`
- `@Weighted`

### 6.3 `RuntimeHook`

`RuntimeHook` 只负责生命周期动作。

用于：

- 启动时注册
- 停止时反注册
- 连接建立和释放
- health ready/live 相关状态切换

## 7. 启动与关停顺序

### 7.1 启动顺序

1. 初始化 `CloudConfig`
2. 初始化 `SecretStore`
3. 初始化 `ServiceDiscovery` / `ServiceRegistry`
4. 初始化 `Tracer` / `MeterRegistry`
5. 初始化 `CloudHttpClient`
6. 启动 HTTP 服务
7. HTTP 绑定地址可用后执行服务注册

### 7.2 关停顺序

1. 先拒绝新流量
2. 先注销服务注册
3. 再关闭 HTTP 服务
4. 再关闭云相关连接

这是固定顺序，不作为可选建议。

## 8. 与 Freeway 既有设计的对齐

freeway-cloud 必须沿用 Freeway 的既有理念：

- 显式装配，而不是扫描
- 默认实现优先，而不是强制外部依赖
- core/ext 分层，而不是把第三方 SDK 塞进核心
- 小而直接的 API，而不是一层层代理语法糖
- `ModuleEx` + `Binder` + `RuntimeHook`，而不是另起一套云框架体系

这意味着 freeway-cloud 不是“再造一个云平台”，而是把 Freeway 的模块化能力延伸到进程边界。

## 边界与扩展原则

freeway-cloud 的核心原则是先保证自己能独立运行，再把云原生增强作为可插拔能力提供。

- core 必须可独立运行，不依赖任何第三方云 SDK
- 默认实现必须覆盖本地、单机、测试环境的基础闭环
- 云原生能力的基础语义要在 core 中成立，而不是由 ext 临时补出来
- `freeway-ext` 只负责第三方适配、云厂商后端和增强能力
- 任何只属于特定云平台或特定基础设施的能力，都优先放进 `freeway-ext`
- 任何影响 core 可运行性的能力，都必须有可脱离外部依赖的 fallback

这意味着 `freeway-cloud` 是 Freeway 的云原生内核延伸，`freeway-ext` 是把 core 不做的和 core 的增强方向接到具体云环境里的适配层。

## 三层职责对照

| 层级 | 负责什么 | 不负责什么 |
|---|---|---|
| `freeway-cloud core` | 服务发现、RPC、配置、观测、韧性、安全、健康等通用云原生语义；本地默认实现；无第三方云 SDK 的可独立运行闭环 | 具体云厂商 API；K8s/网关/Ingress 入口调度；只能靠外部基础设施才成立的能力 |
| `freeway-ext` | 云厂商适配、K8s/注册中心/可观测/存储/密钥等后端接入；高性能或特定场景增强实现 | 改写 core 语义；把特定云平台能力强行塞进 core；破坏默认实现可用性 |
| 基础设施 / 平台 | 集群入口流量调度、Service/Ingress、DNS、网关、证书分发、基础监控平台 | 框架内核语义；业务层 RPC 协议；Freeway 的模块装配 |

## 9. 明确不做的事

以下设计不纳入核心方案：

- 透明远程 bean
- classpath 自动扫描注册
- 业务数据进入 `InvocationContext`
- 实例属性进入 `@Marker`
- 第三方 SDK 进入 core
- 所有云能力塞进单一大接口

## 10. 落地顺序

### Phase 1

- 核心对象
- 本地默认实现
- `CloudModule`
- `ServiceRegistry` / `ServiceDiscovery`
- `CloudHttpClient`
- `@CloudClient`
- `CloudConfig`
- `ConfigRef`

### Phase 2

- `InvocationContext`
- trace / principal / baggage 传播
- `/metrics`
- `/health/live`
- `/health/ready`

### Phase 3

- 重试、熔断、限流
- 服务端导出
- 安全传播

### Phase 4

- `freeway-ext` 后端适配
- Nacos / Consul / Kubernetes / etcd / Otel / S3 / Secrets

## 11. 结语

这份方案的最终判断是：

- freeway-cloud 应该是 Freeway 边界原语的云边延伸
- 它要强调显式、可替换、可测试、可回退
- 它应该让用户“装上就能跑”，而不是“上来先理解一堆魔法”

如果实现时始终遵守这三条：

1. 核心对象少
2. 生命周期显式
3. 默认实现可用

那么 freeway-cloud 就能和 Freeway 现有体系保持一致，并且具备真正可落地的云原生能力。
