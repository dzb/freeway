package com.jujin.freeway.commons.json;

import java.util.IdentityHashMap;

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
            out.append(quote(s));
            return;
        }
        if (value instanceof Character c) {
            out.append(quote(String.valueOf(c)));
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
        if (value instanceof Enum<?> e) {
            out.append(quote(e.name()));
            return;
        }
        throw new IllegalArgumentException(
            "Unsupported JSON value: " + value.getClass().getName()
        );
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
            final boolean[] first = { true };
            object.forEach((key, item) -> {
                if (first[0]) {
                    first[0] = false;
                } else {
                    out.append(',');
                }
                if (pretty) {
                    out.append('\n');
                    indent(out, indent + 1);
                }
                out.append(quote(String.valueOf(key)));
                out.append(':');
                if (pretty) {
                    out.append(' ');
                }
                writeValue(out, item, pretty, indent + 1, context);
            });
            if (pretty && !first[0]) {
                out.append('\n');
                indent(out, indent);
            }
            out.append('}');
        } finally {
            context.exit(object);
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

    private static String quote(String value) {
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) {
                // Slow path — at least one character needs escaping.
                StringBuilder out = new StringBuilder(length + 16);
                out.append('"').append(value, 0, i);
                for (; i < length; i++) {
                    char ch = value.charAt(i);
                    switch (ch) {
                        case '"' -> out.append("\\\"");
                        case '\\' -> out.append("\\\\");
                        case '\b' -> out.append("\\b");
                        case '\f' -> out.append("\\f");
                        case '\n' -> out.append("\\n");
                        case '\r' -> out.append("\\r");
                        case '\t' -> out.append("\\t");
                        default -> {
                            if (ch < 0x20) {
                                out.append(CONTROL_ESCAPES[ch]);
                            } else {
                                out.append(ch);
                            }
                        }
                    }
                }
                out.append('"');
                return out.toString();
            }
        }
        // Fast path — nothing to escape.
        return '"' + value + '"';
    }

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
