package com.jujin.freeway.commons.json;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import java.io.File;
import java.lang.reflect.Array;
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
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Hand-written JSON serializer.
 *
 * <p>Writes both the lightweight {@link JsonObject}/{@link JsonArray} wrappers
 * and raw JDK/domain values ({@code Map}, {@code Iterable}, arrays, beans,
 * temporal types, {@code Optional}, ...) directly in a single pass — no
 * intermediate normalized tree is built for raw structures. Leaf conversions
 * mirror {@link JsonNormalizer} exactly.
 */
final class JsonWriter {

    private static final int MAX_DEPTH = JsonParser.MAX_DEPTH;

    private JsonWriter() {}

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, false, 0, new Context());
        return out.toString();
    }

    static String stringifyPretty(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, true, 0, new Context());
        return out.toString();
    }

    private static void writeValue(
        StringBuilder out,
        Object value,
        boolean pretty,
        int indent,
        Context context
    ) {
        if (indent > MAX_DEPTH) {
            throw new IllegalArgumentException(
                "JSON value nesting too deep (max " + MAX_DEPTH + " levels)"
            );
        }
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof JsonObject object) {
            writeObject(out, object, pretty, indent, context);
            return;
        }
        if (value instanceof JsonArray array) {
            writeSequence(out, array, pretty, indent, context);
            return;
        }
        if (value instanceof String s) {
            quote(out, s);
            return;
        }
        if (value instanceof Character c) {
            quote(out, String.valueOf(c));
            return;
        }
        if (value instanceof Boolean b) {
            out.append(b);
            return;
        }
        if (value instanceof Number number) {
            writeNumber(out, number);
            return;
        }
        if (value instanceof CharSequence cs) {
            quote(out, cs.toString());
            return;
        }
        if (value instanceof Enum<?> e) {
            quote(out, e.name());
            return;
        }
        // Leaf conversions mirroring JsonNormalizer (same dispatch order).
        if (value instanceof LocalDate d) {
            quote(out, d.toString());
            return;
        }
        if (value instanceof LocalTime t) {
            quote(out, t.toString());
            return;
        }
        if (value instanceof LocalDateTime dt) {
            quote(out, dt.toString());
            return;
        }
        if (value instanceof OffsetTime ot) {
            quote(out, ot.toString());
            return;
        }
        if (value instanceof OffsetDateTime odt) {
            quote(out, odt.toString());
            return;
        }
        if (value instanceof ZonedDateTime zdt) {
            quote(out, zdt.toString());
            return;
        }
        if (value instanceof Instant i) {
            quote(out, i.toString());
            return;
        }
        if (value instanceof UUID u) {
            quote(out, u.toString());
            return;
        }
        if (value instanceof Path p) {
            quote(out, p.toString());
            return;
        }
        if (value instanceof Optional<?> opt) {
            writeValue(
                out,
                opt.isPresent() ? opt.get() : null,
                pretty,
                indent + 1,
                context
            );
            return;
        }
        if (value instanceof OptionalInt oi) {
            writeValue(
                out,
                oi.isPresent() ? oi.getAsInt() : null,
                pretty,
                indent + 1,
                context
            );
            return;
        }
        if (value instanceof OptionalLong ol) {
            writeValue(
                out,
                ol.isPresent() ? ol.getAsLong() : null,
                pretty,
                indent + 1,
                context
            );
            return;
        }
        if (value instanceof OptionalDouble od) {
            writeValue(
                out,
                od.isPresent() ? od.getAsDouble() : null,
                pretty,
                indent + 1,
                context
            );
            return;
        }
        if (value instanceof URI u) {
            quote(out, u.toString());
            return;
        }
        if (value instanceof URL u) {
            quote(out, u.toString());
            return;
        }
        if (value instanceof Locale l) {
            quote(out, l.toLanguageTag());
            return;
        }
        if (value instanceof Duration d) {
            quote(out, d.toString());
            return;
        }
        if (value instanceof Date d) {
            quote(out, d.toInstant().toString());
            return;
        }
        if (value instanceof File f) {
            quote(out, f.getPath());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeMap(out, map, pretty, indent, context);
            return;
        }
        if (value.getClass().isArray()) {
            writeArray(out, value, pretty, indent, context);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            writeIterable(out, iterable, pretty, indent, context);
            return;
        }
        writeBean(out, value, pretty, indent, context);
    }

    private static void writeObject(
        StringBuilder out,
        JsonObject object,
        boolean pretty,
        int indent,
        Context context
    ) {
        context.enter(object);
        try {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : object.entries()) {
                if (first) {
                    first = false;
                } else {
                    out.append(',');
                }
                if (pretty) {
                    out.append('\n');
                    indent(out, indent + 1);
                }
                quote(out, entry.getKey());
                out.append(':');
                if (pretty) {
                    out.append(' ');
                }
                writeValue(out, entry.getValue(), pretty, indent + 1, context);
            }
            if (pretty && !first) {
                out.append('\n');
                indent(out, indent);
            }
            out.append('}');
        } finally {
            context.exit(object);
        }
    }

    private static void writeMap(
        StringBuilder out,
        Map<?, ?> map,
        boolean pretty,
        int indent,
        Context context
    ) {
        context.enter(map);
        try {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (first) {
                    first = false;
                } else {
                    out.append(',');
                }
                if (pretty) {
                    out.append('\n');
                    indent(out, indent + 1);
                }
                Object key = entry.getKey();
                if (key == null) {
                    throw new IllegalArgumentException(
                        "Cannot serialize a Map with null keys to JSON"
                    );
                }
                quote(out, String.valueOf(key));
                out.append(':');
                if (pretty) {
                    out.append(' ');
                }
                writeValue(out, entry.getValue(), pretty, indent + 1, context);
            }
            if (pretty && !first) {
                out.append('\n');
                indent(out, indent);
            }
            out.append('}');
        } finally {
            context.exit(map);
        }
    }

    private static void writeSequence(
        StringBuilder out,
        JsonArray array,
        boolean pretty,
        int indent,
        Context context
    ) {
        context.enter(array);
        try {
            out.append('[');
            boolean first = true;
            for (int i = 0; i < array.size(); i++) {
                Object item = array.get(i);
                if (first) {
                    first = false;
                } else {
                    out.append(',');
                }
                if (pretty) {
                    out.append('\n');
                    indent(out, indent + 1);
                }
                writeValue(out, item, pretty, indent + 1, context);
            }
            if (pretty && !first) {
                out.append('\n');
                indent(out, indent);
            }
            out.append(']');
        } finally {
            context.exit(array);
        }
    }

    private static void writeArray(
        StringBuilder out,
        Object array,
        boolean pretty,
        int indent,
        Context context
    ) {
        context.enter(array);
        try {
            out.append('[');
            int length = Array.getLength(array);
            boolean first = true;
            for (int i = 0; i < length; i++) {
                Object item = Array.get(array, i);
                if (first) {
                    first = false;
                } else {
                    out.append(',');
                }
                if (pretty) {
                    out.append('\n');
                    indent(out, indent + 1);
                }
                writeValue(out, item, pretty, indent + 1, context);
            }
            if (pretty && !first) {
                out.append('\n');
                indent(out, indent);
            }
            out.append(']');
        } finally {
            context.exit(array);
        }
    }

    private static void writeIterable(
        StringBuilder out,
        Iterable<?> iterable,
        boolean pretty,
        int indent,
        Context context
    ) {
        context.enter(iterable);
        try {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (first) {
                    first = false;
                } else {
                    out.append(',');
                }
                if (pretty) {
                    out.append('\n');
                    indent(out, indent + 1);
                }
                writeValue(out, item, pretty, indent + 1, context);
            }
            if (pretty && !first) {
                out.append('\n');
                indent(out, indent);
            }
            out.append(']');
        } finally {
            context.exit(iterable);
        }
    }

    private static void writeBean(
        StringBuilder out,
        Object value,
        boolean pretty,
        int indent,
        Context context
    ) {
        context.enter(value);
        try {
            BeanPlan plan = BeanIntrospector.plan(value.getClass());
            out.append('{');
            boolean first = true;
            for (BeanProperty property : plan.properties()) {
                if (first) {
                    first = false;
                } else {
                    out.append(',');
                }
                if (pretty) {
                    out.append('\n');
                    indent(out, indent + 1);
                }
                quote(out, property.name());
                out.append(':');
                if (pretty) {
                    out.append(' ');
                }
                writeValue(
                    out,
                    property.read(value),
                    pretty,
                    indent + 1,
                    context
                );
            }
            if (pretty && !first) {
                out.append('\n');
                indent(out, indent);
            }
            out.append('}');
        } finally {
            context.exit(value);
        }
    }

    private static void indent(StringBuilder out, int level) {
        out.append("  ".repeat(Math.max(0, level)));
    }

    private static void writeNumber(StringBuilder out, Number number) {
        if (number instanceof Double value && !Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON number must be finite");
        }
        if (number instanceof Float value && !Float.isFinite(value)) {
            throw new IllegalArgumentException("JSON number must be finite");
        }
        out.append(number);
    }

    private static void quote(StringBuilder out, String value) {
        int length = value.length();
        out.append('"');
        int last = 0; // start of the unescaped run not yet copied
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) {
                // Copy the safe prefix, then the escape.
                out.append(value, last, i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> out.append(CONTROL_ESCAPES[c]);
                }
                last = i + 1;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                    i++; // valid surrogate pair — both stay raw
                } else {
                    // Lone high surrogate — escape so UTF-8 encoding cannot corrupt it.
                    out.append(value, last, i).append("\\u");
                    appendHex(out, c);
                    last = i + 1;
                }
            } else if (Character.isLowSurrogate(c)) {
                // Lone low surrogate — escape.
                out.append(value, last, i).append("\\u");
                appendHex(out, c);
                last = i + 1;
            }
        }
        out.append(value, last, length);
        out.append('"');
    }

    private static void appendHex(StringBuilder out, char c) {
        out.append(HEX[c >>> 12])
           .append(HEX[(c >>> 8) & 0xF])
           .append(HEX[(c >>> 4) & 0xF])
           .append(HEX[c & 0xF]);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Precomputed {@code \\uXXXX} escapes for control characters U+0000..U+001F. */
    private static final String[] CONTROL_ESCAPES = new String[0x20];

    static {
        for (int i = 0; i < CONTROL_ESCAPES.length; i++) {
            CONTROL_ESCAPES[i] = String.format("\\u%04x", i);
        }
    }

    private static final class Context {

        private final IdentityHashMap<Object, Boolean> active =
            new IdentityHashMap<>();

        void enter(Object value) {
            if (active.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Cyclic JSON value");
            }
        }

        void exit(Object value) {
            active.remove(value);
        }
    }
}
