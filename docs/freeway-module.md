# Module Reference

Module is the unit of composition in Freeway. `ModuleEx` is the Java type name used to avoid a conflict with `java.lang.Module`; conceptually, Freeway code talks about modules.

## What A Module Does

- binds services
- contributes extensions

`bind()` declares. It does not start work. Initialization happens when services are resolved or when runtime hooks fire.

A module declares its bindings and nothing about how the application is composed; grouping lives in the composition itself — the modules the entry point is handed, in order. A library that ships several modules declares them on a bundle class with `@SubModule`.

```java
public final class OrderModule implements ModuleEx {
    @Override
    public void bind(Binder b) {
        b.bind(OrderService.class).to(OrderServiceImpl.class);
    }
}
```

## Composing Modules

### Composition is the modules you place

Place modules where the application is assembled, in the order they should bind, and hand them to the entry point:

```java
FreewayApp.run(
    OrderModule.class,                 // declare by class — the normal way
    HttpModule.class,
    CloudModule.class);                // a bundle: CloudModule + its @SubModule
```

### Declaring modules: class by default, instance for configuration

```java
FreewayApp.run(OrderModule.class, new TenantModule("acme"));
Freeway.create(new OrderModule(), CloudModule.class);   // the same shape
```

A class is instantiated through its **no-arg constructor** when the composition is resolved, not where the call is written — a call site only ever names declarations. A module's constructor carries configuration, not dependencies: there is nothing to inject before the container exists, so dependencies are declared in `bind(Binder)` as always. A class without a no-arg constructor fails at load, naming itself and the fix (`new X(…)`) — inside the framework the only such module is the internal `BootModule`, which the boot layer constructs itself.

Declaring one class twice is refused whether it was named by class or given as an instance: `run(A.class, A.class)` fails and names the fix — declare the class once. A module **instance** belongs to one place in one composition; sharing one instance across two containers is normal, and a class declaration is resolved per load, so each container builds its own module.

`FreewayApp.create(...)` + `.add(...)` assembles the same ordered list:

```java
FreewayApp.create(OrderModule.class)
    .add(CloudModule.class)       // the whole cloud bundle
    .add(HttpModule.class)
    .start();
```

### Bundles: shipping several modules at once

A library that ships more than one module annotates the umbrella module: the class **is** the bundle, and placing it places its declared submodules (after it, in declaration order):

```java
@SubModule({
    CloudContextModule.class,
    CloudRpcModule.class,
    /* … */
})
public final class CloudModule implements ModuleEx {
    @Override public void bind(Binder binder) {
        // the bundle's own, shared surface
    }
}
```

The declaration is static data read while the composition is assembled — not a method the framework calls back into — so the caller still decides what is placed. A submodule is an ordinary module: placing `CloudRpcModule.class` alone is the subset form, and no exclusion list exists because taking the bundle apart is just composing the modules you want.

### What composition guarantees

Assembling the composition normalizes and validates it — cycles are refused (through the module values, and through `@SubModule`: a class cannot bundle itself), and a composition that names a module twice is refused:

| Case | Result |
|---|---|
| two **declarations** of one module class | `IllegalStateException` naming both paths (`application → CloudModule → HttpModule`, …) and stating the rule |
| the same **instance** placed twice | collapsed, keeping the first placement — sharing a module instance is normal |
| anonymous / lambda modules | compared by identity only (no meaningful class); they are instance declarations |
| a `@SubModule` cycle | `IllegalStateException` naming the cycle while the composition is assembled |

Failures surface where the composition is assembled — the code that places the modules — not at container startup.

### Binding order

**Binding order is the pre-order over the placed modules**: a bundle's own `bind()` runs before the modules it declares, and siblings bind in the order they were placed. It is deterministic but **not a contract** — sequencing belongs to `RuntimeHook` anchors (`before`/`after` ids) and contribution `order()`, not to where a module sits. Loading resolves each class declaration while binding, so a class declaration is constructed only then.

The structure is still visible, in the startup log rather than through an API — the container renders the composition it bound, ready for the log:

```
Loaded 9 module(s):
- application
  - OrderModule
  - CloudModule
    - CloudContextModule
    …
```

The root line is the application's name: `application` unless the launch names it (`FreewayApp.create(...).name("order-service")`, or `Freeway.create("order-service", new OrderModule())` without boot — the named container entry takes module instances, since a named class-declaring overload would make a lone name ambiguous). It is presentation only — no binding, ordering or identity depends on it — and it appears in composition errors too, as the first segment of the path that names a duplicate (`order-service → CloudModule → HttpModule`).

### Entry points

```java
Freeway.create(OrderModule.class, HttpModule.class);      // returns the Container
Freeway.create("order-service", new OrderModule());       // the same, with a named root
FreewayApp.run(OrderModule.class, CloudModule.class);     // returns the AppRuntime
FreewayApp.run(new String[]{"--freeway.profile=dev"}, OrderModule.class);
FreewayApp.create(OrderModule.class).name("order-service").start();
```

## SPI auto-discovery

Modules can be discovered automatically through the Java `ServiceLoader` SPI. When a library places its module class name in `META-INF/services/com.jujin.freeway.ioc.ModuleEx`, it is picked up at startup without the caller explicitly listing it.

For example, `freeway-db` ships with:

```
# META-INF/services/com.jujin.freeway.ioc.ModuleEx
com.jujin.freeway.db.DbModule
```

and `freeway-http` with:

```
com.jujin.freeway.http.HttpModule
```

Discovery **fills gaps**: it is skipped for any class the composition already declares — anywhere, bundles included — so a bundle that declares `HttpModule` and an application with discovery on do not collide. The author's declaration wins.

```java
AppRuntime app = FreewayApp.run(new AppModule());
// HttpModule and DbModule are auto-discovered when on the classpath
```

Auto-discovery is enabled by default. Disable it when you want only the modules you placed:

```java
AppRuntime app = FreewayApp.create(new AppModule()).autoDiscovery(false).start();
```

`Freeway.create` performs no discovery at all.

## Composition Rules

- Compose modules explicitly at the entry point; a module never silently installs another module — a bundle declares its submodules with `@SubModule`, which is data the entry point reads.
- Declare `@SubModule` when a library ships more than one module together; place submodules directly for a subset.
- Bindings and contributions merge across module boundaries.
- Ordered contributions can span modules.
- A module should not start servers, open connections, or launch background work in `bind()`.
- Do not rely on bind order; use hooks and contribution order for sequencing.

## Common Patterns

- application modules wire the app together
- library modules adapt standalone code to the container, plus a `@SubModule` bundle class for groups
- framework modules register infrastructure and defaults
- config-driven modules select a concrete implementation from config or environment

## Best Practices

- keep one integration module per library
- keep public library types free of IoC imports
- use stable ids for runtime hooks and ordered contributions
- keep module code declarative and testable
- place a bundle once per composition: one module instance belongs to one place
