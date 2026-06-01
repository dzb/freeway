package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.BeanConstructor;
import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.ioc.annotation.Inject;

final class InstanceFactory {
    private final ContainerImpl container;

    InstanceFactory(ContainerImpl container) {
        this.container = container;
    }

    <T> T instantiate(Class<T> type) {
        try {
            T value = construct(type);
            container.initialize(value);
            return value;
        } catch (Error ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new RuntimeException("Unable to instantiate " + type.getName(), ex);
        }
    }

    <T> T construct(Class<T> type) throws Throwable {
        BeanConstructor constructor = BeanIntrospector.selectConstructor(type, Inject.class);
        Object[] args = container.resolveArguments(type, constructor.parameters());
        return type.cast(constructor.newInstance(args));
    }
}
