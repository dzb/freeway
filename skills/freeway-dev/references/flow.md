# Flow Reference

## Stable API

- `Graph` — immutable runtime model
- `GraphSpec2` — v2 DAG authoring surface (explicit entry, separated nodes+links)
- `FlowEngine` — `load()`, `eval()`, `register(TaskComponent)`, `markerIndex()`
- `FlowModule` — IoC integration; auto-registers contributed `TaskComponent` instances
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
// v2 format (recommended)
GraphSpec2 bp = GraphSpec2.create("flow", spec -> {
    spec.entry("start");
    spec.addStart("start").linkAdd("task");
    spec.addActivity("task").task("!channel:order").linkAdd("end");
    spec.addEnd("end");
});
Graph graph = bp.create(); // normalize() validates links + reachability

// JSON — auto-detects v1/v2
Graph graph = Graph.fromText(json);

// Execution
FlowEngine engine = container.get(FlowEngine.class);
engine.load(graph);
engine.eval("flow", FlowContext.of());
```

## Important Behavior

- `Graph.fromText()` auto-detects v1 (`layout`) and v2 (`nodes`+`links`+`version`) JSON.
- `GraphSpec2.normalize()` validates link references and logs unreachable nodes.
- Flow expressions are cached (LRU, 512 entries) via self-written recursive-descent parser.
