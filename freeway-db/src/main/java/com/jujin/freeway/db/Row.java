package com.jujin.freeway.db;

import com.jujin.freeway.commons.coercion.Coercer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A schema-less query result row with type-safe column access.
 *
 * <p>Supports coercion from the raw JDBC value to common Java types:
 * {@link #string(String)}, {@link #integer(String)}, {@link #longValue(String)},
 * {@link #bool(String)}, {@link #decimal(String)}, {@link #date(String)},
 * {@link #dateTime(String)}, {@link #instant(String)}, {@link #uuid(String)},
 * and more.
 *
 * <p>Example:
 * <pre>{@code
 * Row row = db.query("SELECT id, name FROM users WHERE id = ?", 1).list(Row.class).get(0);
 * long id = row.longValue("id");
 * String name = row.string("name");
 * }</pre>
 */
public final class Row {
    private final Map<String, Object> values;
    private final Map<String, Object> lowerIndex;
    private final Coercer coercer;

    public Row(Map<String, Object> values, Coercer coercer) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        Map<String, Object> index = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null) {
                index.put(key.toLowerCase(Locale.ROOT), value);
            }
        });
        this.lowerIndex = Collections.unmodifiableMap(index);
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    public Set<String> columns() {
        return values.keySet();
    }

    public Object raw(String col) {
        Object value = values.get(col);
        if (value == null && col != null) {
            // Column labels are normalized to lowercase by the row mapper —
            // tolerate any casing from callers instead of silently returning null.
            value = lowerIndex.get(col.toLowerCase(Locale.ROOT));
        }
        return value;
    }

    public <T> T get(String col, Class<T> type) {
        return coercer.coerce(raw(col), type);
    }

    public String string(String col) { return get(col, String.class); }

    public Integer integer(String col) { return get(col, Integer.class); }

    public Long longValue(String col) { return get(col, Long.class); }

    public Boolean booleanValue(String col) { return get(col, Boolean.class); }

    public BigDecimal decimal(String col) { return get(col, BigDecimal.class); }

    public BigInteger bigInt(String col) { return get(col, BigInteger.class); }

    public LocalDate date(String col) { return get(col, LocalDate.class); }

    public LocalDateTime dateTime(String col) { return get(col, LocalDateTime.class); }

    public LocalTime time(String col) { return get(col, LocalTime.class); }

    public Instant instant(String col) { return get(col, Instant.class); }

    public UUID uuid(String col) { return get(col, UUID.class); }

    public byte[] bytes(String col) { return get(col, byte[].class); }

    @Override
    public String toString() {
        return values.toString();
    }
}
