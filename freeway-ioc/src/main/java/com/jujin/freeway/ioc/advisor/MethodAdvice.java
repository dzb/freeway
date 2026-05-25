package com.jujin.freeway.ioc.advisor;

public interface MethodAdvice {
    Object invoke(MethodInvocation invocation) throws Throwable;
}
