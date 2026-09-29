package com.jujin.freeway.db.schema;

import java.util.Locale;
import java.util.Objects;

/**
 * How the Schema layer behaves at startup ({@code freeway.db.schema.mode}).
 *
 * <p>Three postures, one key: {@code auto} converges the database toward the
 * entities (development: zero-friction iteration), {@code validate} compares
 * the entities against the migrated database and fails startup on drift
 * (production: migrations own all DDL), {@code off} skips the layer.
 */
public enum SchemaMode {
    AUTO,
    VALIDATE,
    OFF;

    /**
     * Parses a configured value (case-insensitive, surrounding whitespace
     * ignored). Anything else fails with the valid values named.
     */
    public static SchemaMode of(String value) {
        Objects.requireNonNull(value, "value");
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "auto":
                return AUTO;
            case "validate":
                return VALIDATE;
            case "off":
                return OFF;
            default:
                throw new IllegalArgumentException(
                    "Unknown schema mode '" + value
                        + "' — expected one of auto, validate, off (freeway.db.schema.mode)");
        }
    }
}
