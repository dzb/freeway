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
| `freeway-ioc` | Container, binding DSL, scopes, injection, extensions, symbol config, in-process event bus | commons |
| `freeway-boot` | Launcher, runtime lifecycle, profiles, config cascade (hot reload) | ioc + commons |
| `freeway-http` | Routing, built-in HTTP engine, WebSocket, SSE | ioc + commons (+ boot, test) |
| `freeway-db` | JDBC, ORM, pooling, transactions, migrations | commons (+ ioc, DbModule only) |
| `freeway-flow` | Graph workflow engine — 7 node types, v3 DAG format (boot-validated), task vocabulary, branch isolation | ioc + commons |
| `freeway-cloud` | Cloud-native foundation — discovery, remote invocation (JDK HttpClient), event fabric (`CloudEventBus`), observability, resilience, health, secrets, storage | ioc + commons + http (+ boot, test) |

No module adds an external dependency beyond SLF4J 2.0.18 (declared explicitly
by every module that logs — currently all but `ioc`, which inherits it
transitively) plus JUnit at test scope. Anything else belongs in an ext adapter.

## Naming

- Public interfaces use the domain name: `Container`, `JsonCodec`, `Route`.
- **`XDefault` vs `XImpl`** — the deciding question is whether the *outside can
  substitute* the implementation for that role. The operational test is one
  line: **can a module bind an alternative with `.primary()` and have the
  container honor it?** If yes the type is `XDefault`, no matter how concretely
  the framework itself wires it, and no matter that it lives in `internal`:
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
  - Substituting a role means honoring its whole seam, not just its lookups:
    contributions reach the config chain through one channel —
    `contribute(SymbolProvider.class)` into the extension store; there is no
    register/replay step. A replacement `SymbolSource` must take that view
    itself, in the factory that builds it:
    `binder.bind(SymbolSource.class).to(c -> new MySource(c.extension(SymbolProvider.class)))`.
    A replacement that ignores the view serves only its own tiers and boot's
    cascade disappears silently (`SymbolSourceReplacementTest` pins the
    pattern).
- **Factory verbs**: `of` builds a *value* from the parts you hand it — the
  records do this (`Endpoint.of`, `ServiceInstance.of`, `SymbolSpec.of`),
  mirroring `List.of`. `create` is the framework *entry
  point* that hands you something to configure or run (`Freeway.create`,
  `FreewayApp.create`, `FlowEngine.create`, `Graph.create`), and `.builder()`
  is fluent assembly of a configured object — licensed *only* when the builder
  holds no defaults of its own (`FlowDriverDefault.Builder`: two required
  parts, nothing stated). An object that varies from a stated default in a
  field or two is a `defaults()` value plus per-field withers returning new
  instances — the wither verb is `withX` (`HttpServerConfig.withPort`,
  `CorsFilter.withAllowedOrigins`, `StaticResourceMount.withFallthrough`), while
  a bare noun form means a read (`StaticResourceMount.fallthrough()`), so a call
  site can always tell an assignment from a lookup — never a builder that copies
  those defaults into itself, which is a second owner of
  the same answer.
  Assembling a *service graph* is never a builder's job: `HttpServer.create(…)`
  is the one derivation of a server from its parts, `HttpModule` is its
  container face (keys → value types, contributions → parts), and a caller with
  no container calls `create` directly — the standalone `WebServerBuilder` that
  duplicated that root, and the `HttpModuleConfig` snapshot that restated the
  value types' defaults a second time, are both gone (see CHANGELOG 1.5.5).
  One verb per role: `FreewayApp.create` replaced `FreewayApp.of` and
  `FlowEngine.create` replaced `FlowEngine.newInstance`, so the entry points no
  longer disagree with `Freeway.create` next to them.
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
- **Who may hold the `Container`.** It is the framework's central abstraction, so
  handing one to a collaborator is an intrusion: that collaborator can reach everything,
  and the boundary the handoff was meant to mark is gone. **The test is whether the
  container exists yet at the moment the code is written** — a provider closure, a
  contribution, and a `RuntimeHook` are all written *while the container is still being
  composed*, when there is nothing to inject from, so each receives the `Container` as a
  parameter. Everything written after that point resolves by injection instead, and a
  class inside a module takes its dependencies through `@Inject` / `@Symbol` constructor
  injection. A `RuntimeHook` is the framework's designed point of business intervention
  and is no exception to this: the anonymous class in
  `binder.contribute(RuntimeHook.class).add(id, new RuntimeHook() {...})` is
  constructed at `bind` time, so by the time its `start` runs the instance is long since
  fixed and there is nothing left to inject into it — the container it is handed is the
  only handle that can still reach anything. **Pass a capability, not the container**:
  `ResolvableHandler.resolve` takes a `Supplier<RouteHandler>`, so the `route` package
  never names the type — the container does not cross the boundary, only the ability to
  make a handler does. The grep test, over framework code: in
  `freeway-http/src/main/java`, `route/` and `websocket/` name `Container` in
  javadoc only, and every `container.*` call sits in `HttpModule` —
  one file, because one file is where composition happens. (Tests are assembly
  points and do hold containers; the rule is about the code that ships.) A consequence worth stating,
  because it looks like a limitation: a class-based route handler is built during
  composition, so it **cannot** take a `Scope.THREAD` dependency. That is the scope
  meaning what it says — a thread-level lifetime, opened and closed by
  `Scoping.within(...)`, and the framework opens none of its own — so a
  composition-time instance has no thread to belong to.
