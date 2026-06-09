package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.advisor.MethodAdvice;
import com.jujin.freeway.ioc.advisor.MethodInvocation;

import java.util.function.Predicate;

record AdviceEntry(Predicate<MethodInvocation> selector, MethodAdvice advice) {
}
