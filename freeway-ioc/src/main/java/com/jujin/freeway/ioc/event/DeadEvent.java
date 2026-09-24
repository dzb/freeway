package com.jujin.freeway.ioc.event;

/**
 * Published by EventBus when an event has zero subscribers. Useful for
 * debugging and logging.
 *
 * @param source the {@link EventBus} that emitted the diagnostic
 * @param event  the original zero-subscriber payload
 */
public record DeadEvent(EventBus source, Object event) {}
