# Module Reference

Module is the unit of composition in Freeway. `Module2` is the Java type name used to avoid a conflict with `java.lang.Module`; conceptually, Freeway code talks about modules.

## What A Module Does

- binds services
- contributes extensions
- composes with other modules

`bind()` declares. It does not start work. Initialization happens when services are resolved or when runtime hooks fire.

## Typical Shapes

### Application module

```java
public final class AppModule implements Module2 {
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

## Composition Rules

- Compose modules explicitly at startup.
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

