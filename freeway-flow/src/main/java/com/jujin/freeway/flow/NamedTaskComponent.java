package com.jujin.freeway.flow;

/**
 * Named task component (used for building graphs in hard-coded form)
 */
public interface NamedTaskComponent extends TaskComponent {
    /**
     * Gets the component name
     */
    String name();

    /**
     * Gets the display title
     */
    String title();
}
