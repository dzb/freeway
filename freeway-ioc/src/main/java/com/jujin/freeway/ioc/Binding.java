package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.advisor.Advisor;
import java.util.function.Function;
import java.util.function.Consumer;

public interface Binding<T> {
    Binding<T> to(Class<? extends T> implementation);

    Binding<T> to(T instance);

    Binding<T> to(Function<Container, ? extends T> provider);

    Binding<T> scope(Scope scope);

    Binding<T> id(ServiceId id);

    Binding<T> primary();

    Binding<T> advise(Consumer<Advisor> advisor);
}
