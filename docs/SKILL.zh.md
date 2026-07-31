---
name: freeway-dev
description: 基于 Freeway 框架构建 Java 应用。当用户提到 Freeway、FreewayApp、ModuleEx、binder.install、IoC 容器、DbModule、HttpModule、AppBuilder、路由、ORM、EventBus、Defer、ScopedCache、HealthCheck、HealthFilter、PooledConnection、PostgresDialect、SchemaEntity、freeway-ext 等框架相关术语时触发。涵盖模块编写、依赖注入、HTTP API、数据库操作、事务、事件总线、类型转换、延迟执行、验证、连接池、数据库方言、Schema 迁移等所有方面。同时也适用于回答 Freeway API 用法、项目结构、最佳实践和代码生成类问题。
---

# Freeway 开发技能

你是一个 Freeway 框架专家。Freeway 是一个面向 JDK 25+ 的轻量级现代 Java 应用框架，核心原则：**组合优先、零类路径扫描、零字节码织入、最小依赖**。

## 项目模块结构

```
freeway-commons     JSON、类型转换、Defer、ScopedCache、Bean 内省、验证、日志
freeway-ioc         IoC 容器：绑定、注入、作用域、AOP、事件总线、扩展
freeway-boot        launcher、配置级联、profiles、运行时生命周期
freeway-flow        图编排引擎 — 7 节点类型、v2 DAG 格式、@FlowMarker
freeway-http        HTTP/WebSocket：路由、过滤器、静态文件、multipart、SSE
  ├ 内置引擎          FreewayHttpEngine（HTTP/1.1 + HTTP/2 + WebSocket + HTTPS）
  └ 外部引擎          Undertow / Jetty → 见 freeway-ext
freeway-db          JDBC：ORM、连接池、事务、SQL 构建器、迁移
  └ 外部连接池        HikariCP → 见 freeway-ext

第三方库适配器（freeway-http-undertow, freeway-http-jetty,
freeway-db-hikari, freeway-mq-kafka）在
[freeway-ext](https://github.com/dzb/freeway-ext) 独立维护。
核心模块 SLF4J 以外零外部依赖。
```

## 启动应用

```java
// 最简方式
AppRuntime runtime = FreewayApp.run(new String[0], new AppModule());

// 带更多模块
AppRuntime runtime = FreewayApp.run(new String[0], new AppModule(), new HttpModule(), new DbModule());

// Fluent builder（精细控制）
AppRuntime app = FreewayApp.of(new MyModule())
    .add(new HttpModule(), new DbModule())
    .args("--freeway.profile=dev")
    .classLoader(customLoader)
    .autoDiscovery(false)        // 禁用 SPI 模块发现
    .shutdownHook(false)         // 跳过 JVM shutdown hook
    .config(myConfigLoader)      // 自定义 ConfigLoader
    .start();
```

## 编写模块

所有模块实现 `@FunctionalInterface ModuleEx`：

```java
public class AppModule implements ModuleEx {
    public void bind(Binder b) {
        // 绑定服务
        b.bind(UserService.class).to(UserServiceImpl.class);

        // 安装子模块（链式调用）
        b.install(new HttpModule())
         .install(new DbModule());

        // 贡献扩展（路由、hooks、事件订阅者等）
        b.contribute(Route.class)
            .add(Route.get("/", ctx -> ctx.send(200, "Hello")));

        b.contribute(RuntimeHook.class)
            .add("cache.warmup", new RuntimeHook() {
                public void start(Container c) { c.get(Cache.class).warmup(); }
                public void stop(Container c) { c.get(Cache.class).close(); }
            }).before("freeway.http.server");
    }
}
```

规则：
- **不要在 `bind()` 中启动工作** —— 只做声明；`RuntimeHook.start()` 才是激活点
- **运行时绑定一次** —— 所有模块的 bind 在同一容器初始化时依次调用
- **库类型不应导入 IoC 类型** —— 让库可以脱离容器独立使用

## IoC 容器

### API 速览

| 类型 | 用途 |
|------|------|
| `Container` | 服务查找：`get(Class)`, `get(Class, id)`, `get(Class, Annotation...)`, `extension()`, `create()` |
| `Binder` | 绑定与贡献 DSL，在 `ModuleEx.bind()` 中接收 |
| `Binding<T>` | 绑定配置链：`to()` → `scope()` → `id()` → `primary()` → `marker()` → `advise()` |
| `ModuleEx` | `@FunctionalInterface`，模块入口：`void bind(Binder)` |
| `Freeway` | 容器启动：`Freeway.create(ModuleEx...)` |
| `Scoping` | `Scoping.within()` 进入 Thread 作用域 |
| `Scope` | 枚举：`SINGLETON`、`THREAD`、`PROTOTYPE` |
| `Extension<V>` | 框架内部：聚合贡献值，通过 `container.extension(Class)` 访问。应用代码注入 `List<V>` 或 `Map<String, V>` |
| `EventBus` | 进程内发布-订阅 |
| `RuntimeHook` | 生命周期 start/stop 扩展点 |
| `LoggerSource` | 拥有者感知的日志工厂 |

### 创建容器

