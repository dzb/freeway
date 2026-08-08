package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Container;

/**
 * Published by the application runtime before RuntimeHooks are stopped.
 *
 * <p>Delivered via the container {@code EventBus}: if the app is stopped
 * inside an active {@code Defer} scope, delivery is deferred to the scope's
 * end and dropped on rollback — stop the app outside transactional scopes
 * for immediate delivery.
 */
public record AppStoppingEvent(Container container) {}
