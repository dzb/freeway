# Freeway Structural Audit: Responsibilities, Execution Duplication, and More Appropriate Shapes

> Scope: all of `src/main` in `freeway-commons` / `ioc` / `boot` / `http` / `db` / `flow` / `cloud`,
> plus module boundaries, public entry points, lifecycle, configuration, and assembly paths.
>
> The criterion follows the repository's own principle: **put each fact where it cannot be got wrong; a
> construct that needs a paragraph to justify itself is itself a structural defect**. This audit does not treat
> "zero callers" as dead code outright, nor does it use line count as a reason to split.
>
> Audit baseline: `mvn test` fully green (2483 cases, 0 failure / 0 error). After Batch 0 landed,
> `mvn test` fully green (2487 cases, 0 failure / 0 error; 4 new regression cases).
>
> **Second-round status**: S1–S7 and the small items in §9 have all landed in the target shape of this report;
> the text below keeps the original problem evidence and design derivation, the implementation checklist is in §1.6,
> and batch status is in §11. The final verification numbers are in §12.

## 0. Conclusions First

Freeway has completed the cleanup at the layer of "framework classes no longer use implementation inheritance
between one another". The remaining redundancy is mostly not in copy-paste,
but in **the same fact having two execution sites**:

1. **Module composition**: `ioc.ModuleNode` and `boot.FreewayApp`/`ModuleDiscovery` each implement
   class/instance resolution, `@SubModule` expansion, and module instantiation.
2. **HTTP route assembly**: `HttpModule`, `RouteIndex`, and `WebSocketIndex` each own part of "group expansion" and
   "the final route list"; the two indexes each used to accept a contributions vocabulary of their own.
3. **HTTP response decisions**: the four body kinds each run their own highly similar committed/Date/gzip/Content-Length
   path; the transport then infers framing a second time. This fork has already produced a real boundary defect (see §3.2).
4. **Ordering rules**: contribution ordering and deferred-action ordering each maintain
   before/after/if-present state and a Kahn topological sort.
5. **DB assembly**: `Database.create(Wiring)`, `DbModule.buildDatabase`, and the `DatabaseImpl`
   constructor form three assembly/defaulting paths; the built-in dialects also have two tables — a "binding table" and a "URL detection table".
6. **Startup configuration**: the working-directory base file is read once for profile selection and once for the hot-reload file tier;
   both `ConfigCascade` and `AppConfigDefault` know the standard file names and roles.

None of these problems can be solved by "extracting one more helper". They require **moving a responsibility to a single owner**,
and then deleting the old path. What follows is ordered by value.

| Module | Structural score | Judgment |
|---|---:|---|
| commons | 4/5 | Utilities and scope primitives are clean; numeric conversion and ordering topology still overlap locally |
| ioc | 4/5 | Clearest boundary; ordering state and built-in services being invisible are the main gaps |
| boot | 3.5/5 | The launcher itself is clear, but module composition and config loading carry a second set of semantics |
| http | 3/5 | The direction of composing extension points is right; assembly and response framing still have three owners |
| db | 3.5/5 | The division of labor among `Sql`/`Dialect`/`Schema` is sound; database assembly paths and dialect registration are duplicated |
| flow | 4/5 | Few concepts, clear execution model; a few public types are not closed off, and the entry-point ladder can be tightened further |
| cloud | 3.5/5 | The domain modules are clear; `internal` has become a dumping ground for cross-domain implementations, and backend guards have template duplication |

The most important thing is not raising the scores, but converging S1/S2/S3/S4 below; they determine which shape
new code will copy when modules are added in the future.

---

## 1. The First Landed Batch (Batch 0)

This batch touches only structures inside HTTP that are already pinned by tests and that demonstrably reduce "one fact in two
places". All are source-level breaks (the repository explicitly promises no compatibility); the compile error is the migration path.

### 1.1 The Route Indexes Index Only Final Routes

**Before**

- `RouteIndex(List<Route>, List<RouteGroup>)` expands groups itself;
- `WebSocketIndex(List<WebSocketRoute>, List<WebSocketGroup>)` expands groups itself;
- to resolve class handlers, `HttpModule` also expands groups first and then hands the result to the index; the WebSocket
  path therefore expands the same group twice and relies on the implicit condition "expand reuses endpoint instances".
