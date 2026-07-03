package com.jujin.freeway.flow;

import com.jujin.freeway.flow.v2.GraphSpec2;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Scope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

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
                  "layout": [
                    { "id": "s", "type": "start", "link": "a" },
                    { "id": "a", "type": "activity", "task": "@jsonTask", "link": "e" },
                    { "id": "e", "type": "end" }
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
        var ctx = new java.util.concurrent.ConcurrentHashMap<String, Object>();
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
        System.out.println(puml);
    }

    // --- 子图调用 ---

    @Test
    void testSubGraph() {
        Graph subGraph = Graph.create("sub", spec -> {
            spec.addStart("sub_s").linkAdd("sub_a");
            spec.addActivity("sub_a").task("@subTask").linkAdd("sub_e");
            spec.addEnd("sub_e");
        });

        Graph mainGraph = Graph.create("main", spec -> {
            spec.addStart("s").linkAdd("call");
            spec.addActivity("call").task("#sub").linkAdd("e");
            spec.addEnd("e");
        });

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
        @com.jujin.freeway.ioc.annotation.Inject
        private Greeter greeter;

        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            context.put("result", greeter.greeting());
        }
    }

    @Test
    void containerInjectWiresFields() {
        var container = com.jujin.freeway.ioc.Freeway.create(
                binder -> binder.bind(Greeter.class).to(new Greeter("Hello, Flow!")));

        var task = container.create(InjectedTask.class);

        assertNotNull(task.greeter);
        assertEquals("Hello, Flow!", task.greeter.greeting());
    }

    @Test
    void contributedTaskIsInjectedAndDiscovered() {
        var container = com.jujin.freeway.ioc.Freeway.create(
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

    void flowMarkerIndexExtractsAnnotations() {
        java.util.Set<String> markers = FlowMarkerIndex.extractFlowMarkers(EmailTask.class);
        assertEquals(java.util.Set.of("channel:email", "priority:high"), markers);
    }

    // ── driver resolution ──────────────────────────────────────

    private static Graph graphWithDriver(String driver) {
        return GraphSpec2.create("g", "", driver, s -> {
            s.entry("s");
            s.addStart("s").linkAdd("e");
            s.addEnd("e");
        }).create();
    }

    @Test
    void driverDefaultWhenNull() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.newInstance(Map.of("default", driver));
        Graph g = GraphSpec2.create("g", s -> {
            s.entry("s"); s.addStart("s").linkAdd("e"); s.addEnd("e");
        }).create();
        assertSame(driver, engine.getDriver(g));
    }

    @Test
    void driverDefaultWhenEmpty() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.newInstance(Map.of("default", driver));
        Graph g = GraphSpec2.create("g", "", "", s -> {
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
        var container = com.jujin.freeway.ioc.Freeway.create(binder -> {
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
        Graph g = GraphSpec2.create("g", s -> {
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
        var container = com.jujin.freeway.ioc.Freeway.create(binder -> {
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

        Graph g = GraphSpec2.create("g", "", "fast", s -> {
            s.entry("s"); s.addStart("s").linkAdd("a");
            s.addActivity("a").task("@dummy").linkAdd("e");
            s.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("g", FlowContext.of());
    }

    @Test
    void customDriverViaAddClass() {
        var container = com.jujin.freeway.ioc.Freeway.create(binder -> {
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

        Graph g = GraphSpec2.create("g", "", generatedId, s -> {
            s.entry("s"); s.addStart("s").linkAdd("a");
            s.addActivity("a").task("@dummy").linkAdd("e");
            s.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("g", FlowContext.of());
    }
}
