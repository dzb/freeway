/**
 * Lightweight graph orchestration engine, ported from solon-flow 4.0.2.
 *
 * <p>Source project: <a href="https://github.com/opensolon/solon-flow">opensolon/solon-flow</a>
 * <br>Original author: noear (Xidong)
 * <br>Original license: Apache License 2.0</p>
 *
 * <p>Ported and adapted as a freeway framework module, keeping the core orchestration capabilities while adding zero new third-party dependencies.
 * See {@code README.md} in the module root for details. A few classes are
 * freeway-specific and noted as such in their javadoc: {@code ExecState},
 * {@code ExprEvaluator}, {@code FlowEventBus}, {@code FlowMarker}/
 * {@code FlowMarkerIndex} (the {@code !marker} task syntax), and
 * {@code FlowModule}.</p>
 *
 * <p><b>Naming:</b> property accessors follow Freeway's bare-property
 * convention ({@code graph.nodes()}, {@code spec.title()}) instead of the
 * upstream JavaBean {@code getX()} style — a deliberate divergence from
 * solon-flow so the module reads like every other Freeway module. Keyed
 * lookups on the map-shaped {@link com.jujin.freeway.flow.FlowContext} keep
 * the JDK {@code Map} vocabulary
 * ({@code get} / {@code getAs} / {@code getOrDefault}).</p>
 *
 * <h3>Core entry points</h3>
 * <ul>
 *   <li>{@link com.jujin.freeway.flow.FlowEngine} — the engine; create an instance and execute graphs</li>
 *   <li>{@link com.jujin.freeway.flow.Graph} — the graph; parsed from JSON or built programmatically</li>
 *   <li>{@link com.jujin.freeway.flow.FlowContext} — the context; carries execution variables</li>
 *   <li>{@link com.jujin.freeway.flow.FlowDriverDefault} — the default driver</li>
 *   <li>{@link com.jujin.freeway.flow.FlowModule} — the Freeway IoC module entry point</li>
 * </ul>
 *
 * <h3>Interceptors and event</h3>
 * <ul>
 *   <li>{@link com.jujin.freeway.flow.FlowInterceptor} / {@link com.jujin.freeway.flow.FlowInvocation} — the interceptor chain</li>
 *   <li>{@link com.jujin.freeway.flow.FlowEventBus} — the execution-level event bus</li>
 * </ul>
 *
 * <h3>Expressions</h3>
 * <ul>
 *   <li>{@link com.jujin.freeway.flow.ExprEvaluator} — the minimal conditional expression evaluator</li>
 * </ul>
 *
 * <h3>PlantUML export</h3>
 * <ul>
 *   <li>{@link com.jujin.freeway.flow.Graph#toPlantUml()} — generates PlantUML state diagram text</li>
 *   <li>{@link com.jujin.freeway.flow.PlantUmlOptions} / {@link com.jujin.freeway.flow.PlantUmlDisplayContext} / {@link com.jujin.freeway.flow.PlantUmlDisplayResult}</li>
 * </ul>
 *
 * <h3>Stability</h3>
 * <p>The types listed above (plus the model/spec types and
 * {@code FlowModule}) form the module's stable surface. Everything else in
 * this package is engine machinery that rides on those signatures — no
 * stability promise. The same applies to the classes in
 * {@code com.jujin.freeway.flow.internal} ({@code Stepper},
 * {@code FlowContextImpl}), which exist for root-package assembly only.</p>
 *
 * @see <a href="https://github.com/opensolon/solon-flow">solon-flow</a>
 * @since 1.2.2
 */
package com.jujin.freeway.flow;
