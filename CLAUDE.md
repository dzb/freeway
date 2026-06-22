# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

Requires JDK 25+.

```bash
mvn test                          # all core modules
mvn -pl freeway-ioc -am test      # single module + dependencies
mvn -pl freeway-http -am test
mvn -pl freeway-db -am test
```

Extension modules are in [freeway-ext](https://github.com/dzb/freeway-ext).
Build core first (`mvn install`), then extensions.

JUnit 5.12, SLF4J 2.0.17.

## Module Dependency Graph

```
freeway-commons
 ├─ freeway-ioc
 │   └─ freeway-boot
 ├─ freeway-http
 │   └─ (built-in: FreewayHttpEngine; external: Undertow in freeway-ext)
 └─ freeway-db
     └─ (pools: built-in / hikari)
```

Core modules are in this repository and have zero external dependencies.
Adapter modules with third-party library integrations live in the
[freeway-ext](https://github.com/dzb/freeway-ext) repository and track
the same version.

## Architecture Boundaries

- **`Container`** — IoC boundary only: `get(Class)`, `get(Class, String)`, `close()`. Created via `Freeway.create(Module2...)`.
- **`AppRuntime`** — Application boundary above Container. Owns config, profiles, startup/shutdown, runtime hooks. Created via `Launcher.run(args, Module2...)`.
- **`ServiceId`** is intentionally not a public type — service ids are plain strings, normalized internally by `ServiceIds`.
- **`Defer` / `ScopedCache`** — commons-level `ScopedValue` primitives. `Defer` buffers actions for commit-time drain; `ScopedCache` caches key-value pairs with lifecycle cleanup on scope exit. IoC's thread scope is built on `ScopedCache`.
- **Scopes** declared only via `bind().scope(...)`: `SINGLETON`, `PROTOTYPE`, `THREAD`. Thread scope is entered through `Scoping.within()`.
- **`RuntimeHook`** — module-level start/stop extension. Contributed through `Contribution<RuntimeHook>`, ordered with `before/after`. `HttpModule` contributes the server hook with stable id `"freeway.http.server"`.
- **`LoggerSource`** — built-in logger service. Commons provides JUL fallback for SLF4J only when no external provider is present. Low-level code calls `LoggingBootstrap.logger(...)`, not `LoggerFactory` directly.
- **HTTP** — `WebServer` has explicit `start()`/`stop()`. In boot, the `HttpModule` runtime hook handles this. In tests using `Container` directly, start/stop the server explicitly.
- **DB** — `Database` is the entry point. Named params (`:name`/`$name`), programmatic transactions, built-in pooling, `DatabaseHub` for multi-datasource.

## Naming Rules

- Public interfaces use the domain name directly: `Container`, `JsonCodec`, `RequestContext`.
- Framework-provided implementations use `XDefault`: `AppRuntimeDefault`, `JsonCodecDefault`, `RequestContextDefault`, `CoercerDefault`.
- `DefaultX` is avoided — `XDefault` keeps the interface name dominant.
- `Impl` is reserved for uninteresting concrete implementations where no default strategy is being expressed.
- Internal normalization helpers stay internal (e.g., `ServiceIds`).

## Injection Annotations

All in `com.jujin.freeway.ioc.annotation`: `@Inject`, `@Named`, `@Primary`, `@Symbol`, `@Value`.

- `@Symbol("key")` — strict config lookup, missing key fails.
- `@Value("${key:default}")` — expression expansion with optional default.

## Design Rules

- No classpath scanning. No bytecode weaving.
- Constructor injection for framework internals; field injection acceptable for app code and config values.
- Core modules keep external dependencies out. Adapter modules with third-party deps live in freeway-ext.
- Prefer small explicit APIs over future-proof abstractions.
- Keep concepts few: Module, Service, Extension, Scope, Runtime.

## Config Cascade (high to low priority)

1. CLI args (`--key=value`, `-Dkey=value`)
2. Env vars (`FREEWAY_` prefix)
3. `application-{profile}.json`
4. `application-{profile}.properties`
5. `application.json`
6. `application.properties`

Activate profiles: `--freeway.profile=dev`

## Commit Rules

- Never include `Co-Authored-By`, AI tool names, or any form of AI attribution in commit messages.
- Commit messages describe the change itself, never the process or tooling used.
- All commits appear under the user's name only.
