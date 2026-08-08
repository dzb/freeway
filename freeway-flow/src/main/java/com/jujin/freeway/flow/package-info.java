/**
 * Lightweight graph orchestration engine, ported from solon-flow 4.0.2.
 *
 * <p>Source project: <a href="https://github.com/opensolon/solon-flow">opensolon/solon-flow</a>
 * <br>Original author: noear (Xidong)
 * <br>Original license: Apache License 2.0</p>
 *
 * <p>Ported and adapted as a freeway framework module, keeping the core orchestration capabilities while adding zero new third-party dependencies.
 * See {@code README.md} in the module root for details.</p>
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
 * <h3>Interceptors and events</h3>
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
 *   <li>{@link com.jujin.freeway.flow.Graph#toPlantuml()} — generates PlantUML state diagram text</li>
 *   <li>{@link com.jujin.freeway.flow.PlantumlOptions} / {@link com.jujin.freeway.flow.PlantumlDisplayContext} / {@link com.jujin.freeway.flow.PlantumlDisplayResult}</li>
 * </ul>
 *
 * @see <a href="https://github.com/opensolon/solon-flow">solon-flow</a>
 * @since 1.2.2
 */
package com.jujin.freeway.flow;
