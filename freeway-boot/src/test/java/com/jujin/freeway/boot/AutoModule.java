package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;

public final class AutoModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(LauncherTest.AutoMarker.class).to(new LauncherTest.AutoMarker("auto"));
    }
}
