# Module Reference

Module is the unit of composition in Freeway. `ModuleEx` is the Java type name used to avoid a conflict with `java.lang.Module`; conceptually, Freeway code talks about modules.

## What A Module Does

- binds services
- contributes extensions
- composes with other modules

`bind()` declares. It does not start work. Initialization happens when services are resolved or when runtime hooks fire.

## Typical Shapes

### Application module

```java
public final class AppModule implements ModuleEx {
    @Override
    public List<ModuleEx> subModules() {
        return List.of(new HttpModule(), new DbModule());
    }

    @Override
    public void bind(Binder b) {
        b.bind(UserService.class).to(UserServiceImpl.class);
    }
}
```

### Library module

Library modules keep the public API independent of IoC and expose one integration module:

```text
library
  ├─ public API types
  └─ MyLibModule -> integrates with Freeway
```

## Composing Modules

### Sub-modules are data: `subModules()`

A module that groups others — an umbrella bundle — returns them from
`subModules()`. The container resolves the whole tree *before* binding:
this module first, then its sub-modules depth-first, siblings in declaration
order.

```java
private final List<ModuleEx> subModules = List.of(new HttpModule(), new DbModule());

@Override
public List<ModuleEx> subModules() {
    return subModules;
}
```

`subModules()` is a **view of the composition, not a factory**: declare the
list once (a field) and return it. The framework reads it more than once per
startup — the entry point, to skip modules already declared here when SPI
discovery runs, and the container, to resolve the tree — so a method that
built a fresh list each call would start correctly but make the module set
unobservable (`container.modules()` would report different instances than a
caller reading `subModules()` sees).

There is deliberately no `binder.install(...)`: installing inside `bind()` made
the module tree a side effect of call order, so nothing outside the binder
(the application entry point, diagnostics, tests) could see what an app would
load, and deduplication could not run before binding. Composition as data fixes
both: `container.modules()` returns the flattened tree in bind order.

**去重语义**（对整张图统一生效）：

- 同一**实例**在图上被到达两次（菱形依赖，或环）只绑定一次——这也让互相引用的
  子模块自然终止。
- 同 **class** 的**不同实例**（如 `new HttpModule()` 两次，或某模块既是伞形的
  子模块又被显式添加）抛 `IllegalStateException`（"declared twice"）；
  匿名/lambda 模块没有有意义的 class 身份，只看实例身份。
- SPI 发现只补空缺：类已经在树里（含作为子模块）就不再被发现加入——"bundle 里
  声明 `new HttpModule()`"与"自动发现"因此不会互相冲突。
- `FreewayApp` / `AppBuilder` 仍按 class 对**入口模块列表**去重（显式模块先注册、
  SPI 后来者不覆盖），随后容器对合并后的整张图执行上面的规则。

### Programmatic via `Freeway.create()` / `FreewayApp.run()`

```java
Freeway.create(new HttpModule(), new DbModule(), new AppModule());
FreewayApp.run(new HttpModule(), new DbModule(), new AppModule());
```

### SPI auto-discovery

Modules can be discovered automatically through the Java `ServiceLoader` SPI.
When a library places its module class name in
`META-INF/services/com.jujin.freeway.ioc.ModuleEx`, it is picked up at startup
without the caller explicitly listing it.

For example, `freeway-db` ships with:

```
# META-INF/services/com.jujin.freeway.ioc.ModuleEx
com.jujin.freeway.db.DbModule
```

and `freeway-http` with:

```
com.jujin.freeway.http.HttpModule
```

This means an application can omit listing infrastructure modules and rely on
discovery instead:

```java
AppRuntime app = FreewayApp.run(new AppModule());
// HttpModule and DbModule are auto-discovered when on the classpath
```

Discovery **fills gaps**: a module class already declared anywhere in the
module tree — including as a `subModules()` entry of a bundle — is not added
again, so a bundle that declares `new HttpModule()` and an application with
discovery on do not collide. The author's declaration wins, the same rule that
makes an explicitly added module beat a discovered one.

Auto-discovery is enabled by default. Disable it when you want only explicitly
added modules:

```java
AppRuntime app = FreewayApp.of(new AppModule())
    .autoDiscovery(false)
    .start();
```

Discovery fills gaps only — in the `FreewayApp` path an explicitly added module
always takes precedence over a SPI-discovered one of the same class, and
discovery also skips any class already declared in the module tree (a
`subModules()` entry counts). `Freeway.create` performs no discovery at all,
so passing two distinct instances of one module class there fails startup.

## Composition Rules

- Compose modules explicitly at startup.
- SPI-discovered modules are additive — they do not replace explicit installs.
- Bindings and contributions merge across module boundaries.
- Ordered contributions can span modules.
- A module should not start servers, open connections, or launch background work in `bind()`.

## Common Patterns

- application modules wire the app together
- library modules adapt standalone code to the container
- framework modules register infrastructure and defaults
- config-driven modules select a concrete implementation from config or environment

## Module Selection

Use module selection when a library needs one of several implementations, for example selecting a SQL dialect or a connection pool based on config.

## Best Practices

- keep one integration module per library
- keep public library types free of IoC imports
- use stable ids for runtime hooks and ordered contributions
- keep module code declarative and testable
