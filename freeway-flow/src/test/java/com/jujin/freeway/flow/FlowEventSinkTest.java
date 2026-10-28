package com.jujin.freeway.flow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The core's event exit. An evaluation's {@link FlowEventBus} is scoped to
 * that execution; the sink is how a module hands those events to the
 * application (the container face wires it to {@code EventBus}). The two are
 * not the same audience: local subscribers belong to the evaluation, the sink
 * belongs to the process.
 *
 * <p>The engine clears the execution bus when a top-level eval begins, so a
 * local subscriber is registered during the run (by a task or interceptor) —
 * the same lifetime the isolation contract describes.</p>
 */
class FlowEventSinkTest {

    /** Publishes one event, with a local subscriber registered first. */
    private static FlowDriver publishingDriver(String topic, Object payload, List<String> local) {
        return new FlowDriver() {
            @Override
            public boolean handleCondition(FlowEvaluation evaluation, ConditionDesc condition) {
                return true;
            }

            @Override
            public void handleTask(FlowEvaluation evaluation, TaskDesc task) {
                if (task.isEmpty()) {
                    return;
                }
                if (local != null) {
                    evaluation.context().eventBus().subscribe(topic,
                        event -> local.add(String.valueOf(event)));
                }
                evaluation.context().eventBus().publish(topic, payload);
            }
        };
    }

    private static Graph oneTaskGraph() {
        return GraphSpec.create("sink", s -> {
            s.entry("start");
            s.addStart("start").linkAdd("emit");
            s.addActivity("emit").task("@emit").linkAdd("end");
            s.addEnd("end");
        }).create();
    }

    @Test
    void standaloneEngineForwardsEveryPublishToItsSink() {
        List<String> forwarded = new ArrayList<>();
        List<String> local = new ArrayList<>();
        FlowEngine engine = FlowEngine.create(
            Map.of("default", publishingDriver("audit", "hello", local)),
            List.of(),
            (topic, payload) -> forwarded.add(topic + ":" + payload));

        engine.eval(oneTaskGraph(), FlowContext.of());

        assertEquals(List.of("audit:hello"), forwarded,
            "the sink is the module exit: every publish leaves the execution");
        assertEquals(List.of("hello"), local,
            "the local subscriber keeps its evaluation-scoped audience");
    }

    @Test
    void anEngineWithoutSinkKeepsEventsInsideTheExecution() {
        List<String> local = new ArrayList<>();
        FlowEngine engine = FlowEngine.create(
            Map.of("default", publishingDriver("audit", "hello", local)));

        engine.eval(oneTaskGraph(), FlowContext.of());

        assertEquals(List.of("hello"), local,
            "standalone create() has no application bus; local delivery is all there is");
    }

    @Test
    void aThrowingSinkDoesNotFailTheRun() {
        FlowEngine engine = FlowEngine.create(
            Map.of("default", publishingDriver("audit", "hello", null)),
            List.of(),
            (topic, payload) -> {
                throw new IllegalStateException("bridge is down");
            });

        // The bridge's failure must not become the graph's: the same isolation
        // a local subscriber gets, because a bridge is just another subscriber
        // from the run's point of view.
        engine.eval(oneTaskGraph(), FlowContext.of());
    }
}
