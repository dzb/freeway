package com.jujin.freeway.commons.json;

import java.io.ByteArrayInputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilsTest {
    private static final CoercerDefault FALLBACK_COERCER = new CoercerDefault();
    private static final Coercer COERCER = new Coercer() {
        @Override
        public <T> T coerce(Object input, Class<T> targetType) {
            return JsonUtilsTest.coerce(input, targetType);
        }
    };

    @Test
    void parsesNestedObjectsAndArrays() {
        String json = """
            {
              "app": {
                "name": "Freeway",
                "tags": ["a", "b"]
              },
              "server": {
                "port": 8080
              }
            }
            """;

        JsonObject object = JsonUtils.parseObject(json);

        JsonObject app = (JsonObject) object.get("app");
        JsonObject server = (JsonObject) object.get("server");

        assertEquals("Freeway", app.get("name"));
        assertEquals("a", ((JsonArray) app.get("tags")).get(0));
        assertEquals("b", ((JsonArray) app.get("tags")).get(1));
        assertEquals(8080, server.get("port"));
    }

    @Test
    void buildsChainableObjectsAndArrays() {
        JsonObject root = JsonUtils.object();
        JsonObject app = root.object("app");
        app.put("name", "Freeway");
        JsonArray tags = app.array("tags");
        tags.add("a").add("b");
        root.object("server").put("port", 8080);

        assertEquals("Freeway", ((JsonObject) root.get("app")).get("name"));
        assertEquals(8080, ((JsonObject) root.get("server")).get("port"));
        assertTrue(((JsonArray) ((JsonObject) root.get("app")).get("tags")).toList().size() == 2);
    }

    @Test
    void parsesFromStream() {
        byte[] bytes = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);

        JsonObject object = JsonUtils.parseObject(new ByteArrayInputStream(bytes));

        assertEquals(1, object.get("value"));
    }

    @Test
    void rejectsOversizedStreamInput() {
        var input = new CountingInputStream(JsonParser.MAX_INPUT_BYTES + 1);

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> JsonUtils.parse(input)
        );

        assertTrue(ex.getCause() != null && ex.getCause().getMessage().contains("JSON input too large"));
        assertTrue(input.closed);
    }

    @Test
    void parsesArray() {
        JsonArray array = JsonUtils.parseArray("[1, \"two\", true]");

        assertEquals(1, array.get(0));
        assertEquals("two", array.get(1));
        assertEquals(true, array.get(2));
    }

    @Test
    void stringifiesObjectsAndArrays() {
        JsonObject value = JsonUtils.object();
        value.put("name", "Freeway");
        value.array("tags")
            .add("a")
            .add("b");

        assertEquals("{\"name\":\"Freeway\",\"tags\":[\"a\",\"b\"]}", JsonUtils.stringify(value));
    }

    @Test
    void rejectsCyclicMapDuringNormalization() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("self", value);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> JsonUtils.normalize(value));

        assertTrue(ex.getMessage().contains("Cyclic JSON value"));
    }

    @Test
    void rejectsCyclicJsonObjectDuringStringify() {
        JsonObject first = JsonUtils.object();
        JsonObject second = JsonUtils.object();
        first.put("second", second);
        second.put("first", first);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> JsonUtils.stringify(first));

        assertTrue(ex.getMessage().contains("Cyclic JSON value"));
    }

    @Test
    void rejectsCyclicJsonObjectDuringDeepCopy() {
        JsonObject first = JsonUtils.object();
        JsonObject second = JsonUtils.object();
        first.put("second", second);
        second.put("first", first);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, first::toMap);

        assertTrue(ex.getMessage().contains("Cyclic JSON value"));
    }

    @Test
    void allowsSharedJsonObjectsWhenTheyAreNotCyclic() {
        JsonObject shared = JsonUtils.object().put("value", 1);
        JsonArray array = JsonUtils.array().add(shared).add(shared);

        assertEquals("[{\"value\":1},{\"value\":1}]", JsonUtils.stringify(array));
    }

    @Test
    void parsesEscapeSequences() {
        JsonObject object = JsonUtils.parseObject("{\"msg\":\"a\\n\\t\\\"\\\\b\"}");

        assertEquals("a\n\t\"\\b", object.getString("msg"));
    }

    @Test
    void parsesUnicodeEscape() {
        JsonObject object = JsonUtils.parseObject("{\"ch\":\"\\u0041\\u00e9\"}");

        assertEquals("Aé", object.getString("ch"));
    }

    @Test
    void rejectsUnescapedControlCharactersInStrings() {
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parseObject("{\"msg\":\"line1\nline2\"}"));
    }

    @Test
    void rejectsLeadingZeroNumbers() {
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parse("01"));
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parse("-01"));
    }

    @Test
    void parsesZeroNumbersWithoutLeadingZero() {
        assertEquals(0, JsonUtils.parse("0"));
        assertEquals(new BigDecimal("0.5"), JsonUtils.parse("0.5"));
        assertEquals(0, JsonUtils.parse("-0"));
    }

    @Test
    void handlesBom() {
        String text = "﻿{\"key\":\"value\"}";

        JsonObject object = JsonUtils.parseObject(text);

        assertEquals("value", object.getString("key"));
    }

    @Test
    void handlesNullBooleanLiterals() {
        assertEquals(Boolean.TRUE, JsonUtils.parse("true"));
        assertEquals(Boolean.FALSE, JsonUtils.parse("false"));
        assertThrows(IllegalArgumentException.class, () -> JsonUtils.parse("null_obj"));
    }

    @Test
    void rejectsMalformedTrailingComma() {
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parseObject("{\"a\":1,}"));
    }

    @Test
    void rejectsUnclosedObject() {
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parseObject("{\"a\":1"));
    }

    @Test
    void rejectsUnclosedString() {
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parseObject("{\"a\":\"open"));
    }

    @Test
    void stringifyPretty() {
        JsonObject value = JsonUtils.object();
        value.put("x", 1);
        value.put("y", 2);

        String pretty = JsonUtils.stringifyPretty(value);
        assertTrue(pretty.contains("\n"));
        assertTrue(pretty.contains("  "));
    }

    @Test
    void coercesJsonToRecord() {
        JsonObject data = JsonUtils.object();
        data.put("name", "test");

        record Entry(String name) {}
        Entry entry = JsonUtils.coerce(data, Entry.class);

        assertEquals("test", entry.name());
    }

    @Test
    void coercesJsonToBean() {
        JsonObject data = JsonUtils.object();
        data.put("title", "hello");

        BeanTarget bean = JsonUtils.coerce(data, BeanTarget.class);

        assertEquals("hello", bean.title);
    }

    @Test
    void stringifyNullAndPrimitives() {
        assertEquals("null", JsonUtils.stringify(null));
        assertEquals("42", JsonUtils.stringify(42));
        assertEquals("true", JsonUtils.stringify(true));
        assertEquals("\"text\"", JsonUtils.stringify("text"));
    }

    @Test
    void rejectsNonFiniteNumbersDuringStringify() {
        assertThrows(IllegalArgumentException.class, () -> JsonUtils.stringify(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> JsonUtils.stringify(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> JsonUtils.stringify(Float.NEGATIVE_INFINITY));
    }

    @Test
    void rejectsNonFiniteNumbersInsideObjectsDuringStringify() {
        JsonObject object = JsonUtils.object().put("value", Double.NaN);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> JsonUtils.stringifyPretty(object));

        assertTrue(ex.getMessage().contains("JSON number must be finite"));
    }

    @Test
    void parseLargsInteger() {
        long value = 9_223_372_036_854_775_807L;
        String json = "{\"x\":" + value + "}";

        JsonObject object = JsonUtils.parseObject(json);

        assertEquals(value, object.getLong("x"));
    }

    @Test
    void parseFloatAsIntTruncates() {
        JsonObject object = JsonUtils.parseObject("{\"x\":3.14}");

        assertEquals(Integer.valueOf(3), object.getInt("x"));
    }

    @Test
    void coercesParameterizedCollections() {
        Type type = new TypeRef<List<Endpoint>>() {
        }.type();

        @SuppressWarnings("unchecked")
        List<Endpoint> values = (List<Endpoint>) JsonUtils.coerce(
            JsonUtils.parse("[\"alpha\", \"beta\"]"),
            type,
            COERCER
        );

        assertEquals(List.of(new Endpoint("alpha"), new Endpoint("beta")), values);
    }

    @Test
    void coercesParameterizedMaps() {
        Type type = new TypeRef<Map<Integer, Endpoint>>() {
        }.type();

        @SuppressWarnings("unchecked")
        Map<Integer, Endpoint> values = (Map<Integer, Endpoint>) JsonUtils.coerce(
            JsonUtils.parse("{\"1\":\"alpha\",\"2\":\"beta\"}"),
            type,
            COERCER
        );

        assertEquals(new Endpoint("alpha"), values.get(1));
        assertEquals(new Endpoint("beta"), values.get(2));
    }

    @Test
    void coercesQueueSortedMapAndEnumContainers() {
        Type queueType = new TypeRef<Queue<Endpoint>>() {
        }.type();
        @SuppressWarnings("unchecked")
        Queue<Endpoint> queue = (Queue<Endpoint>) JsonUtils.coerce(
            JsonUtils.parse("[\"alpha\", \"beta\"]"),
            queueType,
            COERCER
        );
        assertEquals(List.of(new Endpoint("alpha"), new Endpoint("beta")), List.copyOf(queue));
        assertTrue(queue instanceof ArrayDeque);

        Type sortedMapType = new TypeRef<SortedMap<Integer, Endpoint>>() {
        }.type();
        @SuppressWarnings("unchecked")
        SortedMap<Integer, Endpoint> sortedMap = (SortedMap<Integer, Endpoint>) JsonUtils.coerce(
            JsonUtils.parse("{\"2\":\"beta\",\"1\":\"alpha\"}"),
            sortedMapType,
            COERCER
        );
        assertEquals(List.of(1, 2), List.copyOf(sortedMap.keySet()));
        assertEquals(new Endpoint("alpha"), sortedMap.get(1));
        assertTrue(sortedMap instanceof TreeMap);

        Type enumSetType = new TypeRef<EnumSet<Color>>() {
        }.type();
        @SuppressWarnings("unchecked")
        EnumSet<Color> colors = (EnumSet<Color>) JsonUtils.coerce(
            JsonUtils.parse("[\"RED\"]"),
            enumSetType,
            COERCER
        );
        assertEquals(EnumSet.of(Color.RED), colors);

        Type enumMapType = new TypeRef<EnumMap<Color, Endpoint>>() {
        }.type();
        @SuppressWarnings("unchecked")
        EnumMap<Color, Endpoint> enumMap = (EnumMap<Color, Endpoint>) JsonUtils.coerce(
            JsonUtils.parse("{\"RED\":\"alpha\"}"),
            enumMapType,
            COERCER
        );
        assertEquals(new Endpoint("alpha"), enumMap.get(Color.RED));
    }

    @Test
    void coercesParameterizedBeans() {
        Type type = new TypeRef<Box<Endpoint>>() {
        }.type();

        @SuppressWarnings("unchecked")
        Box<Endpoint> box = (Box<Endpoint>) JsonUtils.coerce(
            JsonUtils.parse("{\"value\":\"alpha\"}"),
            type,
            COERCER
        );

        assertEquals(new Endpoint("alpha"), box.value());
    }

    @Test
    void roundTripsTemporalAndUuidValues() {
        TemporalEntry entry = new TemporalEntry(
            Instant.parse("2026-06-18T01:02:03Z"),
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        );

        String json = JsonUtils.stringify(entry);
        TemporalEntry roundTrip = JsonUtils.coerce(JsonUtils.parseObject(json), TemporalEntry.class);

        assertEquals(entry, roundTrip);
    }

    @SuppressWarnings("unchecked")
    private static <T> T coerce(Object input, Class<T> targetType) {
        if (input == null) {
            if (!targetType.isPrimitive()) {
                return null;
            }
            return switch (targetType.getName()) {
                case "boolean" -> (T) Boolean.FALSE;
                case "byte" -> (T) Byte.valueOf((byte) 0);
                case "short" -> (T) Short.valueOf((short) 0);
                case "int" -> (T) Integer.valueOf(0);
                case "long" -> (T) Long.valueOf(0L);
                case "float" -> (T) Float.valueOf(0f);
                case "double" -> (T) Double.valueOf(0d);
                case "char" -> (T) Character.valueOf('\0');
                default -> throw new IllegalArgumentException("Unsupported primitive " + targetType.getName());
            };
        }
        if (targetType == Endpoint.class && input instanceof String text) {
            return (T) new Endpoint(text);
        }
        if (targetType.isInstance(input)) {
            return targetType.cast(input);
        }
        if (targetType == String.class) {
            return targetType.cast(String.valueOf(input));
        }
        if (targetType == Integer.class || targetType == int.class) {
            if (input instanceof String text) {
                return (T) Integer.valueOf(text);
            }
            return (T) Integer.valueOf(((Number) input).intValue());
        }
        if (targetType == Long.class || targetType == long.class) {
            if (input instanceof String text) {
                return (T) Long.valueOf(text);
            }
            return (T) Long.valueOf(((Number) input).longValue());
        }
        if (targetType == Double.class || targetType == double.class) {
            if (input instanceof String text) {
                return (T) Double.valueOf(text);
            }
            return (T) Double.valueOf(((Number) input).doubleValue());
        }
        if (targetType == Float.class || targetType == float.class) {
            if (input instanceof String text) {
                return (T) Float.valueOf(text);
            }
            return (T) Float.valueOf(((Number) input).floatValue());
        }
        if (targetType == Short.class || targetType == short.class) {
            if (input instanceof String text) {
                return (T) Short.valueOf(text);
            }
            return (T) Short.valueOf(((Number) input).shortValue());
        }
        if (targetType == Byte.class || targetType == byte.class) {
            if (input instanceof String text) {
                return (T) Byte.valueOf(text);
            }
            return (T) Byte.valueOf(((Number) input).byteValue());
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            if (input instanceof Boolean b) {
                return (T) b;
            }
            return (T) Boolean.valueOf(String.valueOf(input));
        }
        if (targetType == Character.class || targetType == char.class) {
            String text = String.valueOf(input);
            return (T) Character.valueOf(text.isEmpty() ? '\0' : text.charAt(0));
        }
        return FALLBACK_COERCER.coerce(input, targetType);
    }

    private record Endpoint(String value) {
    }

    private record Box<T>(T value) {
    }

    private record TemporalEntry(Instant createdAt, UUID id) {
    }

    private enum Color {
        RED
    }

    private static final class CountingInputStream extends java.io.InputStream {
        private long remaining;
        private boolean closed;

        private CountingInputStream(long length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return ' ';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining <= 0) {
                return -1;
            }
            int read = (int) Math.min(length, remaining);
            for (int i = 0; i < read; i++) {
                buffer[offset + i] = ' ';
            }
            remaining -= read;
            return read;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private abstract static class TypeRef<T> {
        private final Type type;

        private TypeRef() {
            ParameterizedType parameterized = (ParameterizedType) getClass().getGenericSuperclass();
            this.type = parameterized.getActualTypeArguments()[0];
        }

        final Type type() {
            return type;
        }
    }
}

class BeanTarget {
    public String title;
}
