# Flow Reference

## Stable API

- `Graph` — immutable runtime model
- `GraphSpec` — canonical DAG authoring surface (explicit entry, separated nodes+links)
- `FlowEngine` — `load()`, `eval()`, `register(TaskComponent)`, `markerIndex()`
- `FlowDriver` — contributed extension point; graph `"driver"` field selects by id (null/"" → `"default"`)
- `FlowDriverDefault` — built-in driver contributed by `FlowModule` as id `"default"`
- `FlowModule` — IoC integration; binds `FlowContainer`, creates `FlowDriverDefault` internally, merges custom drivers from `Extension<FlowDriver>.asMap()`, auto-registers contributed `TaskComponent` instances
- `@FlowMarker("name")` — repeatable, marks a `TaskComponent` for `!markerName` resolution
- `FlowMarkerIndex` — reverse index from marker names to handlers; `containsAll` matching, most markers wins

## Task Resolution

Nodes use prefix syntax to specify what to execute. Each prefix has distinct resolution logic:

| Prefix | Strategy | Resolves to |
|--------|----------|-------------|
| `!` | Marker | `TaskComponent` by `@FlowMarker` intersection — most markers wins |
| `@` | Bean | `TaskComponent` from IoC by binding id. Also usable in conditions to resolve `ConditionComponent` |
| `#` | Sub-graph | Another loaded graph, executed as a nested subflow |
| `$` | Meta | Reads graph metadata into execution context — no component resolution |

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
// canonical format
GraphSpec bp = GraphSpec.create("flow", spec -> {
    spec.entry("start");
    spec.addStart("start").linkAdd("task");
    spec.addActivity("task").task("!channel:order").linkAdd("end");
    spec.addEnd("end");
});
Graph graph = bp.create(); // normalize() validates links + reachability

// JSON — canonical nodes+links format
Graph graph = Graph.fromText(json);

// Execution
FlowEngine engine = container.get(FlowEngine.class);
engine.load(graph);
engine.eval("flow", FlowContext.of());
```

## Important Behavior

- `Graph.fromText()` parses the canonical `nodes`+`links`+`version` JSON; legacy `layout` format is rejected.
- `GraphSpec.normalize()` validates link references and logs unreachable nodes.
- Flow expressions are cached (LRU, 512 entries) via self-written recursive-descent parser.
