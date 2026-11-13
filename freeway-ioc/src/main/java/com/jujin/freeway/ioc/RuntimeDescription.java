package com.jujin.freeway.ioc;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A read-only, machine-readable snapshot of a container's composition.
 *
 * <p>The snapshot contains metadata only: module declarations, binding
 * identity/scope/markers, and contribution ids/order. It never exposes a
 * service instance, a configuration value or a secret, and building it does
 * not realize lazy services. It exists so tools and coding agents can inspect
 * what the container is made of without parsing startup logs or reflecting
 * into framework internals.</p>
 *
 * @param application the composition root's presentation name
 * @param modules     the declared module tree below the root
 * @param bindings    binding metadata in registration order
 * @param extensions  contribution metadata, ordered by extension-point name
 */
public record RuntimeDescription(
    String application,
    List<ModuleDescription> modules,
    List<BindingDescription> bindings,
    List<ExtensionDescription> extensions
) {

    public RuntimeDescription {
        application = Objects.requireNonNull(application, "application");
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
    }

    /** One module node in the composition tree. */
    public record ModuleDescription(
        String name,
        String type,
        List<ModuleDescription> children
    ) {
        public ModuleDescription {
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
        }
    }

    /** Metadata of one registered binding; no service value is held here. */
    public record BindingDescription(
        String type,
        String id,
        Scope scope,
        boolean primary,
        Set<String> markers
    ) {
        public BindingDescription {
            type = Objects.requireNonNull(type, "type");
            id = Objects.requireNonNull(id, "id");
            scope = Objects.requireNonNull(scope, "scope");
            markers = Set.copyOf(Objects.requireNonNull(markers, "markers"));
        }
    }

    /** One extension point and its contributions in resolved order. */
    public record ExtensionDescription(
        String entryType,
        List<ContributionDescription> contributions
    ) {
        public ExtensionDescription {
            entryType = Objects.requireNonNull(entryType, "entryType");
            contributions = List.copyOf(
                Objects.requireNonNull(contributions, "contributions"));
        }
    }

    /**
     * One contribution. {@code id} is null for an unnamed contribution;
     * {@code valueType} is the value's concrete class, never the value.
     */
    public record ContributionDescription(
        String id,
        String valueType,
        List<String> before,
        List<String> after,
        List<String> beforeIfPresent,
        List<String> afterIfPresent
    ) {
        public ContributionDescription {
            valueType = Objects.requireNonNull(valueType, "valueType");
            before = List.copyOf(Objects.requireNonNull(before, "before"));
            after = List.copyOf(Objects.requireNonNull(after, "after"));
            beforeIfPresent = List.copyOf(
                Objects.requireNonNull(beforeIfPresent, "beforeIfPresent"));
            afterIfPresent = List.copyOf(
                Objects.requireNonNull(afterIfPresent, "afterIfPresent"));
        }
    }
}
