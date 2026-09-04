# freeway-cloud 增量结构审计(2026-09-04)

**范围**:`freeway-cloud`(87 主文件 / 6849 行)及相关依赖面,三维度:结构合理性一致性 / 实现简洁性 / 命名合理性。
**方法**:4 路并行只读审计(跨模块一致性 / rpc+resilience / events+discovery+health / secret+storage+context+observe+internal),全部条目带 file:line 亲自核实,未运行构建。
**基线**:HEAD `2ce5263`(v1.5.0)。对照前两轮审计(09-03 正确性 / 09-04 结构)与期间演进(`c5ca164` *Default 迁出与结构整合、`2086848` ConfigSpec→SymbolSpec 迁移、CLASS 通道 deny-by-default 等),每条标注 **「09-04 遗留」** 或 **「新演进引入」**。

---

## 一、落地质量总评(09-04 八项建议的状态)

| 09-04 建议 | 状态 | 核实 |
|---|---|---|
| S1/S2 `*Default` 迁出 internal | ✅ cloud 侧收口 | internal 26→14 文件,12 个 `*Default` 与接口同住特性包;**ioc/db 未同步**(见 P2-2) |
| S4 抽 `PeerAddress` | ✅ 真收敛 | PeerConnector 461→420 行,地址解析全量归位,渲染点唯一(PeerAddress.toUri) |
| S5 握手校验拆分 | ✅ 真收敛 | `validateHello`+`HelloAdmission` 静态纯函数 + 独立测试;**但会话状态机仍缺**(见 P1-1) |
| S6 Wiring 收口 | ✅ 大部 | 构造器 3→2,编排循环抽 `ResiliencePolicy`;Wiring 仅 2 个 withers(见 P3) |
| C1 SymbolSpec 化 | 🟡 半程 | events/AuthPropagator 已收口;rpc TLS 4 键、HttpServiceDeclaration 仍裸(见 P2-3) |
| C2 splitAndTrim | ✅ | `ConfigLists` 落地,cloud main 零裸 `split(",")` |
| C3 boot test scope | ✅ | 已改;主代码引用 0 处 |
| C4 `resolve(SymbolSpec)` | ✅ | ioc `SymbolSource:80` 默认方法,全量采用 |

另确认收口:shard map 有界驱逐(CloudHttpClientDefault:90-96,305-308)、同线程乱序 close 回归、CLASS deny-by-default + 启动 WARN、16 MiB 上限、握手/退避超时可配。

**干净面(4 路一致确认)**:`ConfigSpec` 在 Java 代码零残留;跨模块 internal 泄漏双向为零;cloud→boot 主代码引用 0;`DefaultX` 全仓零;命名规范(XDefault 位置/Impl 语义)云侧全面遵从。

---

## 二、P1 建议修

### P1-1【09-04 遗留】握手会话状态机缺失:token 门可被跳过(安全)
`PeerHub.ServerSessionHandler.onText`(PeerHub.java:246-266)不要求 hello 先行——任何能建立 WS 的客户端(subprotocol 协商可选,WebSocketUpgrade.java:69-81;无需 token)直接发 `specversion` 帧即进入 `receive()`(PeerHub.java:328-366)。token 校验只存在于 hello 帧路径(`validateHello`:442-451)。配了 token 的节点上,攻击者跳过 hello 即绕过门锁;`allowed-topics` 默认空 = 任意 topic 注入本地 EventBus。客户端腿对称可达(PeerConnector.java:287-299)。文档"首帧即 hello"(design §2.1)与代码不符。
**修法**:hello 先行 + 每会话仅一次 hello 的会话状态机;握手完成前收到 CE 帧 → close(1002)/abort。无任何测试覆盖。

### P1-2【09-04 遗留】重连抑制挂错动作 → peer 永久失联
`suppressReconnect`(PeerConnector.java:226)语义本属于"去重裁决失败(孪生连接存活)"场景,但 `CloudEventSink` 发送失败分支(CloudEventSink.java:82-88)unregister + `peer.close()` 走同一个 closer(PeerConnector.java:347-356,置 suppress+abort)。回调竞争落败时 `handleDisconnect`(:376-388)不再拨号、dialLoop 已退出、`setPeers` 幂等跳过 → 该 peer 静默永久失联直到进程重启(分区恢复场景可致 mesh 双向分裂)。与 sink javadoc"reconnect is the connector's job"矛盾。
**修法**:抑制与否取决于"该 origin 是否仍有存活注册连接"(查 hub),而非挂在传输 close 动作上。

