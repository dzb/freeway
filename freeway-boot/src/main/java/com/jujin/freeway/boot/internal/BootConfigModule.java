package com.jujin.freeway2.boot.internal;

import com.jujin.freeway2.ioc.Binder;
import com.jujin.freeway2.ioc.Module;
import com.jujin.freeway2.ioc.symbol.SymbolProvider;
import java.util.Map;
import java.util.Objects;

public final class BootConfigModule implements Module {
    private final Map<String, String> values;

    public BootConfigModule(Map<String, String> values) {
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    @Override
    public void bind(Binder binder) {
        binder.contribute(SymbolProvider.class).add(values::get);
    }
}
