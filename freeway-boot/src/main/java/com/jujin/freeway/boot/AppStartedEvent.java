package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Container;

/** Published by the application runtime after all RuntimeHooks have started successfully. */
public record AppStartedEvent(Container container) {}
