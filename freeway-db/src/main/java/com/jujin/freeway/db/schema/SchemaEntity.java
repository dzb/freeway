package com.jujin.freeway.db.schema;

import java.util.Objects;

/**
 * Registers a named group of entity classes for auto-schema management.
 * <p>
 * Contribute via {@code binder.contribute(SchemaEntity.class).add(...)}
 * inside a {@code ModuleEx.bind()}. {@code DbModule} collects all
 * contributed groups and executes {@link Schema#ensure}
 * at startup (before migration SQL files), logged by group name.
 *
 * <p>The schema dialect always comes from the {@link Database} — a group is a
 * logical label (typically the domain or module that owns the entities), not
 * a dialect boundary.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * binder.contribute(SchemaEntity.class)
 *     .add(SchemaEntity.of("core", User.class, Post.class))
 *     .add(SchemaEntity.of("audit", AuditLog.class));
 * }</pre>
 */
public final class SchemaEntity {

    private final String name;
    private final Class<?>[] entityTypes;

    private SchemaEntity(String name, Class<?>[] entityTypes) {
        this.name = Objects.requireNonNull(name, "name");
        this.entityTypes = Objects.requireNonNull(entityTypes, "entityTypes").clone();
    }

    /** Register a named group. */
    public static SchemaEntity of(String name, Class<?>... entityTypes) {
        return new SchemaEntity(name, entityTypes);
    }

    /** Logical group name (e.g. "core", "audit"). */
    public String name() { return name; }

    /** The entity classes in this group. */
    public Class<?>[] entityTypes() { return entityTypes.clone(); }
}