- Resolution of direct routes and resolution of group routes are scattered across two loops.

**Now**

- `RouteIndex(List<Route>)` and `WebSocketIndex(List<WebSocketRoute>)` receive only **already expanded
  and already resolved** final routes;
- `HttpModule` is the single assembly point: it merges direct contributions with `group.expand()` first, then
  resolves class handlers/endpoints over the final list;
- `HttpPipeline` and all tests are synchronized to the new shape.

**Benefit**: the index returns to a single responsibility (matching), a group is expanded only once, a class handler is resolved
only once, and adding a new route source no longer requires teaching the index about it.

### 1.2 Two-Phase State Converged for class-based handler/endpoint

**Before**: `ResolvableHandler.resolve` and `ResolvableEndpoint.resolve` were two nearly identical
volatile + synchronized first-assignment state machines; the `isResolved`/`resolved` fields were each duplicated as well.

**Now**: both compose `http.internal.ResolvableValue<T>`; the public route/WebSocket wrapper types
remain (because they must implement different handler interfaces), and only the state machine
"declared class → first injected instance" is merged into one owner.

### 1.3 Only One Copy of the 123-Byte Rule for the WebSocket close reason

**Before**: `WebSocketSessionSupport.truncateCloseReason` did the String version,
and `engine.ws.WebSocketConnection.truncateTo123Bytes` did the byte[] version; both hand-wrote 123 and
the UTF-8 continuation-byte backtracking.

**Now**: `http.internal.WebSocketCloseReason` provides both `truncate(String)` and
`utf8(String)`; session support and the built-in frame writer both call it. The rule can be got wrong in only one place.

### 1.4 Response Preparation Converged into One `write(...)`

**Before**: `output(byte[])`, `output(InputStream, long)`, `outputFile(Path, ...)`,
and `outputFile(FileChannel, ...)` each repeated:

- committed guard;
- `ensureDateHeader()`;
- `allowsBody()`;
- gzip decision;
- `Content-Length` set/remove;
- `Content-Encoding` / `Vary`;
- `writeResponse(...)`.

**Now**: all four body kinds go through the same
`HttpExchangeSupport.write(body, bodyLength, minSizeApplies)`. That method is the single policy point for committed, Date, body suppression, gzip, length, and Vary.
The stream/file framing on the transport side is tightened in step:

- `writeStream` no longer adds/derives Content-Length a second time;
- for the `-1` case where the file-range length can only be determined after the channel is opened, `writeChannel` resolves it
  once **before the head is committed** and uses the final head for both the sendfile and buffered-stream paths;
- `writeSendfile` no longer invents framing of its own.

This batch also fixed a reproducible boundary defect along the way: when a large file of unknown length goes through sendfile, the HTTP/1 writer
sees no `Content-Length` and chooses chunked, but sendfile writes raw bytes without chunk framing.
Now the head already carries the resolved length before it is committed. Regression test:
`Http1xTransportTest.unknownFileLengthIsResolvedBeforeSendfileCommitsHead`.

### 1.5 Other Small Convergences in the Same Batch

- `Compression.gzip(byte[])` now uses `gzipStream(...)`, so the GZIP writer's buffer/setup no longer has
  two construction paths.
- The final gaps on public value/implementation types are closed: `FlowModule`, `Node`, `Link`, `Graph`, `GraphSpec`,
  `FlowEventBus`, `Stepper`, `ValidationResult`, and `SseEmitter` are all marked `final`.
- These changes add no new public framework concept; `ResolvableValue` and
  `WebSocketCloseReason` live in the HTTP module's `internal`.

Verification: `mvn -pl freeway-http -am test` is fully green; the repo-wide regression is at the end of the report.

### 1.6 Second-Round Implementation Record (S1–S7)

