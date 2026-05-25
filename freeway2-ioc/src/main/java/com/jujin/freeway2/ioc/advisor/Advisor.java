package com.jujin.freeway2.ioc.advisor;

import java.util.function.Predicate;

public interface Advisor {
    Advisor wrap(Predicate<MethodInvocation> selector, MethodAdvice advice);
}
