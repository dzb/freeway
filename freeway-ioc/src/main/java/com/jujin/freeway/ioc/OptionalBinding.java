package com.jujin.freeway.ioc;


import java.util.Objects;

/**
 * One optional container lookup for composition-time assembly.
 *
 * <p>Only a genuinely absent binding becomes {@code null}. A configured
 * provider that fails for any other reason is a real startup problem and must
 * not be degraded into "module not installed".</p>
 */
public final class OptionalBinding {

    private OptionalBinding() {}

    public static <T> T get(Container container, Class<T> type) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(type, "type");
        // Selection first: only a genuinely absent binding becomes null. A
        // provider that throws MissingBindingException from a nested dependency
        // must propagate, not be mistaken for "module not installed".
        if (!container.isActiveBindingMarked(type)) {
            return null;
        }
        return container.get(type);
    }
}
