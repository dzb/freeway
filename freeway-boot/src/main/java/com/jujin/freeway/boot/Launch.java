package com.jujin.freeway.boot;

import java.util.List;

/**
 * What a one-shot {@link FreewayApp#run(Launch, com.jujin.freeway.ioc.ModuleEx...)}
 * needs beyond the modules: the command-line arguments and whether SPI modules
 * are discovered.
 *
 * <p>It exists so the "start now" entry point has one shape instead of one
 * overload per knob: {@code run(modules)} is {@code run(Launch.defaults(), modules)},
 * and a caller that has arguments writes {@code run(Launch.of(args), modules)}.</p>
 *
 * @param args          command-line arguments ({@code --key=value}), empty by default
 * @param autoDiscovery whether {@code META-INF/services} modules are discovered
 */
public record Launch(List<String> args, boolean autoDiscovery) {

    public Launch {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** The default launch: no arguments, discovery on. */
    public static Launch defaults() {
        return new Launch(List.of(), true);
    }

    /** A launch with the given arguments and discovery on. */
    public static Launch of(String... args) {
        return new Launch(args == null ? List.of() : List.of(args), true);
    }

    /** The same launch with the given arguments. */
    public Launch withArgs(String... args) {
        return new Launch(args == null ? List.of() : List.of(args), autoDiscovery);
    }

    /** The same launch with discovery on or off. */
    public Launch withAutoDiscovery(boolean autoDiscovery) {
        return new Launch(args, autoDiscovery);
    }
}
