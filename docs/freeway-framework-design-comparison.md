# Freeway / Solon / Spring 设计对比

> 排除了"功能全不全"、"生态大不大"、"用的人多不多"——只谈设计本身。

---

## 一、核心抽象数量对比

一个框架的核心抽象数量，是设计者克制力的直接度量。核心抽象是指：**你理解这个框架前必须搞懂的类型**。

### Spring

```
BeanFactory → BeanDefinition → BeanPostProcessor → ApplicationContext
→ Environment → PropertySource → ResourceLoader → MessageSource
→ ApplicationEvent → ApplicationListener → TransactionManager
→ ProxyFactory → Pointcut → Advisor → Joinpoint
```

粗算：**14-16 个核心抽象**，呈**垂直堆叠**的抽象结构——每一层都是前一层的消费者。

### Solon

```
SolonApp → AppContext → Plugin → EventBus → SolonProps → Router → MethodWrap
```

粗算：**7 个核心抽象**，抽象层级更扁平——`AppContext` 同时是 IoC 容器和应用上下文，不做分层，每个抽象的功能密度更高。

### Freeway

```
Container → Binder → ModuleEx → Defer → Extension<T> → RuntimeHook
```

粗算：**6 个核心抽象**，且**没有层级依赖**——Container 不依赖 Defer，Defer 不依赖 ModuleEx，完全扁平。

---

## 二、设计语言的"减法意识"

设计美学的一个关键指标：**设计者有没有勇气删掉自己写过的代码**。

### Spring：做加法，偶尔重构

倾向于"新增替代方案而不是删除旧的"。API 表面有大量"新旧并存"的痕迹：

- XML 配置 + 注解配置 + Java Config 长期并存
- `FactoryBean` 的 `&` 前缀从未被标记为 deprecated
- `ApplicationContext` 的接口膨胀从未被清理
- `@Configuration` 类中的 `@Bean` 和 `@Component` 类中的 `@Bean` 行为不同，但两者都保留

这不是设计缺陷——而是**向后兼容的优先级高于设计一致性**的工程选择。

### Solon：审慎的加法

核心抽象相对稳定，没有 Spring 那种历史负担（没有 XML 时代），所以 API 表面更干净。`AppContext` 从诞生起就承担了多重职责，这个设计从未被挑战过；`EventBus` 一直是一个静态类，未被重构为接口。

可以概括为"**从一开始就做对了，所以不需要删除**"——但更可能的情况是它还未经历 Spring 那样长达 20 年的使用压力测试。

### Freeway：减法作为第一原则

这是 Freeway 与另外两者最显著的**文化差异**。CHANGELOG 中多个版本都有 "Removed" 章节。被删除的包括：

- `inject()` — 因为鼓励 `new X() + container.inject(x)` 的反模式
- `Extension.Key` — 因为"没用，`Class<?>` 就够了"
- `@Named` — 被 `@Inject("id")` 取代
- `Module2`（更名为 `ModuleEx`）— 因为和 `java.lang.Module` 冲突
- Strict mode — 因为 `System.setProperty` 作为副作用通道
- `logging.properties` — 被 `freeway-logging.properties` 取代
- Extension 适配器模块、benchmark 模块 — 移到外部仓库
- `Route.handlerType` 字段 — 因为 4 字段版本的约束不变量散布在多个消费者中

Route 从 4 字段变为 3 字段是一个典型案例：发现设计瑕疵 → 不打补丁 → 重新审视抽象 → 引入 `LazyHandler` 包装器 → 删除冗余字段。**这不是"修 bug"，这是设计迭代**。

`AGENTS.md` 的仓库约定把这条写成了规则：

> **No compatibility shims**: a renamed key, a replaced API shape or a superseded
> mechanism is deleted, not kept alongside its replacement.

**判断**：在"删除的勇气"这个维度上，Freeway 远高于 Solon，Solon 远高于 Spring。这使 Freeway 的 API 表面比它的代码年龄看起来更新。