| Original problem | Landed shape |
|---|---|
| S1 duplicate module composition implementations | Added `ModuleDeclaration` (a sealed value for class / instance) to unify no-arg construction and `@SubModule` metadata; `Freeway`/`FreewayApp`/`ModuleDiscovery` all carry the same declaration; `ModuleNode` only does normalization, validation, binding order, and render. |
| S2 HTTP assembly | `HttpModule` expands `RouteGroup`/`WebSocketGroup` once and resolves class handlers/endpoints; the two indexes receive only the final lists. |
| S3 response framing | `HttpExchangeSupport.write(...)` is the single policy; the transport resolves the file's real length before committing the head; the sendfile unknown-length framing defect is fixed. |
| S4 ordering | `OrderingSupport` unifies the four kinds of links; `TopologicalOrder` unifies the Kahn sort and tie-break for contribution/scope-action ordering. |
| S5 DB | `DatabaseImpl.assemble(...)` is the only assembly/defaulting point; `Dialect.ofId` catalog + a single selected `Dialect` service; all `Orm` SQL goes through the `Sql` builder. |
| S6 config files | `ConfigFilePlan` unifies file names, role, order, reading, and snapshot; the base file is read only once during startup. |
| S7 observability | `Container.describe()` / `AppRuntime.describe()` return metadata-only descriptions; routes/WebSockets provide registration descriptions that do not expose handlers. |
| cloud internal | Split into subpackages by context/discovery/health/observe; top-level internal keeps only cross-domain support; guard/optional helpers are single-sourced. |
| §9 small items | Public types final, proxy Object methods, header lookup, filter ordering, gzip writer converged; numeric conversion and health payload stay separate because their semantics differ (§9.3/§9.5). |
| Naming batch | The public vocabulary of HTTP exchange/response, DB stats/find/row/SQL, Flow execution/metadata, Cloud RPC/event/module, IoC marker/contribution, and Commons JSON/cache all landed per the naming audit; old names keep no compatibility shim. |

New regressions: `ModuleDeclarationTest`, `RuntimeDescriptionTest`, `AppRuntimeDescriptionTest`,
`ConfigFilePlanTest`, `TopologicalOrderTest`, `unknownFileLengthIsResolvedBeforeSendfileCommitsHead`,
`WebSocketCloseReasonTest`, plus route descriptor / custom dialect cases.

---

## 2. S1 (Landed): Module Composition Has Two Implementations

### Evidence

- `ioc.internal.ModuleNode` is the composition tree:
  - both `of(ModuleEx)` / `of(Class)` call `expand(...)`;
  - `expand` reads `@SubModule` and recursively builds children;
  - `resolve()` instantiates a class declaration with the no-arg constructor;
  - `normalize` handles class/instance deduplication and error paths.
- `boot.FreewayApp`, however, maintains a `List<Supplier<ModuleEx>>` and has its own `instantiate(Class)`
  no-arg reflection implementation (`FreewayApp.java:150`).
- `boot.ModuleDiscovery.collectBundles` hand-writes `@SubModule` expansion again, only to derive
  "the set of declared classes"; the comment says directly "same exclusion the tree applies … so the mirror
  stays exact" (`ModuleDiscovery.java:70-100`).

### Why It Is a Structural Problem

- boot turns a class declaration into an instance ahead of time, so the class-declaration
  path of `ModuleNode` is in practice never taken under `FreewayApp`; the two sets of class resolution semantics agree only by accident at startup.
- The `@SubModule` expansion rule exists twice; change cycle/no-arg-construction/duplicate detection in one place and the other does not follow automatically.
- Composition is boot's input and also ioc's internal structure, yet no **composition value** is owned by both.
  `Supplier` is merely a stopgap that lets boot mix classes with instances.

### Target Shape

Promote "a module declaration waiting to be composed" into first-class data owned by ioc, rather than a supplier list in boot.
Suggested shape:

```java
// the name can be decided later; the point is that class and instance are two forms of the same declaration
sealed interface ModuleRef {
    Class<? extends ModuleEx> type();
    ModuleEx resolve();                 // class uses the no-arg ctor, instance is returned directly
    List<ModuleRef> submodules();       // static expansion of @SubModule
}
```

