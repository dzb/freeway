/**
 * Freeway's in-JVM graph orchestration engine: evaluate a DAG of tasks with
 * gateways, forks and loops, with conditions written as a small expression
 * language and tasks referenced against the container.
 *
 * <p>The graph schema (the {@code version: 3} JSON document shape parsed by
 * {@link com.jujin.freeway.flow.GraphSpec}) is freeway-native: closed node
 * vocabulary, build-time validation (cycles, entry, expressions, references,
 * join declarations all fail the build, never the run), and a PARALLEL model
 * with declared write isolation. This is an orchestrator, not a durable
 * workflow engine — a context cannot be persisted and resumed in another
 * process; evaluation is synchronous in this JVM.</p>
 *
 * <p>Zero dependencies beyond the framework modules it consumes
 * (ioc, commons).</p>
 *
 * <p><b>Provenance.</b> The engine's lineage is
 * <a href="https://github.com/opensolon/solon-flow">solon-flow 4.0.2</a>
 * (Apache License 2.0). What survives that lineage is the node taxonomy and
 * a few traversal invariants; the graph schema, the vocabulary, the
 * execution model, the join/loop semantics and the validation stance are
 * freeway-native rework, not upstream compatibility. See
 * {@code README.md} for the dependency-removal table and
 * {@code docs/graph-v3.md} for the schema.</p>
 *
 * <h3>Core entry points</h3>
 * <ul>
 *   <li>{@link com.jujin.freeway.flow.FlowEngine} — create an instance,
 *       load graphs, {@code eval}</li>
 *   <li>{@link com.jujin.freeway.flow.Graph} / {@link com.jujin.freeway.flow.GraphSpec}
 *       — the immutable runtime model and its validating blueprint</li>
 *   <li>{@link com.jujin.freeway.flow.FlowContext} — the run's data map,
 *       event bus and stop signal</li>
 *   <li>{@link com.jujin.freeway.flow.FlowDriverDefault} — resolves the task
 *       vocabulary: inline components, {@code @name} (container), and
 *       {@code #graphId} (sub-graph); nodes may also carry {@code data}</li>
 *   <li>{@link com.jujin.freeway.flow.FlowModule} — the IoC assembly</li>
 * </ul>
 *
 * <h3>Cross-cutting</h3>
 * <ul>
 *   <li>{@link com.jujin.freeway.flow.FlowInterceptor} — contributed chain
 *       (flow-level and node-level), fixed at load</li>
 *   <li>{@link com.jujin.freeway.flow.ExecState} — per-evaluation join
 *       counters and dead-end marks, engine-owned</li>
 *   <li>{@link com.jujin.freeway.flow.FlowEventBus} — topic pub/sub scoped
 *       to one execution</li>
 *   <li>{@link com.jujin.freeway.flow.ExprEvaluator} — the condition
 *       expression language</li>
 *   <li>{@link com.jujin.freeway.flow.PlantUmlOptions} — diagram rendering
 *       of a graph</li>
 * </ul>
 */
package com.jujin.freeway.flow;
