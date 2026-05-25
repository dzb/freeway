package com.jujin.freeway.commons.json;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilsTest {
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
}

class BeanTarget {
    public String title;
}