- `Freeway.create(...)`, `FreewayApp`, and `ModuleDiscovery` receive/pass the same kind of `ModuleRef`;
- normalization, duplicate classes, `@SubModule` cycles, and instance expansion all happen only once;
- `ModuleNode` may continue to exist, but is responsible only for "the binding order and render of a normalized composition",
  and no longer holds a second body of module resolution knowledge;
- discovery becomes a function over composition (append undiscovered) instead of first understanding
  bundles and then returning an instance list.

This does not add an application-layer concept; it exposes the composition concept that already exists, merely hidden inside `ModuleNode`,
to the boot assembly point within the same framework. If no new public type is wanted, it can be made a
**framework-internal public type** of ioc instead, but there must be only one copy.

### Cost

M/L. Requires synchronizing `FreewayAppTest`, `ModuleNodeTest`, and `ModuleDiscovery`'s discovery cases,
and bringing the external SPI adapter into the same shape. The benefit is deleting the fork point for all subsequent module-composition semantics, so it is worth doing first.

---

## 3. S2 (Landed): HTTP Assembly Still Has Two "Second Layers"

### 3.1 Fixed: the Dual Role of Group Expansion and the Index

See §1.1. The remaining small duplication is the two group records:

- `RouteGroup` and `WebSocketGroup` are both `prefix + List<T>` + normalize + expand,
  differing only in the route constructor.

**Next step**: extract an `http.internal.PrefixedGroup<T>` value (prefix + items + expand function),
leaving the two public records with only their own types and factories; or accept these two 20-line templates and do not abstract.
**Judgment**: if no third kind of group (SSE route group, static route group, etc.) is added in the near future,
keeping the two explicit records is more direct than introducing another generic internal type.

### 3.2 A Boundary to Watch: body and framing Are Still Two Layers

Response policy now has only one support method, but the transport still needs to:

- recompute the actual `Content-Length` for `Bytes` after gzip;
- resolve the real length denoted by `-1` for `File`/`Channel` after opening;
- decide protocol-level chunked.

This is a legitimate "execution fact", but it demands one explicit rule:

> support owns policy; the transport may only resolve unknown execution facts into the final
> head **before committing the head**, and must not change framing afterwards.

The current implementation obeys this rule, and a regression test covers it. If simplifications continue in the next round, `ResponseBody` should carry a
`LengthFact` (Known / ResolvedFromChannel / Unknown) instead of continuing to infer from `-1` and `hasHeader` in `writeStream` /
`writeSendfile`. Otherwise a future body kind (say, a direct
`WritableByteChannel`) will duplicate the same framing decision.

### 3.3 `HttpPipeline`'s Built-in filters Have Two Locations

`HttpPipeline` holds both:

- the two specialized fields `corsFilter` and `healthFilter`;
- the `filters` list.

`HttpServer` then merges and sorts them at construction. CORS needs to be read by the WebSocket upgrade
via `resolveAllowedOrigin`, so it genuinely cannot be hidden as an ordinary filter; but HealthFilter has no comparable
external reader. Options:

- keep only a `List<HttpFilter>`, with `HttpServer` extracting/validating CORS at construction;
- or keep the named fields but make "merge and sort" explicit as `HttpPipeline.filtersInOrder()`, so that
  `HttpServer` does not become the second party that knows the built-in filters.

**Judgment**: medium priority, not a defect; if a second filter that must be read before upgrade is added in the future,
unify immediately.

---

## 4. S3 (Landed): IOC Ordering Rules Have Two State Machines and Two Topological Sorts

### Evidence

- `ExtensionStore.Entry` (`ExtensionStore.java:458`) and
  `ContributionRegistry.DeferredOrdering` (`ContributionRegistry.java:275`) both maintain:
  `beforeIds` / `afterIds` / `beforeIfPresentIds` / `afterIfPresentIds` — four lists —
  plus four same-named fluent methods.
- `ExtensionStore.order()` (`:273`) uses Kahn + PriorityQueue + cycle path;
  `ScopeSession.sort(...)` (`ScopeSession.java:274`) uses Kahn + ArrayDeque again.
- The two are not semantically identical: contribution ordering must preserve the insertion-order tie-break and
  the missing-ref WARN policy; Defer action ordering must split constrained named → unconstrained
  named → unnamed into three segments. But "before/after graph + cycle detection" is the same algorithm.

