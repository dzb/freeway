package com.jujin.freeway.commons.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Property baseline for the JSON codec: a tree written by the encoder parses
 * back to the same text (escapes, arrays, nesting), and malformed text fails
 * with the documented {@link IllegalArgumentException} rather than an
 * unchecked implementation detail.
 */
class JsonPropertyTest {

    @Test
    void randomTreesRoundTripThroughText() {
        Random random = new Random(0x50D);
        for (int iteration = 0; iteration < 300; iteration++) {
            Object value = tree(random, 0);
            String json = JsonUtils.toJson(value);
            Object parsed = JsonUtils.parse(json);
            assertEquals(json, JsonUtils.toJson(parsed),
                "iteration " + iteration + " input=" + json);
        }
    }

    @Test
    void malformedTextOnlyFailsWithIllegalArgumentException() {
        Random random = new Random(0xBAD);
        String alphabet = "{}[],:\"\\ abcdef0123456789\n\t";
        for (int iteration = 0; iteration < 500; iteration++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(80);
            for (int i = 0; i < length; i++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            try {
                JsonUtils.parse(text.toString());
            } catch (IllegalArgumentException expected) {
                // The documented failure mode for malformed input.
            } catch (RuntimeException unexpected) {
                throw new AssertionError("unexpected exception for: " + text, unexpected);
            }
        }
    }

    private static Object tree(Random random, int depth) {
        int kind = depth >= 3 ? random.nextInt(5) : random.nextInt(8);
        return switch (kind) {
            case 0 -> null;
            case 1 -> random.nextBoolean();
            case 2 -> random.nextInt();
            case 3 -> (long) random.nextInt() * 1_000_003L;
            case 4 -> string(random);
            case 5 -> {
                JsonArray array = JsonUtils.array();
                int count = random.nextInt(4);
                for (int i = 0; i < count; i++) {
                    array.add(tree(random, depth + 1));
                }
                yield array;
            }
            case 6 -> {
                JsonObject object = JsonUtils.object();
                int count = random.nextInt(4);
                for (int i = 0; i < count; i++) {
                    object.put("k" + i + "_" + random.nextInt(10), tree(random, depth + 1));
                }
                yield object;
            }
            default -> {
                JsonArray nested = JsonUtils.array().add(tree(random, depth + 1));
                yield nested;
            }
        };
    }

    private static String string(Random random) {
        String alphabet = "abc XYZ09\"\\\n中\u0001";
        int length = random.nextInt(10);
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }
}
