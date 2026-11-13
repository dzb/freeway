package com.jujin.freeway.db.dialect;

import java.util.Locale;

/**
 * H2 execution/compatibility mode. H2's MODE changes the SQL surface it
 * accepts; it does not turn H2 into the target database. In particular, H2
 * keeps its own introspection, DDL transaction behaviour and type system in
 * every mode.
 */
public enum H2Mode {
    /** H2's regular/native mode, including MODE=REGULAR and no MODE parameter. */
    NATIVE,
    /** H2's strict mode: standard SQL surface, no legacy aliases. */
    STRICT,
    /** H2's legacy mode. */
    LEGACY,
    POSTGRESQL,
    MYSQL,
    MARIADB,
    ORACLE,
    MSSQLSERVER,
    DB2,
    DERBY,
    HSQLDB;

    /** True for the modes where H2 applies MySQL/MariaDB syntax rules. */
    public boolean isMySqlFamily() {
        return this == MYSQL || this == MARIADB;
    }

    /** Canonical spelling accepted by H2's MODE URL parameter. */
    public String urlName() {
        return switch (this) {
            case NATIVE -> "REGULAR";
            case STRICT -> "STRICT";
            case LEGACY -> "LEGACY";
            case POSTGRESQL -> "PostgreSQL";
            case MYSQL -> "MySQL";
            case MARIADB -> "MariaDB";
            case ORACLE -> "Oracle";
            case MSSQLSERVER -> "MSSQLServer";
            case DB2 -> "DB2";
            case DERBY -> "Derby";
            case HSQLDB -> "HSQLDB";
        };
    }

    /**
     * Reads the MODE parameter from an H2 JDBC URL. Missing mode means native
     * H2; an unknown mode fails here instead of silently generating SQL for a
     * different mode.
     */
    public static H2Mode fromUrl(String url) {
        if (url == null || url.isBlank()) return NATIVE;
        String mode = null;
        for (String part : url.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase("MODE")) {
                mode = part.substring(eq + 1).trim();
            }
        }
        if (mode == null || mode.isEmpty()) return NATIVE;
        return switch (mode.toUpperCase(Locale.ROOT)) {
            case "REGULAR" -> NATIVE;
            case "STRICT" -> STRICT;
            case "LEGACY" -> LEGACY;
            case "POSTGRESQL" -> POSTGRESQL;
            case "MYSQL" -> MYSQL;
            case "MARIADB" -> MARIADB;
            case "ORACLE" -> ORACLE;
            case "MSSQLSERVER" -> MSSQLSERVER;
            case "DB2" -> DB2;
            case "DERBY" -> DERBY;
            case "HSQLDB" -> HSQLDB;
            default -> throw new IllegalArgumentException(
                "Unsupported H2 MODE '" + mode + "'. Supported modes: "
                    + "REGULAR, STRICT, LEGACY, PostgreSQL, MySQL, MariaDB, "
                    + "Oracle, MSSQLServer, DB2, Derby, HSQLDB");
        };
    }
}
