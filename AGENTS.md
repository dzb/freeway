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
- Package location is orthogonal to the suffix: `internal` is part of Freeway
  and marks "no stability promise" for callers, not a visibility gate — classes
  there may stay `public` when sibling packages assemble them. A `XDefault`
  may live in `internal` when the module so organizes it (`PoolDefault` in
  `db/internal` is still substituted from outside via `.primary()`, which never
  references the class itself).
- An owner's collaborators live in the owner's package, package-private: a
  type is `public` only when another package must assemble or substitute it.
  `ioc.internal` therefore holds exactly one public type — `ContainerImpl`,
  the thing `Freeway` constructs. Two things force a type wider than its owner
  would choose, and both are facts about the caller rather than about the type:
  a public signature that names it, and `ServiceLoader` reflection (see Design
  Rules). A library's own API counts as assembled — no in-repo caller is not
  evidence of anything, so grep cannot answer this question on its own.
- **`require*` refuses, `ensure*` repairs.** Two verbs for two different acts,
  and mixing them costs the reader the whole method. `requireNonNull`,
  `requirePositive`, `requireOpen`, `requireInsertable` demand a precondition
  and throw when it does not hold; `ensureDateHeader`, `ensureTable`,
  `ensureStarted`, `ensureExpanded` make it come true if it does not. A method
  whose body only throws is `require*` whatever it was called — `ensureInsertable`
  read as "rejects an entity" in its own javadoc, and reading it that way is the
  defect. The rule is checkable by reading the body: **does anything get fixed,
  or does the method only refuse?** Local variables and the returned value do not
  count as fixing — `requireIdValues` builds its array and refuses when an id is
  missing, and the refusal is the act the name states. A repair may still throw
  when it cannot do its job (`ensureStarted`); a pure refusal never repairs.
- **A name states one act.** `requireParens` not `requireSpliceParenthesized`:
  the call site already says what is being spliced, so the second word is the
  caller's to read, not the name's. And no participle where a verb belongs —
  the `require*` family is verbs and noun phrases throughout, and
  `parenthesized` describes a state that has not happened yet.
- **A name claims only what the holder can verify.** `baseMaxBodySize`, not
  `configuredMaxBodySize`: the context knows the limit it was constructed with,
  not whether the application configured it or an adapter passed a literal —
  and `configured` already means "application-set" everywhere else in this
  codebase, with `HttpServerConfig.DEFAULT_MAX_BODY_SIZE` standing right there.

## Design Rules

One criterion, from which most of the rest follow:

> **Put each fact where it cannot be got wrong.** The natural fix for a defect is
> to *relocate* the fact — into a constructor argument, a single owning type, a
> shape that cannot express the mistake — not to add the check that was forgotten.
> `maxBodySize` leaking across keep-alive requests is the shape of it: the
> configured ceiling became a **final constructor input** (`baseMaxBodySize`), so
> an engine cannot fail to *set* it, and the mutable value is only the per-request
> copy, restored by `resetMaxBodySize()` in the one place a reused context
> resets. The reset did not disappear — what moved is which of the two numbers is
> a fact. Adding the check is the last resort, not the first.

The judgment procedure that makes this usable on code the rules never mention:

> **How long an explanation does a change need? That is how wrong its structure
> is.** Needing a paragraph to justify a construct means the construct should not
> exist; needing three paragraphs means it certainly should not. Fix the shape and
> the prose deletes itself — `Backoff.ceilingMillis` guards its shift with
> `baseMillis > Long.MAX_VALUE >> attempt` because that says what it checks;
> `attempt >= Long.numberOfLeadingZeros(baseMillis)` computed the same answer and
> cost a derivation to explain. This test needs no new rule, which is why it works
> where rules do not.

The rules below are the consequences that are *not* guessable — the ones carrying
a fact, a number, or a named exception. Anything derivable from the criterion
above is left out on purpose: restating it costs the reader a line and teaches
nothing.

### Dependencies and shape

- No classpath scanning. No bytecode weaving.
- Constructor injection for framework internals; field injection acceptable for
  app code and config values.
- Keep core modules free of external dependencies.
- Prefer small explicit APIs over future-proof abstractions.
- **File size is not a reason to split.** A long file is a prompt to ask whether its
  responsibilities are cohesive and what the split would actually buy — not an
  instruction. `JULEnhancer` (920 lines, one lifecycle: bootstrap config →
  handlers → formatting → rotation) and `Sql` (45 methods, one grammar) are long
  *and* cohesive; splitting them would spread one idea across files and make every
  reader pay for the index. Split when a part has its own reason to change, its own
  tests, or a consumer that wants it without the rest — judge by cohesion and ROI,
  never by line count.
