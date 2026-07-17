# freeway-cloud 实施任务清单

> 目标：按照 `freeway-cloud` 完整设计，分阶段落地一套可运行、可测试、可扩展的云原生基础层。

## 阶段划分

### Phase 0 - 脚手架

目标是让模块结构先成立。

任务：

- 新建 `freeway-cloud` Maven 模块
- 在父 POM 中加入模块
- 建立基础包结构：
  - `context`
  - `config`
  - `discovery`
  - `rpc`
  - `observe`
  - `resilience`
  - `security`
  - `health`
  - `storage`
  - `internal`
- 建立 `CloudModule`
- 建立 `CloudConfigKeys`
- 建立各子系统 `*Module`

验收：

- 模块能编译
- `CloudModule` 能被安装
- 每个子模块都能独立安装

### Phase 1 - 核心对象与本地默认实现

目标是让 `freeway-cloud` 脱离云后也能工作。

任务：

- 定义 `ServiceId`
- 定义 `Endpoint`
- 定义 `ServiceInstance`
- 定义 `InvocationContext`
- 定义 `CloudConfig`
- 实现本地默认版：
  - `ServiceRegistryLocal`
  - `ServiceDiscoveryLocal`
  - `LocalConfigStore`
  - `LocalSecretStore`
  - `CloudHttpClientJdk`
  - `TracerNoop` 或等价默认追踪器
  - `MeterRegistryInMemory`
- 为所有核心接口绑定 `.primary()`

验收：

- 本地环境无需任何外部云组件即可运行
- 默认实现能通过 IoC 解析

### Phase 2 - 注册发现与 RPC

目标是建立最小可用的服务间调用链。

任务：

- 实现 `ServiceRegistry`
- 实现 `ServiceDiscovery`
- 实现 `LoadBalancer`
- 实现 `CloudHttpClient`
- 实现 `@CloudClient`
- 实现 `CloudExporter`
- 实现服务注册 / 注销的 `RuntimeHook`
- 实现调用上下文传播的入站/出站接线

验收：

- 服务可注册、发现、注销
- 声明式客户端可调用服务端导出接口
- 重试时会重新选择实例
- RPC 调用包含超时与错误封装

### Phase 3 - 配置中心

目标是提供运行期可刷新配置。

任务：

- 实现 `ConfigStore`
- 实现 `ConfigRef<T>`
- 实现 `ConfigStoreLoader`
- 实现 `ConfigChangedEvent`
- 让配置变更通过 `EventBus` 发布
- 让动态配置能参与 `SymbolProvider` 解析

验收：

- 配置变更能够被监听
- `ConfigRef` 读取到最新值
- 启动期 `AppConfig` 不被破坏

### Phase 4 - 可观测性

目标是把追踪和指标串起来。

任务：

- 实现 `Tracer`
- 实现 `TraceContext`
- 实现 `Propagator`
- 实现 MDC 关联
- 实现 `MeterRegistry`
- 导出 `/metrics`
- 支持 traceparent 传播

验收：

- 入站/出站都能拿到 trace 上下文
- 日志能关联 traceId
- `/metrics` 可输出

### Phase 5 - 韧性

目标是让 RPC 和本地服务调用具备基本防护。

任务：

- 实现 `Retryer`
- 实现 `CircuitBreaker`
- 实现 `RateLimiter`
- 建立默认策略
- 让 RPC 出站统一套用韧性策略

验收：

- 超时、重试、熔断、限流有明确行为
- 策略可替换

### Phase 6 - 安全

目标是完成服务间安全能力。

任务：

- 实现 `PrincipalContext`
- 实现 token / principal 传播
- 实现 mTLS 的抽象
- 实现密钥源抽象
- 建立 `SecurityFilter`

验收：

- 服务间身份可以传播
- 安全能力可选装
- 无安全模块时默认拒绝 `/rpc/*`

### Phase 7 - 健康检查

目标是完成云原生探针。

任务：

- 拆分 `/health/live`
- 拆分 `/health/ready`
- 将 readiness 与注册中心、关键依赖绑定
- 将注销顺序与关停流程绑定

验收：

- live 与 ready 语义分离
- 关停时先注销后停服

### Phase 8 - 对象存储

目标是补齐可选云能力。

任务：

- 实现 `ObjectStorage`
- 实现 `ObjectMetadata`
- 实现 `ObjectEntry`
- 实现 `PutResult`
- 实现本地文件系统默认版

验收：

- 本地文件系统实现可用
- 作为可选模块不影响核心链路

## 推荐优先级

如果只做最小可用版本，优先顺序应为：

1. Phase 0
2. Phase 1
3. Phase 2
4. Phase 3
5. Phase 4
6. Phase 5
7. Phase 6
8. Phase 7
9. Phase 8

## 关键测试清单

每个阶段都应该补相应回归测试：

- 模块装配测试
- 默认实现解析测试
- 注册 / 发现 / 注销测试
- RPC 成功 / 超时 / 失败 / 重试测试
- 配置变更测试
- Trace 传播测试
- 指标输出测试
- 熔断 / 限流 / 重试测试
- 安全传播测试
- live / ready 探针测试
- 对象存储读写测试

## 交付标准

当且仅当以下条件都满足时，才算 `freeway-cloud` 第一版可交付：

- 可以在无第三方云 SDK 的情况下运行
- 核心抽象有本地默认实现
- RPC、配置、观测、韧性、健康都能独立工作
- `freeway-ext` 只负责第三方适配和增强，不影响 core 的可运行性和默认语义
- 所有能力都有回归测试覆盖

落地时的判断标准也固定为：

- 能在 core 内闭环的能力，优先在 core 内完成
- 依赖特定云平台或基础设施的能力，进入 `freeway-ext`
- 入口流量治理、集群调度、证书平台等，默认不纳入 core
