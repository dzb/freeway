package com.jujin.freeway2.commons.json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.jujin.freeway2.commons.bean.BeanIntrospector;
import com.jujin.freeway2.commons.bean.BeanPlan;
import com.jujin.freeway2.commons.bean.BeanProperty;
import com.jujin.freeway2.commons.scalar.Coercer;
import com.jujin.freeway2.commons.scalar.DefaultCoercer;

public final class JsonUtils {
    private static final DefaultCoercer DEFAULT_COERCER = new DefaultCoercer();

    private JsonUtils() {
    }

    public static JsonObject object() {
        return new JsonObject();
    }

    public static JsonArray array() {
        return new JsonArray();
    }

    public static Object parse(String text) {
        return new Parser(Objects.requireNonNull(text, "text")).parse();
    }

    public static JsonObject parseObject(String text) {
        Object value = parse(text);
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new IllegalArgumentException("JSON root is not an object");
    }

    public static JsonArray parseArray(String text) {
        Object value = parse(text);
        if (value instanceof JsonArray array) {
            return array;
        }
        throw new IllegalArgumentException("JSON root is not an array");
    }

    public static Object parse(InputStream input) {
        return new Parser(readText(input)).parse();
    }

    public static JsonObject parseObject(InputStream input) {
        return parseObject(readText(input));
    }

    public static JsonArray parseArray(InputStream input) {
        return parseArray(readText(input));
    }

    @SuppressWarnings("unchecked")
    public static <T> T coerce(Object value, Class<T> targetType) {
        return (T) coerce(value, (Type) targetType, DEFAULT_COERCER);
    }

    @SuppressWarnings("unchecked")
    public static <T> T coerce(Object value, Class<T> targetType, Coercer coercer) {
        return (T) coerce(value, (Type) targetType, coercer);
    }

    public static Object coerce(Object value, Type type) {
        return coerce(value, type, DEFAULT_COERCER);
    }

