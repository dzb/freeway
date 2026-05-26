package com.jujin.freeway.http;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;
import com.jujin.freeway.ioc.ServiceId;

public final class UndertowWebEngineModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(UndertowWebEngine.class)
            .to(UndertowWebEngine.class)
            .id(ServiceId.of("undertow"));
    }
}
