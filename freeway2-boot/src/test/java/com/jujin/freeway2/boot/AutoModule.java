package com.jujin.freeway2.boot;

import com.jujin.freeway2.ioc.Binder;
import com.jujin.freeway2.ioc.Module;

public final class AutoModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(LauncherTest.AutoMarker.class).to(new LauncherTest.AutoMarker("auto"));
    }
}
