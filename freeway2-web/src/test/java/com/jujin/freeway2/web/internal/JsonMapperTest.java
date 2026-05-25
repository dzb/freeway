package com.jujin.freeway2.web.internal;

import com.jujin.freeway2.commons.json.JsonUtils;
import com.jujin.freeway2.commons.scalar.Coercer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonUtilsTest {
    private record Endpoint(String value) {
    }

    private record Payload(Endpoint endpoint, String mode) {
    }

    private record PrimitivePayload(int count, String name) {
    }

    private static final class View {
        private final String name;
        private final int count;

        private View(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    private static final class MutableView {
        private String name;
        private int count;

        private MutableView() {
        }
    }

    @Test
    void coercesNestedRecordsWithCustomTypeCoercer() {
        Coercer coercer = JsonUtilsTest::coerce;

        Payload payload = JsonUtils.coerce(
            JsonUtils.parse("{\"endpoint\":\"alpha\",\"mode\":\"fast\"}"),
            Payload.class,
            coercer
        );

        assertEquals(new Endpoint("alpha"), payload.endpoint());
        assertEquals("fast", payload.mode());
    }

    @Test
    void defaultsMissingPrimitiveRecordComponents() {
        Coercer coercer = JsonUtilsTest::coerce;

        PrimitivePayload payload = JsonUtils.coerce(
            JsonUtils.parse("{\"name\":\"demo\"}"),
            PrimitivePayload.class,
            coercer
        );

        assertEquals(0, payload.count());
        assertEquals("demo", payload.name());
    }

    @Test
    void serializesRecordsAndBeansAsTrees() {
        assertEquals(
            "{\"endpoint\":{\"value\":\"alpha\"},\"mode\":\"fast\"}",
            JsonUtils.stringify(JsonUtils.normalize(new Payload(new Endpoint("alpha"), "fast")))
        );
        assertEquals(
            "{\"name\":\"demo\",\"count\":3}",
            JsonUtils.stringify(JsonUtils.normalize(new View("demo", 3)))
        );
        assertEquals("null", JsonUtils.stringify(JsonUtils.normalize(null)));
    }

    @Test
    void coercesMutableBeansWithoutReflectiveWriteback() {
        Coercer coercer = JsonUtilsTest::coerce;

        MutableView view = JsonUtils.coerce(
            JsonUtils.parse("{\"name\":\"demo\",\"count\":3}"),
            MutableView.class,
            coercer
        );

        assertEquals("demo", view.name);
        assertEquals(3, view.count);
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
            return (T) Integer.valueOf(((Number) input).intValue());
        }
        if (targetType == Long.class || targetType == long.class) {
            return (T) Long.valueOf(((Number) input).longValue());
        }
        if (targetType == Double.class || targetType == double.class) {
            return (T) Double.valueOf(((Number) input).doubleValue());
        }
        if (targetType == Float.class || targetType == float.class) {
            return (T) Float.valueOf(((Number) input).floatValue());
        }
        if (targetType == Short.class || targetType == short.class) {
            return (T) Short.valueOf(((Number) input).shortValue());
        }
        if (targetType == Byte.class || targetType == byte.class) {
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
        throw new IllegalArgumentException("Unsupported coercion to " + targetType.getName());
    }
}