- **Exports are quarantine, not architecture.** Modularity lives in package
  responsibilities and dependency layering (verified, not declared) — JPMS
  `exports`/`opens` add no design, only enforcement, and this project explicitly
  promises no compatibility, so there is no stranger-facing promise to enforce.
  The JPMS posture today is *none*: no `module-info`, no
  `Automatic-Module-Name`, no `jdeps` gate — those are the hooks to add if a
  JPMS-only downstream ever appears, not something the build already does.

### Extending versus composing

- **Composition over inheritance, with four named exceptions.** Extension happens
  through binding substitution, contribution, `.primary()` and proxy decoration —
  never by subclassing a framework type. A new `extends` must name its exception:
  language-mandated (exceptions, JDK callbacks), true is-a (final, closed set),
  closed protocol hierarchies (invisible outside their package), or implementation
  shared with third-party adapters. Reusing `internal` logic across modules means
  promoting the smallest possible interface — copying it requires a comment
  pointing back at the source.
- **Who may hold the `Container`.** It is the framework's central abstraction, so
  handing one to a collaborator is an intrusion: that collaborator can reach
  everything, and the boundary the handoff was meant to mark is gone. **The test
  is whether the container exists yet at the moment the code is written** — a
  provider closure, a contribution, and a `RuntimeHook` are all written *while the
  container is still being composed*, when there is nothing to inject from, so each
  receives the `Container` as a parameter. Everything written after that point
  resolves by injection instead, and a class inside a module takes its dependencies
  through `@Inject` / `@Symbol` constructor injection. A `RuntimeHook` is the
  framework's designed point of business intervention and is no exception to this:
  the anonymous class in `binder.contribute(RuntimeHook.class).add(id, new
  RuntimeHook() {...})` is constructed at `bind` time, so by the time its `start`
  runs the instance is long since fixed and there is nothing left to inject into it
  — the container it is handed is the only handle that can still reach anything.
  **Pass a capability, not the container**: `ResolvableHandler.resolve` takes a
  `Supplier<RouteHandler>`, so the `route` package never names the type. The grep
  test, over framework code: in `freeway-http/src/main/java`, `route/` and
  `websocket/` name `Container` in javadoc only, and every `container.*` call sits
  in `HttpModule` — one file, because one file is where composition happens. (Tests
  are assembly points and do hold containers; the rule is about the code that
  ships.) A consequence worth stating, because it looks like a limitation: a
  class-based route handler is built during composition, so it **cannot** take a
  `Scope.THREAD` dependency. That is the scope meaning what it says — a
  thread-level lifetime, opened and closed by `Scoping.within(...)`, and the
  framework opens none of its own.
- **Keep concepts few**: Module, Service, Extension, Scope, Runtime. Composition is
  the ordered modules handed to an entry point (`Freeway.create`,
  `FreewayApp.run`/`create`); the validated structure behind it (`ModuleNode`) is
  internal to `ioc.internal`, and boot's launcher is `FreewayApp` itself — a
  separate tree type or builder type would be a sixth concept with no question of
  its own.

### Shape at the API surface

- **Optional inputs**: one or two of them use a documented overload ladder, each
  step stating what it adds (`RemoteCaller.invoke`); three or more use a parameter
  record with `defaults()` and per-field withers
  (`CloudHttpClientDefault.Wiring`), so a call site cannot drift between overloads.
  A record's canonical constructor changes shape whenever a component is added —
  that is the intended outcome, not an accident: no previous-arity delegating
  constructor is kept for callers that have already been compiled. Adding a
  component means updating every call site (in `freeway-ext` too), and the compile
  error is the migration.
- **A public signature constrains its parameter types.** A `public` constructor
  taking a package-private type is unusable outside its package, so the parameter
  is public whether or not the author meant it: `ConfigSources` is public because
  `AppConfigDefault`'s public constructor takes it and `freeway-cloud` builds one
  in its own tests. Widening a type "just in case" and widening one because a
  public signature reaches it are different acts; only the second is forced.
- **`ServiceLoader` providers are public**, the one case where a type inside
  `internal` cannot narrow — `java.base` instantiates it reflectively, so
  `AppLogSource` is public and its no-arg constructor with it. Same family as the
  JDK-callback exception above, and for the same reason: the caller is outside the
  package and outside the module.

### Living with a break

