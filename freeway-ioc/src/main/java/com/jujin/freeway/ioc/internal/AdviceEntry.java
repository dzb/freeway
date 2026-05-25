package com.jujin.freeway2.ioc.internal;

import com.jujin.freeway2.ioc.advisor.MethodAdvice;
import java.util.function.Predicate;

record AdviceEntry(Predicate<com.jujin.freeway2.ioc.advisor.MethodInvocation> selector, MethodAdvice advice) {
}
