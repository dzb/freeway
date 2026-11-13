package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.RuntimeDescription;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A read-only metadata snapshot of a started application: lifecycle state,
 * active profiles, configured key names and the container's composition.
 *
 * <p>It contains no configuration values and no service instances; key names
 * are included because they are the contract an operator or coding agent needs
 * to inspect without leaking secrets.</p>
 */
public record AppRuntimeDescription(
    AppState state,
    List<String> profiles,
    Set<String> configKeys,
    RuntimeDescription container
) {

    public AppRuntimeDescription {
        state = Objects.requireNonNull(state, "state");
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        configKeys = Set.copyOf(Objects.requireNonNull(configKeys, "configKeys"));
        container = Objects.requireNonNull(container, "container");
    }
}
