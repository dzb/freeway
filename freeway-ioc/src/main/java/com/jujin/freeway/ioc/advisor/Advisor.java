package com.jujin.freeway.ioc.advisor;

import java.util.function.Predicate;

/**
 * AOP advisor for wrapping service methods. Used with
 * {@code binder.bind(X.class).to(Y.class).advise(advisor -> ...)}.
 *
 * <p>Example:
 * <pre>{@code
 * binder.bind(UserService.class).to(UserServiceImpl.class).advise(advisor ->
 *     advisor.wrap(
 *         inv -> inv.method().getName().startsWith("get"),
 *         inv -> {
 *             long start = System.nanoTime();
 *             try { return inv.proceed(); }
 *             finally { log.info("{} took {}ns", inv.method(), System.nanoTime() - start); }
 *         }
 *     )
 * );
 * }</pre>
 *
 * @see MethodInvocation
 * @see MethodAdvice
 */
public interface Advisor {

    /**
     * Registers an advice that applies when the selector predicate matches.
     * Multiple {@code wrap()} calls can be chained on the same binding.
     *
     * @param selector predicate that determines which methods to intercept
     * @param advice   the advice to apply
     * @return this advisor for further chaining
     */
    Advisor wrap(Predicate<MethodInvocation> selector, MethodAdvice advice);
}
