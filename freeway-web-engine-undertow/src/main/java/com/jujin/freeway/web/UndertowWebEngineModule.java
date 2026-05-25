package com.jujin.freeway2.web;

import com.jujin.freeway2.ioc.Binder;
import com.jujin.freeway2.ioc.Module;
import com.jujin.freeway2.ioc.ServiceId;

public final class UndertowWebEngineModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(UndertowWebEngine.class)
            .to(UndertowWebEngine.class)
            .id(ServiceId.of("undertow"));
    }
}
