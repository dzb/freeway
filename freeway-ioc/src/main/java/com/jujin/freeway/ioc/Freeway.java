package com.jujin.freeway2.ioc;

import com.jujin.freeway2.ioc.internal.ContainerImpl;
import java.util.Arrays;
import java.util.List;

public final class Freeway2 {
    private Freeway2() {
    }

    public static Container create(com.jujin.freeway2.ioc.Module... modules) {
        return new ContainerImpl(modules == null ? List.of() : Arrays.asList(modules));
    }
}
