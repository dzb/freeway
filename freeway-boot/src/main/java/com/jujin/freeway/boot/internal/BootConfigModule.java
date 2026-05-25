package com.jujin.freeway.boot.internal;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
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
