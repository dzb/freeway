package com.jujin.freeway.commons.json;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class JsonParser {
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
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read JSON input", ex);
        }
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
                    if (c < 0x20) {
                        throw error("Unescaped control character in string");
                    }
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