```java
// 直接容器（测试/独立使用）
Container c = Freeway.create(binder -> {
    binder.bind(MyService.class).to(MyServiceImpl.class);
});
MyService svc = c.get(MyService.class);
c.close();

// 完整应用
AppRuntime runtime = FreewayApp.run(new String[0], new AppModule());
Container c = runtime.container();
```

### 绑定 DSL — 完整链

```java
Freeway.create(binder -> {
    // —— 基础绑定 ——
    binder.bind(Greeter.class).to(GreeterImpl.class);          // 接口 → 实现

    // —— 实例绑定（必须 SINGLETON，不能有 scope/advise） ——
    binder.bind(Config.class).to(new Config(...));

    // —— Provider 绑定（工厂函数，每次按需调用） ——
    binder.bind(Cache.class).to(c -> new Cache(c.get(Config.class)));

    // —— 命名绑定 ——
    binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe");
    binder.bind(PaymentGateway.class).to(PayPalGateway.class).id("paypal");

    // —— 主绑定（无 id 注入时默认解析此实现） ——
    binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary();

    // —— 作用域 ——
    binder.bind(RequestState.class).to(RequestState.class).scope(Scope.THREAD);

    // —— AOP 织入（仅接口→实现类绑定可用） ——
    binder.bind(UserService.class).to(UserServiceImpl.class).advise(advisor ->
        advisor.wrap(inv -> inv.method().getName().startsWith("get"),
                     inv -> { /* 前置/后置逻辑 */ return inv.proceed(); }));
});
```

### 绑定 DSL 方法速查

| 方法 | 用途 | 约束 |
|------|------|------|
| `.to(Class)` | 接口 → 实现类 | 实现类需有无参或 `@Inject` 构造器 |
| `.to(instance)` | 预创建实例 | 必须是 `SINGLETON`，不可再设 scope/advise |
| `.to(c -> ...)` | Provider 工厂 | 每次解析按作用域策略调用 |
| `.id("name")` | 命名标识 | 同一类型 + id 唯一 |
| `.primary()` | 设置为主绑定 | 无 id 注入时默认使用 primary |
| `.marker(Annotation...)` | 添加标记注解 | `binder.bind(Cache.class).to(FastCache.class).marker(Fast.class)` |
| `.scope(Scope)` | 作用域 | `SINGLETON`(默认) / `THREAD` / `PROTOTYPE` |
| `.advise(Consumer<Advisor>)` | AOP 织入 | 仅接口→类绑定 |

### 服务获取

```java
// 按类型（需唯一或设了 primary）
Greeter g = c.get(Greeter.class);
// 按类型 + id
PaymentGateway pg = c.get(PaymentGateway.class, "stripe");
// 按类型 + 标记注解（containsAll 语义）
Cache cache = c.get(Cache.class, Fast.class);
// 运行时访问贡献扩展列表
List<Route> allRoutes = c.extension(Route.class).all();
List<Route> namedRoutes = c.extension(Route.class).asMap().values().stream().toList();
```

### 标记注解（Marker）

模块级标记会传播到所有绑定：

```java
@Marker(Builtin.class)
public class AppModule implements ModuleEx { ... }

// 单绑定时加标记
binder.bind(Cache.class).to(FastCache.class).marker(Fast.class);
```

### 注入注解

`com.jujin.freeway.ioc.annotation` 包下：

| 注解 | 用途 | 示例 |
|------|------|------|
| `@Inject` | 构造器/字段/参数注入 | `@Inject private Logger log;` |
| `@Inject("id")` | 按绑定 id 注入 | `@Inject("audit") Logger audit;` |
| `@Symbol("key")` | 严格配置查找，key 不存在抛异常 | `@Symbol("server.port") int port;` |
| `@Value("${key:default}")` | 配置表达式，可带默认值 | `@Value("${app.timeout:30}") int timeout;` |
| `@PostConstruct` | 初始化回调 | `void init() { ... }` |
| `@PreDestroy` | 销毁回调 | `void cleanup() { ... }` |

注解注入也支持 record：

```java
public record ServerConfig(
    @Symbol("server.port") int port,
    @Value("${app.name:freeway}") String appName
) {}
```

### 注入方式

```java
public class UserService {
    private final UserRepository repo;

    // 构造器注入（框架内部推荐）
    public UserService(UserRepository repo) {
        this.repo = repo;
    }

    // 字段注入（应用代码可接受）
    @Inject private Logger log;
    @Inject("audit") private Logger audit;
    @Value("${app.timeout:30}") private int timeout;
}
```

Primary 解析使用 `binding.primary()` DSL，不是注解。

### 作用域详解

| Scope | 行为 | 销毁时机 |
|-------|------|----------|
| `SINGLETON` | 每个容器一个实例 | 容器 `close()` 时 |
| `PROTOTYPE` | 每次解析创建新实例 | 容器不持有，不自动销毁 |
| `THREAD` | 每个 `Scoping.within()` 边界内一个实例 | 作用域退出时，`@PreDestroy` + `AutoCloseable` |

`Scoping.within()` 基于 JDK 25 `ScopedValue`，虚拟线程零 overhead，支持嵌套。