### Target Shape

1. `OrderConstraints`: a value that expresses only the four link lists and immutable withers, implementing
   `Ordering`; the ExtensionStore entry and the deferred handle both compose it, no longer each keeping four tables.
2. `TopologicalOrder`: a general ordering operator in commons, taking
   `nodes + edges + priority + missing-reference policy` as input and producing ordered nodes or a cycle failure with a
   human-readable path. ExtensionStore and ScopeSession each pass in their own policy.
3. Do not merge the public semantics of `Ordering` and `Defer`: one is composition-time contribution order,
   the other is scope-close action order; they merely share a graph algorithm and should not share types.

### Why It Is Worth It

Ordering is code where "everything looks right and one boundary is wrong" is easy: duplicate ids, missing references, cycle paths, stable ties.
Today the same boundary must be tested twice, and the two sides have different error messages/fallback policies. After the merge, each new kind of ordered contribution no longer requires
copying another set of state.

**Cost**: M. Do `OrderConstraints` first (internal, low risk), then decide whether topological ordering moves down into commons.

---

## 5. S4 (Landed): DB Assembly Has Three Paths and dialect Has Two Tables

### Evidence

- `Database.create(Wiring)` is the standalone assembly point in the documentation;
- `DbModule.buildDatabase` instead directly does `new DatabaseImpl(config, resolver, pool, dialect)`
  (`DbModule.java:199-204`), duplicating the resolution of Pool/Dialect/RowMapper itself;
- `DatabaseImpl` also keeps two public constructors, 2-arg / 4-arg, which each handle the
  `pool == null` / `dialect == null` defaults again.

So the same answer — "use PoolDefault when there is no pool, use Postgres when there is no dialect" — exists in at least
`Database.create`, `DbModule.buildDatabase`, and `DatabaseImpl`.

The second duplication is dialect registration:

- `DbModule.bind` binds the four ids `postgresql/mysql/sqlite/h2` by hand;
- `Dialect.of(url)` maintains another set of URL scheme → dialect instance determinations;
- `DbModule.resolveDialect` detects first, then takes the dialect from the container by id, and falls back to the default with `catch (RuntimeException)`.

The result is that a built-in dialect's "id, class, URL prefix, whether it is primary, H2 mode special-casing" is scattered across
`Dialect`, `DbModule`, and the four dialect classes.

### Target Shape

- `DatabaseWiring` (or `DatabaseAssembly`): the only assembler, taking the optional
  `Pool / Dialect / RowMapper` dependencies, applying defaults once internally, and returning `Database`.
  `Database.create(Wiring)` and `DbModule` both go through it; `DatabaseImpl` keeps only one all-argument constructor.
- `DialectCatalog`: the only id → factory table; `Dialect.of(url)`/`ofId(id)` both read from it;
  the container is responsible only for looking up a user-bound id first and then falling back to the built-in catalog. Remove the catch-all "silently fall back to the default".
- The SQL assembly part of `Orm` should gradually change to building a `Sql` value and then executing it: today `find/insert/update/delete`
  each hand-write strings; `Sql` already has column setting, conflict clauses, and placeholder rendering. Let ORM and user queries share the same
  SQL generation grammar, instead of letting `Orm` become a second grammar.

**Cost**: M. The constructor contraction in `DatabaseImpl` is a compile-break; the catalog change must synchronize
`DialectSyntaxTest`/`DbModuleTest`. The benefit is that all database implementations face only one assembly entry point.

---

## 6. S5 (Landed): Startup Configuration File Facts Have Two Owners

### Evidence

- `ConfigCascade.load` reads the working-directory base file for profile selection:
  `ConfigCascade.readFilesystemBase()` (`:179`);
- afterwards `AppConfigDefault.reload()` reads the same set of base files as override files
  (`AppConfigDefault.readOverride`, `:254`).
- The standard file names, profile variants, and the role of `freeway.config.file` are defined in
  `AppConfigDefault.Role` / `OverrideFile`;
- yet `ConfigCascade.overrideFiles` is where those roles are actually constructed.

