package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.CallBus.CallAdvice;
import com.jujin.freeway.ioc.CallBus.CallChain;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Internal advice chain for {@link com.jujin.freeway.ioc.CallBus}.
 */
public final class CallAdviceChain {

    private final CopyOnWriteArrayList<CallAdviceEntry> advices = new CopyOnWriteArrayList<>();

    public void add(Predicate<String> topics, CallAdvice advice) {
        advices.add(new CallAdviceEntry(topics, advice));
    }

    public Object invoke(String topic, List<?> payload, CallTargetRegistry targets, CallStats stats)
            throws Throwable {
        return runChain(topic, payload, 0, targets, stats);
    }

    public void clear() {
        advices.clear();
    }

    private Object runChain(String topic, List<?> payload, int from, CallTargetRegistry targets,
                             CallStats stats) throws Throwable {
        for (int i = from; i < advices.size(); i++) {
            CallAdviceEntry entry = advices.get(i);
            if (!entry.topics().test(topic)) {
                continue;
            }
            int index = i;
            return entry.advice().around(new CallChain() {
                @Override public String topic() { return topic; }
                @Override public Object payload() { return payload; }
                @Override public Object proceed() throws Throwable {
                    return runChain(topic, payload, index + 1, targets, stats);
                }
            });
        }
        return targets.dispatch(topic, payload, stats);
    }

    private record CallAdviceEntry(Predicate<String> topics, CallAdvice advice) {
        private CallAdviceEntry {
            Objects.requireNonNull(topics, "topics");
            Objects.requireNonNull(advice, "advice");
        }
    }
}
