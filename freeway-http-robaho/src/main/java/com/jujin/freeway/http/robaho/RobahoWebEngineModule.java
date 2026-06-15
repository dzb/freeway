package com.jujin.freeway.http.robaho;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module2;

public final class RobahoWebEngineModule implements Module2{
    @Override
    public void bind(Binder binder) {
        binder.bind(RobahoWebEngine.class)
            .to(RobahoWebEngine.class)
            .id("robaho")
            .primary();
    }
}
