package com.jujin.freeway.commons.json;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared scalar-leaf mapping used by both {@link JsonNormalizer} and
 * {@link JsonWriter}, so the two serialization paths cannot drift apart.
 *
 * <p>Returns the JSON-representable form (a {@link String}) for a supported
 * leaf type, or {@link #UNHANDLED} when the value is not a scalar leaf.
 */
final class JsonLeaves {

    static final Object UNHANDLED = new Object();

    private JsonLeaves() {}

    static Object stringForm(Object value) {
        return switch (value) {
            case CharSequence cs -> cs.toString();
            case Character c -> String.valueOf(c);
            case Enum<?> e -> e.name();
            case LocalDate d -> d.toString();
            case LocalTime t -> t.toString();
            case LocalDateTime dt -> dt.toString();
            case OffsetTime ot -> ot.toString();
            case OffsetDateTime odt -> odt.toString();
            case ZonedDateTime zdt -> zdt.toString();
            case Instant i -> i.toString();
            case UUID u -> u.toString();
            case Path p -> p.toString();
            case URI u -> u.toString();
            case URL u -> u.toString();
            case Locale l -> l.toLanguageTag();
            case Duration d -> d.toString();
            case Date d -> d.toInstant().toString();
            case File f -> f.getPath();
            case null, default -> UNHANDLED;
        };
    }
}
