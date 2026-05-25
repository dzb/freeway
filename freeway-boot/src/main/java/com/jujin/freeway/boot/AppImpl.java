package com.jujin.freeway2.boot;

import com.jujin.freeway2.ioc.Container;

record AppImpl(Container container, AppConfig config) implements App {
    @Override
    public void close() {
        container.close();
    }
}
