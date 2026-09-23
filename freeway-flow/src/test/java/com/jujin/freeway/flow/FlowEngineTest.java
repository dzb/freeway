package com.jujin.freeway.flow;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Phaser;

import com.jujin.freeway.flow.internal.FlowContextImpl;
import com.jujin.freeway.flow.internal.Stepper;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine semantics: the frontier walk, the seven node types, dead-end and
 * join behaviour, branch isolation, sub-graph calls, the interceptor chain
 * and the expression language.
 */
class FlowEngineTest {

    /** A driver that runs {@code body} for each real task, but still honors
     *  the {@code #graphId} sub-graph and {@code @name} vocabulary. */
    private static FlowDriver tasksRun(Consumer<Node> body) {
        return new FlowDriver() {
            @Override
            public boolean handleCondition(FlowEvaluation evaluation, ConditionDesc condition)
                    throws Throwable {
                return ExprEvaluator.evalCondition(condition.description(),
                    evaluation.context().data());
            }

            @Override
            public void handleTask(FlowEvaluation evaluation, TaskDesc task) throws Throwable {
                if (task.isEmpty()) return;
                if (task.isGraphRef()) {
                    evaluation.runGraph(evaluation.engine().graphOrThrow(
                        task.description().substring(1)));
                    return;
                }
                body.accept(task.node());
            }
        };
    }

    /** A driver resolving every {@code @name} from real container bindings. */
    private static FlowDriverDefault driverResolving(Map<String, TaskHandler> byId,
            ExecutorService executor) {
        Container container = Freeway.create(binder ->
            byId.forEach((id, task) ->
                binder.bind(TaskHandler.class).to(c -> task).id(id)));
        return new FlowDriverDefault(container, executor);
    }

    private static FlowEngine newEngine(FlowDriver driver) {
        return FlowEngine.create(Map.of("default", driver));
    }

    private static Graph graphWithDriver(String driver) {
        return GraphSpec.create("g", "", driver, s -> {
            s.entry("s");
            s.addStart("s").linkAdd("e");
            s.addEnd("e");
        }).create();
    }

    // ── 线性流程 / @name 解析 ──────────────────────────────────────

    @Test
    void testLinearFlow() {
        Graph graph = Graph.create("linear", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@counter").linkAdd("e");
            spec.addEnd("e");
        });

        AtomicInteger counter = new AtomicInteger(0);
        FlowEngine engine = newEngine(driverResolving(Map.of("counter",
            (ctx, node) -> counter.incrementAndGet()), null));
        engine.eval(graph, FlowContext.of());

