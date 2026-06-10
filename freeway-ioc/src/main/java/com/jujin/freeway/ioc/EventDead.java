package com.jujin.freeway.ioc;

/** Published by EventBus when an event has zero subscribers. Useful for debugging and logging. */
public record EventDead(Object source, Object event) {}
