# Flow Reference

## Stable API

- `Graph` — immutable runtime model
- `GraphSpec` — canonical DAG authoring surface (explicit entry, separated nodes+links); `create()` is the boot-time validation gate
- `FlowEngine` — `load()`, `unload()`, `graphs()`, `graph()`, `eval(graphId | graph [, context])`, `evalAndGet()`; create via `FlowEngine.create()` (standalone) or resolve from the container after `FlowModule`
- `FlowDriver` — contributed extension point; graph `"driver"` field selects by id (null/"" → `"default"`)
- `FlowDriverDefault` — built-in driver assembled by `FlowModule`; resolves `@name` against the container
- `FlowInterceptor` — contributed chain (`Extension<FlowInterceptor>`): `interceptFlow(context, graph, chain)` + `onNodeStart`/`onNodeEnd`; the chain is fixed at load, immutable while running
- `FlowModule` — IoC integration; binds `FlowEngine` singleton: built-in `"default"` driver + `Extension<FlowDriver>.asMap()` merge (a contributed `"default"` overrides, warn), contributed interceptors frozen into the chain

## Task Vocabulary (closed — build fails otherwise)

| Form | Resolves to |
|------|-------------|
| `@name` | `TaskHandler` (or `ConditionHandler` in a `when`) bound/contributed in the container with id `name` |
| `#graphId` | Another loaded graph, run as a sub-graph sharing the evaluation's join state; a child that never reaches END fails at the calling node |
| inline handler | `TaskHandler`/`ConditionHandler` supplied programmatically |
| node `data` field | Static values written into the context before the task runs |

Markers (`!marker`) and meta tasks (`$metaKey`) were dropped with v3: contribute with an id and use `@name`; use `data` for static values. Old forms fail `GraphSpec.create()` with migration hints.

## Canonical Snippets

```java
// Custom driver registration
binder.contribute(FlowDriver.class)
    .add("custom", new MyCustomDriver())
    .add(MyOtherDriver.class);   // container.create() auto-instantiation

// Graph with custom driver
// { "driver": "custom", ... }
```

```java
// Task handlers are plain container bindings by id:
binder.contribute(TaskHandler.class).add("orderHandler", (ctx, node) -> ...);

GraphSpec bp = GraphSpec.create("flow", spec -> {
    spec.entry("start");
    spec.addStart("start").linkAdd("task");
    spec.addActivity("task").task("@orderHandler")
        .data(Map.of("channel", "email"))            // static values on the node
        .linkAdd("end");
    spec.addEnd("end");
});
Graph graph = bp.create(); // boot gate: links, cycles, entry, vocabulary, when-expressions, join, data keys

// JSON — canonical format; version=3 is the gate
Graph graph = Graph.fromText(json);

// Execution
FlowEngine engine = container.get(FlowEngine.class);
engine.load(graph);
engine.eval("flow", FlowContext.of());
```

## Important Behavior

- `Graph.fromText()` parses only `version=3` canonical `nodes`+`links`; v1 `layout` and v2 are rejected.
- Build-time validation: cycles, unknown node types, out-of-vocabulary task strings, non-compiling `when` expressions, misplaced/unknown `join`, blank `data` keys all fail `create()`.
- Execution is an iterative frontier walk — no path-length stack limit; gateway dead ends fail the run loudly (naming node + graph); explicit `ctx.stop()` and interceptor vetoes are legal early completions.
- `PARALLEL` fork declares branch writes: default `join: "merge"` buffers each branch's writes and merges on clean completion, failing on write-write conflicts; `join: "shared"` opts back into shared writes.
- `$for`/`$in` loops iterate the body sequentially (no link back-edges); the claim is atomic across concurrent arrivals; iteration cap 100,000.
- No pause/resume: evaluation is synchronous in-JVM; `FlowContext.toJson()` is for diagnostics, not persistence.
- Flow expressions are cached (LRU, 512 entries) via self-written recursive-descent parser.
