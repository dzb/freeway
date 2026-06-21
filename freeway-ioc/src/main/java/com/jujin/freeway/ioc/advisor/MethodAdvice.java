package com.jujin.freeway.ioc.advisor;

/**
 * Advice body — the logic to execute around a matched method invocation.
 *
 * @see Advisor#wrap(java.util.function.Predicate, MethodAdvice)
 */
@FunctionalInterface
public interface MethodAdvice {

    /**
     * Invokes the advice. Call {@link MethodInvocation#proceed()} on the
     * invocation parameter to continue the chain.
     *
     * @param invocation the method invocation context
     * @return the return value (should normally be the result of
     *         {@code invocation.proceed()})
     * @throws Throwable if the advice or target method throws
     */
    Object invoke(MethodInvocation invocation) throws Throwable;
}
