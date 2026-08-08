package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Container;

/**
 * Published by the application runtime after all RuntimeHooks have started
 * successfully.
 *
 * <p>Delivered via the container {@code EventBus}: if the app is started
 * inside an active {@code Defer} scope (e.g. a DB transaction), delivery is
 * deferred to the scope's end and dropped on rollback — start/stop the app
 * outside transactional scopes for immediate delivery.
 */
public record AppStartedEvent(Container container) {}