**作用域兼容规则**：Singleton 不能直接注入 thread-scoped 的具体类。需要用接口 + proxy：

```java
// ✅ 正确 — singleton 注入接口，获取 lazy proxy
binder.bind(ScopedApi.class).to(ScopedCounter.class).scope(Scope.THREAD);
binder.bind(ScopedSingletonService.class).to(ScopedSingletonService.class);

// ❌ 错误 — singleton 注入 thread-scoped 具体类
binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD);
binder.bind(ScopedSingleton.class).to(ScopedSingleton.class); // 容器启动报错
```

### Thread 作用域使用

```java
binder.bind(RequestState.class).to(RequestState.class).scope(Scope.THREAD);

Scoping scoping = c.get(Scoping.class);
scoping.within(() -> {
    RequestState state = c.get(RequestState.class);
    // 作用域内复用同一实例
    state.set(..);
});
// 退出时自动销毁
```

### 类型转换

```java
Coercer coercer = c.get(Coercer.class);
int port = coercer.coerce("8080", int.class);

// 自定义规则
binder.contribute(CoerceRule.class).add(new CoerceRule<>(
    String.class, Endpoint.class,
    value -> { String[] p = value.split(":", 2); return new Endpoint(p[0], Integer.parseInt(p[1])); }
));
```

## 扩展（Extension）—— 贡献机制详解

Extension 是 Freeway 的插件化扩展机制，核心思想：**按入口类型贡献，按类型注入**。

### 架构

```
Module.bind() 中
  │
  ├─ binder.contribute(Route.class)       ← 声明扩展点类型
  │   └─ .add(value) / .add(id, value)   ← 贡献值
  │
  └─ Container 启动时收集合并
       │
       ├─ @Inject List<Route> routes         ← 注入为有序列表（所有贡献）
       ├─ @Inject Map<String, Route> routes  ← 注入为 id→value 映射（仅命名贡献）
       └─ c.extension(Route.class).all()     ← 框架运行时按需获取
```

### 贡献 API

```java
public interface Binder {
    <V> Contributions<V> contribute(Class<V> entryType);
}

public interface Contributions<T> {
    void add(T value);                              // 无名贡献，保持插入顺序
    Contribution add(String id, T value);            // 命名贡献，支持 before/after 排序
}

public interface Contribution {
    Contribution before(String... ids);              // 声明在此 id 之前
    Contribution after(String... ids);               // 声明在此 id 之后
}
```

### 排序规则

- `add(value)` — 无名贡献，按插入顺序排列
- `add(id, value)` — 命名贡献，通过 `before()` / `after()` 声明依赖
- `add(Class)` — 从容器自动实例化，生成 canonical id（`snake_name@package`），返回的 `Contribution` 支持 `before/after`
- 拓扑排序：容器在收集完所有模块的贡献后，按 before/after 关系执行拓扑排序
- 重复 id 立即报错
- 缺失的排序目标（before/after 引用不存在的 id）会报错
- 循环依赖在解析时报错

```java
binder.contribute(RuntimeHook.class)
    .add("cache.warmup", hook).before("freeway.http.server")
    .add("freeway.http.server", serverHook);

// 注入
@Inject
private List<RuntimeHook> hooks;     // 按排序后的顺序
```

### 消费方式

```java
// 方式 1：List<V> 注入（所有贡献，按序）
@Inject List<Route> routes;
routes.forEach(r -> register(r));

// 方式 2：Map<String, V> 注入（仅命名贡献，按 id 查找）
@Inject Map<String, FlowDriver> drivers;
FlowDriver custom = drivers.get("custom");

// 方式 3：构造器注入
public class Router {
    private final List<Route> routes;
    private final Map<String, FlowDriver> drivers;
    public Router(List<Route> routes, Map<String, FlowDriver> drivers) {
        this.routes = List.copyOf(routes);
        this.drivers = Map.copyOf(drivers);
    }
}

// 方式 4：Container.extension() 运行时获取（框架模块使用）
List<Route> all = c.extension(Route.class).all();
Map<String, Route> named = c.extension(Route.class).asMap();
```

**三种贡献方式对应的注入目标：**

| 贡献方式 | 出现在 `List<V>` | 出现在 `Map<String, V>` |
|---|---|---|
| `add(value)` — 无名 | ✅ | ❌ |
| `add("id", value)` — 命名 | ✅ | ✅ (id 为 key) |
| `add(Class)` — 自动实例化 | ✅ | ✅ (自动生成 id 为 key) |

`List<V>` = 所有贡献，保持排序。`Map<String, V>` = 仅命名贡献，按 id 查找。

`Extension<V>` 不注入给应用代码，因为它是一个可变的聚合器。应用代码注入
`List<V>` 或 `Map<String, V>` 即可 — 这些都是不可变视图。

### 内置扩展点