### P1-3【09-04 遗留,三轮未对齐】service-id 默认链分叉(行为级)
`HttpServiceDeclaration.java:49-50`:registry service-id = `REGISTRY_SERVICE_ID → freeway.app.name → "freeway-app"`(两级回退);`CloudEventLifecycleHook.java:39-40`(events mesh 身份):= `REGISTRY_SERVICE_ID → "freeway-app"`(**无 freeway.app.name 回退**)。仅配置 `freeway.app.name` 时,注册身份与网格事件源身份**分叉为两个名字**。
**修法**:hook 端补 freeway.app.name 回退,或抽共享回退 helper(A/D 两路独立发现同一问题)。

### P1-4【新演进引入】RemoteCaller 消毒不对称 → 日志伪造面
`RemoteCaller.businessException`(RemoteCaller.java:141-160):`decode(exClass)` 后未消毒即拼入外层消息(:148);传给 `RemoteInvocationException`(:151)的 message 是原样 decoded 值,RIE 构造器(RemoteInvocationException.java:15-19)将其拼进自身消息。打印整个异常链时,含 CRLF 的对端 `X-RPC-Exception`/`X-RPC-Message` 可经 **`Caused by` 层**绕过 `sanitizePeerText`(:167-170 注释明言"防伪造日志行")直达日志。服务端写出侧已消毒(09-03 P2-1 修复),消费侧是半修复。
**修法**(~3 行):decode 后对 exClass 与 message 统一消毒一次,消毒值同时喂外层与 RIE;或下沉到 RIE 构造器。需 1 条回归(伪造含 CRLF 的异常头,断言日志净)。

### P1-5【新演进引入】默认值多 locus 无共享源
- RPC 超时双写:`CloudRpcModule.java:43/45` spec 字面量 10_000/3_000 ↔ `CloudHttpClientDefault.java:125-126` Wiring 默认 10s/3s(本轮引入第二 locus)
- PeerConnector 网络常量:`events/PeerConnector.java:31-35`(BACKOFF_BASE_MS/HANDSHAKE_TIMEOUT…)与 `CloudConfigKeys.java:96-105` EVENTS_*_DEFAULT 同值同语义、零链接
两组均靠 "mirror" 注释维系,绕过项目自己在 resilience 键立的防漂移规矩(CloudConfigKeys:52-57 注释)。

---

## 三、P2 可改

| # | 项 | 归属 | 要点 |
|---|---|---|---|
| P2-1 | import 机械残留清理 | 新演进 | **38 处同包 self-import(19 文件)** + 4 处死 import(`CloudResilienceModule:16`、`InvocationContext:5`、`TracerDefault:12`、`CloudConfigKeys:2`);后者构成类级编译环,删两行即破(A/B/D 三路独立确认) |
| P2-2 | ioc/db `*Default` 放置与规则冲突 | 09-04 S2 遗留 | CLAUDE.md 本轮已写 "XDefault is not internal",但 ioc/internal 仍有 LoggerSourceDefault/SymbolSourceDefault/ProxyFactoryDefault、db/internal 仍有 PoolDefault(+PooledConnectionDefault)。要么迁出,要么在规则里加"包私有默认实现可留 internal"豁免——**项目级决策** |
| P2-3 | C1 收尾:字面量默认 → spec | 09-04 遗留 | rpc TLS 4 键(CloudRpcModule:54,58-60,`""`)、HttpServiceDeclaration:56/57、CloudStorageModule:35(`"cloud-storage"`)——违反 CloudConfigKeys 自家 "canonical defaults beside keys" 声明 |
| P2-4 | 握手线协议双份手拼 | 09-04 遗留 | subprotocol 字面量双处(PeerHub:231/PeerConnector:185);hello/ack 各侧 LinkedHashMap 手拼;hello 携 serviceId(PeerConnector:258)但服务端忽略、ack 不携——**线上字段发而不读,双向不对称**,疑似半截设计;建议包内共享握手 codec |
| P2-5 | `discovery.Health` 归位 | 09-04 遗留 | public 包 public record,唯一消费者 internal/RegistryStore;`starting()`/`isStale()` 全仓零调用,本地后端 live 轴写死。收进 internal 或裁剪,二选一 |
| P2-6 | health 端点双轨 + 文档漂移 | 09-04 遗留 | http 默认 `/healthz` 与 cloud `/health/live`(硬编码)语义重复互不知晓(配同 path 会互抢);design doc:374 "路径可配"与实现不符;`/health/ready` 不在任何配置文档。文档收口或端点对齐 |
| P2-7 | Wiring 补 withers | 新演进 | 9 字段 record 仅 withTracer/withMetrics;直构调用方被迫写 9 参构造器,telescoping 从构造器平移进 record 构造器 |
| P2-8 | CloudRequest.post/bodyAs 零使用 | 新演进 | `post(path,body,ct)`/`post(path,json)`(CloudRequest:33-40)与 `bodyAs(Class,codec)`(:26-29)main+test 零调用;RemoteCaller 无法用(需带版本头)。补用法示例使其成真 API,或删除 |
| P2-9 | 测试包滞后 | 新演进 | ObserveTest/ResilienceTest/SecretStoreDefaultTest/CloudPerformanceTest 仍在 `.cloud.internal` 测试包,被测类已迁出;随 P2-1 一并搬 |

