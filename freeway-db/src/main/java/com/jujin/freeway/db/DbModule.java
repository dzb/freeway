package com.jujin.freeway.db;

import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.DatabaseHubImpl;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;
import com.jujin.freeway.commons.coercion.CoerceRule;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public final class DbModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(Database.class).to(DatabaseImpl.class);
        binder.bind(DatabaseHub.class).to(DatabaseHubImpl.class);
        var rules = binder.contribute((Class) CoerceRule.class);
        rules.add(new CoerceRule<>(String.class, Duration.class, DbModule::parseDuration));
        rules.add(new CoerceRule<>(Date.class, LocalDate.class, d -> ((Date) d).toLocalDate()));
        rules.add(new CoerceRule<>(Timestamp.class, LocalDateTime.class, t -> ((Timestamp) t).toLocalDateTime()));
        rules.add(new CoerceRule<>(Timestamp.class, Instant.class, t -> ((Timestamp) t).toInstant()));
        rules.add(new CoerceRule<>(Time.class, LocalTime.class, t -> ((Time) t).toLocalTime()));
    }

    private static Duration parseDuration(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2).trim()));
            }
            if (value.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            if (value.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            return Duration.ofMillis(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid duration: " + text, e);
        }
    }
}
