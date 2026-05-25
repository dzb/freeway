package com.jujin.freeway.web;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;
import com.jujin.freeway.ioc.ServiceId;

public final class JettyWebEngineModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(JettyWebEngine.class)
            .to(JettyWebEngine.class)
            .id(ServiceId.of("jetty"));
    }
}
