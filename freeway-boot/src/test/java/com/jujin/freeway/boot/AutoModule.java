package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;

public final class AutoModule implements ModuleEx {
    @Override
    public void bind(Binder binder) {
        binder.bind(FreewayAppTest.AutoMarker.class).to(new FreewayAppTest.AutoMarker("auto"));
    }
}