---

## 三、显式性 vs 隐式性

### Spring：隐式的王国

"约定优于配置"也带来了最大的设计代价——**隐式行为的局部推理丧失**：

```java
@Service
public class OrderService {
    @Autowired PaymentService paymentService;
}
```

你无法在这个类中理解 `PaymentService` 何时、如何、从哪里被注入。需要完整的类路径扫描上下文才能推理这个类的行为。再比如：

```java
@SpringBootApplication
public class App { }
```

这一行背后触发了 200+ 个自动配置类的条件评估。需要通过 `/actuator/conditions` 端点来"事后取证"。**故障模式与直觉无关**。

### Solon：有控制的隐式

注解驱动，所以也有隐式行为，但**范围更可控**：

- 类路径扫描的范围更明确（SolonApp 的包扫描约定）
- 插件通过 `META-INF/solon/*.properties` 注册，路径集中且规则简单
- 自动配置机制不如 Spring Boot 复杂，条件评估范围有限
- 生命周期事件的时序是预定义的，订阅者按可预期的顺序触发

没有完全解决"局部推理丧失"的问题（`@Inject` 的注入来源仍然不在调用点），但隐式行为的**覆盖范围**和**嵌套深度**都低于 Spring。

### Freeway：所有关系都在 binder 中

设计语言天生是显式的：

```java
FreewayApp.create(new HttpModule(), new DbModule(), new MyModule())
    .start();
```

- 所有模块在这里列出 → 你知道有哪些组件
- 所有绑定在 `Module.bind(Binder)` 中声明 → 你看到就有，没看到就没有
- 所有扩展通过 `binder.contribute(Class).add(...)` 贡献 → 贡献点和排序规则在一起
- 所有配置通过配置级联加载 → 环境变量、properties 文件、CLI 参数的优先级是已知的

唯一例外是已披露的 SPI：`FreewayApp.run(...)` 默认还会加载 `META-INF/services`
声明的模块（`autoDiscovery(false)` 关闭）。绑定仍从不扫描。

你能够**通过读一个文件**来理解整个应用的结构。恢复了"局部推理"的能力。

代价是：你需要写这个文件。**这正是显式性的价格**。

**判断**：三个框架在"显式-隐式"光谱上分布在三个不同的位置。Freeway 的选择（完全显式）在现代框架中是极度稀缺的——几乎所有的框架都选择了注解驱动的隐式路径。Freeway 走了反方向。

---

## 四、模块边界设计的差异

### Spring：模块边界是包名

```java
org.springframework.beans
org.springframework.context
org.springframework.transaction
org.springframework.aop
```

模块边界是**命名空间级别**的，**没有强制边界**——`BeanPostProcessor` 和 `ApplicationContext` 可以互相引用，尽管在语义上属于不同层。Spring Boot 的自动配置排序通过 `@AutoConfigureAfter`/`@AutoConfigureBefore` 注解建立——运行时"软约束"，缺乏编译期保证。

### Solon：模块边界是 Plugin

```java
public interface Plugin {
    void start(AppContext context);
    void preStop();
    void stop();
}
```

模块边界是一个**显式的契约**——`Plugin` 接口。`Plugin.start()` 接收 `AppContext` 但不接收 `SolonApp`，插件不能控制应用程序的整体生命周期，只能操作容器。这是一个**有限权力**的设计决策。

### Freeway：模块边界是架构分界线

模块边界不止是 `ModuleEx` 接口，而是一个**跨模块的分层约定**：

```
freeway-http/
├── src/main/java/com/jujin/freeway/http/
│   ├── route/Route.java        ← record，零容器依赖
│   ├── route/RouteHandler.java ← @FunctionalInterface，零容器依赖
│   ├── route/RouteIndex.java   ← 纯 trie 数据结构，零容器依赖
│   ├── HttpPipeline.java       ← record，处理声明（HttpServer.create 是唯一推导）
│   └── ...
├── freeway-http-ioc/（概念上）
│   └── HttpModule.java         ← 唯一的容器感知点
```

