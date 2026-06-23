package com.jujin.freeway.db;

/**
 * Configuration keys for the DB module.
 * All keys share the {@code freeway.db} namespace.
 */
public final class DbConfigKeys {
    private DbConfigKeys() {}

    static final String PREFIX = "freeway.db";

    // ── Connection ────────────────────────────────────────────

    public static final String URL      = PREFIX + ".url";
    public static final String USERNAME = PREFIX + ".username";
    public static final String PASSWORD = PREFIX + ".password";

    // ── Pool ──────────────────────────────────────────────────

    public static final String POOL_MAX_SIZE            = PREFIX + ".pool.max-size";
    public static final String POOL_MIN_IDLE            = PREFIX + ".pool.min-idle";
    public static final String POOL_CONNECTION_TIMEOUT  = PREFIX + ".pool.connection-timeout";
    public static final String POOL_MAX_LIFETIME        = PREFIX + ".pool.max-lifetime";
    public static final String POOL_MAX_IDLE_TIME       = PREFIX + ".pool.max-idle-time";
    public static final String POOL_CLEAN_INTERVAL      = PREFIX + ".pool.clean-interval";
    public static final String POOL_HEALTH_CHECK_QUERY  = PREFIX + ".pool.health-check-query";
    public static final String POOL_HEALTH_CHECK_TIMEOUT = PREFIX + ".pool.health-check-timeout";
    public static final String QUERY_TIMEOUT            = PREFIX + ".query-timeout";

    // ── Migration ─────────────────────────────────────────────

    public static final String MIGRATION_ENABLED = PREFIX + ".migration.enabled";
    public static final String MIGRATION_PATH    = PREFIX + ".migration.path";
    public static final String MIGRATION_TABLE   = PREFIX + ".migration.table";

    // ── Schema ────────────────────────────────────────────────

    public static final String SCHEMA_AUTO   = PREFIX + ".schema.auto";
    public static final String SCHEMA_GROUPS = PREFIX + ".schema.groups";

    // ── Dialect ───────────────────────────────────────────────

    public static final String DIALECT = PREFIX + ".dialect";
}
