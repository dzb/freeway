# Boot Reference

Examples below are minimal snippets. They omit imports and app-specific domain types.

## Entrypoints

- `FreewayApp.run(String[] args, Module2...)`
- `FreewayApp.of(Module2...)`
- `AppBuilder.add(...)`
- `AppBuilder.args(...)`
- `AppBuilder.config(...)`
- `AppBuilder.autoDiscovery(...)`
- `AppBuilder.classLoader(...)`
- `AppBuilder.shutdownHook(...)`
- `AppBuilder.start()`

## Runtime Contract

- `AppRuntime` extends `AutoCloseable`
- `AppRuntime.container()`
- `AppRuntime.config()`
- `AppRuntime.state()`
- `AppRuntime.get(Class)`
- `AppRuntime.get(Class, String)`
- `AppRuntime.start()`
- `AppRuntime.close()`

## Lifecycle Facts

- `run(...)` already starts the runtime.
- `close()` shuts down hooks, then closes the container.
- `AppStartedEvent` is published after successful startup.
- `AppStoppingEvent` is published before shutdown.
- Shutdown hook registration is enabled by default.
- Auto-discovery is enabled by default.

## Canonical Snippet

```java
try (AppRuntime runtime = FreewayApp.run(new String[0], new AppModule())) {
    Greeter greeter = runtime.get(Greeter.class);
    System.out.println(greeter.greet("World"));
}
```
