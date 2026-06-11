package com.jujin.freeway.ioc;

/** Published by EventBus when an event has zero subscribers. Useful for debugging and logging. */
public record DeadEvent(Object source, Object event) {}
