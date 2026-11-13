package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.MissingBindingException;

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
        try {
            return container.get(type);
        } catch (MissingBindingException absent) {
            return null;
        }
    }
}
