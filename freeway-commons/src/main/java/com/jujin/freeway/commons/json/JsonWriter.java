package com.jujin.freeway.commons.json;

final class JsonWriter {
    private JsonWriter() {
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, false, 0);
        return out.toString();
    }

    static String stringifyPretty(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, true, 0);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value, boolean pretty, int indent) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof JsonObject object) {
            writeObject(out, object, pretty, indent);
            return;
        }
        if (value instanceof JsonArray array) {
            writeSequence(out, array, pretty, indent);
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
            out.append(number);
            return;
        }
        if (value instanceof Enum<?> e) {
            out.append(quote(e.name()));
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }

    private static void writeObject(StringBuilder out, JsonObject object, boolean pretty, int indent) {
        out.append('{');
        final boolean[] first = {true};
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
            writeValue(out, item, pretty, indent + 1);
        });
        if (pretty && !first[0]) {
            out.append('\n');
            indent(out, indent);
        }
        out.append('}');
    }

    private static void writeSequence(StringBuilder out, JsonArray array, boolean pretty, int indent) {
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
            writeValue(out, item, pretty, indent + 1);
        }
        if (pretty && !first) {
            out.append('\n');
            indent(out, indent);
        }
        out.append(']');
    }

    private static void indent(StringBuilder out, int level) {
        out.append("  ".repeat(Math.max(0, level)));
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
