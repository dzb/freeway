# Module Reference

Module is the unit of composition in Freeway. `ModuleEx` is the Java type name used to avoid a conflict with `java.lang.Module`; conceptually, Freeway code talks about modules.

## What A Module Does

- binds services
- contributes extensions

`bind()` declares. It does not start work. Initialization happens when services are resolved or when runtime hooks fire.

A module is a **leaf**: it knows nothing about how the application is composed. Grouping lives in the composition itself — a `ModuleNode` tree built at the entry point and handed to the container as a value.

```java
public final class OrderModule implements ModuleEx {
    @Override
    public void bind(Binder b) {
        b.bind(OrderService.class).to(OrderServiceImpl.class);
    }
}
```

## Composing Modules

### The module tree is a value

`ModuleNode` is the composition. Build it where the application is assembled, name it there, and pass it to the container:

```java
ModuleNode app = ModuleNode.app("order-service",
    OrderModule.class,                 // declare by class — the normal way
    ModuleNode.group("web", HttpModule.class, WebSocketModule.class),
    CloudModules.standard());          // a fragment built the same way

FreewayApp.run(app);
```

### Declaring modules: class by default, instance for configuration

```java
ModuleNode.leaf(OrderModule.class);          // normal: no-arg constructor
ModuleNode.leaf(new TenantModule("acme"));  // an instance, when the constructor takes configuration
FreewayApp.run(OrderModule.class, HttpModule.class);
FreewayApp.run(ModuleNode.app("orders", OrderModule.class));
```

Naming the class is still explicit — the composition names it, so nothing is scanned — and the class is instantiated through its **no-arg constructor**. A module's constructor carries configuration, not dependencies: there is nothing to inject before the container exists, so dependencies are declared in `bind(Binder)` as always. A class without a no-arg constructor fails where the tree is built, naming itself and the fix (`ModuleNode.leaf(new X(…))`) — inside the framework the only such module is the internal `BootModule`, which the boot layer constructs itself.

Declaring one class twice is the same mistake as two instances of it — the tree holds one instance per module class, so `run(A.class, A.class)` fails and names the fix: declare the class once. Sharing between branches goes through a **fragment**, not through a repeated declaration.

| Factory | Meaning |
|---|---|
| `app(name, …)` | the application root: it names the tree and binds nothing |
| `group(name, …)` | a named bundle node — how a library ships several modules at once |
| `leaf(class \| module)` | a single module, no children |
| `of(class \| module, …)` | a module that groups others |

Every factory takes either a module **class** (instantiated through its no-arg constructor) or an **instance** (for a module whose constructor takes arguments); `app` and `group` accept a varargs list of classes, and anything mixed is expressed with `leaf`/`of` children.

The flat entry points stay as sugar for "the application root's children":

```java
FreewayApp.run(HttpModule.class, DbModule.class);
// ≡ FreewayApp.run(ModuleNode.app("application",
//       ModuleNode.leaf(HttpModule.class), ModuleNode.leaf(DbModule.class)));
```

`Freeway.create(...)` (test and standalone usage) takes the same two forms. `FreewayApp.of(...)` + `.add(...)` accepts modules and fragments in any order:

```java
FreewayApp.of(OrderModule.class)
    .add(CloudModules.standard())
    .add(HttpModule.class)
    .start();
```

### Fragments: shipping several modules at once

A library that needs more than one module exposes a **fragment factory** returning a `ModuleNode` — a plain value a caller can place, nest, inspect or leave out:

```java
public final class CloudModules {
    public static ModuleNode standard() {
        return ModuleNode.group("freeway-cloud",
            CloudContextModule.class,
            CloudRpcModule.class,
            /* … */);
    }
}
```

This is what the deleted umbrella *module* could not do. `CloudModule` was a `ModuleEx` whose `subModules()` owned its children, so an application could not replace or omit one of them (two instances of a module class are refused). A fragment is data: taking it apart is normal composition.

### What construction guarantees

`ModuleNode` normalizes and validates while it is built — cycles are unrepresentable (a child cannot reference a node that does not exist yet), and a tree that names a module twice is refused:

| Case | Result |
|---|---|
| two **instances** of one module class | `IllegalStateException` naming both paths (`app → web → HttpModule`, …) and stating the rule |
| the same **instance** reached twice | collapsed, keeping the first placement — sharing a fragment is normal |
| anonymous / lambda modules | compared by identity only (no meaningful class) |
| `app` / `group` nodes | structural: they bind nothing and are exempt from the class rule |

Failures surface where the tree is built — the assembly code — not at container startup.

### The container holds the tree

```java
Container c = Freeway.create(app);
c.moduleTree();                 // the same ModuleNode the container bound
c.moduleTree().tree();          // TreeNode<ModuleEx>: preOrder / levelOrder / size / height
c.moduleTree().bindOrder();     // modules in binding order (pre-order)
```

**Binding order is the tree's pre-order**: parents before children, siblings in declaration order. It is deterministic but **not a contract** — sequencing belongs to `RuntimeHook` anchors (`before`/`after` ids) and contribution `order()`, not to where a module sits in the tree. `TreeNode.levelOrder()` exists for display.

### Entry points

```java
Freeway.create(app);                                   // returns the Container
FreewayApp.run(app);                                   // returns the AppRuntime
FreewayApp.run(new String[]{"--freeway.profile=dev"}, app);
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

Discovery **fills gaps**: it is skipped for any class the tree already declares — anywhere, fragments included — so a fragment that places `new HttpModule()` and an application with discovery on do not collide. The author's declaration wins.

```java
AppRuntime app = FreewayApp.run(new AppModule());
// HttpModule and DbModule are auto-discovered when on the classpath
```

Auto-discovery is enabled by default. Disable it when you want only the modules you placed:

```java
AppRuntime app = FreewayApp.of(app).autoDiscovery(false).start();
```

`Freeway.create` performs no discovery at all.

## Composition Rules

- Compose modules explicitly at the entry point; a module never installs another module.
- Ship a fragment factory when a library needs more than one module.
- Bindings and contributions merge across module boundaries.
- Ordered contributions can span modules.
- A module should not start servers, open connections, or launch background work in `bind()`.
- Do not rely on bind order; use hooks and contribution order for sequencing.

## Common Patterns

- application modules wire the app together
- library modules adapt standalone code to the container, plus a fragment factory for bundles
- framework modules register infrastructure and defaults
- config-driven modules select a concrete implementation from config or environment

## Best Practices

- keep one integration module per library
- keep public library types free of IoC imports
- use stable ids for runtime hooks and ordered contributions
- keep module code declarative and testable
- place a fragment once per tree: one module instance belongs to one place in one composition