This makes the same file usually parsed twice during startup, and if the file changes between the two reads, profile selection and the final
file tier can come from different contents. It also requires the two readers to agree on "missing/unreadable/whether to strip the activation
key".

### Target Shape

A `ConfigFilePlan` (or `ConfigFiles`) value owns:

- the standard file family (properties/json);
- each file's role (base / profile variant / declared);
- order and precedence;
- a snapshot read once, and the replacement triggered by the watcher.

`ConfigCascade` is responsible only for "computing profiles / ConfigSources / tiers from the snapshot";
`AppConfigDefault` is responsible only for exposing the snapshot as a live SymbolProvider. The classpath baseline's
ClassLoader weak cache can stay (it is an immutable deployment resource), but working-directory files should not be read twice during startup.

**Cost**: M. Requires a new file plan unit test and coverage of the existing convention that "the profile does not change during hot reload".

---

## 7. S6 (Landed): cloud's `internal` Package Carries Too Many Domain Implementations

### Evidence

At least four domain packages currently depend on `cloud.internal` in reverse, forming a package-level cycle:

- `cloud.context` → `internal` (Propagator/Filter implementations);
- `cloud.discovery` → `internal` (RegistryStore, RegistryLifecycleHook, ReadinessGate…);
- `cloud.health` → `internal` (ReadyHandler);
- `cloud.observe` → `internal` (MetricsHandler/TracingFilter);
- and at the same time `internal` → `context` / `discovery` / `health` / `observe`.

This is not a Java syntax error, but it mixes two meanings: "internal is the cross-domain shared base" and "internal is where each domain's implementations
are piled up". When a future agent or developer looks for something in `internal`, the package name cannot tell them
where it belongs.

### Target Shape

- `cloud.<domain>.internal`: that domain's implementations, hooks, handlers; same-package visibility preferred;
- `cloud.internal` keeps only mechanisms that are genuinely cross-domain with no semantic owner (such as `ConstantTime`,
  `CloudHttpClients`, `BackendTypeGuard`);
- a module depends only on its own domain's public API + a small amount of cross-domain support;
- if a type is used by two domains at once, it should be promoted to an explicit public contract instead of continuing to lie in
  `internal` as implicit sharing.

**Benefit**: package boundaries express ownership again; many public-in-internal types can drop to package-private, and the public surface shrinks naturally. **Cost**: M, mostly mechanical moves and import updates, with low behavioral risk.

### 7.1 Backend guard Template Duplication

`SecretModule` and `StorageModule` each contribute a RuntimeHook whose only job is to call
`BackendTypeGuard.warnIfExternal(...)`; `DiscoveryConnectionHook` is duplicated twice as well. One could provide
`BackendTypeGuard.hook(type, key, subsystem)` returning a RuntimeHook, or let a module express the guard as a one-line
contribution. The benefit is putting the single rule "warn when an external backend is not installed" in one place.

### 7.2 Two `optional(Container, Class)` helpers

`RpcModule` and `FleetRegistryModule` both implement the small helper "return null when the binding does not exist".
It belongs in cloud's shared support, or better: make "optional dependency" an explicit concept of the container/assembly layer
(an `optional` query should exist only during composition, not be scattered through business providers). This is a low-risk merge,
but do not introduce a second container API for it.

---

## 8. S7 (Landed): the agentic Era Lacks a Machine-Readable Runtime Description

`docs/module-system.md` currently states explicitly that the composition structure is "visible in the startup log rather than
through an API". That is enough for human logs, but not for agents:

- the composition tree exists only as the string from `ModuleNode.render()`;
- there is no public snapshot of a binding's type/id/scope/primary/markers;
- there is no public snapshot of a contribution's id, order, or origin;
- the HTTP route table has no route descriptor;
- on the configuration side there is only `AppConfig.keys()`, with no structured view of "declared vocabulary + actual origin tier".

So an agent can only parse logs or reflect over framework fields, which is the opposite of the
"explicit, compose-first, agent-friendly" goal.

### Suggested Shape

A runtime description that is **read-only, lazily assembled, and never triggers service instantiation**:

