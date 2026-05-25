package com.jujin.freeway.ioc.advisor;

import java.lang.reflect.Method;

public interface MethodInvocation {
    Object proceed() throws Throwable;

    Object target();

    Method method();

    Object[] arguments();
}
