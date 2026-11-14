# Porting adaptation assessment: solon-flow → freeway-flow

> Assessment date: 2026-06-26  
> Assessment subject: extracting the solon-flow core orchestration engine and adapting it to the freeway framework

## I. Source module analysis: solon-flow

### Module architecture

solon-flow is a general-purpose graph orchestration engine based on the Solon framework, supporting scenarios such as workflows, approval flows, and rule engines.

```
solon-flow/
├── solon-flow/              ← Core engine (source of this port)
├── solon-flow-workflow/     ← Approval flow layer (not ported)
├── solon-flow-projects/     ← Expression adapters (not ported)
├── solon-flow-designer/     ← Visual designer (not ported)
└── solon-flow-dataflow/     ← DataFlow module (not built)
```

### Core model

| Class | Responsibility |
|---|---|
| `Graph` | Graph container: id/title/driver/metas/nodes/links/start |
| `Node` | Node: id/title/type/when/task/nextLinks/prevLinks |
| `Link` | Directed link: prevId/nextId/title/priority/when |
| `NodeType` | 7 types: START(1)/END(2)/ACTIVITY(11)/EXCLUSIVE(21)/INCLUSIVE(31)/PARALLEL(32)/LOOP(33) |

**Design principle**: Spec (mutable Builder) → runtime objects (immutable). GraphSpec/NodeSpec/LinkSpec handle parsing and construction; Graph/Node/Link are immutable runtime models.

### Execution engine

`FlowEngineDefault.node_run()` is the core dispatcher:

```
node_run() dispatches by NodeType:
  START     → trigger lifecycle → flow out along all nextLink
  END       → trigger lifecycle → terminate
  ACTIVITY  → execute task → flow out along all nextLink
  EXCLUSIVE → find the first link whose condition is true → take only that branch
  INCLUSIVE → find all links whose condition is true → take all (with fan-in counting)
  PARALLEL  → wait for all incoming links to arrive → all outgoing links proceed concurrently (thread pool optional)
  LOOP      → iterate the collection pointed to by $for/$in, walking once per iteration
```

## II. External dependency analysis

External dependencies of the solon-flow core engine (the solon-flow module):

| Dependency | Purpose | Required? |
|---|---|---|
| `org.noear:solon` | Solon framework (Utils, IoC, annotations) | Utility classes can be replaced |
| `org.noear:solon-expression` | SnelParser expression parsing | Needs replacement |
| `org.noear:solon-aot` | AOT/native-image support | Not required (provided scope) |
| `org.yaml:snakeyaml` | YAML graph definition parsing | Can be cut (JSON only) |
| `org.noear:snack4` | JSON serialization (ONode) | Needs replacement |
| `org.noear:dami2` | Event bus (DamiBus) | Needs replacement |
| `org.noear:liquor-eval` | Script execution (Scripts.eval) | Needs replacement |
| `org.slf4j:slf4j-api` | Logging interface | Already in freeway |

Among them, **5 must be replaced** (snakeyaml can be cut, solon-aot can be cut, slf4j already exists).

## III. Target environment analysis: freeway

### Framework characteristics

- JDK 25+, using ScopedValue, Record, Virtual Thread
- **core modules have zero external dependencies** (freeway-commons depends only on slf4j)
- compose-first, explicit ModuleEx registration, no classpath scanning

### Available built-in capabilities

| Capability | Location | Purpose |
|---|---|---|
| JSON parsing/serialization | freeway-commons `JsonUtils`/`JsonObject`/`JsonArray` | Replaces snack4 |
| IoC container | freeway-ioc `Container`/`Binder` | Replaces Solon IoC |
| Type conversion | freeway-commons `Coercer` | Type safety |
| Bean introspection | freeway-commons `BeanIntrospector` | Reflection utility |
| AOP | freeway-ioc `Advisor`/`MethodAdvice` | Method-level interception (cannot replace FlowInterceptor) |
| EventBus | freeway-ioc `EventBus` | Application-level pub/sub (too heavy for FlowContext) |
| Module system | freeway-ioc `ModuleEx` | Module registration entry |

### Missing capabilities

| Capability | Description |
|---|---|
| Expression evaluation | freeway has no expression/script engine at all |
| Flow-level interceptor chain | freeway AOP is method-level and cannot observe flow node lifecycles |
| Execution-level event bus | freeway EventBus is an application-level singleton, unsuitable for execution-level scope |

## IV. Dependency replacement plan

| solon-flow dependency | Purpose | freeway replacement | Strategy |
|---|---|---|---|
| `snakeyaml` | YAML parsing | — | **Cut**, JSON only |
| `snack4` (ONode) | JSON serialization | `freeway-commons` JsonObject/JsonArray/JsonUtils | **Replace** |
| `dami2` (DamiBus) | Event bus | `FlowEventBus` (self-written, ~90 lines) | **Self-written** |
| `liquor-eval` (Scripts) | Script evaluation | — | **Cut**, task only supports @bean/#graph/$meta |
| `solon-expression` (SnelParser) | Condition expressions | `ExprEvaluator` (self-written, ~280 lines) | **Self-written** |
| `solon.Utils` | String null/empty check | `str == null \|\| str.isEmpty()` | **Inline** |
| `solon.Assert` | Assertions | `Objects.requireNonNull` | **Replace** |
| `solon.lang.*` | Annotations | — | **Remove** |
| `solon.RankEntity` | Interceptor ordering | `RankedInterceptor` record | **Self-written** |

## V. Feasibility conclusion

### ✅ Fully feasible

1. **The core engine is a pure graph traversal algorithm** — FlowEngineDefault's roughly 400-line `node_run()` and its sub-methods depend on no external library and can be migrated directly
2. **The amount of self-written code is manageable** — ExprEvaluator (280 lines) + FlowEventBus (90 lines) + RankedInterceptor record (10 lines), about 380 lines of new code in total
3. **JSON adaptation is simple** — freeway's JsonObject/JsonArray API is highly similar to snack4's ONode; rewriting fromDom involves about 50 call sites
4. **PlantUML export has zero dependencies** — pure StringBuilder concatenation of the PlantUML text format, retained in full

### Risk points

| Risk | Level | Mitigation |
|---|---|---|
| ExprEvaluator edge cases | Low | 14 assertions + standard recursive-descent pattern |
| JsonObject constructor package-private | Low | Use the `JsonUtils.object()` factory method |
| freeway.Container vs flow.Container naming conflict | Low | Fully qualified names or renaming |
| PARALLEL node thread safety | Low | CountDownLatch + AtomicReference, consistent with solon-flow |

### Final dependencies

```xml
<dependency>com.jujin8.freeway:freeway-commons</dependency>   <!-- JSON + utilities -->
<dependency>com.jujin8.freeway:freeway-ioc</dependency>        <!-- IoC container -->
<dependency>org.slf4j:slf4j-api</dependency>                   <!-- Logging (already present) -->
```

**Zero new third-party dependencies.**