`RouteIndex` 不知道 `Container`，`DatabaseImpl` 不知道 `Container`。IoC 属于 Module，不属于核心实现。这是一个**架构上的分层策略，不是接口约定，而是设计文化**。

这意味着：

- core 代码可以独立测试（不需要容器）
- core 代码可以被其他框架复用（没有框架锁）
- Module 是唯一的"框架意识"入口
- 如果不用 IoC，可以直接 `HttpServer.create(engine, config, pipeline)` 零容器装配，与容器路径共用同一个派生点

**判断**：Spring 的模块边界是命名空间（弱），Solon 的模块边界是 Plugin 接口（中），Freeway 的模块边界是**跨模块的分层契约**（强）。

---

## 五、现代 Java 语言特性的利用

这是 Freeway 和另外两者拉开代差的地方。

### Spring：语言保守主义

核心抽象诞生于 Java 1.3-1.5 时代。直到今天：

- `BeanPostProcessor` 签名还是 `Object postProcessBeforeInitialization(Object bean, String beanName)`——不是泛型的
- 配置属性仍使用字符串字面量（`"classpath:..."`、`"${...}"`）
- 启动流程仍硬编码在 `AbstractApplicationContext.refresh()`（一个 700+ 行的方法）
- 核心运行时抽象没有充分利用 JDK 17 的 sealed class、pattern matching、record

设计语言**定格在了 Java 5-8 的时代**。

### Solon：务实的中等现代化

充分使用了注解、函数式接口、lambda。API 风格与 JDK 8 同步：

```java
Solon.start(MyApp.class, args, app -> {
    app.get("/hello", ctx -> ctx.output("Hello"));
});
```

但不使用 record、sealed class、ScopedValue。设计语言是 **JDK 8-11 水平的现代化**。

### Freeway：将 JDK 25 作为设计基础

每个核心抽象都使用了 Java 25 的能力：

- **ScopedValue**：作为 Defer、事务作用域、线程作用域的基础——替代了 ThreadLocal
- **Record**：`Route`、`RouteGroup`、`HttpPipeline` 都是 record——声明即定义
- **@FunctionalInterface**：`RouteHandler`、`HttpFilter`、`ModuleEx` 都是函数式接口
- **Pattern matching / 虚拟线程**：`instanceof` 绑定与 switch 箭头分发常态化（全仓 140+ 处），
  每连接一个虚拟线程承载 HTTP 引擎。`sealed class` 未用于类型层次——现代化是选择性的，
  不是特性堆砌

ScopedValue 从根本上改变了一种编程模型：

- ThreadLocal 的泄漏风险 → 零泄漏（ScopedValue 不可逃逸）
- ThreadLocal 的继承问题 → 零问题（ScopedValue 自动被子线程继承）
- ThreadLocal 的手动清理 → 零需要（作用域结束自动释放）

事务作用域、Defer 延迟执行、请求级 Scope 是"白送的"——不需要 ThreadLocal 的清理逻辑，不需要 InheritableThreadLocal 的传递约定，不需要在 finally 中手动 remove。

**判断**：Spring 的设计语言定型于 Java 5-8，Solon 定型于 JDK 8-11，Freeway 的设计语言与 JDK 25 共生。Spring 的选择是让 2000 万开发者都能用，Freeway 的选择是"与最新 JDK 一起进化，不接受历史的包袱"。

---

## 六、设计决策的透明度（框架的"诚实度"）

框架是否公开承认 trade-off，还是把框架包装成"完全正确"的样子。

### Spring：很少文档化 trade-off