| 入口类型 | 用途 |
|----------|------|
| `Route.class` | HTTP 路由 |
| `RouteGroup.class` | 路由组（带前缀展开） |
| `HttpFilter.class` | HTTP 请求过滤器 |
| `RuntimeHook.class` | 生命周期钩子 |
| `EventSubscriber.class` | 模块级事件订阅者 |
| `CoerceRule.class` | 类型转换规则 |
| `ExceptionMapper.class` | HTTP 异常映射器 |
| `StaticResourceMount.class` | 静态文件挂载 |
| `WebSocketRoute.class` | WebSocket 路由 |
| `WebSocketGroup.class` | WebSocket 路由组 |
| `RowMapping.class` | 数据库行映射 |
| `DatabaseNamed.class` | 命名数据库注册 |

## EventBus

### 模块级订阅（启动时，支持排序）

```java
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(PostCreatedEvent.class, e -> index(e.post())))
    .add(EventSubscriber.of("notify", PostCreatedEvent.class, e -> sendEmail(e)))
    .after("index");

// 字符串主题订阅
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of("order.placed", payload -> process(payload)));
```

### 运行时订阅

```java
@Inject EventBus bus;

Subscription<PostCreatedEvent> sub = bus.subscribe(PostCreatedEvent.class, e -> { ... });
bus.unsubscribe(sub);
```

### 发布

```java
bus.publish(new PostCreatedEvent(post));         // 类事件
bus.publish("order.placed", payload);             // 字符串主题
bus.publishAsync(new PostCreatedEvent(post));     // 异步（虚拟线程，不参与 Defer）
```

### Stoppable 事件

```java
public class PostCreatedEvent implements EventBus.Stoppable {
    private final AtomicBoolean stopped = new AtomicBoolean();
    @Override public void stop() { stopped.set(true); }
    @Override public boolean isStopped() { return stopped.get(); }
}

// 第一个订阅者验证后停止传播
binder.contribute(EventSubscriber.class)
    .add(EventSubscriber.of(PostCreatedEvent.class, e -> { if (!loggedIn) e.stop(); }));
```

### 内置生命周期事件

- `AppStartedEvent(Container)` — 所有钩子启动后发布
- `AppStoppingEvent(Container)` — 关闭前发布

## AOP

### Advisor API

```java
public interface Advisor {
    Advisor wrap(Predicate<MethodInvocation> selector, MethodAdvice advice);
}

public interface MethodAdvice {
    Object invoke(MethodInvocation invocation) throws Throwable;
}

public interface MethodInvocation {
    Object proceed() throws Throwable;   // 执行原方法
    Object target();                     // 目标对象
    Method method();                     // 当前执行的方法
    Object[] arguments();                // 方法参数
}
```

### 基础用法

```java
binder.bind(UserService.class).to(UserServiceImpl.class).advise(advisor ->
    advisor.wrap(
        inv -> inv.method().getName().startsWith("get"),
        inv -> {
            // 前置增强
            long start = System.nanoTime();
            try {
                Object result = inv.proceed();    // 执行原方法
                return result;
            } finally {
                // 后置增强
                long elapsed = System.nanoTime() - start;
                log.info("{}.{} took {}ns",
                    inv.target().getClass().getSimpleName(),
                    inv.method().getName(), elapsed);
            }
        }
    )
);
```

### 多 Advisor

一个绑定可以叠加多个 `advisor.wrap()`：

```java
binder.bind(UserService.class).to(UserServiceImpl.class).advise(advisor -> {
    // 第一个 — 性能监控
    advisor.wrap(inv -> true, inv -> {
        long start = System.nanoTime();
        try { return inv.proceed(); }
        finally { log.info("{} took {}ms", inv.method().getName(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)); }
    });
    // 第二个 — 安全检查
    advisor.wrap(inv -> inv.isAnnotationPresent(Secured.class), inv -> {
        if (!hasRole("ADMIN")) throw new SecurityException("Access denied");
        return inv.proceed();
    });
});
```

### 选择器模式

选择器 `Predicate<MethodInvocation>` 控制哪些方法被拦截：

```java
advisor.wrap(inv -> true, advice);                                    // 所有方法
advisor.wrap(inv -> inv.method().getName().startsWith("get"), advice); // getter
advisor.wrap(inv -> inv.method().isAnnotationPresent(Timed.class), advice); // 带 @Timed 的方法
advisor.wrap(inv -> inv.method().getParameterCount() == 0, advice);    // 无参方法
```

### 限制

- **仅接口 → 实现类**：AOP 使用 JDK 动态代理，所以目标类型必须是接口，绑定必须是接口到实现类
- **不能代理具体类**：`binder.bind(ConcreteClass.class).to(ConcreteClass.class).advise(...)` 会报错
- **被 final/static/private 修饰的方法不会被代理**

### Thread-Scoped Proxy（跨作用域注入）

当 singleton 需要注入 thread-scoped 服务时，容器自动创建 lazy proxy。这是隐式的 AOP 应用：

```java
// 接口定义
public interface RequestContext {
    String correlationId();
}

// 接口绑定为 THREAD 作用域
binder.bind(RequestContext.class).to(RequestContextDefault.class).scope(Scope.THREAD);

// Singleton 注入接口 — 容器自动生成 proxy，每次调用委托到当前作用域的实例
@Inject
private RequestContext ctx;    // 这是 proxy，不是实际实例
```

## HTTP

### 路由

