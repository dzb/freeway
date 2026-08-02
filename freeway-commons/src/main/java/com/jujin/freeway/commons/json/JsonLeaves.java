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

    static Object leaf(Object value) {
        if (value instanceof CharSequence cs) return cs.toString();
        if (value instanceof Character c) return String.valueOf(c);
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof LocalDate d) return d.toString();
        if (value instanceof LocalTime t) return t.toString();
        if (value instanceof LocalDateTime dt) return dt.toString();
        if (value instanceof OffsetTime ot) return ot.toString();
        if (value instanceof OffsetDateTime odt) return odt.toString();
        if (value instanceof ZonedDateTime zdt) return zdt.toString();
        if (value instanceof Instant i) return i.toString();
        if (value instanceof UUID u) return u.toString();
        if (value instanceof Path p) return p.toString();
        if (value instanceof URI u) return u.toString();
        if (value instanceof URL u) return u.toString();
        if (value instanceof Locale l) return l.toLanguageTag();
        if (value instanceof Duration d) return d.toString();
        if (value instanceof Date d) return d.toInstant().toString();
        if (value instanceof File f) return f.getPath();
        return UNHANDLED;
    }
}
