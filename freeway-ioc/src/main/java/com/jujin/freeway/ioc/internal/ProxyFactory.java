package com.jujin.freeway.ioc.internal;

import java.util.List;
import java.util.function.Supplier;

interface ProxyFactory {
    <T> T create(Class<T> interfaceType, Supplier<T> provider, String description);

    <T> T createAdvised(
        Class<T> interfaceType,
        Supplier<T> provider,
        String description,
        List<AdviceEntry> advices
    );
}