路径变量支持两种语法：`:name`（简洁，Express 风格）和 `{name}`。需要正则约束时用 `{name:regex}`，如 `{id:\\d+}` 仅匹配数字。两种可混用。

**无依赖的简单处理器**用 lambda：

```java
binder.contribute(Route.class)
    .add(Route.get("/", ctx -> ctx.send(200, "Hello")))
    .add(Route.get("/users/:id", ctx -> ctx.sendJson(200, user(ctx.pathVar("id")))))
    .add(Route.post("/users", (ctx, body) -> {
        ctx.sendJson(201, userService.create(body));
    }, User.class))  // 自动 JSON 反序列化
    .add(Route.put("/users/:id", User.class, (ctx, body) -> { ... }))
    .add(Route.delete("/users/:id", ctx -> { ... }));
```

**需要注入服务的处理器**用 handler 类。容器启动时创建实例，构造器参数自动注入：

```java
// 1. 实现 RouteHandler，构造器声明依赖
public static final class GetUser implements RouteHandler {
    private final UserService svc;
    public GetUser(UserService svc) { this.svc = svc; }
    public void handle(HttpContext ctx) throws Exception {
        var user = svc.findById(ctx.pathVar("id"));
        user.ifPresentOrElse(
            u -> ctx.sendJson(200, u),
            () -> ctx.send(404, "Not found"));
    }
}

// 2. 注册时传 class，不传 lambda
binder.contribute(Route.class)
    .add(Route.get("/api/users/:id", GetUser.class));

// 3. 也支持带 body 的 POST/PUT/PATCH（handler 内调 ctx.bodyAsJson()）
binder.contribute(Route.class)
    .add(Route.post("/api/users", CreateUser.class));
```

**选择规则：** handler 纯粹做转发/静态响应 → lambda。handler 需要 `@Inject` 服务 → 类。
如果在 lambda 中写静态方法调用 `Xxx.get()` 来获取服务，就说明该改成 handler 类了。

### RouteGroup

```java
binder.contribute(RouteGroup.class)
    .add(RouteGroup.of("/api/v1",
        Route.get("/users", ctx -> ctx.sendJson(200, users())),
        Route.get("/users/:id", ctx -> ctx.sendJson(200, user(ctx.pathVar("id"))))
    ));
```

### HttpContext 核心方法

| 方法 | 用途 |
|------|------|
| `ctx.method()` | HTTP 方法 |
| `ctx.path()` | 请求路径 |
| `ctx.pathVar("id")` | 路径变量 |
| `ctx.queryParam("q")` | 查询参数 |
| `ctx.header("Accept")` | 请求头 |
| `ctx.bodyAsJson(User.class)` | JSON 反序列化 |
| `ctx.param("name")` | pathVar → queryParam → body 字段（便利方法） |
| `ctx.requestContext().correlationId()` | 唯一请求 id |
| `ctx.status(201)` | 设置状态码 |
| `ctx.headerSet("X-Custom", "value")` | 设置响应头 |
| `ctx.send(200, "plain text")` | 发送文本响应 |
| `ctx.sendJson(200, object)` | 发送 JSON 响应 |
| `ctx.sse()` | 获取 SSE 发射器 |

### 过滤器

```java
public class AuthFilter implements HttpFilter {
    public void doFilter(HttpContext ctx, RouteHandler next) throws Exception {
        if (ctx.header("Authorization") == null) {
            ctx.send(401, "Unauthorized");
            return;
        }
        next.handle(ctx);
    }
}
binder.contribute(HttpFilter.class).add(new AuthFilter());
```

### 静态文件

```java
binder.contribute(StaticResourceMount.class)
    .add(StaticResourceMount.classpath("/", "/public"))
    .add(StaticResourceMount.directory("/uploads", Path.of("/var/uploads")));
```

### WebSocket

```java
binder.contribute(WebSocketRoute.class)
    .add(WebSocketRoute.of("/ws/chat", session -> new WebSocketListener() {
        public void onText(String text) { session.sendText("Echo: " + text); }
        public void onClose(int code, String reason, boolean remote) { }
    }));
```

### SSE

```java
Route.get("/events", ctx -> {
    SseEmitter sse = ctx.sse();
    sse.send("connected");
    sse.send(new SseEvent("data", "msg-1", "update", null));
    sse.complete();
});
```

### 异常映射

```java
binder.contribute(ExceptionMapper.class).add((ctx, ex) -> {
    if (ex instanceof NotFoundException) {
        ctx.sendJson(404, Map.of("error", ex.getMessage()));
        return true;
    }
    return false;
});
```

### 健康检查

内置 `/healthz` 端点，可插拔：

```java
// 默认：HealthCheck.Default 返回 {"status": "ok"}
// 自定义：绑定 HealthCheck 实现
binder.bind(HealthCheck.class).to(c -> () -> {
    Database db = c.get(Database.class);
    boolean ok = db.ping();
    return Map.of("status", ok ? "ok" : "degraded",
                  "db", ok ? "connected" : "unreachable");
}).primary();
```

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `freeway.http.health.enabled` | `true` | 启用/关闭健康检查 |
| `freeway.http.health.path` | `/healthz` | 健康检查路径 |

