package com.jujin.freeway.commons.util;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The object-method contract every framework JDK proxy must answer locally:
 * calls on {@code java.lang.Object} never reach the remote target or the
 * advised instance.
 *
 * <p>One implementation keeps {@code equals}/{@code hashCode} identity and
 * {@code toString} description consistent across the container's advising
 * proxy and cloud's remote proxy.</p>
 */
public final class Proxies {

    private Proxies() {}

    /**
     * Answers an {@code Object} method for {@code proxy}.
     *
     * @param description evaluated only for {@code toString}
     */
    public static Object objectMethod(
        Object proxy,
        Method method,
        Object[] args,
        Supplier<String> description
    ) {
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(description, "description");
        return switch (method.getName()) {
            case "toString" -> description.get();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && proxy == args[0];
            default -> throw new UnsupportedOperationException(
                "Unsupported Object method: " + method.getName());
        };
    }
}
