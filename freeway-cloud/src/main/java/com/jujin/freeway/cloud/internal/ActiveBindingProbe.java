package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.ioc.Container;

import java.lang.annotation.Annotation;
import java.util.Objects;

/**
 * Container-aware probe for the currently selected bindings. Bound by
 * {@code CloudDiscoveryModule} so container-created contributions can ask
 * whether the {@code @Local} default is still in use without being handed the
 * container itself (which is not an injectable boundary type).
 */
public final class ActiveBindingProbe {

    private final Container container;

    public ActiveBindingProbe(Container container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    /** True when {@code type}'s selected binding carries {@code marker}. */
    public <T> boolean hasMarker(Class<T> type, Class<? extends Annotation> marker) {
        return container.isActiveBinding(type, marker);
    }

    /** True when {@code type}'s selected binding is still the {@code @Local} default. */
    public <T> boolean isLocal(Class<T> type) {
        return hasMarker(type, Local.class);
    }
}
