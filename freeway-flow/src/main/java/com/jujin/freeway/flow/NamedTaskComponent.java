package com.jujin.freeway.flow;

/**
 * Named task component (used for building graphs in hard-coded form)
 *
 * @author noear
 * @since 3.8.1
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
