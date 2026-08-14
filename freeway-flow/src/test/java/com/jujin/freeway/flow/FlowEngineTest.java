package com.jujin.freeway.flow;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流引擎核心测试
 */
class FlowEngineTest {

    // --- 线性流程 ---

    @Test
    void testLinearFlow() {
        Graph graph = Graph.create("linear", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@counter").linkAdd("e");
            spec.addEnd("e");
        });

        AtomicInteger counter = new AtomicInteger(0);
        FlowDriverDefault driver = FlowDriverDefault.builder()
                .container(name -> {
                    if ("counter".equals(name)) {
                        return (TaskComponent) (ctx, node) -> counter.incrementAndGet();
                    }
                    return null;
                })
                .build();

        FlowEngine engine = newEngine(driver);
        FlowContext ctx = FlowContext.of();
        engine.eval(graph, ctx);

        assertEquals(1, counter.get());
    }

    // --- 排他网关 ---

    @Test
    void testExclusiveGateway() {
        Graph graph = Graph.create("exclusive", spec -> {
            spec.addStart("s").linkAdd("gw");
            spec.addExclusive("gw").linkAdd("high", ld -> ld.when("score > 80"))
                    .linkAdd("low", ld -> ld.when("score <= 80"));
            spec.addActivity("high").task("@highTask").linkAdd("e");
            spec.addActivity("low").task("@lowTask").linkAdd("e");
            spec.addEnd("e");
        });

        List<String> executed = new ArrayList<>();
        FlowDriverDefault driver = FlowDriverDefault.builder()
                .container(name -> {
                    if ("highTask".equals(name)) {
                        return (TaskComponent) (ctx, node) -> executed.add("high");
                    }
                    if ("lowTask".equals(name)) {
                        return (TaskComponent) (ctx, node) -> executed.add("low");
                    }
                    return null;
                })
                .build();

        FlowEngine engine = newEngine(driver);

        // score = 90 → high
        FlowContext ctx1 = FlowContext.of();
        ctx1.put("score", 90);
        engine.eval(graph, ctx1);
        assertEquals(List.of("high"), executed);

        // score = 50 → low
        executed.clear();
        FlowContext ctx2 = FlowContext.of();
        ctx2.put("score", 50);
        engine.eval(graph, ctx2);
        assertEquals(List.of("low"), executed);
    }

    // --- JSON 解析 ---

    @Test
    void testGraphFromJson() {
        String json = """
                {
                  "id": "json_test",
                  "version": 2,
                  "nodes": [
                    { "id": "s", "type": "start" },
                    { "id": "a", "type": "activity", "task": "@jsonTask" },
                    { "id": "e", "type": "end" }
                  ],
                  "links": [
                    { "from": "s", "to": "a" },
                    { "from": "a", "to": "e" }
                  ]
                }""";

        Graph graph = Graph.fromText(json);
        assertEquals("json_test", graph.getId());
        assertEquals(3, graph.getNodes().size());
        assertNotNull(graph.getNode("s"));
        assertNotNull(graph.getNode("a"));
        assertNotNull(graph.getNode("e"));
        assertEquals(NodeType.START, graph.getStart().getType());

        // 执行
        AtomicInteger counter = new AtomicInteger(0);
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
                .container(name -> {
                    if ("jsonTask".equals(name)) {
                        return (TaskComponent) (ctx, node) -> counter.incrementAndGet();
                    }
                    return null;
                })
                .build());

        engine.eval(graph, FlowContext.of());
        assertEquals(1, counter.get());
    }

    // --- 表达式求值 ---

    @Test
    void testExprEvaluator() {
        var ctx = new ConcurrentHashMap<String, Object>();
        ctx.put("score", 90);
        ctx.put("name", "test");
        ctx.put("active", true);

        assertTrue(ExprEvaluator.evalCondition("score > 80", ctx));
        assertFalse(ExprEvaluator.evalCondition("score < 80", ctx));
        assertTrue(ExprEvaluator.evalCondition("score >= 90", ctx));
        assertTrue(ExprEvaluator.evalCondition("score == 90", ctx));
        assertTrue(ExprEvaluator.evalCondition("name == \"test\"", ctx));
        assertFalse(ExprEvaluator.evalCondition("name == \"other\"", ctx));
        assertTrue(ExprEvaluator.evalCondition("active", ctx));
        assertTrue(ExprEvaluator.evalCondition("score > 80 && active == true", ctx));
        assertFalse(ExprEvaluator.evalCondition("score > 80 && active == false", ctx));
        assertTrue(ExprEvaluator.evalCondition("score > 95 || active == true", ctx));
        assertFalse(ExprEvaluator.evalCondition("!active", ctx));
        assertTrue(ExprEvaluator.evalCondition("!(score < 50)", ctx));
    }

    @Test
    void testExprEvaluatorDepthGuard() {
        var ctx = new ConcurrentHashMap<String, Object>();
        ctx.put("score", 90);
        // Deeply nested parens must fail with FlowException, not a raw
        // StackOverflowError.
        String deep = "(".repeat(100) + "true" + ")".repeat(100);
        assertThrows(FlowException.class,
            () -> ExprEvaluator.evalCondition(deep, ctx));
        // Unary recursion is guarded too.
        assertThrows(FlowException.class,
            () -> ExprEvaluator.evalCondition("!".repeat(100) + "true", ctx));
        // Moderate nesting within the limit still works.
        assertTrue(ExprEvaluator.evalCondition("((((score > 80))))", ctx));
    }

    // --- 混合数值/字符串比较 (Bug 2 回归) ---

    @Test
    void exprEvaluatorComparesNumericStringsByValue() {
        // JSON context values arrive as strings ("score":"90") but must
        // compare numerically against numeric literals, not lexicographically.
        var ctx = new HashMap<String, Object>();
        ctx.put("score", "90");
        assertTrue(ExprEvaluator.evalCondition("\"10\" > 9", ctx),
            "\"10\" > 9 must be true (numeric, not lexicographic)");
        assertFalse(ExprEvaluator.evalCondition("9 > \"10\"", ctx));
        assertFalse(ExprEvaluator.evalCondition("\"10\" < 9", ctx));
        assertTrue(ExprEvaluator.evalCondition("\"10\" == 10", ctx));
        assertTrue(ExprEvaluator.evalCondition("10 == \"10\"", ctx));
        assertTrue(ExprEvaluator.evalCondition("\"1.5\" == 1.5", ctx));
        // Context-provided numeric strings behave the same way.
        assertTrue(ExprEvaluator.evalCondition("score > 80", ctx));
        assertTrue(ExprEvaluator.evalCondition("score == 90", ctx));
        assertTrue(ExprEvaluator.evalCondition("score >= 90", ctx));
        // Non-numeric strings keep the lexicographic fallback: 'a' > '9'.
        assertTrue(ExprEvaluator.evalCondition("\"abc\" > 9", ctx));
        assertFalse(ExprEvaluator.evalCondition("\"abc\" == 9", ctx));
    }

    @Test
    void exprEvaluatorShortCircuitsLogicalOperators() {
        // The right operand of && / || must only be evaluated when it can
        // affect the result — a dead right side must not throw.
        var ctx = new HashMap<String, Object>();
        assertFalse(ExprEvaluator.evalCondition("false && (x - 1)", ctx),
            "false && (x - 1) must short-circuit to false without evaluating (x - 1)");
        assertTrue(ExprEvaluator.evalCondition("true || (x - 1)", ctx),
            "true || (x - 1) must short-circuit to true without evaluating (x - 1)");
        // Non-short-circuit cases still evaluate the right operand and throw.
        assertThrows(FlowException.class,
            () -> ExprEvaluator.evalCondition("true && (x - 1)", ctx));
        assertThrows(FlowException.class,
            () -> ExprEvaluator.evalCondition("false || (x - 1)", ctx));
        // Word forms short-circuit too.
        assertFalse(ExprEvaluator.evalCondition("false and (x - 1)", ctx));
        assertTrue(ExprEvaluator.evalCondition("true or (x - 1)", ctx));
    }

    @Test
    void exprEvaluatorSupportsUnaryMinus() {
        // Full unary minus: -5, -x, -(a+b), --x, plus combinations with
        // arithmetic and comparisons. Previously only signed literals parsed
        // and "-x" failed with "Invalid number: '-'".
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("x", 3);
        ctx.put("a", 1);
        ctx.put("b", 2);

        // literal negation keeps exact integer semantics
        assertTrue(ExprEvaluator.evalCondition("-5", ctx));
        assertTrue(ExprEvaluator.evalCondition("-5 == -5", ctx));
        assertFalse(ExprEvaluator.evalCondition("-5 == 5", ctx));
        // identifier negation
        assertTrue(ExprEvaluator.evalCondition("-x == -3", ctx));
        assertFalse(ExprEvaluator.evalCondition("-x > 0", ctx));
        assertTrue(ExprEvaluator.evalCondition("-x < 0", ctx));
        assertTrue(ExprEvaluator.evalCondition("-x == -3 && -x + 3 == 0", ctx));
        // parenthesized expression negation
        assertTrue(ExprEvaluator.evalCondition("-(a+b) == -3", ctx));
        assertTrue(ExprEvaluator.evalCondition("-(a+b) < 0", ctx));
        // double negation cancels out
        assertTrue(ExprEvaluator.evalCondition("--x == x", ctx));
        assertTrue(ExprEvaluator.evalCondition("--x > 0", ctx));
        assertTrue(ExprEvaluator.evalCondition("-(-x) == 3", ctx));
        // unary minus binds tighter than binary + / -
        assertTrue(ExprEvaluator.evalCondition("-x + 10 == 7", ctx));
        assertTrue(ExprEvaluator.evalCondition("5 - -x == 8", ctx));
        assertTrue(ExprEvaluator.evalCondition("1 - -x == 4", ctx));
        // non-numeric negation fails loudly, like subtraction
        assertThrows(FlowException.class, () -> ExprEvaluator.evalCondition("-\"abc\"", ctx));
        assertThrows(FlowException.class, () -> ExprEvaluator.evalCondition("-true", ctx));
    }

    // --- PlantUML ---

    @Test
    void testPlantuml() {
        Graph graph = Graph.create("plantuml_test", "测试图", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@task1").linkAdd("gw");
            spec.addExclusive("gw")
                    .linkAdd("b", ld -> ld.when("x > 5").title("大"))
                    .linkAdd("c", ld -> ld.when("x <= 5").title("小"));
            spec.addActivity("b").task("@task2").linkAdd("e");
            spec.addActivity("c").task("@task3").linkAdd("e");
            spec.addEnd("e");
        });

        String puml = graph.toPlantuml();
        assertNotNull(puml);
        assertTrue(puml.contains("@startuml"));
        assertTrue(puml.contains("@enduml"));
        assertTrue(puml.contains("测试图"));
        assertTrue(puml.contains("s --> a"));
        assertTrue(puml.contains("<<choice>>"));
    }

    // --- 子图调用 ---

    @Test
    void testSubGraph() {
        Graph subGraph = GraphSpec.create("sub", "", "default", spec -> {
            spec.addStart("sub_s").linkAdd("sub_a");
            spec.addActivity("sub_a").task("@subTask").linkAdd("sub_e");
            spec.addEnd("sub_e");
        }).create();

        Graph mainGraph = GraphSpec.create("main", "", "default", spec -> {
            spec.addStart("s").linkAdd("call");
            spec.addActivity("call").task("#sub").linkAdd("e");
            spec.addEnd("e");
        }).create();

        List<String> executed = new ArrayList<>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
                .container(name -> {
                    if ("subTask".equals(name)) {
                        return (TaskComponent) (ctx, node) -> executed.add("sub");
                    }
                    return null;
                })
                .build());

        engine.load(subGraph);
        engine.load(mainGraph);
        engine.eval("main", FlowContext.of());

        assertEquals(List.of("sub"), executed);
    }

    @Test
    void subGraphUsesItsOwnDriver() {
        List<String> events = new ArrayList<>();

        FlowDriver mainDriver = FlowDriverDefault.builder()
            .container(name -> {
                if ("mainTask".equals(name)) {
                    return (TaskComponent) (ctx, node) ->
                        events.add("main:" + node.getGraph().getId() + ":" + node.getId());
                }
                if ("subTask".equals(name)) {
                    return (TaskComponent) (ctx, node) ->
                        events.add("main:" + node.getGraph().getId() + ":" + node.getId());
                }
                return null;
            })
            .build();

        FlowDriver subDriver = FlowDriverDefault.builder()
            .container(name -> {
                if ("mainTask".equals(name)) {
                    return (TaskComponent) (ctx, node) ->
                        events.add("sub:" + node.getGraph().getId() + ":" + node.getId());
                }
                if ("subTask".equals(name)) {
                    return (TaskComponent) (ctx, node) ->
                        events.add("sub:" + node.getGraph().getId() + ":" + node.getId());
                }
                return null;
            })
            .build();

        FlowEngine engine = FlowEngine.newInstance(Map.of(
            "default", mainDriver,
            "sub", subDriver
        ));

        Graph subGraph = GraphSpec.create("sub", "", "sub", spec -> {
            spec.addStart("sub_s").linkAdd("sub_a");
            spec.addActivity("sub_a").task("@subTask").linkAdd("sub_e");
            spec.addEnd("sub_e");
        }).create();

        Graph mainGraph = GraphSpec.create("main", "", "default", spec -> {
            spec.addStart("main_s").linkAdd("main_a");
            spec.addActivity("main_a").task("@mainTask").linkAdd("call");
            spec.addActivity("call").task("#sub").linkAdd("main_e");
            spec.addEnd("main_e");
        }).create();

        engine.load(subGraph);
        engine.load(mainGraph);
        assertSame(mainDriver, engine.getDriver(mainGraph));
        assertSame(subDriver, engine.getDriver(subGraph));
        engine.eval("main", FlowContext.of());

        assertEquals(List.of(
            "main:main:main_a",
            "sub:sub:sub_a"
        ), events);
    }

    @Test
    void exchangerCopyKeepsExecState() {
        FlowEngine engine = FlowEngine.newInstance();
        Graph graph = graphWithDriver("default");
        FlowExchanger exchanger = new FlowExchanger(
            graph,
            engine,
            engine.getDriver(graph),
            FlowContext.of(),
            -1,
            new AtomicInteger(0)
        );

        exchanger.execState().countSet(graph, "loop", 3);

        FlowExchanger copy = exchanger.copy(graph);
        assertSame(exchanger.execState(), copy.execState());
        assertEquals(3, copy.execState().count(graph, "loop"));
    }

    // --- 停止 ---

    @Test
    void testStop() {
        Graph graph = Graph.create("stop_test", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@stopper").linkAdd("b");
            spec.addActivity("b").task("@neverCalled").linkAdd("e");
            spec.addEnd("e");
        });

        AtomicInteger bCount = new AtomicInteger(0);
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
                .container(name -> {
                    if ("stopper".equals(name)) {
                        return (TaskComponent) (ctx, node) -> ctx.stop();
                    }
                    if ("neverCalled".equals(name)) {
                        return (TaskComponent) (ctx, node) -> bCount.incrementAndGet();
                    }
                    return null;
                })
                .build());

        FlowContext ctx = FlowContext.of();
        engine.eval(graph, ctx);
        assertEquals(0, bCount.get());
    }

    // --- 事件总线 ---

    @Test
    void testEventBus() {
        List<String> received = new ArrayList<>();
        FlowEventBus bus = new FlowEventBus();

        FlowEventBus.Subscription sub = bus.subscribe("order.created", event -> {
            received.add("got: " + event);
        });

        bus.publish("order.created", "hello");
        assertEquals(List.of("got: hello"), received);

        bus.publish("other.topic", "ignored");
        assertEquals(1, received.size()); // 不应收到其他 topic 的事件

        bus.unsubscribe(sub);
        bus.publish("order.created", "after_unsubscribe");
        assertEquals(1, received.size()); // 取消订阅后不再收到
    }

    @Test
    void testEventBusClear() {
        List<String> received = new ArrayList<>();
        FlowEventBus bus = new FlowEventBus();
        bus.subscribe("t1", event -> received.add("t1:" + event));
        bus.subscribe("t2", event -> received.add("t2:" + event));

        bus.publish("t1", "a");
        assertEquals(List.of("t1:a"), received);

        bus.clear();
        bus.publish("t1", "b");
        bus.publish("t2", "c");
        assertEquals(1, received.size()); // 清空后所有 topic 均不再派发
    }

    @Test
    void testEventBusInFlow() {
        List<String> received = new ArrayList<>();

        Graph graph = Graph.create("event_flow", spec -> {
            spec.addStart("s").linkAdd("pub");
            spec.addActivity("pub").task("@publisher").linkAdd("e");
            spec.addEnd("e");
        });

        FlowEngine engine = newEngine(FlowDriverDefault.builder()
                .container(name -> {
                    if ("publisher".equals(name)) {
                        return (TaskComponent) (ctx, node) -> {
                            ctx.eventBus().subscribe("done", event -> received.add((String) event));
                            ctx.eventBus().publish("done", "fired");
                        };
                    }
                    return null;
                })
                .build());

        engine.eval(graph, FlowContext.of());
        assertEquals(List.of("fired"), received);
    }

    // --- 拦截器链 ---

    @Test
    void testInterceptorChain() {
        Graph graph = Graph.create("interceptor_flow", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@taskA").linkAdd("e");
            spec.addEnd("e");
        });

        List<String> events = new ArrayList<>();

        FlowInterceptor auditor = new FlowInterceptor() {
            @Override
            public void interceptFlow(FlowInvocation inv) {
                events.add("flow:before:" + inv.getGraph().getId());
                inv.invoke();
                events.add("flow:after:" + inv.getGraph().getId());
            }

            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                events.add("node:enter:" + node.getId());
            }

            @Override
            public void onNodeEnd(FlowContext ctx, Node node) {
                events.add("node:leave:" + node.getId());
            }
        };

        FlowEngine engine = newEngine(FlowDriverDefault.builder()
                .container(name -> {
                    if ("taskA".equals(name)) {
                        return (TaskComponent) (ctx, node) -> events.add("task:exec:" + node.getId());
                    }
                    return null;
                })
                .build());

        engine.addInterceptor(auditor);
        engine.eval(graph, FlowContext.of());

        assertEquals(List.of(
                "flow:before:interceptor_flow",
                "node:enter:s",
                "node:leave:s",
                "node:enter:a",
                "task:exec:a",
                "node:leave:a",
                "node:enter:e",
                "node:leave:e",
                "flow:after:interceptor_flow"
        ), events);
    }

    @Test
    void testInterceptorStopFlow() {
        Graph graph = Graph.create("stop_interceptor", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@neverRun").linkAdd("e");
            spec.addEnd("e");
        });

        AtomicInteger taskRan = new AtomicInteger(0);

        FlowEngine engine = newEngine(FlowDriverDefault.builder()
                .container(name -> {
                    if ("neverRun".equals(name)) {
                        return (TaskComponent) (ctx, node) -> taskRan.incrementAndGet();
                    }
                    return null;
                })
                .build());

        // 拦截器在 flow 层面直接阻止执行
        engine.addInterceptor(new FlowInterceptor() {
            @Override
            public void interceptFlow(FlowInvocation inv) {
                // 不调用 inv.invoke() → 流程不执行
            }
        });

        engine.eval(graph, FlowContext.of());
        assertEquals(0, taskRan.get());
    }

    @Test
    void testMultipleInterceptors() {
        Graph graph = Graph.create("multi_interceptor", spec -> {
            spec.addStart("s").linkAdd("e");
            spec.addEnd("e");
        });

        List<String> order = new ArrayList<>();

        FlowInterceptor a = new FlowInterceptor() {
            @Override public void interceptFlow(FlowInvocation inv) {
                order.add("A:before");
                inv.invoke();
                order.add("A:after");
            }
        };
        FlowInterceptor b = new FlowInterceptor() {
            @Override public void interceptFlow(FlowInvocation inv) {
                order.add("B:before");
                inv.invoke();
                order.add("B:after");
            }
        };

        FlowEngine engine = FlowEngine.newInstance();
        engine.addInterceptor(a);
        engine.addInterceptor(b);
        engine.eval(graph, FlowContext.of());

        // a 先注册，b 后注册（同 index=0），按添加顺序：a → b
        assertEquals(List.of("A:before", "B:before", "B:after", "A:after"), order);
    }

    // ──── typed task 注册 ────

    public record Greeter(String greeting) {}

    @FlowMarker("test:injected")
    public static final class InjectedTask implements TaskComponent {
        @Inject
        private Greeter greeter;

        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            context.put("result", greeter.greeting());
        }
    }

    @Test
    void containerInjectWiresFields() {
        var container = Freeway.create(
                binder -> binder.bind(Greeter.class).to(new Greeter("Hello, Flow!")));

        var task = container.create(InjectedTask.class);

        assertNotNull(task.greeter);
        assertEquals("Hello, Flow!", task.greeter.greeting());
    }

    @Test
    void contributedTaskIsInjectedAndDiscovered() {
        var container = Freeway.create(
                binder -> {
                    binder.bind(Greeter.class).to(new Greeter("Hi!"));
                    binder.contribute(TaskComponent.class).add(InjectedTask.class);
                });

        var driver = new FlowDriverDefault(null, null);
        var engine = new FlowEngineImpl(Map.of("default", driver));
        for (var handler : container.extension(TaskComponent.class).all()) {
            engine.register(handler);
        }

        engine.load(Graph.create("test", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a")
                .task("!test:injected")
                .linkAdd("e");
            spec.addEnd("e");
        }));

        var ctx = FlowContext.of();
        engine.eval("test", ctx);

        assertEquals("Hi!", ctx.getAs("result"));
    }

    // ──── Flow marker resolution tests ────

    @FlowMarker("channel:email")
    @FlowMarker("priority:high")
    static class EmailTask implements TaskComponent {
        @Override
        public void run(FlowContext context, Node node) {
            context.put("handler", "email");
        }
    }

    @FlowMarker("channel:sms")
    @FlowMarker("priority:high")
    static class SmsTask implements TaskComponent {
        @Override
        public void run(FlowContext context, Node node) {
            context.put("handler", "sms");
        }
    }

    @FlowMarker("channel:email")
    @FlowMarker("priority:low")
    static class BatchEmailTask implements TaskComponent {
        @Override
        public void run(FlowContext context, Node node) {
            context.put("handler", "batch-email");
        }
    }

    @Test
    void markerResolvesMostSpecificHandler() {
        Graph graph = Graph.create("marker_flow", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a")
                .task("!channel:email !priority:high")
                .linkAdd("e");
            spec.addEnd("e");
        });

        FlowEngine engine = FlowEngine.newInstance();
        // Register handlers — they get auto-indexed in markerIndex
        engine.register(new EmailTask());
        engine.register(new SmsTask());
        engine.register(new BatchEmailTask());
        engine.load(graph);

        FlowContext ctx = FlowContext.of();
        engine.eval(graph, ctx);

        assertEquals("email", ctx.getAs("handler"),
            "Should resolve to most specific: EmailTask (2 markers > BatchEmailTask's 2 but email+high matches)");
    }

    @Test
    void markerFailsWhenNoHandlerMatches() {
        Graph graph = Graph.create("no_match", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a")
                .task("!channel:push")
                .linkAdd("e");
            spec.addEnd("e");
        });

        FlowEngine engine = FlowEngine.newInstance();
        engine.register(new EmailTask());
        engine.load(graph);

        assertThrows(FlowException.class, () ->
            engine.eval(graph, FlowContext.of()));
    }

    @Test
    void markerResolutionViaDriver() {
        Graph graph = Graph.create("driver_marker", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a")
                .task("!channel:sms !priority:high")
                .linkAdd("e");
            spec.addEnd("e");
        });

        FlowEngine engine = FlowEngine.newInstance();
        engine.register(new SmsTask());
        engine.register(new EmailTask());
        engine.load(graph);

        FlowContext ctx = FlowContext.of();
        engine.eval(graph, ctx);

        assertEquals("sms", ctx.getAs("handler"));
    }

    private static FlowEngine newEngine(FlowDriver driver) {
        return FlowEngine.newInstance(Map.of("default", driver));
    }

    // ── regression: deep-graph stack safety ───────────────────────

    private static Graph chain(int nodes) {
        GraphSpec b = GraphSpec.create("chain_" + nodes, x -> {});
        b.entry("s");
        b.addStart("s");
        for (int i = 0; i < nodes; i++) b.addActivity("a" + i);
        b.addEnd("e");
        b.link("s", "a0");
        for (int i = 0; i < nodes - 1; i++) b.link("a" + i, "a" + (i + 1));
        b.link("a" + (nodes - 1), "e");
        return b.create();
    }

    @Test
    void moderatelyDeepGraphStillRuns() {
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> {})
            .build());
        assertDoesNotThrow(() -> engine.eval(chain(500), FlowContext.of()));
    }

    @Test
    void excessivelyDeepGraphFailsCleanlyInsteadOfStackOverflow() {
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> {})
            .build());
        int nodes = FlowEngineImpl.MAX_EXECUTION_DEPTH + 200;
        FlowException ex = assertThrows(
            FlowException.class,
            () -> engine.eval(chain(nodes), FlowContext.of())
        );
        assertTrue(ex.getMessage().contains("depth"),
            "must report a clear depth error, got: " + ex.getMessage());
    }

    @Test
    void flowMarkerIndexExtractsAnnotations() {
        Set<String> markers = FlowMarkerIndex.extractFlowMarkers(EmailTask.class);
        assertEquals(Set.of("channel:email", "priority:high"), markers);
    }

    // ── driver resolution ──────────────────────────────────────

    private static Graph graphWithDriver(String driver) {
        return GraphSpec.create("g", "", driver, s -> {
            s.entry("s");
            s.addStart("s").linkAdd("e");
            s.addEnd("e");
        }).create();
    }

    @Test
    void driverDefaultWhenNull() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.newInstance(Map.of("default", driver));
        Graph g = GraphSpec.create("g", s -> {
            s.entry("s"); s.addStart("s").linkAdd("e"); s.addEnd("e");
        }).create();
        assertSame(driver, engine.getDriver(g));
    }

    @Test
    void driverDefaultWhenEmpty() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.newInstance(Map.of("default", driver));
        Graph g = GraphSpec.create("g", "", "", s -> {
            s.entry("s"); s.addStart("s").linkAdd("e"); s.addEnd("e");
        }).create();
        assertSame(driver, engine.getDriver(g));
    }

    @Test
    void driverDefaultWhenBlank() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.newInstance(Map.of("default", driver));
        Graph g = GraphSpec.create("g", "", "   ", s -> {
            s.entry("s"); s.addStart("s").linkAdd("e"); s.addEnd("e");
        }).create();
        assertSame(driver, engine.getDriver(g));
    }

    @Test
    void driverDefaultWhenLiteralDefault() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.newInstance(Map.of("default", driver));
        Graph g = graphWithDriver("default");
        assertSame(driver, engine.getDriver(g));
    }

    @Test
    void driverCustomById() {
        FlowDriver defaultDriver = new FlowDriverDefault(null, null);
        FlowDriver customDriver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.newInstance(Map.of(
            "default", defaultDriver,
            "custom", customDriver
        ));
        Graph g = graphWithDriver("custom");
        assertSame(customDriver, engine.getDriver(g));
    }

    @Test
    void driverUnknownThrows() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.newInstance(Map.of("default", driver));
        Graph g = graphWithDriver("nonexistent");
        assertThrows(IllegalArgumentException.class, () -> engine.getDriver(g));
    }

    @Test
    void driverMultipleCoexist() {
        var a = new AtomicInteger(0);
        FlowDriver driverA = FlowDriverDefault.builder()
            .container(name -> { a.incrementAndGet(); return null; }).build();
        var b = new AtomicInteger(0);
        FlowDriver driverB = FlowDriverDefault.builder()
            .container(name -> { b.incrementAndGet(); return null; }).build();

        FlowEngine engine = FlowEngine.newInstance(Map.of("a", driverA, "b", driverB));

        assertSame(driverA, engine.getDriver(graphWithDriver("a")));
        assertSame(driverB, engine.getDriver(graphWithDriver("b")));
    }

    // ── FlowContainer binding + custom driver integration ────

    static class CountingDriver extends FlowDriverDefault {
        final AtomicInteger invoked = new AtomicInteger(0);
        final String label;

        CountingDriver(FlowContainer container, String label) {
            super(container, null);
            this.label = label;
        }

        @Override
        public void postHandleTask(FlowExchanger exchanger, TaskDesc task) throws Throwable {
            invoked.incrementAndGet();
            exchanger.context().put("driver", label);
        }
    }

    /** add(Class)-compatible driver — constructor takes only injectable types. */
    static class InjectedDriver extends FlowDriverDefault {
        final AtomicInteger invoked = new AtomicInteger(0);

        public InjectedDriver(FlowContainer container) {
            super(container, null);
        }

        @Override
        public void postHandleTask(FlowExchanger exchanger, TaskDesc task) throws Throwable {
            invoked.incrementAndGet();
            exchanger.context().put("driver", "injected");
        }
    }

    @Test
    void flowContainerBindingResolvesBeanNames() {
        var counter = new AtomicInteger(0);
        var container = Freeway.create(binder -> {
            binder.bind(FlowContainer.class)
                .to((Container c) -> (FlowContainer) name -> {
                    if ("counter".equals(name)) {
                        return (TaskComponent) (ctx, node) -> counter.incrementAndGet();
                    }
                    return null;
                })
                .scope(Scope.SINGLETON);
        });

        FlowContainer fc = container.get(FlowContainer.class);
        assertNotNull(fc);

        var driver = new FlowDriverDefault(fc, null);
        FlowEngine engine = FlowEngine.newInstance(Map.of("default", driver));
        Graph g = GraphSpec.create("g", s -> {
            s.entry("s"); s.addStart("s").linkAdd("a");
            s.addActivity("a").task("@counter").linkAdd("e");
            s.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("g", FlowContext.of());

        assertEquals(1, counter.get());
    }

    @Test
    void customDriverViaContribute() {
        var container = Freeway.create(binder -> {
            binder.bind(FlowContainer.class)
                .to((Container c) -> (FlowContainer) name -> null)
                .scope(Scope.SINGLETON);
            binder.contribute(FlowDriver.class)
                .add("fast", new CountingDriver(null, "fast"));
        });

        // Simulate FlowModule assembly
        Map<String, FlowDriver> driverMap = new HashMap<>();
        driverMap.put("default", new FlowDriverDefault(
            container.get(FlowContainer.class), null));
        driverMap.putAll(container.extension(FlowDriver.class).asMap());
        FlowEngine engine = FlowEngine.newInstance(driverMap);

        Graph g = GraphSpec.create("g", "", "fast", s -> {
            s.entry("s"); s.addStart("s").linkAdd("a");
            s.addActivity("a").task("@dummy").linkAdd("e");
            s.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("g", FlowContext.of());
    }

    @Test
    void customDriverViaAddClass() {
        var container = Freeway.create(binder -> {
            binder.bind(FlowContainer.class)
                .to((Container c) -> (FlowContainer) name -> null)
                .scope(Scope.SINGLETON);
            // add(Class) uses container.create() — InjectedDriver(FlowContainer) gets injected
            binder.contribute(FlowDriver.class)
                .add(InjectedDriver.class);
        });

        Map<String, FlowDriver> driverMap = new HashMap<>();
        driverMap.put("default", new FlowDriverDefault(
            container.get(FlowContainer.class), null));
        driverMap.putAll(container.extension(FlowDriver.class).asMap());
        FlowEngine engine = FlowEngine.newInstance(driverMap);

        // add(Class) generates canonical id: injected_driver@package
        String generatedId = driverMap.keySet().stream()
            .filter(k -> !"default".equals(k)).findFirst().orElseThrow();

        Graph g = GraphSpec.create("g", "", generatedId, s -> {
            s.entry("s"); s.addStart("s").linkAdd("a");
            s.addActivity("a").task("@dummy").linkAdd("e");
            s.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("g", FlowContext.of());
    }

    // ── null container guard + standalone error ──────────────────

    @Test
    void nullContainerThrowsClearErrorForBeanName() {
        // FlowDriverDefault.getInstance() has container=null
        FlowEngine engine = FlowEngine.newInstance(); // uses getInstance()
        Graph g = GraphSpec.create("g", spec -> {
            spec.entry("s"); spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@counter").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);

        // IllegalStateException now propagates unwrapped (Issue 3 fix)
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> engine.eval("g", FlowContext.of()));
        assertTrue(ex.getMessage().contains("No FlowContainer configured"));
    }

    // ── loop node via v2 entry (now works with entry type fix) ───

    @Test
    void loopNodeViaV2() {
        var counter = new AtomicInteger(0);
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> counter.incrementAndGet())
            .build());
        // Entry points to the LOOP node — keeps its LOOP type (not force-promoted to START)
        Graph g = GraphSpec.create("loop", spec -> {
            spec.entry("l");
            spec.addLoop("l").metaPut("$for", "item")
                .metaPut("$in", List.of(1, 2, 3))
                .task("@dummy").linkAdd("a");
            spec.addActivity("a").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("loop", FlowContext.of());
        // LOOP task runs once + activity runs 3x = 4 executions
        assertEquals(4, counter.get());
    }

    @Test
    void loopIterationLimitFailsFast() {
        // A misconfigured/oversized $in must not spin forever — the engine
        // enforces a hard iteration cap and fails with a clear error.
        List<Integer> huge = new ArrayList<>(FlowEngineImpl.MAX_LOOP_ITERATIONS + 1);
        for (int i = 0; i < FlowEngineImpl.MAX_LOOP_ITERATIONS + 1; i++) {
            huge.add(i);
        }
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> {})
            .build());
        Graph g = GraphSpec.create("loop", spec -> {
            spec.entry("l");
            spec.addLoop("l").metaPut("$for", "item")
                .metaPut("$in", huge)
                .task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        FlowException ex = assertThrows(FlowException.class,
            () -> engine.eval("loop", FlowContext.of()));
        assertTrue(ex.getMessage().contains("LOOP iteration limit"),
            "expected iteration limit error, got: " + ex.getMessage());
    }

    @Test
    void standaloneDriverErrorMessageIsGeneric() {
        FlowEngine engine = FlowEngine.newInstance(Map.of("a", new FlowDriverDefault(null, null)));
        Graph g = graphWithDriver("nonexistent");
        engine.load(g);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> engine.eval("g", FlowContext.of()));
        assertTrue(ex.getMessage().contains("No driver found"));
        assertTrue(ex.getMessage().contains("newInstance"));
    }

    // ── node types via v2 ─────────────────────────────────────────

    @Test
    void inclusiveGatewayViaV2() {
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph g = GraphSpec.create("inc", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("gw");
            spec.addInclusive("gw").task("@dummy")
                .linkAdd("a").linkAdd("b");
            spec.addActivity("a").task("@dummy").linkAdd("e");
            spec.addActivity("b").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("inc", FlowContext.of());
        assertTrue(executed.contains("gw"));
        assertTrue(executed.contains("a"));
        assertTrue(executed.contains("b"));
    }

    @Test
    void exclusiveGatewayDefaultPathViaV2() {
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph g = GraphSpec.create("ex", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("gw");
            // two links: one conditional (won't match), one default
            spec.addExclusive("gw").task("@dummy")
                .linkAdd("false_path", link -> link.when("false == true"))
                .linkAdd("default_path");
            spec.addActivity("false_path").task("@dummy").linkAdd("e");
            spec.addActivity("default_path").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("ex", FlowContext.of());
        assertTrue(executed.contains("gw"));
        assertTrue(executed.contains("default_path"));
        assertFalse(executed.contains("false_path"));
    }

    // --- 网关死路静默成功 (Bug 1 回归) ---

    @Test
    void exclusiveDeadEndWithoutDefaultThrows() {
        // EXCLUSIVE node whose condition never matches and that has no
        // default link: the run previously "succeeded" without reaching END.
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph g = GraphSpec.create("ex_dead", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("gw");
            spec.addExclusive("gw").task("@dummy")
                .linkAdd("never", link -> link.when("false == true"));
            spec.addActivity("never").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        FlowException ex = assertThrows(FlowException.class,
            () -> engine.eval("ex_dead", FlowContext.of()));
        assertTrue(ex.getMessage().contains("gw"),
            "error must name the stuck node, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("ex_dead"),
            "error must name the graph, got: " + ex.getMessage());
        assertFalse(executed.contains("never"), "dead path must not run");
    }

    @Test
    void exclusiveDeadEndResolvedByDefaultLink() {
        // The same gateway shape with a default link must complete normally.
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph g = GraphSpec.create("ex_default", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("gw");
            spec.addExclusive("gw").task("@dummy")
                .linkAdd("never", link -> link.when("false == true"))
                .linkAdd("fallback");
            spec.addActivity("never").task("@dummy").linkAdd("e");
            spec.addActivity("fallback").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        assertDoesNotThrow(() -> engine.eval("ex_default", FlowContext.of()));
        assertTrue(executed.contains("fallback"), "default link must be taken");
    }

    @Test
    void inclusiveJoinMissingArrivalThrows() {
        // An INCLUSIVE join with two incoming links but only one reachable
        // branch never activates — the join body and downstream were silently
        // skipped. It must now fail the run.
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph g = GraphSpec.create("inc_dead", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("x");
            // EXCLUSIVE always routes to branch a; branch b is never reached,
            // so the join below only ever receives one of its two arrivals.
            spec.addExclusive("x").task("@dummy")
                .linkAdd("a", link -> link.when("true == true"))
                .linkAdd("b", link -> link.when("false == true"));
            spec.addActivity("a").task("@dummy").linkAdd("gw");
            spec.addActivity("b").task("@dummy").linkAdd("gw");
            spec.addInclusive("gw").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        FlowException ex = assertThrows(FlowException.class,
            () -> engine.eval("inc_dead", FlowContext.of()));
        assertTrue(ex.getMessage().contains("gw"),
            "error must name the stuck join, got: " + ex.getMessage());
        assertFalse(executed.contains("e"),
            "the join body and downstream must not run");
    }

    @Test
    void parallelJoinMissingArrivalThrows() {
        // Same shape for a PARALLEL join node with multiple incoming links.
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph g = GraphSpec.create("par_dead", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("x");
            spec.addExclusive("x").task("@dummy")
                .linkAdd("a", link -> link.when("true == true"))
                .linkAdd("b", link -> link.when("false == true"));
            spec.addActivity("a").task("@dummy").linkAdd("j");
            spec.addActivity("b").task("@dummy").linkAdd("j");
            spec.addParallel("j").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        FlowException ex = assertThrows(FlowException.class,
            () -> engine.eval("par_dead", FlowContext.of()));
        assertTrue(ex.getMessage().contains("j"),
            "error must name the stuck join, got: " + ex.getMessage());
        assertFalse(executed.contains("e"),
            "the join body and downstream must not run");
    }

    @Test
    void completedJoinDoesNotThrow() {
        // A join that receives all its branches activates and clears the
        // provisional dead-end — the graph completes normally.
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph g = GraphSpec.create("join_ok", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("p");
            spec.addParallel("p").task("@dummy").linkAdd("a").linkAdd("b");
            spec.addActivity("a").task("@dummy").linkAdd("gw");
            spec.addActivity("b").task("@dummy").linkAdd("gw");
            spec.addInclusive("gw").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        assertDoesNotThrow(() -> engine.eval("join_ok", FlowContext.of()));
        assertTrue(executed.contains("gw"),
            "the join must activate once both branches arrive, got " + executed);
        assertTrue(executed.contains("a") && executed.contains("b"),
            "both branches must reach the join, got " + executed);
    }

    @Test
    void stepperHalfOpenIntervalSemantics() {
        // [start, end) — end is exclusive, documented in Stepper's javadoc.
        var s1 = Stepper.from("1...9");
        var collected = new ArrayList<Integer>();
        while (s1.hasNext()) collected.add(s1.next());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), collected);

        // Explicit step.
        var s2 = Stepper.from("1:10:2");
        var collected2 = new ArrayList<Integer>();
        while (s2.hasNext()) collected2.add(s2.next());
        assertEquals(List.of(1, 3, 5, 7, 9), collected2);

        // Non-divisible step stops before end.
        var s3 = Stepper.from("1:10:4");
        var collected3 = new ArrayList<Integer>();
        while (s3.hasNext()) collected3.add(s3.next());
        assertEquals(List.of(1, 5, 9), collected3);

        // Empty range.
        assertFalse(Stepper.from("5...5").hasNext());
        assertThrows(IllegalArgumentException.class, () -> Stepper.from("1:9:0"));
        assertThrows(IllegalArgumentException.class, () -> Stepper.from("1:9"));
        assertThrows(IllegalArgumentException.class, () -> Stepper.from("a...b"));
    }

    @Test
    void parallelGatewayFansOutAcrossExecutor() throws Exception {
        // PARALLEL branches must run concurrently on the driver's executor.
        // The PARALLEL node itself runs on the calling thread (no-op task);
        // only branch tasks block on the barrier.
        int branches = 8;
        ExecutorService executor = Executors.newFixedThreadPool(branches);
        try {
            var maxConcurrent = new AtomicInteger(0);
            var active = new AtomicInteger(0);
            var barrier = new CountDownLatch(branches);
            var executed = new ConcurrentLinkedQueue<String>();
            FlowEngine engine = newEngine(FlowDriverDefault.builder()
                .executor(executor)
                .container(name -> {
                    if ("noop".equals(name)) {
                        return (TaskComponent) (ctx, node) -> {};
                    }
                    return (TaskComponent) (ctx, node) -> {
                        int cur = active.incrementAndGet();
                        maxConcurrent.accumulateAndGet(cur, Math::max);
                        executed.add(node.getId());
                        barrier.countDown();
                        // Hold the branch open until all branches are inside —
                        // proves concurrent execution rather than sequential.
                        barrier.await();
                        active.decrementAndGet();
                    };
                })
                .build());
            Graph g = GraphSpec.create("par", spec -> {
                spec.entry("s");
                spec.addStart("s").linkAdd("p");
                spec.addParallel("p").task("@noop").linkAdd("a").linkAdd("b")
                    .linkAdd("c").linkAdd("d").linkAdd("e").linkAdd("f")
                    .linkAdd("g").linkAdd("h");
                spec.addActivity("a").task("@dummy").linkAdd("end");
                spec.addActivity("b").task("@dummy").linkAdd("end");
                spec.addActivity("c").task("@dummy").linkAdd("end");
                spec.addActivity("d").task("@dummy").linkAdd("end");
                spec.addActivity("e").task("@dummy").linkAdd("end");
                spec.addActivity("f").task("@dummy").linkAdd("end");
                spec.addActivity("g").task("@dummy").linkAdd("end");
                spec.addActivity("h").task("@dummy").linkAdd("end");
                spec.addEnd("end");
            }).create();
            engine.load(g);
            engine.eval("par", FlowContext.of());
        assertEquals(branches, executed.size());
        assertEquals(8, maxConcurrent.get(),
            "branches must overlap in time (concurrent execution)");
        } finally {
            executor.shutdownNow();
        }
    }

    // --- audit gap tests ---

    @Test
    void pauseAndResumeContinuesFromTrace() {
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph g = GraphSpec.create("chain", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a"); // START carries no task
            spec.addActivity("a").task("@dummy").linkAdd("b");
            spec.addActivity("b").task("@dummy").linkAdd("c");
            spec.addActivity("c").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);

        FlowContext ctx = FlowContext.of();
        // steps=3: s(START,1) a(2) b(3) — c is recorded but its task does not
        // run (nextStep stops it). Two task-carrying nodes executed.
        engine.eval("chain", 3, ctx);
        assertEquals(2, executed.size(), "steps=3 must stop after two task nodes");

        executed.clear();
        engine.eval("chain", -1, ctx); // resume from the last traced node (c)
        assertEquals(List.of("c"), executed,
            "resume must continue from the last traced node");
    }

    @Test
    void markerResolutionRejectsAmbiguousSpecificity() {
        FlowMarkerIndex index = new FlowMarkerIndex();
        index.register(comp("one"), Set.of("a"));
        index.register(comp("two"), Set.of("a"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> index.resolve(Set.of("a")));
        assertTrue(ex.getMessage().contains("equal specificity"),
            "expected ambiguity error, got: " + ex.getMessage());
    }

    @Test
    void inclusiveGatewayJoinsMultipleIncomingBranches() {
        var executed = new ConcurrentLinkedQueue<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph g = GraphSpec.create("incjoin", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("p");
            spec.addParallel("p").task("@dummy").linkAdd("a").linkAdd("b");
            spec.addActivity("a").task("@dummy").linkAdd("gw");
            spec.addActivity("b").task("@dummy").linkAdd("gw");
            spec.addInclusive("gw").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("incjoin", FlowContext.of());
        assertTrue(executed.contains("gw"), "inclusive gateway must execute");
        assertTrue(executed.contains("a") && executed.contains("b"),
            "both branches must reach the gateway");
    }

    @Test
    void metaTaskReadsGraphMetadata() {
        // $meta tasks write the resolved value into the context under
        // _meta_<key>; a downstream @beanName task reads it back.
        var seen = new AtomicReference<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> {
                if ("check".equals(name)) {
                    return (TaskComponent) (ctx, node) ->
                        seen.set(ctx.getAs("_meta_endpoint"));
                }
                return (TaskComponent) (ctx, node) -> {};
            })
            .build());
        Graph g = GraphSpec.create("meta", spec -> {
            spec.metaPut("endpoint", "http://example.com");
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("$endpoint").linkAdd("b");
            spec.addActivity("b").task("@check").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("meta", FlowContext.of());
        assertEquals("http://example.com", seen.get(),
            "$meta task must expose the graph metadata via _meta_<key>");
    }

    @Test
    void subgraphTaskInvokesLoadedGraph() {
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph child = GraphSpec.create("child", spec -> {
            spec.entry("cs");
            spec.addStart("cs").linkAdd("ca");
            spec.addActivity("ca").task("@dummy").linkAdd("ce");
            spec.addEnd("ce");
        }).create();
        Graph parent = GraphSpec.create("parent", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("#child").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(child);
        engine.load(parent);
        engine.eval("parent", FlowContext.of());
        assertTrue(executed.contains("ca"),
            "#graph subgraph call must execute the child graph, got " + executed);
    }

    @Test
    void repeatedSubgraphInvocationExecutesBodyEachTime() {
        // Regression: the trace held the child's END node after the first
        // call, so a second call replayed in reverting mode and silently
        // skipped the body.
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> executed.add(node.getId()))
            .build());
        Graph child = GraphSpec.create("child2", spec -> {
            spec.entry("cs");
            spec.addStart("cs").linkAdd("ca");
            spec.addActivity("ca").task("@dummy").linkAdd("ce");
            spec.addEnd("ce");
        }).create();
        Graph parent = GraphSpec.create("parent2", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("#child2").linkAdd("b");
            spec.addActivity("b").task("#child2").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(child);
        engine.load(parent);
        engine.eval("parent2", FlowContext.of());
        assertEquals(2, executed.stream().filter("ca"::equals).count(),
            "each #graph call must execute the child body, got " + executed);
    }

    @Test
    void exprEvaluatorHandlesLargeNumbersExactly() {
        // Regression: comparisons routed through doubleValue() collapsed
        // distinct longs at/above 2^53.
        Map<String, Object> ctx = Map.of();
        assertFalse(ExprEvaluator.evalCondition(
            "9223372036854775807 == 9223372036854775806", ctx),
            "adjacent longs above 2^53 must compare unequal");
        assertTrue(ExprEvaluator.evalCondition(
            "9007199254740993 > 9007199254740992", ctx),
            "longs above 2^53 must order correctly");
    }

    @Test
    void exprEvaluatorInterpretsBooleanStringsByValue() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("flag", "false");
        ctx.put("zero", "0");
        assertFalse(ExprEvaluator.evalCondition("flag", ctx),
            "a \"false\" string must be falsy");
        assertFalse(ExprEvaluator.evalCondition("zero", ctx),
            "a \"0\" string must be falsy");
        assertTrue(ExprEvaluator.evalCondition("flag == false", ctx),
            "truthiness and equality must agree for boolean strings");
    }

    @Test
    void exprEvaluatorHandlesOutOfRangeListIndex() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("items", List.of("a", "b"));
        assertTrue(ExprEvaluator.evalCondition("items.99 == null", ctx),
            "an out-of-range index must resolve to null, not throw");
        assertTrue(ExprEvaluator.evalCondition("items.0 == \"a\"", ctx),
            "an in-range index still resolves");
    }

    @Test
    void exprEvaluatorRejectsPathologicallyLongFlatChains() {
        // Regression: "a && a && ..." built a left-leaning tree that evaded
        // the nesting guard and overflowed the stack at eval time.
        String flat = "true && ".repeat(3000) + "true";
        assertThrows(FlowException.class,
            () -> ExprEvaluator.evalCondition(flat, Map.of()),
            "a flat chain beyond the term limit must be rejected at compile time");
    }

    @Test
    void exprEvaluatorIsThreadSafeUnderConcurrentEvaluation() throws Exception {
        // The compiled AST is shared via the static cache: concurrent
        // evaluation of the same expression and concurrent compilation of
        // distinct expressions must both be safe (cache hits, cache misses,
        // and the synchronizedMap access-order relink all race here).
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("score", 90);
        ctx.put("name", "freeway");
        int threads = 8;
        int iterations = 2000;
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int t = 0; t < threads; t++) {
                int seed = t;
                tasks.add(() -> {
                    for (int i = 0; i < iterations; i++) {
                        // Mix of shared (cached) and distinct (compiled) expressions.
                        String expr = "score > 80 && name == \"freeway\""
                            + (i % 2 == 0 ? "" : " && " + seed + " < 100");
                        if (!ExprEvaluator.evalCondition(expr, ctx)) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (var f : pool.invokeAll(tasks)) {
                assertTrue(f.get(), "every concurrent evaluation must return the expected result");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static TaskComponent comp(String name) {
        // Captures name: a capturing lambda creates a fresh instance per call,
        // so two registrations are distinct components (a stateless lambda
        // would be interned by the JVM and collapse to one Entry).
        return (ctx, node) -> { String tag = name; if (tag == null) throw new IllegalStateException(); };
    }

    @Test
    void parallelBranchesConvergeOnInclusiveGatewayOnce() throws Exception {
        // The ExecState atomic-join fix: PARALLEL branches converging on the
        // same INCLUSIVE gateway must execute it exactly once, never twice or
        // zero times, regardless of interleaving.
        int branches = 6;
        ExecutorService executor = Executors.newFixedThreadPool(branches);
        try {
            var gatewayExecutions = new AtomicInteger();
            FlowEngine engine = newEngine(FlowDriverDefault.builder()
                .executor(executor)
                .container(name -> (TaskComponent) (ctx, node) -> {
                    if ("gw".equals(node.getId())) gatewayExecutions.incrementAndGet();
                })
                .build());
            Graph g = GraphSpec.create("parinc", spec -> {
                spec.entry("s");
                spec.addStart("s").linkAdd("p");
                spec.addParallel("p").task("@noop").linkAdd("a").linkAdd("b")
                    .linkAdd("c").linkAdd("d").linkAdd("e").linkAdd("f");
                spec.addActivity("a").task("@dummy").linkAdd("gw");
                spec.addActivity("b").task("@dummy").linkAdd("gw");
                spec.addActivity("c").task("@dummy").linkAdd("gw");
                spec.addActivity("d").task("@dummy").linkAdd("gw");
                spec.addActivity("e").task("@dummy").linkAdd("gw");
                spec.addActivity("f").task("@dummy").linkAdd("gw");
                spec.addInclusive("gw").task("@dummy").linkAdd("end");
                spec.addEnd("end");
            }).create();
            engine.load(g);
            engine.eval("parinc", FlowContext.of());
            assertEquals(1, gatewayExecutions.get(),
                "inclusive gateway must join concurrent branches exactly once");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void flowContextJsonRoundTrip() {
        FlowContext ctx = FlowContext.of();
        ctx.data().put("name", "alice");
        ctx.data().put("count", 42);
        ctx.stop();

        String json = ctx.toJson();
        FlowContext restored = FlowContextImpl.fromJson(json);

        assertEquals("alice", restored.data().get("name"));
        assertEquals(42, restored.data().get("count"));
        assertTrue(restored.isStopped(), "stopped flag must survive serialization");
    }

    @Test
    void flowContextPutAllSkipsNullValuesLikePut() {
        // put() ignores null values; putAll() must behave the same — a null
        // value must neither be stored nor wipe an existing key.
        FlowContext ctx = FlowContext.of();
        ctx.put("kept", "v");

        Map<String, Object> mixed = new HashMap<>();
        mixed.put("a", 1);
        mixed.put("b", null);
        mixed.put("c", "x");
        ctx.putAll(mixed);

        assertEquals(1, ctx.get("a"));
        assertEquals("x", ctx.getAs("c"));
        assertNull(ctx.getAs("b"), "null values must not be stored by putAll");

        // a null value does not remove an existing key
        Map<String, Object> nullOnly = new HashMap<>();
        nullOnly.put("kept", null);
        ctx.putAll(nullOnly);
        assertEquals("v", ctx.getAs("kept"));

        // non-null values still overwrite
        Map<String, Object> overwrite = new HashMap<>();
        overwrite.put("a", 2);
        ctx.putAll(overwrite);
        assertEquals(2, ctx.get("a"));
    }

    @Test
    void traceResumePositionSurvivesJsonRoundTrip() {
        // The trace's last-node-per-graph position is the resume point for a
        // paused run — losing it across a JSON round-trip silently restarts
        // the graph from START on resume.
        Graph graph = Graph.create("trace_roundtrip", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@noop").linkAdd("e");
            spec.addEnd("e");
        });
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> { })
            .build());

        FlowContext ctx = FlowContext.of();
        engine.eval(graph, ctx);
        assertFalse(ctx.lastNodeId() == null || ctx.lastNodeId().isEmpty(),
            "eval must record the last node");

        FlowContext restored = FlowContextImpl.fromJson(ctx.toJson());
        assertEquals(ctx.lastNodeId(), restored.lastNodeId(),
            "the resume position must survive the JSON round-trip");
    }

    // ── S2 fixes: $for LOOP concurrency, sub-graph interceptors, stale event
    //    bus subscriptions, onNodeStart/onNodeEnd pairing, per-iteration join
    //    counter reset ──────────────────────────────────────────────────────

    @Test
    void parallelBranchesReachSameForLoopOnlyOnce() throws Exception {
        // Two PARALLEL branches converge on the same $for LOOP node. The
        // "is a loop already running?" check and the iterator push must be
        // atomic: only the first arrival may run the node (task + body), the
        // second must skip. A barrier releases both branches together and the
        // body task holds the claiming branch mid-loop so the second arrival
        // is guaranteed to observe the live iterator.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var bodyCount = new AtomicInteger(0);
            var loopTaskCount = new AtomicInteger(0);
            for (int i = 0; i < 3; i++) {
                bodyCount.set(0);
                loopTaskCount.set(0);
                CountDownLatch barrier = new CountDownLatch(2);
                FlowEngine engine = newEngine(FlowDriverDefault.builder()
                    .executor(executor)
                    .container(name -> {
                        if ("barrier".equals(name)) {
                            return (TaskComponent) (ctx, node) -> {
                                barrier.countDown();
                                barrier.await();
                            };
                        }
                        if ("loopTask".equals(name)) {
                            return (TaskComponent) (ctx, node) -> loopTaskCount.incrementAndGet();
                        }
                        return (TaskComponent) (ctx, node) -> {
                            if ("body".equals(node.getId())) {
                                bodyCount.incrementAndGet();
                                // Keep the claiming branch inside the loop so
                                // the other branch arrives while it is live.
                                Thread.sleep(30);
                            }
                        };
                    })
                    .build());
                Graph g = GraphSpec.create("parloop", spec -> {
                    spec.entry("s");
                    spec.addStart("s").linkAdd("p");
                    spec.addParallel("p").task("@noop").linkAdd("a").linkAdd("b");
                    spec.addActivity("a").task("@barrier").linkAdd("l");
                    spec.addActivity("b").task("@barrier").linkAdd("l");
                    spec.addLoop("l").metaPut("$for", "item")
                        .metaPut("$in", List.of(1, 2, 3))
                        .task("@loopTask").linkAdd("body");
                    spec.addActivity("body").task("@dummy").linkAdd("e");
                    spec.addEnd("e");
                }).create();
                engine.load(g);
                engine.eval("parloop", FlowContext.of());
                assertEquals(3, bodyCount.get(),
                    "the loop body must run exactly once (3 items), not once per branch — got "
                        + bodyCount.get());
                assertEquals(1, loopTaskCount.get(),
                    "only the claiming branch may run the LOOP task — got " + loopTaskCount.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void perEvalInterceptorsCoverSubgraphNodesOnce() {
        // Per-eval FlowOptions interceptors must apply to sub-graph nodes too
        // (runGraph previously passed null options, so sub-graph nodes missed
        // every per-eval onNodeStart/onNodeEnd), and the engine-level
        // interceptor list must still fire exactly once per node — never
        // twice on sub-graph nodes because the sub-eval re-merges it.
        var engineVisits = new ConcurrentLinkedQueue<String>();
        FlowInterceptor engineLevel = new FlowInterceptor() {
            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                engineVisits.add(node.getGraph().getId() + ":" + node.getId());
            }
        };

        var evalVisits = new ConcurrentLinkedQueue<String>();
        var flowWraps = new ConcurrentLinkedQueue<String>();
        FlowInterceptor perEval = new FlowInterceptor() {
            @Override
            public void interceptFlow(FlowInvocation inv) {
                flowWraps.add(inv.getGraph().getId());
                inv.invoke();
            }

            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                evalVisits.add(node.getGraph().getId() + ":" + node.getId());
            }
        };

        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (flowCtx, node) -> {})
            .build());
        engine.addInterceptor(engineLevel);

        Graph sub = GraphSpec.create("subI", spec -> {
            spec.entry("cs");
            spec.addStart("cs").linkAdd("ca");
            spec.addActivity("ca").task("@dummy").linkAdd("ce");
            spec.addEnd("ce");
        }).create();
        Graph main = GraphSpec.create("mainI", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("call");
            spec.addActivity("call").task("#subI").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(sub);
        engine.load(main);

        FlowContext ctx = FlowContext.of();
        FlowExchanger exchanger = new FlowExchanger(
            main, engine, engine.getDriver(main), ctx, -1, new AtomicInteger(0));
        engine.eval(main, exchanger, new FlowOptions().interceptorAdd(perEval));

        assertEquals(Set.of("mainI:s", "mainI:call", "mainI:e", "subI:cs", "subI:ca", "subI:ce"),
            new HashSet<>(evalVisits),
            "the per-eval interceptor must observe every node of both graphs, got " + evalVisits);
        assertTrue(flowWraps.contains("mainI"),
            "per-eval interceptFlow must wrap the top-level eval");
        assertTrue(flowWraps.contains("subI"),
            "per-eval interceptFlow must wrap the sub-graph eval");
        assertEquals(6, engineVisits.size(),
            "the engine-level interceptor must fire once per node, got " + engineVisits);
        assertEquals(6, new HashSet<>(engineVisits).size(),
            "the engine-level interceptor must not fire twice on any node");
    }

    @Test
    void reusedContextClearsStaleEventBusSubscriptionsOnFreshRun() {
        // A paused run keeps its event-bus subscriptions (resume needs them).
        // Reusing the same FlowContext for a FRESH run of an unrelated graph
        // must clear those stale subscriptions — otherwise the old closures
        // keep firing long after that run is gone.
        Graph graphA = GraphSpec.create("subA", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("sub");
            spec.addActivity("sub").task("@subscribe").linkAdd("pub");
            spec.addActivity("pub").task("@publish").linkAdd("e");
            spec.addEnd("e");
        }).create();
        Graph graphB = GraphSpec.create("graphB", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("e");
            spec.addEnd("e");
        }).create();

        List<String> received = new ArrayList<>();
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> {
                if ("subscribe".equals(name)) {
                    return (TaskComponent) (flowCtx, node) ->
                        flowCtx.eventBus().subscribe("stale.topic", e -> received.add((String) e));
                }
                return (TaskComponent) (flowCtx, node) -> {};
            })
            .build());
        engine.load(graphA);
        engine.load(graphB);

        FlowContext ctx = FlowContext.of();
        // Pause A mid-run (steps=2 stops at the publish node): the subscribe
        // task ran, the eval did not complete, so the subscription is kept.
        engine.eval("subA", 2, ctx);
        ctx.eventBus().publish("stale.topic", "paused-run");
        assertEquals(List.of("paused-run"), received,
            "the subscription must survive a paused run");

        // A fresh run of an unrelated graph with the same context must drop
        // the stale subscription.
        engine.eval("graphB", ctx);
        ctx.eventBus().publish("stale.topic", "after-fresh-run");
        assertEquals(List.of("paused-run"), received,
            "stale subscriptions from the paused run must be cleared on a fresh run");

        // Resuming the SAME graph must keep subscriptions: pause again (fresh
        // context) then resume — the publish node of the resumed run must
        // still reach the subscriber registered during the paused run.
        List<String> received2 = new ArrayList<>();
        FlowEngine engine2 = newEngine(FlowDriverDefault.builder()
            .container(name -> {
                if ("subscribe".equals(name)) {
                    return (TaskComponent) (flowCtx, node) ->
                        flowCtx.eventBus().subscribe("stale.topic", e -> received2.add((String) e));
                }
                if ("publish".equals(name)) {
                    return (TaskComponent) (flowCtx, node) ->
                        flowCtx.eventBus().publish("stale.topic", "from-resumed-run");
                }
                return (TaskComponent) (flowCtx, node) -> {};
            })
            .build());
        engine2.load(graphA);
        FlowContext ctx2 = FlowContext.of();
        engine2.eval("subA", 2, ctx2); // pause again — subscriber registered
        ctx2.eventBus().publish("stale.topic", "paused-2");
        assertEquals(List.of("paused-2"), received2);

        engine2.eval("subA", -1, ctx2); // resume — trace keeps the record
        assertEquals(List.of("paused-2", "from-resumed-run"), received2,
            "resume of the same graph must keep its subscriptions");
    }

    @Test
    void onNodeStartThrowStillPairsOnNodeEnd() {
        // onNodeStart throwing must still produce exactly one onNodeEnd, and
        // the original exception must propagate (not be masked).
        var events = new ArrayList<String>();
        var taskRan = new AtomicInteger(0);
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> taskRan.incrementAndGet())
            .build());
        engine.addInterceptor(new FlowInterceptor() {
            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                if ("a".equals(node.getId())) {
                    throw new IllegalStateException("boom at " + node.getId());
                }
            }

            @Override
            public void onNodeEnd(FlowContext ctx, Node node) {
                events.add("end:" + node.getId());
            }
        });
        Graph g = GraphSpec.create("startThrow", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> engine.eval("startThrow", FlowContext.of()));
        assertEquals("boom at a", ex.getMessage(), "the original exception must propagate");
        assertEquals(0, taskRan.get(), "the task must not run after onNodeStart throws");
        assertTrue(events.contains("end:a"),
            "onNodeEnd must pair with the failed onNodeStart, got " + events);
    }

    @Test
    void onNodeStartFalseStillPairsOnNodeEnd() {
        // onNodeStart returning false (stopped/interrupted) must still
        // produce exactly one onNodeEnd for the skipped node.
        var events = new ArrayList<String>();
        var taskRan = new AtomicInteger(0);
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> taskRan.incrementAndGet())
            .build());
        engine.addInterceptor(new FlowInterceptor() {
            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                if ("a".equals(node.getId())) {
                    ctx.stop(); // makes the engine's onNodeStart return false
                }
            }

            @Override
            public void onNodeEnd(FlowContext ctx, Node node) {
                events.add("end:" + node.getId());
            }
        });
        Graph g = GraphSpec.create("stopStart", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);

        engine.eval("stopStart", FlowContext.of());
        assertEquals(0, taskRan.get(), "the task must not run when onNodeStart returns false");
        assertTrue(events.contains("end:a"),
            "onNodeEnd must pair with a false onNodeStart, got " + events);
    }

    @Test
    void inclusiveJoinInsideLoopResetsCounterPerIteration() {
        // A LOOP whose body contains an INCLUSIVE fork-join: iteration 1
        // routes only one branch to the join (short arrival — counter 1,
        // provisional dead-end recorded), iteration 2 routes both. The join
        // counter must be reset at each iteration start, otherwise the
        // residue from iteration 1 falsely activates the join early and/or a
        // spurious dead-end fails the run.
        var joinExecutions = new AtomicInteger(0);
        FlowEngine engine = newEngine(FlowDriverDefault.builder()
            .container(name -> (TaskComponent) (ctx, node) -> {
                if ("join".equals(node.getId())) joinExecutions.incrementAndGet();
            })
            .build());
        Graph g = GraphSpec.create("loopjoin", spec -> {
            spec.entry("l");
            spec.addLoop("l").metaPut("$for", "item")
                .metaPut("$in", List.of(1, 2))
                .task("@noop").linkAdd("fork");
            spec.addInclusive("fork").task("@noop")
                .linkAdd("a", link -> link.when("item >= 1"))   // both iterations
                .linkAdd("b", link -> link.when("item == 2"));  // iteration 2 only
            spec.addActivity("a").task("@noop").linkAdd("join");
            spec.addActivity("b").task("@noop").linkAdd("join");
            spec.addInclusive("join").task("@noop").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);

        assertDoesNotThrow(() -> engine.eval("loopjoin", FlowContext.of()));
        assertEquals(1, joinExecutions.get(),
            "the join must activate exactly once (iteration 2, after both arrivals), got "
                + joinExecutions.get());
    }
}
