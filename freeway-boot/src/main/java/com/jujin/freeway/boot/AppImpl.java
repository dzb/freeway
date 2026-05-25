package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Container;

record AppImpl(Container container, AppConfig config) implements App {
    @Override
    public void close() {
        container.close();
    }
}
