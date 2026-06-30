# Design Decisions

Key architectural decisions and the reasoning behind them.

## Striped Lock for Singleton Concurrency

**Decision:** Replace `synchronized (targetCache)` with 64-way striped locking for singleton creation in `ServiceRuntime.realize()`.

**Why:** The global lock serialized all singleton creation, creating a bottleneck during startup (dense service creation) and lazy-load concurrency. Channeling contention to 64 stripes via `key.hashCode() & STRIPE_MASK` provides per-key serialization without global serialization.

**Key constraint:** `synchronized` (reentrant) over `ConcurrentHashMap.computeIfAbsent`. `computeIfAbsent` throws "Recursive update" on reentrant calls for the same key, which occurs with proxy-based lazy resolution in circular dependency scenarios. `synchronized` is inherently reentrant.

```java
Object lock = lockStripes[key.hashCode() & STRIPE_MASK];
synchronized (lock) {
    Object cached = targetCache.get(key);
    if (cached == null) {
        cached = binding.directInstance();
        targetCache.put(key, cached);
    }
    return binding.type().cast(cached);
}
```

**See also:** `ServiceRuntime.java:80-91` (`freeway-ioc`)

---

## Shutdown Exception Aggregation

**Decision:** Accumulate all shutdown-phase exceptions into a single `RuntimeException` with suppressed exceptions. Always print to stderr unconditionally, then rethrow.

**Why:** Shutdown involves multiple sequential steps (publish lifecycle event → stop hooks → close container → log). A failure in an earlier step should not prevent later steps from running. Previously, early shutdown exceptions silently swallowed later failures. The new approach:

- Each step wraps its exception via `accumulate()` (null-safe suppression chaining)
- After all steps, `failure.printStackTrace(System.err)` outputs everything (SLF4J may be unavailable during shutdown)
- Rethrows the aggregated failure so callers know shutdown was incomplete
- Sets `state = FAILED` on error

**See also:** `AppRuntimeDefault.close()`

---

## AST Caching for ExprEvaluator

**Decision:** Cache compiled expression ASTs in a `LinkedHashMap` LRU cache (max 512 entries) with `ReentrantReadWriteLock` for concurrent access.

**Why:** The flow engine evaluates the same condition expression repeatedly across graph traversals. Previously, every `evalCondition()` call re-parsed the expression from scratch. The cache stores the AST after first parse; subsequent evaluations skip parsing entirely.

```java
private static final Map<String, AstNode> CACHE = new LinkedHashMap<>(512, 0.75f, true) {
    protected boolean removeEldestEntry(Map.Entry<String, AstNode> eldest) {
        return size() > 512;
    }
};
```

Read-write lock allows concurrent reads (fast path) and serializes the rare write (first parse, cache miss). The `match()` method contains a fix where operator characters (like `!`) skip ident-boundary validation — `!isIdentStart(token.charAt(0))` prevents `!active` from being incorrectly rejected.

**See also:** `ExprEvaluator.java` (`freeway-flow`)

---

## HTTP Body/Multipart Size Limits

**Decision:** Add `maxBodySize` (default 10MB) to `HttpServerConfig` and `maxPartSize` to `MultipartForm.parse()`.

**Why:** Without size limits, large HTTP bodies or multipart uploads can cause OOM by consuming all available heap. The limit is applied at the read boundary: the server rejects bodies exceeding the configured maximum before buffering them entirely.

```java
public static final long DEFAULT_MAX_BODY_SIZE = 10 * 1024 * 1024L; // 10MB
```

When exceeded, a `BodyTooLargeException` is thrown and handled by the built-in `ExceptionMapper` returning HTTP 413 Payload Too Large.

**See also:** `HttpServerConfig.java`, `MultipartForm.java` (`freeway-http`)

---

## Container API: Remove `inject()`, Add `create()`

**Decision:** `Container.inject(Object)` was removed. `Container.create(Class<T>)` added as the public API.

**Why:** `inject()` was a "half measure" encouraging `new X() + container.inject(x)` — an anti-pattern where the caller manages part of the lifecycle and the container manages the rest. `create()` provides a clean contract: constructor injection + field injection + `@PostConstruct`, caller owns the lifecycle, no caching, no container management. The name `create()` was chosen over `instantiate()` as more idiomatic.

Final Container API: `get(Class)`, `get(Class, String)`, `extension(Class)`, `create(Class)`, `close()`.

**See also:** `Container.java` (`freeway-ioc`)

---

## Route Record: Remove `handlerType` Field

**Decision:** The `Route` record went from 4 fields to 3 (`method`, `path`, `handler`). The `handlerType` field and its accessor were removed.

**Why:** The 4-field version created an awkward "either handler or handlerType" invariant that shifted validation complexity into every consumer. By moving class-based lazy resolution into `LazyHandler` (a `RouteHandler` wrapper), the record itself has no special cases — it always carries a resolved `RouteHandler`. Factory methods like `Route.get("/path", MyHandler.class)` internally create `new LazyHandler(MyHandler.class)`.

**See also:** `Route.java`, `LazyHandler.java` (`freeway-http`)

---

## RouteIndex: Remove Container Dependency

**Decision:** `RouteIndex` constructor no longer accepts a `Container` parameter. Container-dependent resolution of lazy handlers moved to `HttpModule`'s provider function.

**Why:** `RouteIndex` is a pure data structure (trie-based path matcher) — it should not know about IoC. The Container dependency was leaked into it because class-based handlers needed resolution during construction. The fix:

