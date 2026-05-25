package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.advisor.MethodAdvice;
import java.util.function.Predicate;

record AdviceEntry(Predicate<com.jujin.freeway.ioc.advisor.MethodInvocation> selector, MethodAdvice advice) {
}
