package com.jujin.freeway.commons.json;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Date;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TransferQueue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void duplicateObjectKeysLastWins() {
        // {"a":1,"a":2} → {"a":2}: parsing follows Map.put semantics — the
        // last occurrence of a key wins (documented contract, not an error).
        JsonObject object = JsonUtils.parseObject("{\"a\":1,\"a\":2}");

        assertEquals(2, object.get("a"));
        assertEquals(1, object.size());
    }

    @Test
    void duplicateObjectKeysLastWinsInNestedObjects() {
        // The same last-wins rule applies at every nesting level.
        JsonObject object = JsonUtils.parseObject(
            "{\"outer\":{\"a\":1,\"a\":2,\"b\":\"keep\"},\"top\":1,\"top\":\"last\"}"
        );

        JsonObject outer = (JsonObject) object.get("outer");
        assertEquals(2, outer.get("a"));
        assertEquals("keep", outer.get("b"));
        assertEquals(2, outer.size());
        assertEquals("last", object.get("top"));
        assertEquals(2, object.size());
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
    void parsesUnicodeEscapeNul() {
        Object result = JsonUtils.parse("\"\\u0000\"");
        assertEquals("\u0000", result.toString());
        // NUL adjacent to regular text survives round-trip.
        Object mixed = JsonUtils.parse("\"a\\u0000b\"");
        assertEquals("a\u0000b", mixed.toString());
    }

    @Test
    void stringLengthBoundaryIsParsedLength() {
        // The limit counts parsed characters, so an escape sequence counts as
        // one char. Exactly MAX is accepted; MAX+1 is rejected — in both the
        // fast path (no escapes) and the slow path (escapes present).
        // Matches JsonParser.MAX_STRING_LENGTH (private).
        int max = 10 * 1024 * 1024;

        Object fast = JsonUtils.parse("\"" + "a".repeat(max) + "\"");
        assertEquals(max, fast.toString().length());

        assertThrows(IllegalArgumentException.class,
            () -> JsonUtils.parse("\"" + "a".repeat(max + 1) + "\""));

        // Slow path: MAX-6 plain + \n (1 char) + 5 plain = MAX parsed chars.
        Object slow = JsonUtils.parse("\"" + "a".repeat(max - 6) + "\\n" + "aaaaa" + "\"");
        assertEquals(max, slow.toString().length());

        assertThrows(IllegalArgumentException.class,
            () -> JsonUtils.parse("\"" + "a".repeat(max - 5) + "\\n" + "aaaaa" + "\""),
            "parsed length MAX+1 must be rejected");
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
    void rejectsNonHexUnicodeEscape() {
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parse("\"\\u-123\""));
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parse("\"\\uZZZZ\""));
    }

    @Test
    void escapesLoneSurrogates() {
        String escapedHigh = JsonUtils.stringify("\ud800");
        assertTrue(escapedHigh.contains("\\ud800"),
            "lone high surrogate must be escaped, got: " + escapedHigh);
        String escapedLow = JsonUtils.stringify("\udfff");
        assertTrue(escapedLow.contains("\\udfff"),
            "lone low surrogate must be escaped, got: " + escapedLow);

        // A valid surrogate pair round-trips unchanged.
        String emoji = "\ud83d\ude00";
        assertEquals(emoji, JsonUtils.parse(JsonUtils.stringify(emoji)));
    }

    @Test
    void rejectsNonJsonWhitespace() {
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parse("[\u000B1\u000B]"),
            "vertical tab is not JSON whitespace");
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parse("{\u001Ca\u001C:1}"),
            "file separator is not JSON whitespace");
    }

    @Test
    void rejectsNonAsciiDigitsInNumbers() {
        assertThrows(IllegalArgumentException.class, () ->
            JsonUtils.parse("[١٢٣]"),
            "Arabic-Indic digits are not valid JSON number digits");
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

    @Test
    void transferQueueReturnsLinkedTransferQueue() {
        Type type = new TypeRef<TransferQueue<String>>() {}.type();
        @SuppressWarnings("unchecked")
        TransferQueue<String> queue =
            (TransferQueue<String>) JsonUtils.coerce(
                JsonUtils.parse("[\"alpha\"]"), type, COERCER);
        assertNotNull(queue);
        assertTrue(queue instanceof LinkedTransferQueue,
            "TransferQueue should return LinkedTransferQueue, got: " + queue.getClass());
    }

    @Test
    void integerAccessorRejectsOutOfRangeNumber() {
        JsonObject obj = JsonUtils.object().put("x", 3_000_000_000L);
        assertThrows(IllegalArgumentException.class, () -> obj.getInt("x"));
    }

    @Test
    void longValueRejectsBigIntegerOutOfRange() {
        BigInteger huge = new BigInteger("99999999999999999999");
        JsonObject obj = JsonUtils.object().put("x", huge);
        assertThrows(IllegalArgumentException.class, () -> obj.getLong("x"));
    }

    @Test
    void nullMapKeyThrows() {
        Map<Object, String> map = new HashMap<>();
        map.put(null, "value");
        assertThrows(IllegalArgumentException.class, () -> JsonUtils.stringify(map));
    }

    @Test
    void stringifyDuration() {
        String json = JsonUtils.stringify(Duration.ofMinutes(5));
        assertEquals("\"PT5M\"", json);
    }

    @Test
    void stringifyDate() {
        String json = JsonUtils.stringify(new Date(0));
        assertEquals("\"1970-01-01T00:00:00Z\"", json);
    }

    @Test
    void stringifyOptionalInt() {
        String json = JsonUtils.stringify(OptionalInt.of(3));
        assertEquals("3", json);
    }

    @Test
    void stringifyHandlesURI() {
        String json = JsonUtils.stringify(URI.create("https://example.com"));
        assertEquals("\"https://example.com\"", json);
    }

    @Test
    void longValueRejectsNaN() {
        JsonObject obj = JsonUtils.object().put("x", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> obj.getLong("x"));
        JsonObject obj2 = JsonUtils.object().put("x", Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> obj2.getLong("x"));
    }

    @Test
    void integerRejectsNaN() {
        JsonObject obj = JsonUtils.object().put("x", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> obj.getInt("x"));
    }

    @Test
    void stringifyHandlesCharSequence() {
        String json = JsonUtils.stringify(new StringBuilder("hello"));
        assertEquals("\"hello\"", json,
                "StringBuilder should serialize as a string");
    }

    @Test
    void rejectsOversizedNumberToken() {
        // Regression: parseNumber had no token-length limit, so an unbounded
        // digit run reached BigInteger/BigDecimal (super-linear cost) — a
        // 20MB number caused a CPU/memory spike instead of a fast error.
        // 20MB digits pass the 32MB input cap but must trip the 10MB
        // number-token limit immediately.
        String huge = "9".repeat(20 * 1024 * 1024);

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> JsonUtils.parse(huge)
        );

        assertTrue(ex.getMessage().contains("JSON number too long"),
            "must fail via the number-token limit, got: " + ex.getMessage());
    }

    @Test
    void rejectsOversizedStringInput() {
        // Regression: parse(String) had no total input cap (the stream path
        // did). An oversized string must fail fast with a clear error instead
        // of driving unbounded parse work.
        String huge = " ".repeat(JsonParser.MAX_INPUT_BYTES + 1);

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> JsonUtils.parse(huge)
        );

        assertTrue(ex.getMessage().contains("JSON input too large"),
            "must fail via the input-size cap, got: " + ex.getMessage());
    }

    @Test
    void parseNumberAcceptsBigInteger() {
        Object result = JsonUtils.parse("9223372036854775808"); // > Long.MAX_VALUE
        assertEquals(new BigInteger("9223372036854775808"), result);
    }

    @Test
    void jsonObjectKeySetPreservesInsertionOrder() {
        JsonObject obj = JsonUtils.parseObject("{\"c\":1,\"b\":2,\"a\":3}");
        assertEquals(List.of("c", "b", "a"), List.copyOf(obj.keySet()));
    }

    @Test
    void concurrentNavigableMapReturnsConcurrentSkipListMap() {
        Type type = new TypeRef<ConcurrentNavigableMap<Integer, String>>() {}.type();
        @SuppressWarnings("unchecked")
        ConcurrentNavigableMap<Integer, String> map =
            (ConcurrentNavigableMap<Integer, String>) JsonUtils.coerce(
                JsonUtils.parse("{\"1\":\"alpha\"}"), type, COERCER);
        assertNotNull(map);
        assertTrue(map instanceof ConcurrentSkipListMap,
            "ConcurrentNavigableMap should return ConcurrentSkipListMap, got: " + map.getClass());
    }

    @Test
    void blockingQueueReturnsLinkedBlockingQueue() {
        Type type = new TypeRef<BlockingQueue<String>>() {}.type();
        @SuppressWarnings("unchecked")
        BlockingQueue<String> queue =
            (BlockingQueue<String>) JsonUtils.coerce(
                JsonUtils.parse("[\"alpha\"]"), type, COERCER);
        assertNotNull(queue);
        assertTrue(queue instanceof LinkedBlockingQueue,
            "BlockingQueue should return LinkedBlockingQueue, got: " + queue.getClass());
    }

    @Test
    void blockingDequeReturnsLinkedBlockingDeque() {
        Type type = new TypeRef<BlockingDeque<String>>() {}.type();
        @SuppressWarnings("unchecked")
        BlockingDeque<String> deque =
            (BlockingDeque<String>) JsonUtils.coerce(
                JsonUtils.parse("[\"alpha\"]"), type, COERCER);
        assertNotNull(deque);
        assertTrue(deque instanceof LinkedBlockingDeque,
            "BlockingDeque should return LinkedBlockingDeque, got: " + deque.getClass());
    }

    @Test
    void booleanCoercionRejectsNonBooleanInput() {
        // CoercerDefault accepts "yes"/"on"/"1" by design (config coercion).
        // JSON booleans only accept "true"/"false". Test through JSON path:
        JsonObject obj = JsonUtils.parseObject("{\"flag\": true}");
        assertEquals(Boolean.TRUE, obj.getBoolean("flag"));

        obj = JsonUtils.parseObject("{\"flag\": false}");
        assertEquals(Boolean.FALSE, obj.getBoolean("flag"));

        // "yes" is not valid JSON boolean
        JsonObject bad = JsonUtils.parseObject("{\"flag\": \"yes\"}");
        assertThrows(IllegalArgumentException.class, () -> bad.getBoolean("flag"));
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

    @Test
    void stringifyRawStructuresDirectly() {
        // A raw Map/List/bean tree is written in one pass — no intermediate
        // normalized tree — with leaf conversions matching JsonNormalizer.
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "freeway");
        raw.put("count", 42);
        raw.put("ratio", 1.5);
        raw.put("flag", true);
        raw.put("nothing", null);
        raw.put("tags", List.of("a", "b"));
        raw.put("when", LocalDate.of(2026, 6, 18));
        raw.put("maybe", Optional.of("present"));
        raw.put("color", Color.RED);
        raw.put("endpoint", new Endpoint("x"));

        String json = JsonUtils.stringify(raw);
        assertEquals(
            "{\"name\":\"freeway\",\"count\":42,\"ratio\":1.5,\"flag\":true,"
                + "\"nothing\":null,\"tags\":[\"a\",\"b\"],\"when\":\"2026-06-18\","
                + "\"maybe\":\"present\",\"color\":\"RED\","
                + "\"endpoint\":{\"value\":\"x\"}}",
            json
        );

        // Parity with the previous normalize-then-write path.
        assertEquals(
            JsonUtils.stringify(JsonUtils.normalize(raw)),
            json
        );
    }

    @Test
    void stringifyRawArrayAndPrimitiveArray() {
        int[] ints = {1, 2, 3};
        assertEquals("[1,2,3]", JsonUtils.stringify(ints));

        String[] strs = {"a", null, "c"};
        assertEquals("[\"a\",null,\"c\"]", JsonUtils.stringify(strs));
    }

    @Test
    void rejectsCyclicRawList() {
        List<Object> list = new ArrayList<>();
        list.add("x");
        list.add(list);

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> JsonUtils.stringify(list)
        );
        assertTrue(ex.getMessage().contains("Cyclic"),
            "cyclic raw list must be rejected by the writer, got: " + ex.getMessage());
    }

    @Test
    void writerAndNormalizerAgreeOnScalarLeaves() {
        LeafBean bean = new LeafBean();

        String direct = JsonUtils.stringify(bean);
        assertEquals(direct, JsonUtils.stringify(JsonUtils.normalize(bean)),
            "writer and normalizer must map scalar leaves identically");
    }

    private static class LeafBean {
        public Date date = new Date(0);
        public File file = new File("tmp/leaf.txt");
        public Locale locale = Locale.CHINA;
        public Duration duration = Duration.ofSeconds(5);
        public LocalTime time = LocalTime.NOON;
        public UUID uuid =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
        public Color color = Color.RED;
        public Optional<String> maybe = Optional.of("v");
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

    private static final class CountingInputStream extends InputStream {
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

    // ====================== bean getter serialization ======================

    @Test
    void stringifyIncludesGetterOnlyAndIsProperties() {
        // Regression: getter-only/computed properties and isX() booleans used
        // to vanish from JSON (serialization only read fields). They are now
        // first-class bean properties.
        GetterBean bean = new GetterBean();

        JsonObject out = JsonUtils.parseObject(JsonUtils.stringify(bean));
        assertEquals("demo", out.getString("name"));
        assertEquals(42, out.getInt("computed"));
        assertEquals(Boolean.TRUE, out.getBoolean("active"));
        // Normalizer mirrors the writer exactly (same property order).
        assertEquals(
            JsonUtils.stringify(bean),
            JsonUtils.stringify(JsonUtils.normalize(bean))
        );
    }

    @Test
    void stringifyPrefersTransformingGetterOverField() {
        // A getter is the preferred read path: its transformation is honored.
        TransformBean bean = new TransformBean();
        bean.value = "abc";

        assertEquals("{\"value\":\"ABC\"}", JsonUtils.stringify(bean));
    }

    @Test
    void coerceSkipsReadOnlyGetterOnlyProperty() {
        // Deserialization cannot write a computed property — the value is
        // skipped, the bean still constructs, and the getter keeps winning.
        GetterBean round = JsonUtils.coerce(
            JsonUtils.parse("{\"name\":\"x\",\"computed\":999,\"active\":false}"),
            GetterBean.class
        );

        assertEquals("x", round.name);
        assertEquals(42, round.getComputed(),
            "read-only computed property must not be overwritten");
        assertEquals(true, round.isActive(),
            "read-only isX property must not be overwritten");
    }

    static class GetterBean {
        private String name = "demo";

        public String getName() {
            return name;
        }

        public int getComputed() {
            return 42;
        }

        public boolean isActive() {
            return true;
        }
    }

    static class TransformBean {
        private String value = "abc";

        public String getValue() {
            return value.toUpperCase();
        }
    }

    // ====================== regression fixes ======================

    @Test
    void selfReferentialGenericBoundDoesNotStackOverflow() {
        // Regression: class Node<T extends Comparable<T>> with a
        // self-referential field (Node<T> next) binds T to Comparable<T>;
        // resolving T then recursed resolve(T) -> resolve(Comparable<T>) ->
        // resolve(T) -> ... forever, ending in StackOverflowError (an Error
        // nobody could catch). The resolver now erases re-entrant type
        // variables to Object, so coercion completes.
        @SuppressWarnings("rawtypes")
        GenericNode node = JsonUtils.coerce(
            JsonUtils.parse(
                "{\"value\":1,\"next\":{\"value\":2,\"next\":{\"value\":3}}}"
            ),
            GenericNode.class
        );

        assertEquals(1, node.value);
        assertEquals(2, node.next.value);
        assertEquals(3, node.next.next.value);
        assertNull(node.next.next.next, "tail must terminate cleanly");
    }

    @Test
    void cyclicBoundFieldStillCoercesNormalTypes() {
        // The same cyclic-bound class coerces plain comparable values
        // (Integer is Comparable) without recursion.
        GenericNode<Integer> node = JsonUtils.coerce(
            JsonUtils.parse("{\"value\":1}"),
            GenericNode.class
        );
        assertEquals(1, node.value);
    }

    static class GenericNode<T extends Comparable<T>> {
        T value;
        GenericNode<T> next;
    }

    @Test
    void concreteCollectionAndMapTargets() {
        // Regression: privateLookupIn failed for java.base classes, so
        // ArrayList/HashMap targets threw "Cannot instantiate collection/map".
        JsonCodec codec = new JsonCodecDefault();
        assertEquals(List.of(1, 2), codec.fromJson("[1,2]", ArrayList.class));
        Map<?, ?> map = codec.fromJson("{\"k\":1}", HashMap.class);
        assertEquals(1, map.get("k"));
    }

    @Test
    void getLongRejectsOutOfRangeDouble() {
        // (long)1e300 saturates to Long.MAX_VALUE silently; the BigDecimal
        // branch rejects the same magnitude.
        JsonObject obj = JsonUtils.object();
        obj.put("x", 1e300);
        assertThrows(IllegalArgumentException.class, () -> obj.getLong("x"));
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
