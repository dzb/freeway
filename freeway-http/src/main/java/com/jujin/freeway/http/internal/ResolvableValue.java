package com.jujin.freeway.http.internal;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The one two-phase value holder shared by class-declared route handlers and
 * WebSocket endpoints: a declaration names the class, composition later hands
 * in the one instance built through the container.
 *
 * <p>Resolution is first-wins and thread-safe. The supplier runs only while
 * the value is still absent, so a wrapper expanded into several routes is
 * instantiated exactly once. The holder stores no container reference: the
 * caller owns assembly and passes the built instance in, which keeps the
 * route and WebSocket packages free of container vocabulary.</p>
 *
 * <p>Internal to the HTTP module — public only because the owner wrappers live
 * in sibling packages and compose it.</p>
 *
 * @param <T> the declared service/handler type
 */
public final class ResolvableValue<T> {

    private final Class<? extends T> declaredType;
    private volatile T value;

    public ResolvableValue(Class<? extends T> declaredType) {
        this.declaredType = Objects.requireNonNull(declaredType, "declaredType");
    }

    public Class<? extends T> declaredType() {
        return declaredType;
    }

    public boolean isResolved() {
        return value != null;
    }

    /**
     * Hands in the built value; the first hand-in wins. The factory is
     * consulted only while unresolved.
     */
    public T resolve(Supplier<? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        T resolved = value;
        if (resolved == null) {
            synchronized (this) {
                resolved = value;
                if (resolved == null) {
                    value = resolved = Objects.requireNonNull(
                        factory.get(), "factory.get()");
                }
            }
        }
        return resolved;
    }

    /** The resolved value, or {@code null} before composition hands it in. */
    public T peek() {
        return value;
    }
}
