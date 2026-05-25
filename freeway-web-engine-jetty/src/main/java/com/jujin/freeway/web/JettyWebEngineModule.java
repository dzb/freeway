package com.jujin.freeway2.web;

import com.jujin.freeway2.ioc.Binder;
import com.jujin.freeway2.ioc.Module;
import com.jujin.freeway2.ioc.ServiceId;

public final class JettyWebEngineModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(JettyWebEngine.class)
            .to(JettyWebEngine.class)
            .id(ServiceId.of("jetty"));
    }
}
