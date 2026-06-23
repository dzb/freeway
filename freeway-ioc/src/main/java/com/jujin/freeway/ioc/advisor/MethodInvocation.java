package com.jujin.freeway.ioc.advisor;

import java.lang.reflect.Method;

/**
 * Represents a single method invocation being intercepted.
 * Calling {@link #proceed()} continues the chain to the target method.
 */
public interface MethodInvocation {

    /**
     * Proceeds with the next interceptor or the target method.
     *
     * @return the return value of the invocation
     * @throws Throwable if the invocation fails
     */
    Object proceed() throws Throwable;

    /**
     * Returns the target (proxied) object.
     */
    Object target();

    /**
     * Returns the method being invoked.
     */
    Method method();

    /**
     * Returns a defensive copy of the invocation arguments.
     */
    Object[] arguments();
}
