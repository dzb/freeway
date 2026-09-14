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
    HttpModule.class,
    CloudModule.class);                // a bundle: CloudModule + its @SubModule

FreewayApp.run(app);
```

### Declaring modules: class by default, instance for configuration

```java
ModuleNode.leaf(OrderModule.class);          // normal: no-arg constructor
ModuleNode.leaf(new TenantModule("acme"));  // an instance, when the constructor takes configuration
FreewayApp.run(OrderModule.class, HttpModule.class);
FreewayApp.run(ModuleNode.app("orders", OrderModule.class));
```

Naming the class is still explicit — the composition names it, so nothing is scanned — and it is instantiated through its **no-arg constructor** when **loading starts**, not while the tree is composed: each node holds its declaration (a class, or an instance for a configured module). A module's constructor carries configuration, not dependencies: there is nothing to inject before the container exists, so dependencies are declared in `bind(Binder)` as always. A class without a no-arg constructor fails at load, naming itself and the fix (`ModuleNode.leaf(new X(…))`) — inside the framework the only such module is the internal `BootModule`, which the boot layer constructs itself.

Declaring one class twice is refused the same way whether it was named by class or given as an instance — the tree holds one declaration per module class, so `run(A.class, A.class)` fails and names the fix: declare the class once. Sharing between branches goes through a **shared node value**, not through a repeated declaration. A class-only tree can be loaded by more than one container: each load resolves its own module.

| Factory | Meaning |
|---|---|
| `app(name, …)` | the application root: it names the tree and binds nothing |
| `leaf(class \| module)` | a module node — a single module, or a bundle's subtree when its class declares `@SubModule` |

Every factory takes either a module **class** (resolved through its no-arg constructor at load) or an **instance** (for a module whose constructor takes arguments, and the only form a lambda or anonymous module can take); `app` accepts a varargs list of classes, and anything mixed is expressed with `leaf` children.

The flat entry points stay as sugar for "the application root's children":

```java
FreewayApp.run(HttpModule.class, DbModule.class);
// ≡ FreewayApp.run(ModuleNode.app("application",
//       ModuleNode.leaf(HttpModule.class), ModuleNode.leaf(DbModule.class)));
```

`Freeway.create(...)` (test and standalone usage) takes the same two forms. `FreewayApp.of(...)` + `.add(...)` accepts modules and composed trees in any order:

```java
FreewayApp.of(OrderModule.class)
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

The declaration is static data read while the composition tree is built — not a method the framework calls back into — so the entry point still decides what is placed. A submodule is an ordinary module: placing `ModuleNode.leaf(CloudRpcModule.class)` alone is the subset form, and no exclusion list exists because taking the bundle apart is just composing the modules you want.

### What construction guarantees

`ModuleNode` normalizes and validates while it is built — cycles are refused (through the value graph, and through `@SubModule`: a class cannot bundle itself), and a tree that names a module twice is refused:

| Case | Result |
|---|---|
| two **declarations** of one module class | `IllegalStateException` naming both paths (`app → web → HttpModule`, …) and stating the rule |
| the same **instance** reached twice | collapsed, keeping the first placement — sharing a node value is normal |
| anonymous / lambda modules | compared by identity only (no meaningful class); they are instance declarations |
| the application root | structural: it binds nothing and is exempt from the class rule |
| a `@SubModule` cycle | `IllegalStateException` naming the cycle while the tree is built |

Failures surface where the tree is built — the assembly code — not at container startup.

### The container holds the tree

```java
Container c = Freeway.create(app);
c.moduleTree();                 // the same ModuleNode the container bound
c.moduleTree().render();        // indented structure, as shown in the startup log
c.moduleTree().children();      // child nodes in order; a plain module has none
c.moduleTree().bindOrder();     // module nodes in binding order (pre-order); each resolves to its module
c.moduleTree().bindOrder().get(0).resolve();  // the module behind a declaration
```

**Binding order is the tree's pre-order over module nodes**: the application root binds nothing, a module binds before the modules below it, and siblings bind in declaration order. It is deterministic but **not a contract** — sequencing belongs to `RuntimeHook` anchors (`before`/`after` ids) and contribution `order()`, not to where a module sits in the tree. Loading resolves each declaration while binding, so a class declaration is constructed only then.

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

Discovery **fills gaps**: it is skipped for any class the tree already declares — anywhere, bundles included — so a bundle that places `new HttpModule()` and an application with discovery on do not collide. The author's declaration wins.

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
- place a bundle once per tree: one module instance belongs to one place in one composition