```java
record RuntimeDescription(
    String application,
    List<ModuleDescription> modules,       // tree + bind order
    List<BindingDescription> bindings,     // type/id/scope/primary/markers
    List<ExtensionDescription> extensions, // entry type + contribution ids/order
    Set<String> configKeys                 // names only; never values
) {}
```

- `Container.description()` returns it; `AppRuntime` can forward it as
  `AppRuntime.description()`;
- the HTTP module can additionally provide a `RouteDescription` (method/path/order) without exposing handler instances;
- it contains no instances, no configuration values, and no secrets; this is an inspection API, not a second container.
- optionally let `AppRuntime` output a JSON document under explicit configuration (through the existing `JsonCodec`) for CI/agents
  to read; do not output sensitive content by default.

This adds no new runtime concept; it merely exposes existing metadata through stable value types. Priority is high because it
directly determines the framework's observability in agentic toolchains.

---

## 9. Cross-Module Consistency and Small Items

### 9.1 Public Types' final Gaps (Fixed in Batch 0)

The main code no longer has `extends` between framework classes; the audit also found a set of public non-final types
with neither extension-point semantics nor subclasses: `ValidationResult`, `FlowModule`, `Node`, `Link`, `Graph`,
`GraphSpec`, `FlowEventBus`, `internal.Stepper`, `SseEmitter`.

Among them, `FlowModule` was the only non-final module class of all modules. All have been marked `final`.
Rule: apart from the `RuntimeException` family (whose inheritance expresses error types), public value/implementation types default to
`final`; anything that needs extension keeps only a contract interface. The change is a compile-break, but the API surface shrinks,
and it prevents users from mistakenly believing these types were designed to be inherited.

### 9.2 Handling Same-Named Object Methods (Converged)

`ServiceProxy` and `RemoteProxyFactory` each implement the proxy's
`toString/hashCode/equals` dispatch separately. The semantics are the same (equals identity, hashCode identity,
toString description); only the description string differs. This can converge into one
`Proxies.objectMethod(proxy, method, args, description)` in commons; the benefit is that the proxy rule is written once.
**Landed**: `commons.util.Proxies.objectMethod(...)` unifies the dispatch; the description string is still supplied by the caller.

### 9.3 Local Duplication of numeric Conversion (Kept Separate)

`CoercerDefault`, `JsonAccessors`, and `BeanValidator` each have a small
"Number → BigDecimal/BigInteger" function. They serve config coercion, JSON
access, and validation respectively, with similar semantics. A package-private/public utility could be extracted, but do not
mistakenly merge them into one coercion policy: the three differ in their handling of null/exceptions/precision. They were not merged in this round: they are not the same coercion policy, and keeping the domain semantics is clearer.

### 9.4 `HttpHeaders.headerValue/Values` and `RequestViewSupport`'s header lookup (Converged)

Both do case-insensitive multi-value lookup. `HeaderStore` already handles canonicalization;
`RequestViewSupport`'s fallback scan can reuse `HttpHeaders`'s helper, or explicitly require
"RequestViewSupport input is already canonicalized, so no second scan is made". The duplication is small today, but the rules would
fork: one allows a non-canonical map, the other relies on a canonical map.

### 9.5 Health Responses in `HealthFilter` / `ReadyHandler` / `HealthModule` (Kept Separate)

All three construct `{"status":"ok"|"unhealthy", ...}`, but the payloads differ: HTTP health has only
status/detail, while cloud readiness also has each contributor's structure. Do not force one record onto three different payloads;
a shared `HealthResponse` value (status + detail + optional
details) could be extracted, or the status quo kept with the payload boundary documented. **Merging by force is not recommended at present.**

---

## 10. Places Not to Touch (Avoiding Convergence for Its Own Sake)

1. **Do not merge `JsonUtils` and `JsonCodec`**: one is a container-free static entry point, the other is a container-aware, replaceable entry point;
   merging would drag an IoC dependency into the JSON base layer.
2. **Do not merge the three event planes**: the local EventBus, cloud mesh, and Kafka adapter have different reliability levels
   and failure semantics; "event" is not the same concept.
3. **Do not split long files such as `Sql`, `JULEnhancer`, `Schema`**: their length comes from one grammar /
   one lifecycle / one schema pipeline; there is no independent reason to change.
