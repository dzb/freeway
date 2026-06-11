package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.internal.ContainerImpl;
import java.util.Arrays;
import java.util.List;

public final class Freeway {

    private Freeway() {}

    public static Container create(com.jujin.freeway.ioc.Module... modules) {
        return new ContainerImpl(
            modules == null ? List.of() : Arrays.asList(modules)
        );
    }
}
