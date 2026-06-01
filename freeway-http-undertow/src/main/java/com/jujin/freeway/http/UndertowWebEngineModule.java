package com.jujin.freeway.http;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;

public final class UndertowWebEngineModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(UndertowWebEngine.class)
            .to(UndertowWebEngine.class)
            .id("undertow");
    }
}
