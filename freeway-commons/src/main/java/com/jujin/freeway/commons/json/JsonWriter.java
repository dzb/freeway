package com.jujin.freeway.commons.json;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import java.lang.reflect.Array;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Hand-written JSON serializer.
 *
 * <p>Writes both the lightweight {@link JsonObject}/{@link JsonArray} wrappers
 * and raw JDK/domain values ({@code Map}, {@code Iterable}, arrays, beans,
 * temporal types, {@code Optional}, ...) directly in a single pass — no
 * intermediate normalized tree is built for raw structures. Leaf conversions
 * share the scalar-leaf mapping with {@link JsonNormalizer} via
 * {@link JsonLeaves}, so the two paths cannot drift apart.
 *
 * <p><b>Bean serialization:</b> bean properties come from
 * {@link BeanPlan} — fields define the property set, a {@code getX()}/
 * {@code isX()} accessor is the preferred read path when present (transforming
 * getters are honored), and getter-only (computed) properties serialize as
 * read-only members. See {@link BeanPlan} for the full property model.
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
        // Shared scalar-leaf mapping — keeps leaf conversions consistent
        // with JsonNormalizer (single source of truth).
        Object leaf = JsonLeaves.leaf(value);
        if (leaf != JsonLeaves.UNHANDLED) {
            quote(out, (String) leaf);
            return;
        }
        if (value instanceof Optional<?> opt) {
            writeOptional(
                out,
                () -> opt.isPresent() ? opt.get() : null,
                pretty,
                indent,
                context
            );
            return;
        }
        if (value instanceof OptionalInt oi) {
            writeOptional(
                out,
                () -> oi.isPresent() ? oi.getAsInt() : null,
                pretty,
                indent,
                context
            );
            return;
        }
        if (value instanceof OptionalLong ol) {
            writeOptional(
                out,
                () -> ol.isPresent() ? ol.getAsLong() : null,
                pretty,
                indent,
                context
            );
            return;
        }
        if (value instanceof OptionalDouble od) {
            writeOptional(
                out,
                () -> od.isPresent() ? od.getAsDouble() : null,
                pretty,
                indent,
                context
            );
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

    /**
     * Writes the value supplied by {@code unwrapped} one level deeper than
     * the current indent — the shared shape of the four Optional branches
     * in {@link #writeValue}. The supplier keeps the {@code isPresent} check
     * lazy so an empty optional is never unwrapped.
     */
    private static void writeOptional(
        StringBuilder out,
        Supplier<Object> unwrapped,
        boolean pretty,
        int indent,
        Context context
    ) {
        writeValue(out, unwrapped.get(), pretty, indent + 1, context);
    }

    private static void writeObject(
        StringBuilder out,
        JsonObject object,
        boolean pretty,
        int indent,
        Context context
    ) {
        withCycleGuard(out, object, pretty, indent, context, (o, g, p, i, c) -> {
            o.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((JsonObject) g).entries()) {
                first = writePrefix(o, first, p, i + 1);
                quote(o, entry.getKey());
                o.append(':');
                if (p) {
                    o.append(' ');
                }
                writeValue(o, entry.getValue(), p, i + 1, c);
            }
            writeSuffix(o, p, first, i);
            o.append('}');
        });
    }

    private static void writeMap(
        StringBuilder out,
        Map<?, ?> map,
        boolean pretty,
        int indent,
        Context context
    ) {
        withCycleGuard(out, map, pretty, indent, context, (o, g, p, i, c) -> {
            o.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) g).entrySet()) {
                first = writePrefix(o, first, p, i + 1);
                Object key = entry.getKey();
                if (key == null) {
                    throw new IllegalArgumentException(
                        "Cannot serialize a Map with null keys to JSON"
                    );
                }
                quote(o, String.valueOf(key));
                o.append(':');
                if (p) {
                    o.append(' ');
                }
                writeValue(o, entry.getValue(), p, i + 1, c);
            }
            writeSuffix(o, p, first, i);
            o.append('}');
        });
    }

    private static void writeSequence(
        StringBuilder out,
        JsonArray array,
        boolean pretty,
        int indent,
        Context context
    ) {
        withCycleGuard(out, array, pretty, indent, context, (o, g, p, i, c) -> {
            o.append('[');
            boolean first = true;
            for (int idx = 0; idx < ((JsonArray) g).size(); idx++) {
                first = writePrefix(o, first, p, i + 1);
                writeValue(o, ((JsonArray) g).get(idx), p, i + 1, c);
            }
            writeSuffix(o, p, first, i);
            o.append(']');
        });
    }

    private static void writeArray(
        StringBuilder out,
        Object array,
        boolean pretty,
        int indent,
        Context context
    ) {
        withCycleGuard(out, array, pretty, indent, context, (o, g, p, i, c) -> {
            o.append('[');
            int length = Array.getLength(g);
            boolean first = true;
            for (int idx = 0; idx < length; idx++) {
                first = writePrefix(o, first, p, i + 1);
                writeValue(o, Array.get(g, idx), p, i + 1, c);
            }
            writeSuffix(o, p, first, i);
            o.append(']');
        });
    }

    private static void writeIterable(
        StringBuilder out,
        Iterable<?> iterable,
        boolean pretty,
        int indent,
        Context context
    ) {
        withCycleGuard(out, iterable, pretty, indent, context, (o, g, p, i, c) -> {
            o.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) g) {
                first = writePrefix(o, first, p, i + 1);
                writeValue(o, item, p, i + 1, c);
            }
            writeSuffix(o, p, first, i);
            o.append(']');
        });
    }

    private static void writeBean(
        StringBuilder out,
        Object value,
        boolean pretty,
        int indent,
        Context context
    ) {
        withCycleGuard(out, value, pretty, indent, context, (o, g, p, i, c) -> {
            BeanPlan plan = BeanIntrospector.plan(g.getClass());
            o.append('{');
            boolean first = true;
            for (BeanProperty property : plan.properties()) {
                first = writePrefix(o, first, p, i + 1);
                quote(o, property.name());
                o.append(':');
                if (p) {
                    o.append(' ');
                }
                writeValue(o, property.read(g), p, i + 1, c);
            }
            writeSuffix(o, p, first, i);
            o.append('}');
        });
    }

    /**
     * Emits the per-element prefix shared by every container skeleton: a
     * comma before every element but the first, then the pretty-mode line
     * break and indentation. Returns the updated {@code first} flag.
     */
    private static boolean writePrefix(
        StringBuilder out,
        boolean first,
        boolean pretty,
        int indent
    ) {
        if (first) {
            first = false;
        } else {
            out.append(',');
        }
        if (pretty) {
            out.append('\n');
            indent(out, indent);
        }
        return first;
    }

    /**
     * Emits the pretty-mode line break before the closing bracket of a
     * non-empty container.
     */
    private static void writeSuffix(
        StringBuilder out,
        boolean pretty,
        boolean first,
        int indent
    ) {
        if (pretty && !first) {
            out.append('\n');
            indent(out, indent);
        }
    }

    /**
     * Runs {@code work} with {@code guard} registered as active in
     * {@code context}, releasing it in a finally block so the cycle guard is
     * cleared even when the work throws. Unlike the {@link Supplier}-based
     * {@code JsonNormalizer} twin, {@code work} receives the shared state as
     * parameters, keeping every call-site lambda non-capturing — a per
     * container write must not allocate.
     */
    private static void withCycleGuard(
        StringBuilder out,
        Object guard,
        boolean pretty,
        int indent,
        Context context,
        GuardedWork work
    ) {
        context.enter(guard);
        try {
            work.run(out, guard, pretty, indent, context);
        } finally {
            context.exit(guard);
        }
    }

    @FunctionalInterface
    private interface GuardedWork {
        void run(
            StringBuilder out,
            Object guard,
            boolean pretty,
            int indent,
            Context context
        );
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