1. `HttpModule` binds `RouteIndex` via a provider function
2. The provider resolves all `LazyHandler` instances by calling `lh.resolve(container)` 
3. Passes the resolved handlers to `new RouteIndex(routes, groups)`

This keeps the IoC dependency boundary at the Module level, identical to the `freeway-db` pattern.

**See also:** `HttpModule.java:45-52`, `RouteIndex.java`, `LazyHandler.java`

---

## Class-Based Contribution Via `Contributions.add(Class)`

**Decision:** `binder.contribute(T).add(MyImpl.class)` defers instantiation and injection until after all bindings are registered.

**Why:** When `add(Class)` is called during `bind()`, the container's bindings are not yet fully registered — so `container.create(implClass)` would fail if `MyImpl` depends on bindings from the same module. The solution defers instantiation to `flushPending()` (after all module `bind()` methods have run):

```java
List<Runnable> pendingCreates = new ArrayList<>();

// In add(Class):
pendingCreates.add(() -> {
    V instance = container.create(implClass);
    deferred.apply(ext.add(id, instance));
});

// In flushPending():
for (var action : pendingCreates) action.run();
```

The auto-generated id uses snake_case from the class name (`MyHandler` → `"my_handler"`). `DeferredContribution` stores `before/after` ordering constraints during `add(Class)` and applies them to the real `Contribution` at flush time.

**See also:** `BinderImpl.java`, `Contributions.java` (`freeway-ioc`)

---

## Three-Layer Architecture

```
freeway-commons         zero deps
 ├─ freeway-ioc         depends on commons
 │   ├─ freeway-http    depends on ioc (+ commons transitive)
 │   └─ freeway-flow    depends on ioc + commons
 └─ freeway-db          depends on commons (ioc optional)
```

**Decision:** `freeway-db` makes IoC optional — it is independently usable with only `freeway-commons`. `DbModule` bridges it to the container.

**Why:** A database access layer should be testable standalone and usable in non-IoC contexts. The IoC integration is contained entirely in `DbModule` — a gateway module that wires standalone DB types to IoC scopes/configuration. `freeway-http` follows the same pattern: `HttpModule` is the sole IoC-aware entry point; the rest of `freeway-http` has no dependency on `freeway-ioc`.

---

## `.primary()` for Implementation Selection

**Decision:** Default implementations are bound without `.primary()`. Alternative implementations are bound with `.primary()` for automatic selection.

**Why:** Avoids config keys for engine/pool/dialect selection. Adding the extension module to the classpath is sufficient — the container resolves the `.primary()` binding automatically. Same pattern across:

- HTTP engine: `FreewayHttpEngine` (built-in, no `.primary()`) vs `UndertowEngine` (with `.primary()`)
- Connection pool: `PoolDefault` (built-in) vs `HikariPool` (with `.primary()`)
- DB dialect: `PostgresDialect` (default) vs custom (with `.primary()`)

---

## LazyHandler Resolution Strategy

**Decision:** `LazyHandler.resolve()` uses DCL (double-checked locking) with `volatile`. Resolution is eager in `HttpModule`'s provider (before `RouteIndex` construction) but the mechanism supports lazy resolution at match time.

```java
RouteHandler resolve(Container container) {
    RouteHandler h = resolved;
    if (h == null) {
        synchronized (this) {
            h = resolved;
            if (h == null) {
                resolved = h = container.get(handlerType);
            }
        }
    }
    return h;
}
```

**Why:** DCL provides thread-safe lazy initialization with minimal overhead on the fast path (a single volatile read). The `synchronized` block is package-private and only called during module wiring, not on the request path. The `handle()` method throws if `resolved` is null — this is a programming error guard (must call `resolve()` before handling) rather than a fallback.

**See also:** `LazyHandler.java`

---

## DeferredContribution: Bridge bind() → flush()

**Decision:** A lightweight `DeferredContribution` stores `before/after` ordering constraints during `bind()` and applies them post-instantiation.

**Why:** `add(Class)` defers instantiation to `flushPending()`, but the caller specifies ordering at `bind()` time. `DeferredContribution` captures those ordering constraints eagerly and replays them onto the real `Contribution` (from `ext.add(id, instance)`) once the instance is created. This matches `Extension.Entry`'s behavior (append-only `before`/`after` lists).

**See also:** `BinderImpl.DeferredContribution`

---

## Utility Naming Convention

**Decision:** Framework-provided implementations use the naming convention `XDefault` (e.g. `AppRuntimeDefault`, `JsonCodecDefault`, `RequestContextDefault`, `CoercerDefault`).

**Why:** `XDefault` keeps the interface name dominant in alphabetized lists and imports. The name reads as "the default X" rather than "a default of X." The alternative `DefaultX` was rejected because it submerges the interface name.

## Design Philosophy

A summary of the project's architectural values as established in this session:

- **Small explicit APIs over future-proof abstractions.** Start with what's needed. Don't abstract for hypothetical future requirements.
- **Module as the IoC boundary.** IoC dependencies should not leak into the module's main codebase — they belong in the Module integration class.
- **Constructor injection for framework internals**, field injection acceptable for app code and config values.
- **No classpath scanning, no bytecode weaving.** Everything is explicit in `bind()`.
- **Records for value types.** `Route`, `HttpServerConfig`, `HttpServerConfig.Ssl` are records with compact constructors for validation.
- **Fail early and noisily.** Validation exceptions during construction, `BodyTooLargeException` during oversized request handling, shutdown exceptions always surfaced.
