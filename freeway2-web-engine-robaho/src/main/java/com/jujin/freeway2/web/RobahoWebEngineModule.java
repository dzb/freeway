package com.jujin.freeway2.web;

import com.jujin.freeway2.ioc.Binder;
import com.jujin.freeway2.ioc.Module;
import com.jujin.freeway2.ioc.ServiceId;

public final class RobahoWebEngineModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(RobahoWebEngine.class)
            .to(RobahoWebEngine.class)
            .id(ServiceId.of("robaho"))
            .primary();
    }
}
