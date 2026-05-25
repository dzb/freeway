package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.advisor.Advisor;
import com.jujin.freeway.ioc.advisor.MethodAdvice;
import com.jujin.freeway.ioc.advisor.MethodInvocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

final class AdvisorImpl implements Advisor {
    private final List<AdviceEntry> entries = new ArrayList<>();

    @Override
    public Advisor wrap(Predicate<MethodInvocation> selector, MethodAdvice advice) {
        entries.add(new AdviceEntry(
            Objects.requireNonNull(selector, "selector"),
            Objects.requireNonNull(advice, "advice")
        ));
        return this;
    }

    List<AdviceEntry> entries() {
        return List.copyOf(entries);
    }
}
