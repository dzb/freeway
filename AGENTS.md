# Repository Guidelines

Freeway is a JDK 25+ multi-module Maven project. Keep changes scoped, explicit,
and convention over configuration. This file is the single home for repo-wide
conventions; architecture and lifecycle details live in
[docs/architecture.md](docs/architecture.md), and the design judgment behind the
rules lives in [TASTE.md](TASTE.md).

## Build

Requires JDK 25+ (JUnit 6.1.3, SLF4J 2.0.18).

```
mvn test                            # all core modules
mvn -pl freeway-ioc test            # one module
mvn -pl freeway-http -am test       # module + upstream deps
mvn -pl freeway-cloud -am test
mvn test -Dtest=CoercerDefaultTest
```

Run `mvn -pl <module> -am test`, or `clean test` after moving/deleting a type:
a stale `target/` hides deleted classes and installed snapshots hide ABI breaks.

Core must be installed before building ext:

```
mvn -o install -DskipTests
# then, in freeway-ext:
mvn -o clean test
```

CI runs four gates: `verify` (source/javadoc JARs + tests), the demos profile,
the docs consistency tests, and a cross-repository ext build against the core
snapshot. CI is verification-only: it never signs, deploys or publishes, and
holds no release credentials. Releases are manual and belong to the signing
machine.

## Module Map

Core — only what every Freeway application needs:

| Module | Purpose | Depends on |
|---|---|---|
| `freeway-commons` | JSON, coercion, defer, scoped cache, validation, logging | — |
| `freeway-ioc` | Container, bindings, scopes, injection, extensions, symbol config, event bus | commons |
| `freeway-boot` | Launcher, runtime lifecycle, profiles, config cascade/hot reload | ioc + commons |
| `freeway-http` | Routing, built-in HTTP engine, WebSocket, SSE | ioc + commons (+ boot, test) |
| `freeway-db` | JDBC, ORM, pooling, transactions, migrations | commons (+ ioc for `DbModule`) |
| `freeway-flow` | Graph workflow engine, v3 DAG, task vocabulary | ioc + commons |
| `freeway-cloud` | Service-membership lifecycle, RPC, mesh events, observability, resilience, health, secrets, storage, discovery contracts, static discovery | ioc + commons + http (+ boot, test) |

