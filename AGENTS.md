# Repository Guidelines

Freeway is a JDK 25+ multi-module Maven project. Keep changes scoped, explicit,
and convention over configuration. This file is the single home for repo-wide
conventions: build, module layout, naming, design rules, testing, commit rules.
Architecture boundaries and framework internals live in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — read it before changing module
boundaries, hooks, config resolution or lifecycle.

## Build

Requires JDK 25+ (JUnit 6.1.3, SLF4J 2.0.18).

```
mvn test                            # all core modules
mvn -pl freeway-ioc test            # single module
mvn -pl freeway-http -am test       # module + its upstream deps
mvn -pl freeway-cloud -am test      # module + its upstream deps
mvn test -Dtest=CoercerDefaultTest  # one test class
```

Run `mvn -pl <module> -am test` (or `clean test`) after touching a module that
others consume: a stale installed snapshot hides ABI changes, and the failure
surfaces later as a `NoSuchMethodError` in a downstream module. Third-party
adapters (Undertow/Jetty engines, HikariCP, Kafka) live in
[freeway-ext](https://github.com/dzb/freeway-ext): `mvn install` the core first.

## Module Map

| Module | Purpose | Module dependencies |
|--------|---------|---------------------|
| `freeway-commons` | JSON, coercion, defer, scoped cache, validation, logging | — |
| `freeway-ioc` | Container, binding DSL, scopes, injection, extensions, symbol config | commons |
| `freeway-boot` | Launcher, runtime lifecycle, profiles, config cascade (hot reload) | ioc |
| `freeway-http` | Routing, built-in HTTP engine, WebSocket, SSE | ioc + commons |
| `freeway-db` | JDBC, ORM, pooling, transactions, migrations | commons (+ ioc, DbModule only) |
| `freeway-flow` | Graph workflow engine — 7 node types, v2 DAG format, task resolution | ioc + commons |
| `freeway-cloud` | Cloud-native foundation — discovery, remote invocation (JDK HttpClient), observability, resilience, health, secrets, storage | ioc + commons + http (+ boot, test) |

No module adds an external dependency beyond SLF4J 2.0.18 (declared by
`commons`, `http` and `cloud`; the rest inherit it transitively) plus JUnit at
test scope. Anything else belongs in an ext adapter.

## Naming

- Public interfaces use the domain name: `Container`, `JsonCodec`, `Route`.
- **`XDefault` vs `XImpl`** — the deciding question is whether the *outside can
  substitute* the implementation for that role:
  - `XDefault` — it can: an extension binds an alternative with `.primary()`,
    an adapter builds on the default, or config activates another one.
    Examples: `AppRuntimeDefault`, `JsonCodecDefault`, `PoolDefault`,
    `FlowDriverDefault`, `FlowEngineDefault`, `ExchangeMetaDefault` and the
    twelve cloud defaults.
  - `XImpl` — it cannot: container-internal assembly (`ContainerImpl`,
    `BindingImpl`, `DatabaseImpl`), engine-internal components
    (`HttpContextImpl`), or per-owner types that coexist with other
    implementations (`PooledConnectionImpl`). A type stays `XDefault` even
    where the framework wires it concretely.
- `DefaultX` is avoided — `XDefault` keeps the interface name dominant.
- Package location is orthogonal to the suffix: `internal` is part of Freeway
  and marks "no stability promise" for callers, not a visibility gate — classes
  there may stay `public` when sibling packages assemble them. A `XDefault`
  may live in `internal` when the module so organizes it (`PoolDefault` in
  `db/internal` is still substituted from outside via `.primary()`, which never
  references the class itself).
- An owner's collaborators live in the owner's package, package-private: a
  type is `public` only when another package must assemble or substitute it.
  `ioc.internal` therefore holds exactly one public type — `ContainerImpl`,
  the thing `Freeway` constructs.

## Design Rules

- No classpath scanning. No bytecode weaving.
- Constructor injection for framework internals; field injection acceptable for
  app code and config values.
- Keep core modules free of external dependencies.
- Prefer small explicit APIs over future-proof abstractions.
- Keep concepts few: Module, Service, Extension, Scope, Runtime.
- **Optional inputs**: one or two of them use a documented overload ladder, each
  step stating what it adds (`RemoteCaller.invoke`); three or more use a
  parameter record with `defaults()` and per-field withers
  (`CloudHttpClientDefault.Wiring`), so a call site cannot drift between
  overloads. A record's canonical constructor changes shape whenever a component
  is added — that is the intended outcome, not an accident: no previous-arity
  delegating constructor is kept for callers that have already been compiled.
  Adding a component means updating every call site (in `freeway-ext` too), and
  the compile error is the migration.
- **No compatibility shims**: a renamed key, a replaced API shape or a superseded
  mechanism is deleted, not kept alongside its replacement. Two live names for one
  thing are two answers to one question, and the reader pays for them forever.
  The one obligation this leaves is loudness: a deletion that could make existing
  configuration stop taking effect must report itself at startup and name the fix
  (`JULEnhancer.renamedFileNotice`). Compatibility is not a goal of this project —
  adapters and applications are expected to move with it.
- **Stability means stable semantics, not a frozen shape**: "unchanged" is the usual
  reading of "stable", but it only holds for behaviour. A frozen signature whose
  behaviour drifts is the worst case there is — it bypasses the compiler and lands in
  production. Adaptation is a reading problem: an adapter or an agent moves with a break
  as soon as it can see what changed, which is why the change record is part of the
  change rather than paperwork. Three criteria separate evolution from churn:
  - a behaviour change carries its "why" in the CHANGELOG — an unexplained behaviour
    change is the real instability, whether or not a signature moved;
  - a shape change surfaces in the compiler: with source-level consumers the compile
    error *is* the migration path, and only a clean build proves anything (a stale
    `target/` swallows it, as it did when `Wiring` lost its previous arity);
  - a break worth paying for leaves the caller's code smaller or unchanged. Adapting by
    renaming something, adding an argument or relocating a concept is churn; a break that
    deletes a concept or a layer is the kind that earns its cost.
- **Config ownership by layer**: `ioc.symbol` holds resolution mechanisms that
  know no concrete keys — value types (`SymbolSpec.list`, `splitList`) and
  shape rules (`SymbolSpec.activated`/`mode`), parameterized by the caller's
  key. `boot` holds the application-configuration content — the config file
  family, profile selection. Each feature module owns its own
  keys' declarations, token tables and presence criteria. A mechanism with
  application data in it moves down only when it carries no key names; data
  about specific keys moves up to their owner.

## Testing

JUnit 6.1.3. Tests use `*Test` suffix, live beside the module they cover.
Add regression coverage for failure modes on resource and lifecycle boundaries.
A framework member with no caller inside this repository is not dead — the
consumers live in the ext repository or in applications — so look for the
*role* before removing it, and pin new contracts with a test rather than a
comment.

## Regressions to Watch

- **Static files**: keep resolved paths inside the mount root; disallow symlink
  traversal.
- **Connections**: release pooled connections exactly once, including exception
  paths and repeated `close()`.
- **Runtime hooks**: fail startup on invalid hook configuration (don't silently
  skip).
- **SQL parameters**: respect strings, comments, PostgreSQL `::` casts, and
  repeated named parameters.

## Commit Rules

- Never include `Co-Authored-By`, AI tool names, or any form of AI attribution in commit messages.
- Commit messages describe the change itself, never the process or tooling used.
- All commits appear under the user's name only.

## Further Reading

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — module boundaries, injection
  annotations, config cascade mechanics, lifecycle notes.
- [docs/DEVELOPER-GUIDE.md](docs/DEVELOPER-GUIDE.md) — comprehensive usage
  guide for all modules.
- [docs/freeway-config.md](docs/freeway-config.md) — every config key, by module.
- [docs/](docs/) — config samples, DB usage, feature summaries.
