package com.jujin.freeway2.ioc.advisor;

public interface MethodAdvice {
    Object invoke(MethodInvocation invocation) throws Throwable;
}
