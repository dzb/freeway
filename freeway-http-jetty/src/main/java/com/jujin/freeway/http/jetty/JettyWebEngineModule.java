package com.jujin.freeway.http.jetty;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module2;

public final class JettyWebEngineModule implements Module2{
    @Override
    public void bind(Binder binder) {
        binder.bind(JettyWebEngine.class)
            .to(JettyWebEngine.class)
            .id("jetty");
    }
}
