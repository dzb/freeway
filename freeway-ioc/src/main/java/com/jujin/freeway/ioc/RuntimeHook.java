package com.jujin.freeway.ioc;

/**
 * Runtime lifecycle extension contributed by modules.
 */
public interface RuntimeHook {
    void start(Container container) throws Exception;

    default void stop(Container container) throws Exception {}
}