## 数据库

### 独立使用（无 IoC）

```java
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test", "sa", "");
Database db = new DatabaseBuilder().config(config).build();
Orm orm = Orm.of(db);
```

### IoC 集成

```java
FreewayApp.run(new String[0], new AppModule(), new DbModule());
// Database, Orm, Coercer 可注入
```

### 查询

```java
// 位置参数
List<User> users = db.query("SELECT * FROM users WHERE active = ?", true).list(User.class);

// 命名参数
User u = db.query("SELECT * FROM users WHERE id = :id")
    .param("id", 42).one(User.class).orElseThrow();

// Stream（需 try-with-resources）
try (var stream = db.query("SELECT * FROM big_table").stream(Row.class)) {
    stream.forEach(row -> process(row));
}

// 执行
ExecuteResult r = db.execute("INSERT INTO users (name) VALUES (?)", "Alice");
r.rows();   // 影响行数
r.key();    // 生成的主键
```

### Row 读取

```java
Row r = db.query("SELECT * FROM t").list(Row.class).get(0);
r.string("name");
r.integer("age");
r.longVal("id");
r.bool("active");
r.decimal("amount");
r.date("created_at");
r.dateTime("updated_at");
r.uuid("uuid_col");
```

### 事务

```java
db.transaction(() -> {
    db.execute("UPDATE ledger SET amount = ? WHERE id = ?", 100L, 1L);
    db.execute("INSERT INTO audit_log (msg) VALUES (?)", "transfer");
});

// 隔离级别
db.transaction(IsolationLevel.SERIALIZABLE, () -> { ... });
```

事务内 EventBus 事件自动延迟到提交后触发（通过 Defer 机制）。

### SQL 构建器

```java
SQL sql = SQL.select("u.name, o.total")
    .from("users u")
    .join("orders o").on("u.id = o.user_id")
    .where("u.active = ?", true)
    .orderBy("o.total DESC")
    .limit(10);
db.query(sql).list(UserOrderView.class);
```

### ORM

```java
@Table("posts")
public class Post {
    @Id @Generated private Long id;
    private String title;
    @Column("created_at") private LocalDateTime createdAt;
}

Orm orm = Orm.of(db);
orm.insert(post);               // 自动回写自增 id
orm.findById(Post.class, 1L);   // Optional<Post>
orm.findAll(Post.class);
orm.save(post);                 // upsert
orm.update(post);
orm.delete(post);
orm.deleteById(Post.class, 1L);
```

### 连接池

```java
PoolConfig config = PoolConfig.defaults(url, user, pass);

// 配置项：maxSize(默认10), minIdle(2), connectionTimeout(10s),
//         maxLifetime(30min), maxIdleTime(10min), cleanInterval(2min)
```

IoC 下通过 `.primary()` 模式选择：`PoolDefault`（默认）或引入 `HikariPoolModule` 切换。

### 数据库方言

方言控制 DDL 生成（标识符引用、自增、元数据查询）。选择方式与 Pool、HttpEngine 一致：bind by id，config 选中，URL 自动检测兜底。

**解析链：**
```
freeway.db.dialect=mysql          ← 配置？
  → container.get(Dialect, id)    ← 按 id 查找绑定
  → jdbc:postgresql://...         ← URL 自动检测
  → PostgresDialect               ← 默认 primary
```

**内置：** `PostgresDialect`（id `"postgresql"`，默认）

**自定义方言：**
```java
// 实现 Dialect → 绑定
binder.bind(Dialect.class).to(MySqlDialect.class).id("mysql").primary();
// 通过配置选中: freeway.db.dialect=mysql
```

**按 SchemaEntity 组覆盖（上述全局方言仍为默认）：**
```java
binder.contribute(SchemaEntity.class)
    .add(SchemaEntity.of("audit", new MySqlDialect(), AuditLog.class));
```

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `freeway.db.dialect` | (auto) | 方言 id，覆盖 URL 自动检测 |

### Schema & 迁移

Freeway 提供两种互补的数据库演进机制：Schema（注解驱动，当前态 DDL）和 Migration（版本化 SQL 文件）。

#### Schema — 注解驱动的自动 DDL

```java
// 独立使用（dialect 参数必须显式提供）
// 方言取自 db.dialect()，无需显式传入
Schema.ensure(db, User.class, Post.class);

// 策略：表不存在 → CREATE TABLE IF NOT EXISTS
//       列缺失   → ALTER TABLE ADD COLUMN
//       索引缺失 → CREATE INDEX IF NOT EXISTS
//       已存在   → 不触碰
```

**IoC 集成 — 通过 SchemaEntity 贡献实体类：**

```java
public class AppModule implements ModuleEx {
    public void bind(Binder b) {
        b.install(new DbModule());

        // 注册实体类 → 启动时自动建表
        b.contribute(SchemaEntity.class)
            .add(SchemaEntity.of("app", User.class, Post.class));
    }
}
```

`DbModule` 在启动时自动运行 `Schema.ensure()` → `MigrationRunner.run()`，顺序由 `RuntimeHook("freeway.db.migration")` 保证。

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `freeway.db.schema.auto` | `true` | 是否启用注解自动建表 |
| `freeway.db.schema.groups` | (all) | 逗号分隔的要运行的组名，空=全部 |

