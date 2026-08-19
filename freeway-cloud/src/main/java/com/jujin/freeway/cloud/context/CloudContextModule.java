package com.jujin.freeway.cloud.context;

import com.jujin.freeway.cloud.internal.AuthPropagator;
import com.jujin.freeway.cloud.internal.TracePropagator;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;

/**
 * IoC wiring for the context subsystem: {@code InvocationContext} (ScopedValue
 * carrier), {@code Propagator} chain, and the inbound {@code PropagationFilter}.
 */
@Marker(Builtin.class)
public final class CloudContextModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.contribute(Propagator.class).add("trace", new TracePropagator());
        b.contribute(Propagator.class).add("auth", new AuthPropagator());
        b.contribute(HttpFilter.class).add(PropagationFilter.class);
    }
}