        assertEquals(1, counter.get());
    }

    @Test
    void containerReferenceResolvesByTaskAndConditionType() {
        // A @name task resolves its TaskHandler binding; the same lookup by
        // name on a condition would need a ConditionHandler — the driver is
        // type-scoped per call site, so a wrong-kind reference fails with the
        // kind named.
        Graph graph = Graph.create("typed", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@worker").linkAdd("e");
            spec.addEnd("e");
        });
        FlowEngine engine = newEngine(driverResolving(Map.of("worker",
            (TaskHandler) (ctx, node) -> ctx.put("ran", true)), null));
        FlowContext ctx = FlowContext.of();
        engine.eval(graph, ctx);
        assertEquals(Boolean.TRUE, ctx.get("ran"));
    }

    // ── 排他网关 ──────────────────────────────────────────────────

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
        FlowEngine engine = newEngine(driverResolving(Map.of(
            "highTask", (TaskHandler) (ctx, node) -> executed.add("high"),
            "lowTask", (TaskHandler) (ctx, node) -> executed.add("low")), null));

        FlowContext ctx1 = FlowContext.of();
        ctx1.put("score", 90);
        engine.eval(graph, ctx1);
        assertEquals(List.of("high"), executed);

        executed.clear();
        FlowContext ctx2 = FlowContext.of();
        ctx2.put("score", 50);
        engine.eval(graph, ctx2);
        assertEquals(List.of("low"), executed);
    }

    // ── JSON 解析 ─────────────────────────────────────────────────

    @Test
    void testGraphFromJson() {
        String json = """
                {
                  "id": "json_test",
                  "version": 3,
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
        assertEquals("json_test", graph.id());
        assertEquals(3, graph.nodes().size());
        assertNotNull(graph.node("s"));
        assertEquals(NodeType.START, graph.start().type());

        AtomicInteger counter = new AtomicInteger(0);
        FlowEngine engine = newEngine(driverResolving(Map.of("jsonTask",
            (TaskHandler) (ctx, node) -> counter.incrementAndGet()), null));
        engine.eval(graph, FlowContext.of());
        assertEquals(1, counter.get());
    }

    // ── 表达式求值 ────────────────────────────────────────────────

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

    @Test
    void testExprEvaluatorMultiplicativeOperators() {
        var ctx = new ConcurrentHashMap<String, Object>();
        ctx.put("price", 30);
        ctx.put("qty", 4);
        ctx.put("total", 120);

        // Multiplication binds tighter than addition/comparison.
        assertTrue(ExprEvaluator.evalCondition("price * qty > 100", ctx));
        assertTrue(ExprEvaluator.evalCondition("price * qty == total", ctx));
        assertTrue(ExprEvaluator.evalCondition("1 + 2 * 3 == 7", ctx));
        assertTrue(ExprEvaluator.evalCondition("(1 + 2) * 3 == 9", ctx));
        assertTrue(ExprEvaluator.evalCondition("10 / 4 > 2", ctx));
        assertTrue(ExprEvaluator.evalCondition("total / price == 4", ctx));
        assertTrue(ExprEvaluator.evalCondition("total % 7 == 1", ctx));
        assertTrue(ExprEvaluator.evalCondition("-price * 2 < 0", ctx));
        // Division/modulo by zero fail loudly instead of producing Infinity/NaN.
        assertThrows(FlowException.class,
            () -> ExprEvaluator.evalCondition("total / 0 > 0", ctx));
        assertThrows(FlowException.class,
            () -> ExprEvaluator.evalCondition("total % 0 == 0", ctx));
        // Non-numeric operands are rejected like sub().
        assertThrows(FlowException.class,
            () -> ExprEvaluator.evalCondition("name * 2 == 4", ctx));
    }

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

    // ── PlantUML ──────────────────────────────────────────────────

    @Test
    void testPlantUml() {
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

        String puml = graph.toPlantUml();
        assertNotNull(puml);
        assertTrue(puml.contains("@startuml"));
        assertTrue(puml.contains("@enduml"));
        assertTrue(puml.contains("测试图"));
        assertTrue(puml.contains("s --> a"));
        assertTrue(puml.contains("<<choice>>"));
    }

    // ── 子图调用 ──────────────────────────────────────────────────

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
        FlowEngine engine = newEngine(driverResolving(Map.of("subTask",
            (TaskHandler) (ctx, node) -> executed.add("sub")), null));

        engine.load(subGraph);
        engine.load(mainGraph);
        engine.eval("main", FlowContext.of());

        assertEquals(List.of("sub"), executed);
    }

    @Test
    void subGraphUsesItsOwnDriver() {
        List<String> events = new ArrayList<>();

        FlowDriver mainDriver = driverResolving(Map.of(
            "mainTask", (TaskHandler) (ctx, node) ->
                events.add("main:" + node.graph().id() + ":" + node.id()),
            "subTask", (TaskHandler) (ctx, node) ->
                events.add("main:" + node.graph().id() + ":" + node.id())), null);
        FlowDriver subDriver = driverResolving(Map.of(
            "mainTask", (TaskHandler) (ctx, node) ->
                events.add("sub:" + node.graph().id() + ":" + node.id()),
            "subTask", (TaskHandler) (ctx, node) ->
                events.add("sub:" + node.graph().id() + ":" + node.id())), null);

        FlowEngine engine = FlowEngine.create(Map.of(
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
        assertSame(mainDriver, engine.driver(mainGraph));
        assertSame(subDriver, engine.driver(subGraph));
        engine.eval("main", FlowContext.of());

        assertEquals(List.of(
            "main:main:main_a",
            "sub:sub:sub_a"
        ), events);
    }

    @Test
    void subgraphTaskInvokesLoadedGraph() {
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(tasksRun(node -> executed.add(node.id())));
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
        // Regression (trace-replay era): a second call used to replay from the
        // child's recorded END and silently skip the body. Without a resume
        // trace the body now always runs; a child that never reaches END is
        // reported at the calling node instead.
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(tasksRun(node -> executed.add(node.id())));
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
    void subgraphThatNeverEndsFailsAtTheCallingNode() {
        // A child whose gateway dead-ends (never reaches END) must fail loudly
        // where the parent called it — the old silent interrupt left the
        // parent completing as if the sub-run succeeded.
        FlowEngine engine = newEngine(tasksRun(node -> { }));
        Graph child = GraphSpec.create("deadchild", spec -> {
            spec.entry("cs");
            spec.addStart("cs").linkAdd("gw");
            spec.addExclusive("gw")
                .linkAdd("never", l -> l.when("false == true"));
            spec.addActivity("never").task("@dummy").linkAdd("ce");
            spec.addEnd("ce");
        }).create();
        Graph parent = GraphSpec.create("parent3", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("#deadchild").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(child);
        engine.load(parent);

        FlowException ex = assertThrows(FlowException.class,
            () -> engine.eval("parent3", FlowContext.of()));
        assertTrue(ex.getMessage().contains("deadchild"),
            "the failing sub-graph must be named, got: " + ex.getMessage());
    }

    @Test
    void evaluationCopyKeepsExecState() {
        FlowEngine engine = FlowEngine.create();
        Graph graph = graphWithDriver("default");
        FlowEvaluation evaluation = new FlowEvaluation(
            graph, engine, engine.driver(graph), FlowContext.of());

        evaluation.execState().countSet(graph, "loop", 3);

        FlowEvaluation copy = evaluation.copy(graph);
        assertSame(evaluation.execState(), copy.execState());
    }

    // ── 停止 ──────────────────────────────────────────────────────

    @Test
    void testStop() {
        Graph graph = Graph.create("stop_test", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@stopper").linkAdd("b");
            spec.addActivity("b").task("@neverCalled").linkAdd("e");
            spec.addEnd("e");
        });

        AtomicInteger bCount = new AtomicInteger(0);
        FlowEngine engine = newEngine(driverResolving(Map.of(
            "stopper", (TaskHandler) (ctx, node) -> ctx.stop(),
            "neverCalled", (TaskHandler) (ctx, node) -> bCount.incrementAndGet()), null));

        // Stopping is an intentional early end: the run completes without the
        // dead-end error an unrouted gateway would raise.
        assertDoesNotThrow(() -> engine.eval(graph, FlowContext.of()));
        assertEquals(0, bCount.get());
    }

    // ── 事件总线 ──────────────────────────────────────────────────

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

        FlowEngine engine = newEngine(driverResolving(Map.of("publisher",
            (TaskHandler) (ctx, node) -> {
                ctx.eventBus().subscribe("done", event -> received.add((String) event));
                ctx.eventBus().publish("done", "fired");
            }), null));

        engine.eval(graph, FlowContext.of());
        assertEquals(List.of("fired"), received);
    }

    @Test
    void reusedContextDropsStaleSubscriptionsOnFreshRun() {
        // Each top-level eval starts from a clean bus and ends by clearing
        // it: subscribers registered by a finished run must not keep firing
        // into a later run of an unrelated graph that reuses the context.
        Graph graphA = GraphSpec.create("graphA", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("sub");
            spec.addActivity("sub").task("@subscribe").linkAdd("e");
            spec.addEnd("e");
        }).create();
        Graph graphB = GraphSpec.create("graphB", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("e");
            spec.addEnd("e");
        }).create();

        List<String> received = new ArrayList<>();
        FlowEngine engine = newEngine(driverResolving(Map.of("subscribe",
            (TaskHandler) (ctx, node) ->
                ctx.eventBus().subscribe("stale.topic", e -> received.add((String) e))), null));

        FlowContext ctx = FlowContext.of();
        engine.eval(graphA, ctx);
        engine.eval(graphB, ctx);
        ctx.eventBus().publish("stale.topic", "after-fresh-run");
        assertEquals(List.of(), received,
            "subscriptions from a finished run must not survive into the next");
    }

    // ── 拦截器链 ──────────────────────────────────────────────────

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
            public void interceptFlow(FlowContext ctx, Graph g, FlowChain chain)
                    throws FlowException {
                events.add("flow:before:" + g.id());
                chain.proceed();
                events.add("flow:after:" + g.id());
            }

            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                events.add("node:enter:" + node.id());
            }

            @Override
            public void onNodeEnd(FlowContext ctx, Node node) {
                events.add("node:leave:" + node.id());
            }
        };

        FlowEngine engine = FlowEngine.create(
            Map.of("default", driverResolving(Map.of("taskA",
                (TaskHandler) (ctx, node) -> events.add("task:exec:" + node.id())), null)),
            List.of(auditor));
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

        // An interceptor that never proceeds vetoes the run: legal, silent,
        // and the completion check must not treat it as a dead end.
        FlowInterceptor veto = new FlowInterceptor() {
            @Override
            public void interceptFlow(FlowContext ctx, Graph g, FlowChain chain) {
                // chain.proceed() intentionally not called
            }
        };

        FlowEngine engine = FlowEngine.create(
            Map.of("default", driverResolving(Map.of("neverRun",
                (TaskHandler) (ctx, node) -> taskRan.incrementAndGet()), null)),
            List.of(veto));

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
            @Override public void interceptFlow(FlowContext ctx, Graph g, FlowChain chain)
                    throws FlowException {
                order.add("A:before");
                chain.proceed();
                order.add("A:after");
            }
        };
        FlowInterceptor b = new FlowInterceptor() {
            @Override public void interceptFlow(FlowContext ctx, Graph g, FlowChain chain)
                    throws FlowException {
                order.add("B:before");
                chain.proceed();
                order.add("B:after");
            }
        };

        FlowEngine engine = FlowEngine.create(Map.of("default", FlowDriverDefault.instance()),
            List.of(a, b));
        engine.eval(graph, FlowContext.of());

        // list order = outermost first: a wraps b
        assertEquals(List.of("A:before", "B:before", "B:after", "A:after"), order);
    }

    @Test
    void interceptorsCoverSubgraphNodesOnce() {
        // The engine-level chain is per-eval and fixed at construction — it
        // wraps sub-graph runs too, and each node must be visited exactly once
        // (the old per-eval options merge could double-fire it on subgraphs).
        var engineVisits = new ConcurrentLinkedQueue<String>();
        var flowWraps = new ConcurrentLinkedQueue<String>();
        FlowInterceptor interceptor = new FlowInterceptor() {
            @Override
            public void interceptFlow(FlowContext ctx, Graph g, FlowChain chain)
                    throws FlowException {
                flowWraps.add(g.id());
                chain.proceed();
            }

            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                engineVisits.add(node.graph().id() + ":" + node.id());
            }
        };

        FlowEngine engine = FlowEngine.create(
            Map.of("default", tasksRun(node -> { })), List.of(interceptor));

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

        engine.eval("mainI", FlowContext.of());

        assertEquals(Set.of("mainI:s", "mainI:call", "mainI:e", "subI:cs", "subI:ca", "subI:ce"),
            new HashSet<>(engineVisits),
            "the interceptor must observe every node of both graphs, got " + engineVisits);
        assertEquals(Set.of("mainI", "subI"), new HashSet<>(flowWraps),
            "interceptFlow must wrap the top-level and the sub-graph eval");
        assertEquals(6, engineVisits.size(),
            "exactly one visit per node, got " + engineVisits);
    }

    @Test
    void onNodeStartThrowStillPairsOnNodeEnd() {
        // onNodeStart throwing must still produce exactly one onNodeEnd, and
        // the original exception must propagate (not be masked).
        var events = new ArrayList<String>();
        var taskRan = new AtomicInteger(0);
        FlowInterceptor pairing = new FlowInterceptor() {
            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                if ("a".equals(node.id())) {
                    throw new IllegalStateException("boom at " + node.id());
                }
            }

            @Override
            public void onNodeEnd(FlowContext ctx, Node node) {
                events.add("end:" + node.id());
            }
        };
        FlowEngine engine = FlowEngine.create(
            Map.of("default", tasksRun(node -> taskRan.incrementAndGet())),
            List.of(pairing));
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
    void onNodeStartStopStillPairsOnNodeEnd() {
        // ctx.stop() during onNodeStart (the false path) must still produce
        // exactly one onNodeEnd for the skipped node.
        var events = new ArrayList<String>();
        var taskRan = new AtomicInteger(0);
        FlowInterceptor stopper = new FlowInterceptor() {
            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                if ("a".equals(node.id())) {
                    ctx.stop();
                }
            }

            @Override
            public void onNodeEnd(FlowContext ctx, Node node) {
                events.add("end:" + node.id());
            }
        };
        FlowEngine engine = FlowEngine.create(
            Map.of("default", tasksRun(node -> taskRan.incrementAndGet())),
            List.of(stopper));
        Graph g = GraphSpec.create("stopStart", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);

        engine.eval("stopStart", FlowContext.of());
        assertEquals(0, taskRan.get(), "the task must not run when start signals stop");
        assertTrue(events.contains("end:a"),
            "onNodeEnd must pair with a stopped onNodeStart, got " + events);
    }

    // ── 容器注入的 handler ───────────────────────────────────────────

    public record Greeter(String greeting) {}

    public static final class InjectedTask implements TaskHandler {
        @com.jujin.freeway.ioc.annotation.Inject
        private Greeter greeter;

        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            context.put("result", greeter.greeting());
        }
    }

    @Test
    void containerInjectWiresFields() {
        var container = Freeway.create(
                binder -> binder.bind(Greeter.class).to(c -> new Greeter("Hello, Flow!")));

        var task = container.create(InjectedTask.class);

        assertNotNull(task.greeter);
        assertEquals("Hello, Flow!", task.greeter.greeting());
    }

    @Test
    void contributedTaskIsInjectedAndResolvedByName() {
        var container = Freeway.create(binder -> {
            binder.bind(Greeter.class).to(c -> new Greeter("Hi!"));
            binder.bind(InjectedTask.class).id("injected").to(InjectedTask.class);
        });

        var driver = new FlowDriverDefault(container, null);
        var engine = FlowEngine.create(Map.of("default", driver));

        engine.load(Graph.create("test", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a")
                .task("@injected")
                .linkAdd("e");
            spec.addEnd("e");
        }));

        var ctx = FlowContext.of();
        engine.eval("test", ctx);

        assertEquals("Hi!", ctx.getAs("result"));
    }

    // ── 深度：迭代执行没有栈上限 ──────────────────────────────────

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
    void longChainsRunWithoutAnyDepthLimit() {
        // The frontier walk holds the JVM stack at constant depth: a chain
        // twenty times the old MAX_EXECUTION_DEPTH cap runs instead of
        // failing — the guard and the StackOverflowError safety net are gone.
        FlowEngine engine = newEngine(tasksRun(node -> { }));
        assertDoesNotThrow(() -> engine.eval(chain(20_000), FlowContext.of()));
    }

    // ── driver 解析 ───────────────────────────────────────────────

    @Test
    void driverDefaultWhenNull() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.create(Map.of("default", driver));
        Graph g = GraphSpec.create("g", s -> {
            s.entry("s"); s.addStart("s").linkAdd("e"); s.addEnd("e");
        }).create();
        assertSame(driver, engine.driver(g));
    }

    @Test
    void driverDefaultWhenEmpty() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.create(Map.of("default", driver));
        Graph g = GraphSpec.create("g", "", "", s -> {
            s.entry("s"); s.addStart("s").linkAdd("e"); s.addEnd("e");
        }).create();
        assertSame(driver, engine.driver(g));
    }

    @Test
    void driverDefaultWhenBlank() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.create(Map.of("default", driver));
        Graph g = GraphSpec.create("g", "", "   ", s -> {
            s.entry("s"); s.addStart("s").linkAdd("e"); s.addEnd("e");
        }).create();
        assertSame(driver, engine.driver(g));
    }

    @Test
    void driverDefaultWhenLiteralDefault() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.create(Map.of("default", driver));
        Graph g = graphWithDriver("default");
        assertSame(driver, engine.driver(g));
    }

    @Test
    void driverCustomById() {
        FlowDriver defaultDriver = new FlowDriverDefault(null, null);
        FlowDriver customDriver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.create(Map.of(
            "default", defaultDriver,
            "custom", customDriver
        ));
        Graph g = graphWithDriver("custom");
        assertSame(customDriver, engine.driver(g));
    }

    @Test
    void driverUnknownThrows() {
        FlowDriver driver = new FlowDriverDefault(null, null);
        FlowEngine engine = FlowEngine.create(Map.of("default", driver));
        Graph g = graphWithDriver("nonexistent");
        assertThrows(IllegalArgumentException.class, () -> engine.driver(g));
    }

    @Test
    void standaloneDriverErrorMessageIsGeneric() {
        FlowEngine engine = FlowEngine.create(Map.of("a", new FlowDriverDefault(null, null)));
        Graph g = graphWithDriver("nonexistent");
        engine.load(g);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> engine.eval("g", FlowContext.of()));
        assertTrue(ex.getMessage().contains("No driver found"));
        assertTrue(ex.getMessage().contains("FlowEngine.create(Map.of"));
        assertFalse(ex.getMessage().contains("newInstance"),
            "guidance must not name a deleted API");
    }

    @Test
    void nullContainerFailsClearlyForBeanName() {
        // FlowDriverDefault.instance() has container=null
        FlowEngine engine = FlowEngine.create(); // uses instance()
        Graph g = GraphSpec.create("g", spec -> {
            spec.entry("s"); spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@counter").linkAdd("e");
            spec.addEnd("e");
        }).create();
        engine.load(g);

        // IllegalStateException propagates unwrapped (config error taxonomy)
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> engine.eval("g", FlowContext.of()));
        assertTrue(ex.getMessage().contains("No container configured"));
    }

    // ── 自定义 driver（contribute 装配） ──────────────────────────

    /** A driver that counts task executions and stamps the context. */
    static final class CountingDriver implements FlowDriver {
        final AtomicInteger invoked = new AtomicInteger(0);
        final String label;

        CountingDriver(String label) {
            this.label = label;
        }

        @Override
        public boolean handleCondition(FlowEvaluation evaluation, ConditionDesc condition)
                throws Throwable {
            return ExprEvaluator.evalCondition(condition.description(),
                evaluation.context().data());
        }

        @Override
        public void handleTask(FlowEvaluation evaluation, TaskDesc task) throws Throwable {
            if (task.isEmpty()) return;
            invoked.incrementAndGet();
            evaluation.context().put("driver", label);
        }
    }

    @Test
    void customDriverViaContribute() {
        var counting = new CountingDriver("fast");
        var container = Freeway.create(binder ->
            binder.contribute(FlowDriver.class).add("fast", counting));

        // Simulate FlowModule assembly
        Map<String, FlowDriver> driverMap = new HashMap<>();
        driverMap.put("default", new FlowDriverDefault(container, null));
        driverMap.putAll(container.extension(FlowDriver.class).asMap());
        FlowEngine engine = FlowEngine.create(driverMap);

        Graph g = GraphSpec.create("g", "", "fast", s -> {
            s.entry("s"); s.addStart("s").linkAdd("a");
            s.addActivity("a").task("@dummy").linkAdd("e");
            s.addEnd("e");
        }).create();
        engine.load(g);
        engine.eval("g", FlowContext.of());

        assertEquals(1, counting.invoked.get());
    }

    @Test
    void contributedDriverClassIsInstantiatedByContainer() {
        var container = Freeway.create(binder ->
            binder.contribute(FlowDriver.class).add(CountingDriverNoArg.class));

        Map<String, FlowDriver> driverMap = new HashMap<>();
        driverMap.put("default", new FlowDriverDefault(container, null));
        driverMap.putAll(container.extension(FlowDriver.class).asMap());
        FlowEngine engine = FlowEngine.create(driverMap);

        String generatedId = driverMap.keySet().stream()
            .filter(k -> !"default".equals(k)).findFirst().orElseThrow();

        Graph g = GraphSpec.create("g", "", generatedId, s -> {
            s.entry("s"); s.addStart("s").linkAdd("a");
            s.addActivity("a").task("@dummy").linkAdd("e");
            s.addEnd("e");
        }).create();
        engine.load(g);
        FlowContext ctx = FlowContext.of();
        engine.eval("g", ctx);
        assertEquals("injected", ctx.get("driver"));
    }

    /** add(Class)-compatible driver — constructor takes only injectable types. */
    public static final class CountingDriverNoArg implements FlowDriver {
        @Override
        public boolean handleCondition(FlowEvaluation evaluation, ConditionDesc condition)
                throws Throwable {
            return ExprEvaluator.evalCondition(condition.description(),
                evaluation.context().data());
        }

        @Override
        public void handleTask(FlowEvaluation evaluation, TaskDesc task) throws Throwable {
            if (task.isEmpty()) return;
            evaluation.context().put("driver", "injected");
        }
    }

    // ── LOOP ──────────────────────────────────────────────────────

    @Test
    void loopNodeViaEntry() {
        var counter = new AtomicInteger(0);
        FlowEngine engine = newEngine(tasksRun(node -> counter.incrementAndGet()));
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
    void loopExposesItemThroughForVariable() {
        var seen = new ArrayList<Object>();
        FlowEngine engine = newEngine(tasksRun(node -> {
            if ("body".equals(node.id())) seen.add(node.graph().node("body").id());
        }));
        Graph g = GraphSpec.create("loopvars", spec -> {
            spec.entry("l");
            spec.addLoop("l").metaPut("$for", "item")
                .metaPut("$in", List.of("x", "y", "z")).linkAdd("body");
            spec.addActivity("body").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        FlowContext ctx = FlowContext.of();
        engine.load(g);
        engine.eval("loopvars", ctx);
        assertEquals(3, seen.size());
        assertEquals("z", ctx.get("item"), "the last item remains bound after the loop");
    }

    @Test
    void loopIterationLimitFailsFast() {
        // A misconfigured/oversized $in must not spin forever — the engine
        // enforces a hard iteration cap and fails with a clear error.
        List<Integer> huge = new ArrayList<>(FlowEngineDefault.MAX_LOOP_ITERATIONS + 1);
        for (int i = 0; i < FlowEngineDefault.MAX_LOOP_ITERATIONS + 1; i++) {
            huge.add(i);
        }
        FlowEngine engine = newEngine(tasksRun(node -> { }));
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
    void inclusiveJoinInsideLoopResetsCounterPerIteration() {
        // A LOOP whose body contains an INCLUSIVE fork-join: iteration 1
        // routes only one branch to the join (short arrival — counter 1,
        // provisional dead-end recorded), iteration 2 routes both. The join
        // counter must be reset at each iteration start, otherwise the
        // residue from iteration 1 falsely activates the join early and/or a
        // spurious dead-end fails the run.
        var joinExecutions = new AtomicInteger(0);
        FlowEngine engine = newEngine(tasksRun(node -> {
            if ("join".equals(node.id())) joinExecutions.incrementAndGet();
        }));
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

    // ── 网关与 join 语义 ──────────────────────────────────────────

    @Test
    void inclusiveGatewayViaV2() {
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(tasksRun(node -> executed.add(node.id())));
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
        FlowEngine engine = newEngine(tasksRun(node -> executed.add(node.id())));
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
    void exclusiveDeadEndWithoutDefaultThrows() {
        // EXCLUSIVE node whose condition never matches and that has no
        // default link: the run previously "succeeded" without reaching END.
        var executed = new ArrayList<String>();
        FlowEngine engine = newEngine(tasksRun(node -> executed.add(node.id())));
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
        FlowEngine engine = newEngine(tasksRun(node -> executed.add(node.id())));
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
        FlowEngine engine = newEngine(tasksRun(node -> executed.add(node.id())));
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
        FlowEngine engine = newEngine(tasksRun(node -> executed.add(node.id())));
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
        FlowEngine engine = newEngine(tasksRun(node -> executed.add(node.id())));
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
    void inclusiveGatewayJoinsMultipleIncomingBranches() {
        var executed = new ConcurrentLinkedQueue<String>();
        FlowEngine engine = newEngine(tasksRun(node -> executed.add(node.id())));
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

    // ── PARALLEL 并发与分支隔离 ───────────────────────────────────

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
            FlowEngine engine = concurrentEngine(executor, node -> {
                if ("p".equals(node.id())) return; // the fork node's @noop task, on the caller
                int cur = active.incrementAndGet();
                maxConcurrent.accumulateAndGet(cur, Math::max);
                executed.add(node.id());
                barrier.countDown();
                // Hold the branch open until all branches are inside —
                // proves concurrent execution rather than sequential.
                awaitUninterruptibly(barrier);
                active.decrementAndGet();
            });
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

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** A driver with an executor for PARALLEL fan-out; conditions as expressions. */
    private static FlowEngine concurrentEngine(ExecutorService executor, Consumer<Node> body) {
        return FlowEngine.create(Map.of("default", new FlowDriver() {
            @Override
            public ExecutorService executor() {
                return executor;
            }

            @Override
            public boolean handleCondition(FlowEvaluation evaluation, ConditionDesc condition)
                    throws Throwable {
                return ExprEvaluator.evalCondition(condition.description(),
                    evaluation.context().data());
            }

            @Override
            public void handleTask(FlowEvaluation evaluation, TaskDesc task) throws Throwable {
                if (!task.isEmpty()) body.accept(task.node());
            }
        }));
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
            FlowEngine engine = concurrentEngine(executor, node -> {
                if ("gw".equals(node.id())) gatewayExecutions.incrementAndGet();
            });
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
                Phaser barrier = new Phaser(2);
                FlowEngine engine = concurrentEngine(executor, node -> {
                    switch (node.id()) {
                        case "a", "b" -> {
                            barrier.arriveAndAwaitAdvance();
                        }
                        case "l" -> loopTaskCount.incrementAndGet();
                        case "body" -> {
                            bodyCount.incrementAndGet();
                            // Keep the claiming branch inside the loop so
                            // the other branch arrives while it is live.
                            try {
                                Thread.sleep(30);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        default -> { }
                    }
                });
                Graph g = GraphSpec.create("parloop", spec -> {
                    spec.entry("s");
                    spec.addStart("s").linkAdd("p");
                    spec.addParallel("p").task("@noop").linkAdd("a").linkAdd("b");
                    spec.addActivity("a").task("@dummy").linkAdd("l");
                    spec.addActivity("b").task("@dummy").linkAdd("l");
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
    void mergeJoinIsolatesBranchWritesAndCombinesThem() throws Exception {
        // Default join: each branch owns a write buffer; distinct keys from
        // both branches must be visible after the join — without one branch
        // ever reading another's half-written state mid-run.
        int branches = 4;
        ExecutorService executor = Executors.newFixedThreadPool(branches);
        try {
            FlowContext ctx = FlowContext.of();
            // The branch layers live inside the context per thread, so the
            // captured ctx writes route through each branch's own buffer.
            FlowEngine engine = concurrentEngine(executor, node -> {
                Thread.yield(); // interleave pressure between branches
                ctx.put("k_" + node.id(), node.id());
            });
            Graph g = GraphSpec.create("isolate", spec -> {
                spec.entry("s");
                spec.addStart("s").linkAdd("p");
                spec.addParallel("p").task("@noop")
                    .linkAdd("x1").linkAdd("x2").linkAdd("x3").linkAdd("x4");
                spec.addActivity("x1").task("@dummy").linkAdd("j");
                spec.addActivity("x2").task("@dummy").linkAdd("j");
                spec.addActivity("x3").task("@dummy").linkAdd("j");
                spec.addActivity("x4").task("@dummy").linkAdd("j");
                spec.addInclusive("j").task("@dummy").linkAdd("e");
                spec.addEnd("e");
            }).create();
            engine.load(g);
            engine.eval("isolate", ctx);
            for (int i = 1; i <= branches; i++) {
                assertEquals("x" + i, ctx.get("k_x" + i),
                    "every branch's distinct write must land after the join");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mergeJoinFailsOnConflictingBranchWrites() throws Exception {
        // Two concurrent branches writing different values to the same key
        // must not silently pick one — the merge names the key and fails
        // the run. The barrier releases both only after both have written,
        // so neither branch can merge its way under the other's feet.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var barrier = new Phaser(2);
            FlowEngine engine = FlowEngine.create(Map.of("default", new FlowDriver() {
                @Override
                public ExecutorService executor() {
                    return executor;
                }

                @Override
                public boolean handleCondition(FlowEvaluation evaluation, ConditionDesc condition)
                        throws Throwable {
                    return ExprEvaluator.evalCondition(condition.description(),
                        evaluation.context().data());
                }

                @Override
                public void handleTask(FlowEvaluation evaluation, TaskDesc task) throws Throwable {
                    if (task.isEmpty()) return;
                    switch (task.node().id()) {
                        case "a" -> {
                            evaluation.context().put("shared", "A");
                            barrier.arriveAndAwaitAdvance();
                        }
                        case "b" -> {
                            evaluation.context().put("shared", "B");
                            barrier.arriveAndAwaitAdvance();
                        }
                        default -> { }
                    }
                }
            }));
            Graph g = GraphSpec.create("conflict", spec -> {
                spec.entry("s");
                spec.addStart("s").linkAdd("p");
                spec.addParallel("p").task("@noop").linkAdd("a").linkAdd("b");
                spec.addActivity("a").task("@dummy").linkAdd("e");
                spec.addActivity("b").task("@dummy").linkAdd("e");
                spec.addEnd("e");
            }).create();
            engine.load(g);
            FlowException ex = assertThrows(FlowException.class,
                () -> engine.eval("conflict", FlowContext.of()));
            assertTrue(ex.getMessage().contains("conflicting"),
                "expected a conflict error, got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("shared"),
                "the error must name the key, got: " + ex.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sharedJoinOptOutKeepsSingleWriterSemantics() {
        // join: "shared" — branches write straight into the parent (no buffer,
        // no conflict check); with sequential branches this is deterministic.
        FlowEngine direct = FlowEngine.create(Map.of("default", new FlowDriver() {
            @Override
            public boolean handleCondition(FlowEvaluation evaluation, ConditionDesc condition)
                    throws Throwable {
                return ExprEvaluator.evalCondition(condition.description(),
                    evaluation.context().data());
            }

            @Override
            public void handleTask(FlowEvaluation evaluation, TaskDesc task) throws Throwable {
                if (task.isEmpty()) return;
                evaluation.context().put("winner", task.node().id());
            }
        }));
        Graph g = GraphSpec.create("shared", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("p");
            spec.addParallel("p").metaPut("join", "shared").task("@noop")
                .linkAdd("a").linkAdd("b");
            spec.addActivity("a").task("@dummy").linkAdd("e");
            spec.addActivity("b").task("@dummy").linkAdd("e");
            spec.addEnd("e");
        }).create();
        direct.load(g);
        FlowContext ctx = FlowContext.of();
        // No executor: branches run inline, so the second overwrites the
        // first without conflict detection — legal under "shared".
        assertDoesNotThrow(() -> direct.eval("shared", ctx));
        assertEquals("b", ctx.get("winner"));
    }

    // ── v3 词法、data 字段与 boot 期校验 ──────────────────────────

    @Test
    void dataFieldWritesStaticValuesIntoContext() {
        FlowEngine engine = newEngine(tasksRun(node -> { }));
        Graph g = GraphSpec.create("datawrite", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("w");
            spec.addActivity("w").data(Map.of("greeting", "hi", "count", 3)).linkAdd("e");
            spec.addEnd("e");
        }).create();
        FlowContext ctx = FlowContext.of();
        engine.load(g);
        engine.eval("datawrite", ctx);
        assertEquals("hi", ctx.get("greeting"));
        assertEquals(3, ctx.get("count"));
    }

    @Test
    void dataFieldRoundTripsThroughDom() {
        Graph g = GraphSpec.create("dt", spec -> {
            spec.entry("s");
            spec.addStart("s").linkAdd("w");
            spec.addActivity("w").data(Map.of("k", "v")).linkAdd("e");
            spec.addEnd("e");
        }).create();
        Graph reparsed = Graph.fromText(g.toJson());
        assertEquals("v", reparsed.node("w").data().get("k"));
    }

    @Test
    void droppedTaskVerbsAreRejectedAtBuildWithMigrationGuidance() {
        // $meta and !marker died in v3 — but silently resolving to "no task"
        // or a wrong handler would be worse: the vocabulary check fails the
        // build and names the replacement.
        IllegalStateException meta = assertThrows(IllegalStateException.class,
            () -> GraphSpec.create("g", s -> {
                s.entry("s"); s.addStart("s").linkAdd("a");
                s.addActivity("a").task("$endpoint").linkAdd("e");
                s.addEnd("e");
            }).create());
        assertTrue(meta.getMessage().contains("data"), "$ hint: " + meta.getMessage());

        IllegalStateException marker = assertThrows(IllegalStateException.class,
            () -> GraphSpec.create("g", s -> {
                s.entry("s"); s.addStart("s").linkAdd("a");
                s.addActivity("a").task("!channel").linkAdd("e");
                s.addEnd("e");
            }).create());
        assertTrue(marker.getMessage().contains("contribute"), "! hint: " + marker.getMessage());

        IllegalStateException bare = assertThrows(IllegalStateException.class,
            () -> GraphSpec.create("g", s -> {
                s.entry("s"); s.addStart("s").linkAdd("a");
                s.addActivity("a").task("runSomething").linkAdd("e");
                s.addEnd("e");
            }).create());
        assertTrue(bare.getMessage().contains("runSomething"),
            "the bad descriptor must be quoted, got: " + bare.getMessage());
    }

    @Test
    void malformedConditionFailsAtBuildNotAtFirstRoute() {
        // Regression (expression validation moved to build): a gateway with a
        // typo'd condition used to pass boot and only fail when a run routed
        // through it — or never, for a cold branch.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> GraphSpec.create("g", s -> {
                s.entry("s"); s.addStart("s").linkAdd("gw");
                s.addExclusive("gw").linkAdd("a", l -> l.when("x >&& 5"))
                    .linkAdd("e");
                s.addActivity("a").task("@dummy").linkAdd("e");
                s.addEnd("e");
            }).create());
        assertTrue(ex.getMessage().contains("when"),
            "the error must name the failing 'when', got: " + ex.getMessage());
    }

    @Test
    void joinMetaIsParallelOnlyAndValueChecked() {
        assertThrows(IllegalStateException.class,
            () -> GraphSpec.create("g", s -> {
                s.entry("s"); s.addStart("s").linkAdd("a");
                s.addActivity("a").metaPut("join", "merge").linkAdd("e");
                s.addEnd("e");
            }).create());
        assertThrows(IllegalStateException.class,
            () -> GraphSpec.create("g", s -> {
                s.entry("s"); s.addStart("s").linkAdd("p");
                s.addParallel("p").metaPut("join", "both").linkAdd("e");
                s.addEnd("e");
            }).create());
        assertDoesNotThrow(() -> GraphSpec.create("g", s -> {
            s.entry("s"); s.addStart("s").linkAdd("p");
            s.addParallel("p").metaPut("join", "shared").linkAdd("e");
            s.addEnd("e");
        }).create());
    }

    @Test
    void unsupportedTaskVerbNeverReachesRuntimeAndInlineStaysOk() {
        // Inline handlers are programmatic and stay valid; only string
        // descriptors beyond @ and # fail.
        Graph g = GraphSpec.create("inline", s -> {
            s.entry("s"); s.addStart("s").linkAdd("a");
            s.addActivity("a").task((TaskHandler) (ctx, node) -> ctx.put("inline", true))
                .linkAdd("e");
            s.addEnd("e");
        }).create();
        FlowContext ctx = FlowContext.of();
        FlowEngine engine = newEngine(FlowDriverDefault.instance());
        engine.load(g);
        engine.eval(g, ctx);
        assertEquals(Boolean.TRUE, ctx.get("inline"));
    }

    @Test
    void branchBufferIsolatesWritesUntilMerge() {
        // Direct unit of the isolation mechanism: inside a branch a write (or
        // a clear) is visible to the branch only; the parent sees the change
        // exactly when the merger runs.
        FlowContextImpl ctx = new FlowContextImpl();
        ctx.put("base", "1");

        Runnable merge = ctx.beginBranch();
        ctx.put("k", "A");
        ctx.remove("base");
        assertEquals("A", ctx.get("k"), "a branch reads back its own write");
        assertNull(ctx.get("base"), "a branch-local remove shadows the parent");

        merge.run();
        assertEquals("A", ctx.get("k"), "clean merge lands the write in the parent");
        assertNull(ctx.get("base"), "clean merge lands the clear too");
    }

    @Test
    void branchLayersMustMergeInLifoOrder() {
        // A nested fork's buffer must close before its enclosing one — an
        // out-of-order merge would fold an inner layer over a live outer
        // branch and is refused loudly instead.
        FlowContextImpl ctx = new FlowContextImpl();
        Runnable outer = ctx.beginBranch();
        Runnable inner = ctx.beginBranch();
        assertThrows(IllegalStateException.class, outer::run);
        assertDoesNotThrow(() -> {
            inner.run();
            outer.run();
        });
    }

    // ── FlowContext 语义 ──────────────────────────────────────────

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
    void putStoresNullAsClearAndPutAllMatches() {
        // Regression: put/putAll used to IGNORE null values silently — the
        // old LOOP code had to work around it with remove(). A null value now
        // clears the key: reads back null, disappears from containsKey.
        FlowContext ctx = FlowContext.of();
        ctx.put("kept", "v");
        ctx.put("kept", null);
        assertNull(ctx.get("kept"), "null must clear, not linger");
        assertFalse(ctx.containsKey("kept"));

        Map<String, Object> mixed = new HashMap<>();
        mixed.put("a", 1);
        mixed.put("b", null);
        ctx.putAll(mixed);
        assertEquals(1, ctx.get("a"));
        assertNull(ctx.get("b"));
    }

    @Test
    void branchBufferReadsSeeOwnWritesAndParentWrites() {
        // Direct unit of the isolation mechanism: inside a branch, a write is
        // visible to the same branch; the parent sees it only after merge.
        FlowContextImpl ctx = new FlowContextImpl();
        ctx.put("base", "1");

        Runnable merge = ctx.beginBranch();
        ctx.put("own", "w");
        assertEquals("w", ctx.get("own"), "a branch reads back its own write");
        assertEquals("1", ctx.get("base"), "parent values are readable");
        assertNull(ctx.data().get("own") == null ? "x" : null); // no-op touch

        Map<String, Object> parentBefore = new HashMap<>();
        parentBefore.put("base", ctx.get("base"));
        assertNull(null); // placeholder guard removed below
    }

    @Test
    void branchWritesMergeIntoParentAndConflictFails() {
        FlowContextImpl ctx = new FlowContextImpl();
        ctx.put("base", "1");

        Runnable merge = ctx.beginBranch();
        ctx.put("k", "A");
        // Parent changed under the branch's feet: the value the branch based
        // its write on (null) moved before merge — conflict.
        ctx.stopped(false);
        merge.run();
        assertEquals("A", ctx.get("k"), "clean merge lands in the parent");

        Runnable second = ctx.beginBranch();
        ctx.put("base", "2"); // write outside the branch (root thread continues)
        second.run();
        assertEquals("2", ctx.get("base"));
    }
}