- **No compatibility shims, and a break is the migration path.** A renamed key, a
  replaced API shape or a superseded mechanism is deleted, not kept alongside its
  replacement: two live names for one thing are two answers to one question, and
  the reader pays forever. "Stability" therefore means stable *semantics*, not a
  frozen shape — a frozen signature whose behaviour drifts is the worst case there
  is, because it bypasses the compiler and lands in production. Three criteria
  separate evolution from churn:
  - a behaviour change carries its "why" in the CHANGELOG — an unexplained
    behaviour change is the real instability, whether or not a signature moved;
  - a shape change surfaces in the compiler: with source-level consumers the
    compile error *is* the migration path, and only a clean build proves anything
    (a stale `target/` swallows it, as it did when `Wiring` lost its previous
    arity);
  - a break worth paying for leaves the caller's code smaller or unchanged.
    Adapting by renaming something, adding an argument or relocating a concept is
    churn; a break that deletes a concept or a layer earns its cost.

  The one obligation a deletion leaves is loudness: a change that could make
  existing configuration stop taking effect must report itself at startup and name
  the fix (`JULEnhancer.renamedFileNotice`). Compatibility is not a goal of this
  project — adapters and applications are expected to move with it.

### Configuration

- **Config ownership by layer**: `ioc.symbol` holds resolution mechanisms that
  know no concrete keys — value types (`SymbolSpec.list`, `splitList`) and shape
  rules (`SymbolSpec.activated`/`mode`), parameterized by the caller's key. `boot`
  holds the application-configuration content — the config file family, profile
  selection. Each feature module owns its own keys' declarations, token tables and
  presence criteria. A mechanism carrying application data moves down only when it
  carries no key names; data about specific keys moves up to their owner.
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
  `SymbolProvider` (JVM system properties is `SymbolProvider.systemProperties()` at
  `TIER_SYS_PROPS`), and a source *is* the chain over them
  (`SymbolSource.of(coercer, providers…)`) — never a per-caller
  re-implementation of lookup, expansion or typing. The container builds the chain
  with its own `Coercer` so contributed `CoerceRule`s reach `resolve(SymbolSpec)`; a
  caller without a container (an adapter, a test) builds the same chain with the
  coercer it has and gets the same semantics.

### Complexity is a separate axis from correctness

A check that is *right* can still be ruinously *slow*, and the two failures are
independent — so they must be verified separately. `FlowEngineDefault`'s
join-domain validation was correct by every positive and negative case while
`load()` took 271 seconds on a 100 003-node graph: the depth was recomputed inside
a `Comparator`, and `Node.prevLinks()` scans every link in the graph on first
touch per node. Fixing that also flipped a recursion direction, turning three
correct cases into false rejections — and **the scale probe could not tell**: it
passed with the criterion broken, the criterion probe passed with the complexity
broken.

- **Adding a check to a lifecycle hook or `load()` path: measure complexity on an
  input an order of magnitude larger.** Nothing else catches it. The full test
  suite contains no graph large enough for this to show, and startup time is not
  something anyone tests. 403 / 2 003 / 20 003 / 100 003 nodes loading in
  3 / 3 / 62 / 171 ms is the table that exposed it — the broken shape took 271 s
  on the largest of those.
- **Run the criterion probe and the scale probe as separate steps.** A green run
  of each says nothing about the other.

### Regressions to watch

Structural hazards that have each produced a real defect here, and that no type
enforces — see `docs/audit-correctness-and-redundancy-1.5.6.md` for the evidence.

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
  shared `AbstractHttpContext.readBody` (413), which delegates to the one limiter
  (`http.internal.LimitedInputStream`) the built-in engine runs, so adapter and
  built-in 413 behaviour are the same code rather than two loops that happen to
  agree; **`maxBodySize` per-request reset** — a reused context takes the
  configured limit as a constructor argument and calls `resetMaxBodySize()` from
  its own `reset()`, so a filter's raised limit cannot become the connection's
  ceiling; gzip negotiation through the shared `Compression`,
  `ExchangeHandler.websocket` consulted for every upgrade candidate, and every
  `HttpServerConfig` field honored or reported at startup.

## Testing

JUnit 6.1.3. Tests use `*Test` suffix, live beside the module they cover.
Add regression coverage for failure modes on resource and lifecycle boundaries.
A framework member with no caller inside this repository is not dead — the
consumers live in the ext repository or in applications — so look for the
*role* before removing it, and pin new contracts with a test rather than a
comment.

A test earns its place by failing against the shape it describes. If reverting the
fix leaves it green, it is documenting the implementation rather than the
contract — say so in its own name and javadoc, or rewrite it to assert the fact
that actually distinguishes the two shapes. The seal's final drain is guarded by
`ContainerCloseTest`: deleting `drainRemaining`'s close() phase turns 3 of its 11
cases red — a service realized inside `@PreDestroy` never gets closed, an event
published from a `close()` callback is dropped because the bus closed first, and
a failing `close()` reports nothing. `SealDrainLockTest`'s own cases stay green
either way; they observe callbacks the *main* drain runs, so they are contract
tests and say so in their names and javadoc.

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