---

## 四、P3 观察(择要)

- **storage metadata 契约**:`ObjectStorageDefault.put` 静默忽略 ObjectMetadata(:77-120 未读),etag 双语义(PutResult SHA-256 vs ObjectEntry size+mtime)——两轮遗留,收敛需小设计决策
- **TracerDefault 跨线程 close**:restoreThreadState(:170-189)只在关闭线程清 ambient/MDC,owner 线程残留、池线程可能吞入他人 baseAmbient;javadoc(:99-104)宣称过度,契约本限 same-scope——建议 javadoc 降级或跨线程 close 仅清栈+WARN
- **文档漂移(收口批次)**:`freeway-cloud-unified-design.md` 仍称全部 XDefault 在 internal/(:33,168,224,252,337)、仍提已删除的 ContextExecutor(:134,325-326)、`loadBalancer.choose` 签名过时(:258);`application.properties.sample:301` 与 freeway-config.md 样例用错键拼法 `trace-enabled`(真实键 `freeway.cloud.rpc.trace.enabled`,照抄静默无效)
- **埋点不对称(by-design)**:入站 RPC/events 零 observe 引用,与设计文档"被调方自建根 span"一致——建议 CloudObserveModule javadoc 写明,避免误读为缺口
- **CloudContextModule.java:22-28** 顺序约束注释与实现(槽位合并,顺序无关)不符
- 16 MiB 上限计数单位不一致:服务端按字节、TextMessageAssembler 按字符
- `.upload-*.tmp` 落 bucket 目录,list() 瞬时可见、崩溃不清理
- MetricsDefault 的 counterValue/timerCount/… 与 public TimerData 仅测试使用
- 命名/杂项:CloudConfigKeys 2 行 CJK 注释(全仓唯一);events `-ms` vs RPC 无单位键名;`@Local` @Target 含 PARAMETER/FIELD 但实际仅作 marker token;PeerConnector 构造器 connectTimeout 无 null/≤0 防御(兄弟参数均有);PeerHub/SecretSymbolSource FQCN 内联与 import 混用(~15 处)

---

## 五、前 10 高价值项(跨路汇总排序)

1. **P1-1 握手状态机**(安全门绕过;两轮审计漏网)
2. **P1-2 suppressReconnect 错挂**(永久失联;mesh 分裂)
3. **P1-4 消毒不对称**(日志伪造;~3 行修)
4. **P1-3 service-id 默认链分叉**(身份分裂;3 路独立发现)
5. **P2-1 import 机械残留**(38+4 处、19 文件;含类级编译环)
6. **P1-5 默认值双 locus**(防漂移规矩被绕过)
7. **P2-6 health 双轨 + 文档**(端点互抢陷阱)
8. **P2-4 握手 codec 收敛**(serviceId 发而不读)
9. **P2-2 ioc/db *Default 口径**(项目级决策)
10. **P2-5 Health 归位 + P2-8 API 面整形**(1.5.0 尚可改时定夺)

---

## 六、测试缺口(与 P1 一一对应)

- 无 pre-hello CE 帧用例、无二次 hello 用例(P1-1)
- 无"发送失败丢弃后重拨"用例(P1-2)
- 无伪造含 CRLF 的 X-RPC-Exception 头断言日志净的用例(P1-4)
- 无"仅配 freeway.app.name"时注册/网格身份一致性用例(P1-3)

**已核对无问题(勿重复审查)**:register 去重唯一实现、事件不回桥 + fworigin 自源丢弃、TextMessageAssembler 无职责重叠(仅 JDK 客户端腿需要)、internal 双向零泄漏、PeerHub 非第二个 EventBus(无订阅者不派发,入站经 EventBusInbound 窄面)。
