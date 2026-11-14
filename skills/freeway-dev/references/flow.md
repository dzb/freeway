# Flow Reference

## Stable API

- `Graph` - immutable runtime model
- `GraphSpec` - canonical DAG authoring surface (explicit entry, separated nodes+links); `spec.create()` builds the immutable `Graph` and is the boot-time validation gate
- `FlowEngine` - `load()`, `unload()`, `graphs()`, `find()`/`require()`, `run(graphId | graph [, context])`, `runAndGet()`; create via `FlowEngine.create()` (standalone) or resolve from the container after `FlowModule`
- `FlowDriver` - contributed extension point; graph `"driver"` field selects by id (null/`""` -> `"default"`)
- `FlowDriverDefault` - built-in driver assembled by `FlowModule`; resolves `@name` against the container
- `FlowInterceptor` - contributed chain (`Extension<FlowInterceptor>`): `interceptFlow(context, graph, chain)` + `onNodeStart`/`onNodeEnd`; the chain is fixed at load, immutable while running
- `FlowModule` - IoC integration; binds `FlowEngine` singleton: built-in `"default"` driver + `Extension<FlowDriver>.asMap()` merge (a contributed `"default"` overrides, warn), contributed interceptors frozen into the chain

## Graph Definition

Seven node types: `START`, `END`, `ACTIVITY`, `EXCLUSIVE`, `INCLUSIVE`, `PARALLEL`, `LOOP` (JSON `type` is case-insensitive). The v3 shape is canonical: separated `nodes` + `links` with an explicit `entry`. `Graph.fromText()` accepts only `version=3` with both arrays; `spec.create()` validates every link endpoint, rejects cycles, duplicate unconditional links, entry problems and bad vocabulary or expressions, and warns about nodes unreachable from the entry (they may still be sub-graph targets).

```json
{
  "id": "orderFlow",
  "version": 3,
  "entry": "start",
  "nodes": [
    {"id": "start", "type": "start"},
    {"id": "approve", "type": "activity", "task": "@orderService"},
    {"id": "end", "type": "end"}
  ],
  "links": [
    {"from": "start", "to": "approve"},
    {"from": "approve", "to": "end"}
  ]
}
```

| Part | Meaning |
|------|---------|
| `id`, `version` | Graph id and the fixed schema gate (`3`) |
| `entry` | Explicit entry node; without it exactly one `START` is required |
| `nodes[]` | `id` + `type`; optional `task`, `data`, `when`, `title`, `meta` |
| `links[]` | `from` / `to`; optional `when`, `priority`, `title`, `meta` |

## Task Resolution (closed vocabulary - build fails otherwise)

| Form | Resolves to |
|------|-------------|
| `@name` | `TaskHandler` (or `ConditionHandler` in a `when`) bound in the container with id `name` |
| `#graphId` | Another loaded graph, run as a sub-graph sharing the evaluation's join state; a child that never reaches END fails at the calling node |
| inline handler | `TaskHandler`/`ConditionHandler` supplied programmatically, bypassing the container |
| node `data` field | Static values written into the context before the task runs; not a handler |

A `when` also accepts a bare expression evaluated by `ExprEvaluator`; tasks do not. Markers (`!marker`) and meta tasks (`$metaKey`) were dropped with v3: contribute with an id and use `@name`; use `data` for static values. Old forms fail graph construction with migration hints.

## Driver

Each graph selects its driver with the top-level `"driver"` field; absent, null, or blank selects `"default"`. `FlowModule` constructs the built-in `FlowDriverDefault` under `"default"` and merges contributed drivers with `container.extension(FlowDriver.class).asMap()`. A contributed driver with id `"default"` overrides the built-in and logs a warning. `FlowDriverDefault` executes inline handlers, resolves `@name` against the container, and runs `#graphId` as a nested sub-graph.

```java
// Contribute a custom driver, then name it in the graph
binder.contribute(FlowDriver.class)
    .add("custom", new MyCustomDriver())
    .addDeferred(MyOtherDriver.class);   // instantiated and injected by the container

// { "driver": "custom", ... }
```

`addDeferred(Class)` builds the driver after every module has bound, so it can inject any module's services. An unknown driver id fails when the engine resolves the driver for that graph.

## Canonical Snippets

```java
// Task handlers are plain container bindings by id:
TaskHandler orderHandler = (ctx, node) -> ...;
binder.bind(TaskHandler.class).to(c -> orderHandler).id("orderHandler");

GraphSpec bp = GraphSpec.create("flow", spec -> {
    spec.entry("start");
    spec.addStart("start").linkTo("task");
    spec.addActivity("task").task("@orderHandler")
        .data(Map.of("channel", "email"))            // static values on the node
        .linkTo("end");
    spec.addEnd("end");
});
Graph graph = bp.create(); // boot gate: links, cycles, entry, vocabulary, when-expressions, join, data keys

// JSON - canonical format; version=3 is the gate
Graph parsed = Graph.fromText(json);
```

## Execution

```java
FlowEngine engine = container.get(FlowEngine.class);
engine.load(graph);
engine.run("orderFlow", FlowContext.of());

// IoC - FlowModule resolves @name handlers and merges contributed drivers
FreewayApp.run(Launch.of(args), new AppModule(), new FlowModule());
```

A standalone `FlowEngine.create()` has only the built-in default driver and no container, so it supports inline handlers and `#graphId` but not `@name`. Under `FlowModule`, container-bound `TaskHandler`s (bind them with `.id(name)`) and contributed `FlowDriver`s are reachable, and the interceptor chain is frozen at engine construction.

The engine also provides PlantUML export (`Graph.toPlantUml()`), per-node execution tracing through interceptors and the execution-scoped `FlowEventBus`, sub-graph calls, and no external dependencies beyond the framework modules it consumes.

## Important Behavior

- `Graph.fromText()` parses only `version=3` canonical `nodes`+`links`; v1 `layout` and v2 are rejected.
- Build-time validation: link endpoints, cycles, duplicate unconditional links, unknown node types, out-of-vocabulary task strings, non-compiling `when` expressions, misplaced/unknown `join`, blank `data` keys all fail `create()`; unreachable nodes warn.
- Execution is an iterative frontier walk - no path-length stack limit; gateway dead ends fail the run loudly (naming node + graph); explicit `ctx.stop()` and interceptor vetoes are legal early completions.
- `PARALLEL` fork declares branch writes: default `join: "merge"` buffers each branch's writes and merges on clean completion, failing on write-write conflicts; `join: "shared"` opts back into shared writes.
- `$for`/`$in` loops iterate the body sequentially (no link back-edges); the claim is atomic across concurrent arrivals; iteration cap 100,000.
- No pause/resume: evaluation is synchronous in-JVM; `FlowContext.toJson()` is for diagnostics, not persistence.
- Flow expressions are cached (LRU, 512 entries) via self-written recursive-descent parser.