官方参考文档长达 1000+ 页，但大部分是 API 参考和配置说明，很少讨论**设计权衡**。例如 `@Configuration` 和 `@Component` 中 `@Bean` 方法行为不同的问题——文档只是告诉你"应该在 `@Configuration` 类中声明 `@Bean` 方法"，但没有讨论**为什么两者行为不同**（CGLIB 代理 vs 无代理），以及**为什么保留这个差异**（向后兼容 vs 设计一致性）。

### Solon：适量的设计说明

DeepWiki 文档覆盖了架构说明和 API 参考，但 "Design Decision" 类的内容分散各处，没有集中记录 trade-off。

### Freeway：决策的 why 跟着变更走

Freeway 没有一份集中的"设计决策"文件——why 写在变更记录本身：

- **CHANGELOG 的 Removed / 迁移条目自带删除理由**：`CorsFilter.Builder` 被删，因为
  "重述策略默认值的对象是同一个答案的第二个持有者"；`internal.HttpModuleConfig` 被删，
  因为它是"键的第二份所有者——13 个 server 旋钮的默认逐个重述"；
- **复杂模块有自己的决策文档**：`docs/freeway-flow-design-decisions.md` 逐条记录
  （如 "Striped Lock for Singleton Concurrency"：`ConcurrentHashMap.computeIfAbsent`
  在递归场景抛 "Recursive update"，所以选择可重入的锁）；
- **版本发布说明承载当时的权衡**：`docs/RELEASE-NOTES-*.md` 逐版记录引入与取舍的背景
  （如 1.5.0 讨论 `.primary()` 绑定、构造器选择与配置激活的分工）。

每个决策都对应一个**真实的问题场景**（不是抽象的可能性），然后给出**具体的权衡分析**。

**判断**：这不是文档数量的差异，而是**设计文化**的差异。Freeway 把"记录权衡"视为设计的组成部分，而非事后的文档工作。这反映了对框架使用者的尊重——不把使用者当作"只需要知道怎么用"的人。

---

## 七、综合对比

| 维度 | Spring | Solon | Freeway |
|---|---|---|---|
| **核心抽象数量** | 14-16，层级堆叠 | 7-8，功能密度高 | 6，完全扁平 |
| **减法意识** | 弱（历史债重） | 中（稳定，但少删除） | 强（删除是文化） |
| **显式性** | 隐式为主 | 适度隐式 | 完全显式 |
| **模块边界强度** | 弱（包即模块） | 中（Plugin 约定） | 强（跨模块分层契约） |
| **设计语言时代** | Java 5-8 | JDK 8-11 | JDK 25 |
| **trade-off 透明度** | 低 | 中 | 高 |
| **核心代码与框架的耦合** | 高（无处不 Spring） | 中 | 低（core 零容器依赖） |

---

## 八、各自的独特价值主张

### Spring
- **价值**：约定优于配置，极低决策成本
- **代价**：你需要信任它的约定，并理解它背后复杂的适配层
- **适合**：大规模团队、快速交付、生态丰富的场景

### Solon
- **价值**：比 Spring 更轻，但保留了注解驱动的开发体验
- **代价**：生态不如 Spring，但够用
- **适合**：需要比 Spring 更轻但不想换范式的团队

### Freeway
- **价值**：完全透明、零魔法、你完全控制每一个绑定关系
- **代价**：你需要显式写出所有关系（这既是代价也是价值）
- **适合**：需要完全掌控运行时、追求极致性能、小团队或个人项目

---

## 九、结语

三个框架服务于不同的**信任模型**：

- **Spring** 让你信任它的自动装配
- **Solon** 让你信任它的轻量注解
- **Freeway** 不要求你信任任何东西——每一行代码都在那里，你可以读完、理解、修改

从设计语言的纯粹性和一致性来看，**Freeway 高于 Solon，Solon 高于 Spring**。但设计语言的纯粹性从来不是框架选择的首要标准——它是给懂得欣赏的人的额外奖赏。