    public static Object coerce(Object value, Type type, Coercer coercer) {
        Objects.requireNonNull(coercer, "coercer");
        if (type instanceof Class<?> targetType) {
            Object plain = normalize(value);
            if (plain == null) {
                return DefaultCoercer.defaultValue(targetType);
            }
            if (targetType.isInstance(plain)) {
                return targetType.cast(plain);
            }
            if (plain instanceof JsonObject object && Map.class.isAssignableFrom(targetType)) {
                return coerceToMap(object, targetType);
            }
            if (plain instanceof JsonArray array && Collection.class.isAssignableFrom(targetType)) {
                return coerceToCollection(array, targetType);
            }
            if (plain instanceof JsonObject object && !targetType.isArray() && !targetType.isEnum()) {
                BeanPlan plan = BeanIntrospector.plan(targetType);
                return plan.record()
                    ? constructRecord(object, plan, coercer)
                    : constructBean(object, plan, coercer);
            }
            if (plain instanceof JsonArray array && targetType.isArray()) {
                return coerceToArray(array, targetType.getComponentType(), coercer);
            }
            return coercer.coerce(plain, targetType);
        }
        throw new IllegalArgumentException("Unsupported JSON target type: " + type.getTypeName());
    }

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, false, 0);
        return out.toString();
    }

    public static String stringifyPretty(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, true, 0);
        return out.toString();
    }

    private static String readText(InputStream input) {
        Objects.requireNonNull(input, "input");
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read JSON input", ex);
        }
    }

    /**
     * 将任意 Java 对象归一化为 JSON 兼容结构（{@link JsonObject}、{@link JsonArray}、标量）。
     * <p>
     * 注意：{@link Iterable} 统一归一化为 {@link JsonArray}，会丢失有序性/唯一性等集合语义。
     * 如需保留 {@link Set}、{@link java.util.Queue} 等语义，请在归一化前先显式转换。
     */
    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonObject || value instanceof JsonArray || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character character) {
            return String.valueOf(character);
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return normalizeArray(value);
        }
        if (value instanceof Iterable<?> iterable) {
            return normalizeIterable(iterable);
        }
        return normalizeBean(value);
    }

    private static Object constructRecord(JsonObject data, BeanPlan plan, Coercer coercer) {
        if (!plan.constructable()) {
            throw new IllegalArgumentException("Cannot construct record type: " + plan.type().getName());
        }
        Object[] args = new Object[plan.properties().size()];
        for (int i = 0; i < plan.properties().size(); i++) {
            BeanProperty property = plan.properties().get(i);
            args[i] = data.containsKey(property.name())
                ? coerce(data.get(property.name()), property.type(), coercer)
                : coerce(null, property.type(), coercer);
        }
        return plan.constructor().newInstance(args);
    }

    private static Object constructBean(JsonObject data, BeanPlan plan, Coercer coercer) {
        if (!plan.constructable()) {
            throw new IllegalArgumentException("Type " + plan.type().getName() + " has no no-arg constructor");
        }
        Object bean = plan.constructor().newInstance();
        for (BeanProperty property : plan.properties()) {
            if (!property.writable() || !data.containsKey(property.name())) {
                continue;
            }
            property.write(bean, coerce(data.get(property.name()), property.type(), coercer));
        }
        return bean;
    }

    private static Object coerceToArray(JsonArray array, Class<?> componentType, Coercer coercer) {
        Object result = Array.newInstance(componentType, array.size());
        for (int i = 0; i < array.size(); i++) {
            Array.set(result, i, coerce(array.get(i), componentType, coercer));
        }
        return result;
    }

    private static JsonObject normalizeBean(Object value) {
        BeanPlan plan = BeanIntrospector.plan(value.getClass());
        JsonObject object = object();
        for (BeanProperty property : plan.properties()) {
            object.put(property.name(), property.read(value));
        }
        return object;
    }

    private static JsonArray normalizeArray(Object array) {
        JsonArray result = array();
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            result.add(Array.get(array, i));
        }
        return result;
    }

    private static JsonArray normalizeIterable(Iterable<?> iterable) {
        JsonArray result = array();
        for (Object item : iterable) {
            result.add(item);
        }
        return result;
    }

    private static JsonObject normalizeMap(Map<?, ?> map) {
        JsonObject result = object();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static Object coerceToMap(JsonObject object, Class<?> targetType) {
        Object target = newCollectionInstance(targetType);
        if (target instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> mutable = (Map<Object, Object>) map;
            mutable.putAll(object.toMap());
            return target;
        }
        return object.toMap();
    }

    private static Object coerceToCollection(JsonArray array, Class<?> targetType) {
        Object target = newCollectionInstance(targetType);
        if (target instanceof Collection<?> collection) {
            @SuppressWarnings("unchecked")
            Collection<Object> mutable = (Collection<Object>) collection;
            mutable.addAll(array.toList());
            return target;
        }
        return array.toList();
    }

    private static Object newCollectionInstance(Class<?> targetType) {
        if (targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) {
            return null;
        }
        try {
            Constructor<?> constructor = targetType.getDeclaredConstructor();
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    static Object deepCopy(Object value) {
        if (value instanceof JsonObject object) {
            return object.toMap();
        }
        if (value instanceof JsonArray array) {
            return array.toList();
        }
        return value;
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

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = stripBom(text);
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (!eof()) {
                throw error("Unexpected trailing content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (eof()) {
                throw error("Unexpected end of input");
            }
            char c = peek();
            return switch (c) {
                case '{' -> parseObjectValue();
                case '[' -> parseArrayValue();
                case '"' -> parseString();
                case 't', 'f', 'n' -> parseLiteral();
                default -> parseNumber();
            };
        }

        private JsonObject parseObjectValue() {
            expect('{');
            JsonObject result = JsonUtils.object();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private JsonArray parseArrayValue() {
            expect('[');
            JsonArray result = JsonUtils.array();
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!eof()) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (eof()) {
                    throw error("Unterminated escape sequence");
                }
                c = next();
                switch (c) {
                    case '"', '\\', '/' -> out.append(c);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(parseUnicodeEscape());
                    default -> throw error("Unsupported escape sequence");
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > text.length()) {
                throw error("Unterminated unicode escape");
            }
            String hex = text.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException ex) {
                throw error("Invalid unicode escape");
            }
        }

        private Object parseLiteral() {
            if (match("true")) {
                return Boolean.TRUE;
            }
            if (match("false")) {
                return Boolean.FALSE;
            }
            if (match("null")) {
                return null;
            }
            throw error("Unknown literal");
        }

        private Number parseNumber() {
            int start = index;
            if (peek() == '-') {
                next();
            }
            readDigits();
            boolean decimal = false;
            if (!eof() && peek() == '.') {
                decimal = true;
                next();
                readDigits();
            }
            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                decimal = true;
                next();
                if (!eof() && (peek() == '+' || peek() == '-')) {
                    next();
                }
                readDigits();
            }
            String text = this.text.substring(start, index);
            try {
                if (decimal) {
                    return new BigDecimal(text);
                }
                long value = Long.parseLong(text);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return (int) value;
                }
                return value;
            } catch (NumberFormatException ex) {
                throw error("Invalid number");
            }
        }

        private void readDigits() {
            if (eof() || !Character.isDigit(peek())) {
                throw error("Expected digit");
            }
            while (!eof() && Character.isDigit(peek())) {
                next();
            }
        }

        private boolean match(String literal) {
            if (text.startsWith(literal, index)) {
                index += literal.length();
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!eof() && Character.isWhitespace(peek())) {
                index++;
            }
        }

        private void expect(char expected) {
            if (eof() || next() != expected) {
                throw error("Expected '" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (!eof() && peek() == expected) {
                index++;
                return true;
            }
            return false;
        }

        private char next() {
            return text.charAt(index++);
        }

        private char peek() {
            return text.charAt(index);
        }

        private boolean eof() {
            return index >= text.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at index " + index);
        }

        private static String stripBom(String text) {
            return Objects.requireNonNull(text, "text").startsWith("\ufeff") ? text.substring(1) : text;
        }
    }
}