4. **Do not merge `TaskDesc`/`ConditionDesc` into a public generic**: the two driver callbacks' owner types differ, and separate
   public shapes are more direct; internal sharing is already solved by `Ref`.
5. **Do not split assembly just to eliminate `HttpModule`'s many imports**: it is the composition root, and seeing the
   container's wiring in one place is an architectural choice; if it must be split, only pure-algorithm helpers can be split out, and `container.get(...)`
   must not be scattered across multiple classes.
6. **Do not introduce JPMS exports as an architectural device**: modularity and boundaries should be expressed by actual package ownership and
   import rules; exports are only a publication promise, not a design.
7. **Do not keep compatibility shims**: this repository's convention is break + compile error + CHANGELOG migration;
   delete old constructors/old helpers directly when the new shape lands.

---

## 11. Suggested Implementation Order

| Batch | Content | Size | Status |
|---|---|---|---|
| Batch 0 | HTTP index/group, Resolvable, close reason, response planning, gzip | S/M | ✅ Landed |
| Batch 1 | `ModuleDeclaration` + boot/ioc shared composition declaration | M/L | ✅ Landed |
| Batch 2 | `OrderingSupport` + `TopologicalOrder` | M | ✅ Landed |
| Batch 3 | `DatabaseImpl.assemble` + selected `Dialect` + `Dialect.ofId` + Orm through `Sql` | M | ✅ Landed |
| Batch 4 | `ConfigFilePlan`, single read of startup files | M | ✅ Landed |
| Batch 5 | `cloud.<domain>.internal` + backend guard/optional helpers | M | ✅ Landed |
| Batch 6 | `RuntimeDescription` / `AppRuntimeDescription` / route descriptors | M | ✅ Landed |
| Batch 7 | final gaps, proxy object method, header lookup, filter order | S | ✅ Landed |

Each batch should keep the full test suite green on its own; do not bind these changes into one "big refactor" commit, otherwise there is no way to locate which
structural change broke behavior.

---

## 12. Verification Record

- Audit baseline: `mvn test` fully green (2483 cases, 0 failure / 0 error).
- After Batch 0: `mvn test` fully green (2487 cases, 0 failure / 0 error).
- Second round final: `mvn test` fully green (2443 cases, 0 failure / 0 error / 0 skipped).
  - commons 422 / ioc 335 / boot 128 / http 527 / db 541 / flow 129 / cloud 361.
  - The count is lower than after Batch 0 because the refactor retired/merged old suites that had lost their test subjects (mainly the deleted
    ErrorHandler/old HTTP context classes), not because tests were skipped; this round's new coverage is below.
- `mvn -Pdemos -DskipTests compile` passes.
- New regressions:
  - `Http1xTransportTest.unknownFileLengthIsResolvedBeforeSendfileCommitsHead`
  - `WebSocketCloseReasonTest` covers null, short reasons, the ASCII limit, and UTF-8 code-point boundaries
  - the new route/group/index shape is covered by `RouteIndexTest`, `WebSocketIndexTest`,
    and `UnresolvedHandlerRouteTest`; `RouteIndexTest` also pins the route descriptor
  - `ModuleDeclarationTest` covers class/instance, mixed declarations, bundles, and no-arg construction errors
  - `RuntimeDescriptionTest` / `AppRuntimeDescriptionTest` cover metadata-only descriptions
    and not instantiating lazy services
  - `ConfigFilePlanTest` covers base snapshot reuse and activation-key stripping for profile variants
  - `TopologicalOrderTest` covers Kahn ordering, the cycle remainder, and input-order tie-break
  - `DbModuleTest.customDialectIsSelectedByIdWithoutPrimaryTypeBinding` and
    `SqlTest.offsetWithoutLimitUsesTheDialectSpelling`
  - `FlowEngineTest.findReturnsEmptyAndRequireThrowsForUnknownGraph`,
    the missing-name Optional assertion in `DatabaseRegistryImplTest`,
    and `StaticResourceMountTest.durationCacheMaxAgeRendersWholeSeconds`

> The goal of the audit is not to reduce the number of types to zero, but to give every fact that still exists a single answer.
