package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.internal.ContainerImpl;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public final class Freeway {

    private Freeway() {}

    public static Container create(Module2... modules) {
        return create(modules == null ? List.of() : Arrays.asList(modules));
    }

    public static Container create(Collection<? extends Module2> modules) {
        return new ContainerImpl(modules == null ? List.of() : modules);
    }
}
