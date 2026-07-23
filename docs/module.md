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
    public void bind(Binder b) {
        b.bind(UserService.class).to(UserServiceImpl.class);
        b.install(new HttpModule()).install(new DbModule());
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

## Installing Modules

### Explicit install via `binder.install()`

A module composes with other modules by calling `binder.install()` inside `bind()`:

```java
@Override
public void bind(Binder b) {
    b.install(new HttpModule()).install(new DbModule());
    b.bind(MyService.class).to(MyServiceImpl.class);
}
```

`install()` calls the module's `bind()` immediately — its services and extensions
are registered in the same container. Installing the same module type more than
once is a no-op (deduplication by `module.getClass()`). Returns this `Binder`
for method chaining.

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

This means an application module can omit explicit `install()` calls for
infrastructure modules and rely on discovery instead:

```java
AppRuntime app = FreewayApp.run(new AppModule());
// HttpModule and DbModule are auto-discovered when on the classpath
```

Auto-discovery is enabled by default. Disable it when you want only explicitly
added modules:

```java
AppRuntime app = FreewayApp.of(new AppModule())
    .autoDiscovery(false)
    .start();
```

Discovery deduplicates by module class — an explicitly installed instance
always takes precedence over a SPI-discovered one of the same type.

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

