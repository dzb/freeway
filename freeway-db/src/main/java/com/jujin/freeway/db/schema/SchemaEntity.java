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
 * <h3>Usage</h3>
 * <pre>{@code
 * // Default dialect
 * binder.contribute(SchemaEntity.class)
 *     .add(SchemaEntity.of("core", User.class, Post.class))
 *     .add(SchemaEntity.of("audit", AuditLog.class));
 *
 * // Per-group dialect override
 * binder.contribute(SchemaEntity.class)
 *     .add(SchemaEntity.of("core", new PostgresDialect(), UserProfile.class));
 * }</pre>
 *
 * <h3>Naming</h3>
 * The group name is a logical label — typically the domain or module
 * that owns the entities. It appears in startup logs and can be used
 * to reason about which module contributed which table definitions.
 */
public final class SchemaEntity {

    private final String name;
    private final Dialect dialect;
    private final Class<?>[] entityTypes;

    private SchemaEntity(String name, Dialect dialect, Class<?>[] entityTypes) {
        this.name = Objects.requireNonNull(name, "name");
        this.dialect = dialect;
        this.entityTypes = Objects.requireNonNull(entityTypes, "entityTypes").clone();
    }

    /** Register a named group with the default dialect. */
    public static SchemaEntity of(String name, Class<?>... entityTypes) {
        return new SchemaEntity(name, null, entityTypes);
    }

    /** Register a named group with an explicit dialect. */
    public static SchemaEntity of(String name, Dialect dialect, Class<?>... entityTypes) {
        return new SchemaEntity(name, dialect, entityTypes);
    }

    /** Logical group name (e.g. "core", "audit"). */
    public String name() { return name; }

    /** Per-group dialect override, or {@code null} to use the global dialect. */
    public Dialect dialect() { return dialect; }

    /** The entity classes in this group. */
    public Class<?>[] entityTypes() { return entityTypes.clone(); }
}