#### Migration — 版本化 SQL 文件

```java
// 独立使用
MigrationRunner runner = new MigrationRunner(db, true, "db/migration", "_migrations");
int ran = runner.run();  // 返回新执行的迁移数，已执行的跳过
```

**文件命名（强制）：** `V` + 数字 + `__` + 描述 + `.sql`
```
db/migration/
├── V001__create_users.sql
├── V002__add_email.sql
└── V20240615__add_index.sql  ← 时间戳版本也支持
```

每个迁移在独立事务中执行。已应用的迁移不可再修改 — SHA-256 校验和不匹配会阻止启动。多实例并发有数据库级锁保护。

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `freeway.db.migration.enabled` | `true` | 关闭后跳过 SQL 迁移 |
| `freeway.db.migration.path` | `db/migration/` | 类路径上的 SQL 文件目录 |
| `freeway.db.migration.table` | `_migrations` | 跟踪表名 |

**IoC 集成** — DbModule 绑定了 MigrationRunner，无需额外配置。

#### Dev vs Production 工作流

```
开发环境                              生产环境
───────                              ──────
freeway.db.schema.auto=true          freeway.db.schema.auto=false

① 实体类加 @Column("phone")          ② 写 V004__add_phone.sql
   → 重启 → 列自动添加                    从 Schema.define() 输出推导
                                   ③ 部署 → Migration 执行 V004
```

**Schema 适合开发迭代**（零摩擦），**Migration 适合生产上线**（可审计、可精确控制）。同一套实体类和 SQL 文件，切换只需一个配置键。

## Flow — 图编排引擎

7 种节点类型的轻量级图编排引擎：`START`、`END`、`ACTIVITY`、`EXCLUSIVE`、`INCLUSIVE`、`PARALLEL`、`LOOP`。v1 格式兼容 solon-flow，v2（`GraphSpec2`，`nodes`+`links` 结构）是 Freeway 原生格式，两者共享统一运行时。

### 图定义 — v2 格式（推荐）

```java
// 编程式
GraphSpec2 bp = GraphSpec2.create("orderFlow", spec -> {
    spec.entry("start");
    spec.addStart("start").linkAdd("approve");
    spec.addActivity("approve").task("!channel:order").linkAdd("end");
    spec.addEnd("end");
});
Graph graph = bp.create();          // normalize() 自动校验 link 引用 + 可达性

// JSON — fromText() 自动检测 v1/v2 格式
Graph graph = Graph.fromText("""
{
    "id": "orderFlow", "version": 2, "entry": "start",
    "nodes": [
        {"id": "start", "type": "start"},
        {"id": "approve", "type": "activity", "task": "!channel:order"},
        {"id": "end", "type": "end"}
    ],
    "links": [
        {"from": "start", "to": "approve"},
        {"from": "approve", "to": "end"}
    ]
}
""");
```

### 任务解析

节点通过前缀语法指定要执行的内容，不同前缀走不同解析逻辑：

| 前缀 | 语法 | 解析为 |
|------|------|--------|
| `!` (marker) | `!channel:order !priority:high` | `TaskComponent`，按 `@FlowMarker` 交集匹配，标记最多者胜出 |
| `@` (bean) | `@orderService` | `TaskComponent`，容器按 binding id 查找。条件节点也支持 `@`，解析为 `ConditionComponent` |
| `#` (子图) | `#approvalFlow` | 调用已加载的命名子图，嵌套执行 |
| `$` (meta) | `$app.name` | 读取图元数据注入执行上下文，不解析为组件 |

`@FlowMarker("channel:order")` 注解在 `TaskComponent` 实现类上，自动注册到 marker index。

### Driver（驱动器）

图通过 `"driver"` 字段选择驱动器（null/"" → `"default"`）。`FlowModule` 绑定 `FlowContainer` 后创建 `FlowDriverDefault` 作为默认驱动器，再合并从 `Extension<FlowDriver>.asMap()` 获取的自定义驱动器。自定义驱动器通过扩展点贡献：

```java
// 自定义驱动器
binder.contribute(FlowDriver.class)
    .add("custom", new MyCustomDriver())
    .add(MyOtherDriver.class);  // add(Class) → container.create() 自动注入
```

图定义中指定：`{ "driver": "custom", ... }`

### 执行

```java
FlowEngine engine = container.get(FlowEngine.class);
engine.load(graph);
engine.eval("orderFlow", FlowContext.of());

// 通过 IoC — FlowModule 自动注册贡献的 TaskComponent 和 FlowDriver
FreewayApp.run(args, new AppModule(), new FlowModule());
```

支持 PlantUML 导出、执行追踪（暂停/恢复）、子图调用、拦截器链。零外部依赖。

## Commons 工具

### JSON

```java
JsonObject obj = JsonUtils.parseObject("{\"name\":\"Alice\"}");
JsonArray arr = JsonUtils.parseArray("[1, 2, 3]");
String json = JsonUtils.stringify(obj);
String pretty = JsonUtils.stringifyPretty(obj);

// JsonCodec（可注入）
@Inject JsonCodec codec;
String json = codec.toJson(user);
User u = codec.fromJson(json, User.class);
```

