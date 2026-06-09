package com.jujin.freeway.commons.json;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class JsonParser {
    /**
     * Maximum nesting depth for JSON objects and arrays.
     * Prevents stack overflow attacks from deeply nested structures.
     */
    private static final int MAX_DEPTH = 1000;

    /**
     * Maximum length for JSON string values.
     * Prevents OOM attacks from extremely long strings.
     */
    private static final int MAX_STRING_LENGTH = 10 * 1024 * 1024;

    /**
     * Maximum size for JSON input streams.
     * Prevents unbounded memory use while decoding streamed JSON.
     */
    static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;

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

    private JsonParser() {
    }

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
            byte[] data = readBytes(input);
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read JSON input", ex);
        }
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > MAX_INPUT_BYTES - read) {
                throw new IllegalArgumentException("JSON input too large (max " + MAX_INPUT_BYTES + " bytes)");
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = stripBom(text);
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
                throw error("JSON nesting too deep (max " + MAX_DEPTH + " levels)");
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

        private JsonObject parseObjectValue(int depth) {
            expect('{');
            JsonObject result = JsonUtils.object();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                if (result.size() >= MAX_OBJECT_SIZE) {
                    throw error("JSON object too large (max " + MAX_OBJECT_SIZE + " entries)");
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
                    throw error("JSON array too large (max " + MAX_ARRAY_SIZE + " elements)");
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
            StringBuilder out = new StringBuilder();
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
                        throw error("JSON string too long (max " + MAX_STRING_LENGTH + " characters)");
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
                    throw error("JSON string too long (max " + MAX_STRING_LENGTH + " characters)");
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
            if (eof()) {
                throw error("Expected digit");
            }
            if (peek() == '0') {
                next();
                if (!eof() && Character.isDigit(peek())) {
                    throw error("Leading zeros are not allowed");
                }
            } else {
                readDigits();
            }
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
