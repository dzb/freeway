package com.jujin.freeway.flow;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
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

    @Test
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
        List<Integer> huge = new java.util.ArrayList<>(FlowEngineImpl.MAX_LOOP_ITERATIONS + 1);
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
            var executed = new java.util.concurrent.ConcurrentLinkedQueue<String>();
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
        var executed = new java.util.concurrent.ConcurrentLinkedQueue<String>();
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
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
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
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
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
}
