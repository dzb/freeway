package com.jujin.freeway.http.undertow;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module2;

public final class UndertowWebEngineModule implements Module2{
    @Override
    public void bind(Binder binder) {
        binder.bind(UndertowWebEngine.class)
            .to(UndertowWebEngine.class)
            .id("undertow");
    }
}
