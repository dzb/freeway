package com.jujin.freeway.flow;

/**
 * Named task handler (used for building graphs in hard-coded form)
 */
public interface NamedTaskHandler extends TaskHandler {
    /**
     * Gets the handler name
     */
    String name();

    /**
     * Gets the display title
     */
    String title();
}
