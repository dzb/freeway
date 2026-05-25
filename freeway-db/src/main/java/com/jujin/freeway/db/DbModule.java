package com.jujin.freeway.db;

import com.jujin.freeway.db.internal.DatabaseImpl;
import com.jujin.freeway.db.internal.DatabaseHubImpl;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;
import com.jujin.freeway.commons.scalar.CoercionRule;
import java.time.Duration;

public final class DbModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(Database.class).to(DatabaseImpl.class);
        binder.bind(DatabaseHub.class).to(DatabaseHubImpl.class);
        binder.contribute((Class) CoercionRule.class).add(
            new CoercionRule<>(String.class, Duration.class, DbModule::parseDuration)
        );
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
