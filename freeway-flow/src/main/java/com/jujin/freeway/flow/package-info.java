/**
 * 轻量级图编排引擎，移植自 solon-flow 4.0.2。
 *
 * <p>源项目：<a href="https://github.com/opensolon/solon-flow">opensolon/solon-flow</a>
 * <br>原始作者：noear (西东)
 * <br>原始许可：Apache License 2.0</p>
 *
 * <p>移植适配为 freeway 框架模块，保持核心编排能力的同时做到零新增三方依赖。
 * 详见模块根目录 {@code README.md}。</p>
 *
 * <h3>核心入口</h3>
 * <ul>
 *   <li>{@link com.jujin.freeway.flow.FlowEngine} — 引擎，创建实例并执行图</li>
 *   <li>{@link com.jujin.freeway.flow.Graph} — 图，从 JSON 解析或编程构建</li>
 *   <li>{@link com.jujin.freeway.flow.FlowContext} — 上下文，携带执行变量</li>
 *   <li>{@link com.jujin.freeway.flow.FlowDriverDefault} — 默认驱动器</li>
 *   <li>{@link com.jujin.freeway.flow.FlowModule} — Freeway IoC 模块入口</li>
 * </ul>
 *
 * <h3>拦截器与事件</h3>
 * <ul>
 *   <li>{@link com.jujin.freeway.flow.FlowInterceptor} / {@link com.jujin.freeway.flow.FlowInvocation} — 拦截器链</li>
 *   <li>{@link com.jujin.freeway.flow.FlowEventBus} — 执行级事件总线</li>
 * </ul>
 *
 * <h3>表达式</h3>
 * <ul>
 *   <li>{@link com.jujin.freeway.flow.ExprEvaluator} — 极简条件表达式求值器</li>
 * </ul>
 *
 * <h3>PlantUML 导出</h3>
 * <ul>
 *   <li>{@link com.jujin.freeway.flow.Graph#toPlantuml()} — 生成 PlantUML 状态图文本</li>
 *   <li>{@link com.jujin.freeway.flow.PlantumlOptions} / {@link com.jujin.freeway.flow.PlantumlDisplayContext} / {@link com.jujin.freeway.flow.PlantumlDisplayResult}</li>
 * </ul>
 *
 * @see <a href="https://github.com/opensolon/solon-flow">solon-flow</a>
 * @since 1.2.2
 */
package com.jujin.freeway.flow;