- Keep core modules free of external dependencies.
- Prefer small explicit APIs over future-proof abstractions.
- **File size is not a reason to split.** A long file is a prompt to ask whether its
  responsibilities are cohesive and what the split would actually buy — not an
  instruction. `JULEnhancer` (920 lines, one lifecycle: bootstrap config → handlers →
  formatting → rotation) and `Sql` (45 methods, one grammar) are long *and* cohesive;
  splitting them would spread one idea across files and make every reader pay for the
  index. Split when a part has its own reason to change, its own tests, or a consumer
  that wants it without the rest — judge by cohesion and ROI, never by line count.
- Keep concepts few: Module, Service, Extension, Scope, Runtime. Composition is
  the ordered modules handed to an entry point (`Freeway.create`,
  `FreewayApp.run`/`create`); the validated structure behind it (`ModuleNode`)
  is internal to `ioc.internal`, and boot's launcher is `FreewayApp` itself — a
  separate tree type or builder type would be a sixth concept with no question
  of its own.
- **Composition over inheritance, with four named exceptions.** Extension happens
  through binding substitution, contribution, `.primary()` and proxy decoration —
  never by subclassing a framework type. A new `extends` must name its exception:
  language-mandated (exceptions, JDK callbacks), true is-a (final, closed set),
  closed protocol hierarchies (invisible outside their package), or implementation
  shared with third-party adapters. Reusing `internal` logic across modules means
  promoting the smallest possible interface — copying it requires a comment
  pointing back at the source.
- **Exports are quarantine, not architecture.** Modularity lives in package
  responsibilities and dependency layering (verified, not declared) — JPMS
  `exports`/`opens` add no design, only enforcement, and this project explicitly
  promises no compatibility, so there is no stranger-facing promise to enforce.
  If a module's internals ever get messy enough to want a fence, that want is
  recorded as cleanup debt, not modernization. The JPMS posture today is *none*:
  no `module-info`, no `Automatic-Module-Name`, no `jdeps` gate — those two are
  the hooks to add if a JPMS-only downstream ever appears, not something the
  build already does.
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
- **Keys live in a `ConfigKeys` nested in the module that reads them, spelled as
  full literals**: `HttpModule.ConfigKeys`, `DbModule.ConfigKeys`,
  `CloudModule.ConfigKeys` (both namespaces it spells) and — for the cascade's own
  activation keys — `BootModule.ConfigKeys`. A key is never composed from
  `PREFIX + "…"`, so the table answers "what is the exact key?" on its own line;
  `PREFIX` survives only as the namespace the vocabulary is fenced to, and the
  module contributes `KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX, …)` naming
  every namespace it spells — a table key outside them fails at bind instead of
  leaving a namespace the unknown-key check never reports on. Identity strings that
  merely look like keys (runtime-hook ids, route paths, contribution ids) stay out
  of `ConfigKeys`: that type boundary is what keeps them out of the vocabulary.
  `commons` is the one exception — it is not a module, so its logging table stays a
  top-level package-private `LogKeys` published as `LogConfig` for boot to declare,
  and `EnvKeys` stays the env-mapping mechanism. Infra owns no *feature*
  configuration: among the infra modules only logging is application config.
- **One chain, one tier definition**: a configuration tier *is* a
  `SymbolProvider` (JVM system properties is `SymbolProvider.systemProperties()`
  at `TIER_SYS_PROPS`), and a source *is* the chain over them
  (`SymbolSource.of(coercer, providers…)`) — never a per-caller re-implementation
  of lookup, expansion or typing. The container builds the chain with its own
  `Coercer` so contributed `CoerceRule`s reach `resolve(SymbolSpec)`; a caller
  without a container (an adapter, a test) builds the same chain with the
  coercer it has and gets the same semantics.

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
- **Adapters**: a third-party engine's contract tests must pin the
  contract-typed edges no type enforces — `maxBodySize` accounting through the
  shared `AbstractHttpContext.readBody` (413), which now delegates to the one
  limiter (`http.internal.LimitedInputStream`) the built-in engine runs, so
  adapter and built-in 413 behaviour are the same code rather than two loops
  that happen to agree; gzip negotiation through the shared `Compression`,
  `ExchangeHandler.websocket` consulted for every upgrade candidate, and every
  `HttpServerConfig` field honored or reported at startup.

## Commit Rules

- Never include `Co-Authored-By`, AI tool names, or any form of AI attribution in commit messages.
- Commit messages describe the change itself, never the process or tooling used.
- All commits appear under the user's name only.

## Further Reading

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — module boundaries, injection
  annotations, config cascade mechanics, lifecycle notes.
- [docs/freeway-reflection.md](docs/freeway-reflection.md) — every reflective
  site, its cache shape, and the rules for adding one.
- [docs/DEVELOPER-GUIDE.md](docs/DEVELOPER-GUIDE.md) — comprehensive usage
  guide for all modules.
- [docs/freeway-config.md](docs/freeway-config.md) — every config key, by module.
- [docs/](docs/) — config samples, DB usage, feature summaries.
