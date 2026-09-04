package com.jujin.freeway.db;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerImpl;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

final class Coercions {
    private Coercions() {}

    static List<CoerceRule<?, ?>> jdbcDefaults() {
        return List.of(
            new CoerceRule<>(Date.class, LocalDate.class, Date::toLocalDate),
            new CoerceRule<>(Timestamp.class, LocalDateTime.class, Timestamp::toLocalDateTime),
            new CoerceRule<>(Timestamp.class, Instant.class, Timestamp::toInstant),
            new CoerceRule<>(Time.class, LocalTime.class, Time::toLocalTime)
        );
    }

    /**
     * A CoercerImpl carrying the JDBC rules (Date/Timestamp/Time →
     * java.time) that the DatabaseBuilder and IoC paths always contribute.
     */
    static Coercer jdbcCoercer() {
        CoercerImpl coercer = new CoercerImpl();
        for (var rule : jdbcDefaults()) {
            coercer.register(rule);
        }
        return coercer;
    }

}

