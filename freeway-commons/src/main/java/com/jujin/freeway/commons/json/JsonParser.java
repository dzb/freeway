package com.jujin.freeway.commons.json;

import com.jujin.freeway.commons.util.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Hand-written recursive-descent JSON parser — zero allocations from
 * intermediate DOM nodes. Produces either a raw {@code Map/List/String/Number}
 * or the lightweight {@link JsonObject}/{@link JsonArray} wrappers.
 *
 * <p><b>Duplicate object keys:</b> when an object contains the same key more
 * than once (e.g. {@code {"a":1,"a":2}}), the last occurrence wins — plain
 * {@code Map.put} semantics, matching {@link JsonObject#put(String, Object)}.
 * This is a documented contract, not an error: parsing never rejects
 * duplicates, so callers that need strict uniqueness must validate their
 * input themselves.
 */
final class JsonParser {

    /**
     * Maximum nesting depth for JSON objects and arrays.
     * Prevents stack overflow attacks from deeply nested structures.
     */
    static final int MAX_DEPTH = 1000;

    /**
     * Maximum length for JSON string values.
     * Prevents OOM attacks from extremely long strings.
     */
    private static final int MAX_STRING_LENGTH = 10 * 1024 * 1024;

    /**
     * Maximum length for a single JSON number token.
     * {@link #parseNumber} would otherwise let an unbounded run of digits
     * reach {@link BigInteger}/{@link BigDecimal} (super-linear cost),
     * causing CPU/memory spikes. Matches {@link #MAX_STRING_LENGTH}.
     */
    private static final int MAX_NUMBER_LENGTH = MAX_STRING_LENGTH;

    /**
     * Maximum size for JSON input streams.
     * Prevents unbounded memory use while decoding streamed JSON.
     */
    static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;

    /**
     * Maximum size for a {@link #parse(String)} input, applied to the
     * character count. The stream path caps raw bytes at
     * {@link #MAX_INPUT_BYTES}; the string path applies the same budget so a
     * single parse call cannot drive unbounded work (recursion, string
     * building, number decoding) from an already-allocated string.
     */
    private static final int MAX_INPUT_CHARS = MAX_INPUT_BYTES;

    /**
     * Maximum size for JSON arrays.
     * Prevents OOM attacks from extremely large arrays.
     */
    private static final int MAX_ARRAY_SIZE = 1_000_000;

    /**
     * Maximum size for JSON objects.
     * Prevents OOM attacks from extremely large objects.
     */
    private static final int MAX_OBJECT_SIZE = 1_000_000;

    private JsonParser() {}

    static Object parse(String text) {
        return new Parser(Objects.requireNonNull(text, "text")).parse();
    }

    static JsonObject parseObject(String text) {
        Object value = parse(text);
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new IllegalArgumentException("JSON root is not an object");
    }

    static JsonArray parseArray(String text) {
        Object value = parse(text);
        if (value instanceof JsonArray array) {
            return array;
        }
        throw new IllegalArgumentException("JSON root is not an array");
    }

    static Object parse(InputStream input) {
        return new Parser(readText(input)).parse();
    }

    static JsonObject parseObject(InputStream input) {
        return parseObject(readText(input));
    }

    static JsonArray parseArray(InputStream input) {
        return parseArray(readText(input));
    }

    private static String readText(InputStream input) {
        Objects.requireNonNull(input, "input");
        try (input) {
            byte[] data = ByteStreams.readBytes(
                input,
                MAX_INPUT_BYTES,
                "JSON input"
            );
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read JSON input", ex);
        }
    }

    private static final class Parser {

        private final String text;
        private int index;

        private Parser(String text) {
            String t = Objects.requireNonNull(text, "text");
            if (t.length() > MAX_INPUT_CHARS) {
                // Applied to every entry path (String and stream alike): a
                // single parse must not drive unbounded work — recursion,
                // string building, number decoding — from an input that is
                // already in memory. The stream path caps raw bytes at
                // MAX_INPUT_BYTES before decoding; UTF-8 decoding never
                // expands characters beyond bytes, so this char budget is
                // consistent with that byte budget.
                throw new IllegalArgumentException(
                    "JSON input too large (max " +
                        MAX_INPUT_CHARS +
                        " characters)"
                );
            }
            // \uFEFF as an escape, not a literal: a bare BOM in source is
            // invisible to review and one editor normalization away from
            // silently breaking the strip.
            this.text = t.startsWith("\uFEFF") ? t.substring(1) : t;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue(0);
            skipWhitespace();
            if (!eof()) {
                throw error("Unexpected trailing content");
            }
            return value;
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error(
                    "JSON nesting too deep (max " + MAX_DEPTH + " levels)"
                );
            }
            skipWhitespace();
            if (eof()) {
                throw error("Unexpected end of input");
            }
            char c = peek();
            return switch (c) {
                case '{' -> parseObjectValue(depth + 1);
                case '[' -> parseArrayValue(depth + 1);
                case '"' -> parseString();
                case 't', 'f', 'n' -> parseLiteral();
                default -> parseNumber();
            };
        }

        /**
         * Parses one JSON object. Duplicate keys follow {@code Map.put}
         * semantics — the last value for a key wins (see class javadoc);
         * no duplicate-key diagnostics are produced.
         */
        private JsonObject parseObjectValue(int depth) {
            expect('{');
            JsonObject result = JsonUtils.object();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                if (result.size() >= MAX_OBJECT_SIZE) {
                    throw error(
                        "JSON object too large (max " +
                            MAX_OBJECT_SIZE +
                            " entries)"
                    );
                }
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue(depth));
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private JsonArray parseArrayValue(int depth) {
            expect('[');
            JsonArray result = JsonUtils.array();
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                if (result.size() >= MAX_ARRAY_SIZE) {
                    throw error(
                        "JSON array too large (max " +
                            MAX_ARRAY_SIZE +
                            " elements)"
                    );
                }
                result.add(parseValue(depth));
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            int start = index;

            // Fast path — scan for the closing quote when no escape or
            // control character is present, then substring directly.
            // Bounds-checked inline so oversized strings still abort early.
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '"') {
                    if (index - start > MAX_STRING_LENGTH) {
                        throw error(
                            "JSON string too long (max " +
                                MAX_STRING_LENGTH +
                                " characters)"
                        );
                    }
                    String result = text.substring(start, index);
                    index++;
                    return result;
                }
                if (c == '\\' || c < 0x20) {
                    break;
                }
                if (index - start >= MAX_STRING_LENGTH) {
                    throw error(
                        "JSON string too long (max " +
                            MAX_STRING_LENGTH +
                            " characters)"
                    );
                }
                index++;
            }

            // Slow path — escapes or control characters present.
            StringBuilder out = new StringBuilder(
                Math.min(index - start + 8, MAX_STRING_LENGTH + 16)
            );
            out.append(text, start, index);
            while (!eof()) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw error("Unescaped control character in string");
                    }
                    out.append(c);
                    if (out.length() > MAX_STRING_LENGTH) {
                        throw error(
                            "JSON string too long (max " +
                                MAX_STRING_LENGTH +
                                " characters)"
                        );
                    }
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
                if (out.length() > MAX_STRING_LENGTH) {
                    throw error(
                        "JSON string too long (max " +
                            MAX_STRING_LENGTH +
                            " characters)"
                    );
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > text.length()) {
                throw error("Unterminated unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(index + i), 16);
                if (digit < 0) {
                    throw error("Invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            index += 4;
            return (char) value;
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
            if (eof()) {
                throw error("Expected digit");
            }
            if (peek() == '0') {
                next();
                if (!eof() && isAsciiDigit(peek())) {
                    throw error("Leading zeros are not allowed");
                }
            } else {
                readDigits(start);
            }
            boolean decimal = false;
            if (!eof() && peek() == '.') {
                decimal = true;
                next();
                readDigits(start);
            }
            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                decimal = true;
                next();
                if (!eof() && (peek() == '+' || peek() == '-')) {
                    next();
                }
                readDigits(start);
            }
            if (decimal) {
                try {
                    return new BigDecimal(this.text.substring(start, index));
                } catch (NumberFormatException ex) {
                    throw error("Invalid number");
                }
            }
            try {
                long value = Long.parseLong(this.text, start, index, 10);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return (int) value;
                }
                return value;
            } catch (NumberFormatException ex) {
                try {
                    return new BigInteger(this.text.substring(start, index));
                } catch (NumberFormatException ignored) {
                    // fall through to error
                }
                throw error("Invalid number");
            }
        }

        private void readDigits(int tokenStart) {
            if (eof() || !isAsciiDigit(peek())) {
                throw error("Expected digit");
            }
            while (!eof() && isAsciiDigit(peek())) {
                next();
                // Bounds-checked inline so an oversized number token aborts as
                // soon as the limit is crossed, before BigInteger/BigDecimal
                // ever sees it (their digit processing is super-linear).
                if (index - tokenStart > MAX_NUMBER_LENGTH) {
                    throw error(
                        "JSON number too long (max " +
                            MAX_NUMBER_LENGTH +
                            " characters)"
                    );
                }
            }
        }

        private static boolean isAsciiDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private boolean match(String literal) {
            if (text.startsWith(literal, index)) {
                index += literal.length();
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!eof() && isJsonWhitespace(peek())) {
                index++;
            }
        }

        /** RFC 8259 whitespace: space, horizontal tab, line feed, carriage return. */
        private static boolean isJsonWhitespace(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r';
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
    }
}
