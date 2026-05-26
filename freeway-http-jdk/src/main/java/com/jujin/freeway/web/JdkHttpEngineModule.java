package com.jujin.freeway.web;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;
import com.jujin.freeway.ioc.ServiceId;

public final class JdkHttpEngineModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(JdkHttpEngine.class)
            .to(JdkHttpEngine.class)
            .id(ServiceId.of("jdk"));
    }
}