### Defer —— 边界延迟执行

```java
Defer.within(() -> {
    db.execute("UPDATE ...");
    Defer.defer(() -> cache.invalidate("key"));  // 提交后执行
});
```

框架已内置场景：DB 事务内 EventBus 自动延迟、HTTP 请求边界、Kafka 记录处理。

### ScopedCache —— 作用域缓存

```java
ScopedCache.within(() -> {
    Connection conn = ScopedCache.get("db", () -> dataSource.getConnection());
    // 同一 key 在作用域内复用
});
// 退出时自动清理
```

IoC 的 `Scope.THREAD` 基于 ScopedCache 实现。

### BeanValidator

```java
public class CreateUserRequest {
    @NotBlank private String name;
    @NotNull @Size(min = 1, max = 150) private Integer age;
    @Valid private Address address;
}

ValidationResult result = BeanValidator.validate(request);
if (result.hasErrors()) { ... }
```

### 日志

Freeway 内置 SLF4J 2 + JUL 日志后端，零依赖可用。添加 Logback 自动切换。

**配置：** `freeway-log.properties`（classpath 根），框架不打包此文件。优先级：

```
-D 参数 > FREEWAY_ 环境变量 > freeway-log.properties > 代码默认值
```

**零配置使用：**
```bash
java -jar app.jar    # 控制台自动开、文件自动写 logs/{app.name}.log
```

**单文件自定义：**
```properties
freeway.log.file=auto                        # logs/{app.name}.log（默认）
# freeway.log.file=logs/myapp.log            # 自定义路径
# freeway.log.file=off                       # 关文件日志
freeway.log.file.max-size=104857600           # 100 MB
freeway.log.file.max-history=30               # days
freeway.log.file.compress=true                # GZIP
```

**多文件日志：**
```bash
-Dfreeway.log.files=audit
-Dfreeway.log.file.audit.path=logs/audit.log
-Dfreeway.log.file.audit.logger=com.myapp.audit
```

每个文件有独立的 `JULFileHandler`（时间+大小双滚动+GZIP），路由到指定 Logger。配置写入 `freeway-log.properties` 或通过 `-D` 覆盖。

**级别支持：** SLF4J 名（TRACE/DEBUG/INFO/WARN/ERROR）和 JUL 名（FINEST/FINE/INFO/WARNING/SEVERE），不区分大小写。

**Late re-attach：** 如果命名文件 handler 被 JUL LogManager 清掉：
```java
LogBootstrap.applyNamedFileLoggers();  // 在 FreewayApp.run() 之后
```

**环境变量：** 所有 `freeway.log.*` 键支持 `FREEWAY_` 前缀——`FREEWAY_LOG_LEVEL=DEBUG` 等同 `-Dfreeway.log.level=DEBUG`。

## 应用生命周期

```
CREATED → STARTING → RUNNING → STOPPING → STOPPED
                                  ↘  FAILED
```

### AppRuntime 用法

```java
AppRuntime runtime = FreewayApp.run(new String[0], new AppModule());
Container c = runtime.container();        // 获取容器
AppConfig cfg = runtime.config();          // 获取配置
AppState state = runtime.state();          // 当前状态
runtime.start();                           // 手动启动（run() 已自动启动）
runtime.close();                           // 停止应用
```

### 配置级联（优先级从低到高）

1. `application.properties`
2. `application.json`
3. `application-{profile}.properties`
4. `application-{profile}.json`
5. 环境变量（`FREEWAY_` 前缀）
6. CLI 参数（`--key=value`, `-Dkey=value`）

Dotted keys（如 `--app.name=foo`）原样透传。不含 `.` 的简单键自动加 `freeway.` 前缀。
激活 profile：`--profile=dev`（等同于 `--freeway.profile=dev`）

## 测试模式

### IoC 测试
```java
Container c = Freeway.create(binder -> {
    binder.bind(MyService.class).to(MyServiceImpl.class);
});
MyService svc = c.get(MyService.class);
c.close();
```

### HTTP 测试
```java
WebServer server = c.get(WebServer.class);
server.start();
try {
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder().uri(URI.create("http://localhost:" + server.port() + "/test"))
            .GET().build(), BodyHandlers.ofString());
    assertEquals(200, resp.statusCode());
} finally { server.stop(); }
```

### DB 测试（H2 内存）
```java
PoolConfig config = PoolConfig.defaults("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
Database db = new DatabaseBuilder().config(config).build();
Schema.ensure(db, TestEntity.class);
```

## 设计约定

- **Public interfaces** 使用裸领域名：`Container`, `JsonCodec`, `RequestContext`
- **默认实现** 使用 `XDefault` 后缀：`AppRuntimeDefault`, `JsonCodecDefault`, `CoercerDefault`
- **`Impl` 后缀** 保留给非策略性具体实现
- **库类型不应导入 IoC 类型** —— Library → DbModule 模式：库本身零 IoC 依赖
- **不要在 `bind()` 中启动工作**
