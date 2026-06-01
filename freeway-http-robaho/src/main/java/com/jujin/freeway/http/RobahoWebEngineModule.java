package com.jujin.freeway.http;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;

public final class RobahoWebEngineModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(RobahoWebEngine.class)
            .to(RobahoWebEngine.class)
            .id("robaho")
            .primary();
    }
}
