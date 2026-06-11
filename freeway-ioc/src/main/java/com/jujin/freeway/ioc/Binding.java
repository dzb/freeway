package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.advisor.Advisor;
import java.util.function.Consumer;
import java.util.function.Function;

public interface Binding<T> {
    Binding<T> to(Class<? extends T> implementation);

    Binding<T> to(T instance);

    Binding<T> to(Function<Container, ? extends T> provider);

    Binding<T> scope(Scope scope);

    Binding<T> id(String id);

    Binding<T> primary();

    Binding<T> advise(Consumer<Advisor> advisor);
}
