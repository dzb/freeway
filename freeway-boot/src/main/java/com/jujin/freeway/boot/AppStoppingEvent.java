package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Container;

/** Published by the application runtime before RuntimeHooks are stopped. */
public record AppStoppingEvent(Container container) {}
