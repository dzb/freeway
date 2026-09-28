# Reflection Inventory

Every reflective site in the framework, its cache shape, and its path. GraalVM is
**not adopted** (no serverless demand); the purpose is hygiene: reflection is the
one machinery this framework cannot delete, so it gets one home, one cache
discipline, and a single page to consult when something is added or when
reachability metadata is ever needed.

## The rules the inventory enforces

1. **One home**: class-shape introspection (fields, properties, constructors,
   method handles) lives in `commons.bean` (`BeanIntrospector`, `BeanPlan`,
   `MethodHandleUtils`). Modules call those; nothing scans a class by hand.
2. **ClassValue for anything keyed by a class**, with the two documented
   exceptions below. A new `Class.getClass*` / `getDeclared*` / `Method.invoke`
   site outside this table needs a row and a reason.
3. **Failures are not cached** — a broken class must fail the same way on every
   attempt. Only deterministic successes are stored.
4. **Spread invocation (`invokeWithArguments`) is the standard form.** Pre-adapted
   exact handles are licensed only by profiling: a JSON-path experiment did the
   pre-adaptation and measured no steady-state effect (benchmark
   `1.5.6-SNAPSHOT` appendix — transient compile tax that JIT erases). It was
   reverted; the table records what exists, not what might be faster.

## Sites

| Site | Mechanism | Cache | Path |
|---|---|---|---|
| `commons.bean.BeanIntrospector` | plans; wrapped constructors; constructor **selection** | `ClassValue` × 3 (`PLANS`, `CONSTRUCTORS`, `SELECTED`) | every instance creation |
| `commons.bean.MethodHandleUtils` | method / default-method / constructor / VarHandle lookup | `ClassValue` per declaring class, inner `ConcurrentHashMap` | every advice call, lifecycle call, bean read |
| `commons.bean.BeanPlan` | record + bean property model (`VarHandle` fields, MH accessors) | via `BeanIntrospector.PLANS` | JSON, ORM, validation, injection |
| `commons.json.JsonCoercions` | empty-collection constructors | `ClassValue` | JSON deserialization |
| `commons.logging.LogBootstrap` | `Class.forName(name, false, loader)` provider probe | none (cold, once per boot) | startup |
| `ioc.internal.ContainerImpl` | constructor injection via `selectConstructor` + parameter resolution | `SELECTED` (type × annotation) | `Container.create`, prototypes, class-routed handlers |
| `ioc.internal.Lifecycle` | `@PostConstruct`/`@PreDestroy` discovery | `ClassValue` plan per concrete class | realize / destroy |
| `ioc.internal.ServiceProxy` | JDK `Proxy` over the service interface + per-proxy handle map | proxy built once per binding; map bounded by interface method count (deliberate micro-cache: shared-map lookup removed from advised calls) | advised service calls |
| `ioc.symbol.KnownKeys.of` | `getFields()` over a module's nested `ConfigKeys` table, namespace-fenced (a key outside the declared prefixes fails the bind) | none needed (once per module bind) | startup |
| `commons.logging.LogKeys.knownKeys` | `getDeclaredFields()` over its own package-private table (the fixed `freeway.log.*` names; fragments and `app.name` filtered out) | none needed (once per boot) | startup |
| `http.HttpModule` | `getFields()` over `HttpModule.ConfigKeys` (retired-prefix notice) | once per startup | startup |
| `ioc.internal.ModuleNode` | `getDeclaredConstructor()` module instantiation (class declarations) | none (cold, once per declared class per load) | composition assembly |
| `boot.FreewayApp` | same no-arg instantiation for `add(Class...)` (mirrors `ModuleNode.resolve`) | none (cold) | composition assembly |
| `db.internal.RowMapperResolver` | row mapper per type (BeanPlan-backed) | `ConcurrentHashMap` **per container instance** — deliberate: mapper creation reads resolver-owned coercer/ORM state, not just the class | queries |
| `cloud.rpc.RpcTarget` | declared-type `getMethods()` → dispatch table; call via MH handle | built once per export at startup | RPC dispatch |
| `cloud.rpc.RemoteProxyFactory` | JDK `Proxy` over the exported API interface | per call site, once | client |

## SPI and service files (what native would additionally need, someday)

`META-INF/services`: commons registers the SLF4J provider; db/http/boot register
`ModuleEx` (auto-discovery is opt-out by design). If a reachability pass ever
happens, the list is: this table's cached reflection (none — all reachability-free
*mechanically*, but `BeanPlan`-processed application types are not),
`Proxy` targets (service interfaces, export APIs), the SPI files above, and
config-file resources. Application entities/records are the user's share of that
bill — the boundary is documented, not hidden.