Optional modules live in [freeway-ext](https://github.com/dzb/freeway-ext):
Fleet client/protocol, Fleet server, Consul, HTTP engines, Kafka, Hikari,
testkits, benchmark and the Fleet demo. Ext may host complete optional
applications and tools, not only third-party adapters.

Boundary rules:

- Core main code depends on no external library beyond SLF4J. Test scope may use
  JUnit and H2 (`freeway-db`'s dialect tests); runtime H2, Hikari, Jetty,
  Undertow, Kafka and similar belong to ext.
- A shared contract is its own artifact: `freeway-fleet-protocol` is shared by
  Fleet client and server; the server never depends on the client to learn the
  wire shape.
- Ext depends only on core public contracts. An import of
  `com.jujin.freeway.*.internal` from ext is a missing public type, not a
  shortcut.
- `freeway-gateway` / server-side LB is a **non-goal** for the current
  boundary. Use client-side LB internally and mature ingress/LB externally;
  revisit only for a Freeway-specific requirement.

## Naming

- Public interfaces use the domain noun (`Container`, `JsonCodec`, `Route`).
  A module owns its keys, protocol, tests, docs and default implementation.
- **Contract + composition over inheritance.** Extend through binding
  substitution, contribution, `.primary()`, proxy decoration and SPI
  composition; never by subclassing a framework type. A new `extends` names one
  of three exceptions: language/JDK callback, true is-a, or a closed protocol
  hierarchy invisible outside its package.
- **`XDefault` vs `XImpl`.** The operational test is: can a module bind an
  alternative with `.primary()` and have the container honor it? If yes, the
  type is `XDefault` even when it lives in `internal` (`JsonCodecDefault`,
  `PoolDefault`, `FlowEngineDefault`). If no, it is `XImpl`
  (`ContainerImpl`, `BindingImpl`, `HttpTransportImpl`, `PooledConnectionImpl`).
- **Short names remove package context, not meaning.** `cloud.rpc.RpcModule`,
  not `CloudRpcModule`; `FleetModule`, not `CloudFleetRegistryModule`. Abbreviate
  only unambiguous domain words (`ioc`, `db`, `http`, `rpc`, `fleet`), never
  vowel surgery (`Reg`, `Svc`) or ambiguous acronyms. The same word spans
  artifact, wire and config: `freeway-fleet`, `/fleet`, `X-Fleet-*`,
  `freeway.fleet.*`.
- **Factory verbs.** `of` builds a value from its parts (records, `List.of`);
  `create` is an entry point (`Freeway.create`, `FlowEngine.create`); `.builder()`
  is only for fluent assembly with no defaults of its own. Small variations use
  `defaults()` + `withX` withers, never a builder that copies defaults. Service
  graphs are assembled by `create`, not by builders.
- **`internal` means no stability promise, not "package-private".** It may hold
  public classes when sibling packages assemble them; the public surface there
  is budgeted in [api/internal-surface.txt](api/internal-surface.txt) and guarded
  by `InternalSurfaceBudgetTest`. The built-in HTTP engine has the same
  no-stability stance; its public cross-package seams are budgeted in
  [api/http-engine-surface.txt](api/http-engine-surface.txt) and guarded by
  `EngineSurfaceBudgetTest`. An owner's collaborators stay in the owner's
  package and package-private; widen a type only when a public signature names it
  or `ServiceLoader` instantiates it reflectively.
- **`require*` refuses, `ensure*` repairs.** `requireNonNull` / `requireOpen`
  only demand a precondition; `ensureStarted` / `ensureTable` make the state
  true or throw when they cannot. A method whose body only refuses is `require*`.
- **A name states one act.** `requireParens`, not `requireSpliceParenthesized`;
  no participle where a verb belongs. **A name claims only what the holder can
  verify**: `baseMaxBodySize`, not `configuredMaxBodySize`.

## Design Rules

One criterion: **put each fact where it cannot be got wrong.** Relocate the fact
into a constructor input, an owning type or a shape that cannot express the
mistake; adding a check is the last resort.

A structure that needs a paragraph to justify is already wrong. If the prose
describes a derivation the shape could avoid, fix the shape and delete the prose.

### Dependencies and shape

- No classpath scanning. No bytecode weaving.
- Constructor injection for framework internals; field injection is acceptable
  for application code and config values.
- Prefer small explicit APIs to future-proof abstractions.
- File size is not a split reason. Split when a part has its own reason to
  change, its own tests, or a consumer that wants it without the rest. Cohesive
  long files (`JULEnhancer`, `Sql`) may stay long.
- No JPMS `module-info`, `Automatic-Module-Name` or `jdeps` gate today. Modularity
  lives in package responsibilities and dependency layering, not `exports`.
- Composition exceptions are exactly: language-mandated, true is-a, closed
  protocol hierarchy. Third-party adapters compose shared policy objects
  (`HttpExchangeSupport`, `WebSocketSessionSupport`) instead of inheriting them.
- Reusing `internal` logic across modules means promoting the smallest possible
  interface; copying it requires a comment pointing at the source.

### Who may hold the Container

The test is whether the container exists when the code is written:

- a provider closure, contribution or `RuntimeHook` is written during
  composition, so it receives the `Container` as a parameter;
- code written after composition resolves by `@Inject` / `@Symbol` constructor
  injection instead.

Pass a capability, not the container: `PendingHandler` takes a
`Supplier<RouteHandler>` so the route package never names `Container`. In
shipped code, keep `container.*` calls where composition happens (for example
`HttpModule`); tests are assembly points and may hold containers. A class-based
route handler is built during composition and therefore cannot take a
`Scope.BOUNDARY` dependency.

### Keep concepts few

Module (with `ModuleDeclaration` for class/instance placement), Service,
Extension, Scope, Runtime. Composition is the ordered declarations handed to
`Freeway.create` / `FreewayApp.run`; `FreewayApp` alone appends
`META-INF/services` providers by default (`autoDiscovery(false)` disables it),
and `Freeway.create` never discovers. `ModuleDeclaration` owns no-arg module
construction and `@SubModule`; `ModuleNode` is internal to `ioc.internal`;
`FreewayApp` is the launcher, not a separate tree/builder type.

### API surface

- One or two optional inputs: a documented overload ladder. Three or more: a
  parameter record with `defaults()` and per-field `withX` withers. A record's
  canonical constructor changes; no previous-arity delegating constructor stays.
- A public signature also widens its parameter types: a `public` constructor
  taking a package-private type is unusable outside its package.
- `ServiceLoader` providers must be public, with a public no-arg constructor.

### Living with a break

- No compatibility shims. A renamed key, replaced shape or superseded mechanism
  is deleted, not kept beside its replacement; two names for one answer are two
  answers.
- Stability means stable semantics, not a frozen signature. A behaviour change
  carries its "why" in the CHANGELOG; a shape change appears in the compiler; a
  break must leave the caller smaller or delete a concept/layer. Rename-only
  breaks are churn.
- A deletion that can silently change configuration must announce itself at
  startup and name the fix (`JULEnhancer.renamedFileNotice`).

### Configuration

- **Ownership by layer.** `ioc.symbol` holds key-agnostic mechanisms
  (`SymbolSpec.list`, `activated`/`mode`). `boot` holds application config
  files/profiles. Each feature module owns its key declarations, vocabularies and
  presence criteria; a mechanism carrying application data moves down only when
  it names no keys.
- **`f2.*` / `F2.*` are input aliases, never a second namespace.** Normalize
  `f2.*`, `F2.*`, `F2_*`, and `f2_*` at the configuration boundary to the
  canonical `freeway.*` key; `ConfigKeys`, `KnownKeys`, and modules never list
  an alias spelling. Any alias/canonical collision in one source is a
  duplicate-key error.
- **One `ConfigKeys` per reading module, full literals.** Keys are never
  composed from `PREFIX + "..."`; the table answers "what is the exact key?".
  `PREFIX` only fences the namespace; `KnownKeys.of(ConfigKeys.class,
  ConfigKeys.PREFIX, ...)` must name every namespace. Identity strings (hook
  ids, route paths, contribution ids) stay out of `ConfigKeys`.
- Current ownership: `CloudModule.ConfigKeys` owns application identity only;
  `MemberModule.ConfigKeys`, `RpcModule.ConfigKeys`,
  `ResilienceModule.ConfigKeys`, `EventMeshModule.ConfigKeys`,
  `SecretModule.ConfigKeys`, `StorageModule.ConfigKeys`,
  `ContextModule.ConfigKeys` and `LoadBalancer.ConfigKeys` own their own
  keys. Fleet keys live in ext: `FleetKeys` (`freeway.fleet.*`) and
  `FleetServerModule.ConfigKeys` (`freeway.fleet.db.*`). `commons` is not a
  module: logging stays in package-private `LogKeys`, published via `LogConfig`.
- **One chain, one tier definition.** A tier is a `SymbolProvider`
  (`SymbolProvider.systemProperties()` at `TIER_SYS_PROPS`); a source is the
  chain over them (`SymbolSource.of(coercer, providers...)`). Never reimplement
  lookup, expansion or typing. A replacement `SymbolSource` must consume
  `contribute(SymbolProvider.class)` and the container `Coercer`, or it loses
  boot's cascade. A caller without a container builds the same chain with its
  own coercer.

### Complexity and correctness are separate

A correct check can still be slow. `FlowEngineDefault.load()` was correct by
every case while a 100 003-node graph took 271s because depth was recomputed in
a comparator and `Node.prevLinks()` scanned links per node.

- Measure any new lifecycle/`load()` check on an input an order of magnitude
  larger; the full suite has no graph large enough.
- Run the criterion probe and the scale probe as separate steps. Green on one
  says nothing about the other.

### Regressions to watch

- **Static files:** keep resolved paths inside the mount root; disallow symlink
  traversal.
- **Connections:** release pooled connections exactly once, including exception
  paths and repeated `close()`.
- **Runtime hooks:** fail startup on invalid hook configuration; never silently
  skip.
- **SQL parameters:** respect strings, comments, PostgreSQL `::` casts and
  repeated named parameters.
- **HTTP adapters:** pin the contract edges no type enforces — maxBodySize
  accounting through `HttpExchangeSupport.body()` (413), immutable request
  snapshots, no body bytes for HEAD/204/304, `isCommitted()` before first head
  byte, protocol framing owned by the transport, native body limits translated
  to `BodyTooLargeException` inside `framedBodyStream()`, shared gzip, WebSocket
  upgrade matching and every `HttpServerConfig` field honored or reported.

## Testing

JUnit 6.1.3. Tests use `*Test`, live beside their module, and cover failure modes
on resource/lifecycle boundaries. A framework member with no in-repo caller is
not dead: consumers live in ext or applications, so find the role before deleting
it, and pin new contracts with tests rather than comments.

A test earns its place by failing against the shape it describes. If reverting the
fix leaves it green, it is documenting implementation rather than contract; say
so in its name/javadoc or rewrite it. `ContainerCloseTest` guards the seal's final
drain: deleting `drainRemaining`'s close phase turns 3 of 11 cases red (a service
realized in `@PreDestroy` is never closed, a close-callback event is dropped, a
failing close reports nothing). `SealDrainLockTest` stays green either way; its
cases are contract tests and say so.

## Commit Rules

- Never include `Co-Authored-By`, AI tool names, or any form of AI attribution.
- Commit messages describe the change, never the process or tooling.
- All commits appear under the user's name only.

## Further Reading

- [docs/architecture.md](docs/architecture.md) — boundaries, injection, config
  cascade and lifecycle.
- [docs/reflection.md](docs/reflection.md) — every reflective site and cache.
- [docs/developer-guide.md](docs/developer-guide.md) — usage of all modules.
- [docs/configuration.md](docs/configuration.md) — every core config key.
- [docs/reference/metrics.md](docs/reference/metrics.md),
  [docs/reference/failures.md](docs/reference/failures.md) — metric and failure
  contracts.
- [ROADMAP.md](ROADMAP.md) — evaluated future work.
- [TASTE.md](TASTE.md) — the design judgments.
- [LESSONS.md](LESSONS.md) — refactor failure modes and prevention rules.
- [docs/](docs/) — samples, DB usage, feature summaries.
