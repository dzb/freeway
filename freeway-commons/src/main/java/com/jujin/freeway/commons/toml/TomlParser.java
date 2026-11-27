package com.jujin.freeway.commons.toml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Hand-written cursor parser for the deliberately small TOML subset the
 * configuration system accepts:
 *
 * <ul>
 *   <li>tables {@code [a.b]} and arrays of tables {@code [[a]]} (dotted paths,
 *       bare or quoted key segments)</li>
 *   <li>keys: bare ({@code A-Z a-z 0-9 _ -}) or basic strings</li>
 *   <li>values: basic strings ({@code \b \t \n \f \r \" \\} escapes plus
 *       four-digit unicode escapes), decimal 64-bit integers, booleans,
 *       single-line string arrays</li>
 *   <li>{@code #} comments on their own line, after values and after headers</li>
 * </ul>
 *
 * <p>Everything else is a rejected input, named as such rather than guessed:
 * literal strings, multi-line strings, inline tables, dotted keys, floats,
 * date/time values, digit separators, non-decimal integers, empty string
 * arrays, comments inside a string array. {@link #parse} also rejects what
 * TOML itself forbids: a duplicate key, a table header defined twice, a
 * header colliding with an already-assigned value.
 *
 * <p>Unlike the JSON parser (last duplicate key wins), a duplicate key here is
 * an error: config and manifest files are hand-edited, so a repeated key is a
 * mistake to surface, not a precedence rule to exploit.
 */
final class TomlParser {

    /**
     * Parse budget for the character-count entry path. The config cascade
     * caps file reads at its own {@code MAX_BYTES} before reaching here; this
     * budget keeps a single in-memory parse bounded the same way.
     */
    static final int MAX_INPUT_CHARS = 16 * 1024 * 1024;

    /** Maximum number of segments in one {@code [a.b.c]} header path. */
    static final int MAX_TABLE_DEPTH = 64;

    private TomlParser() {}

    /**
     * Parses one document into an immutable tree: tables are
     * {@code Map<String, Object>} in document order, values are
     * {@code String}, {@code Long}, {@code Boolean}, {@code List<String>}
     * (string arrays) or {@code List<Map<String, Object>>} (arrays of tables).
     */
    static Map<String, Object> parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException(
                "TOML input too large (max " + MAX_INPUT_CHARS + " characters)");
        }
        return new Parser(text).parse();
    }

    private static final class Parser {

        private final String text;
        private final Map<String, Object> root = new LinkedHashMap<>();
        /**
         * Tables an explicit {@code [a.b]} header has defined, held by
         * identity: the same path under two different array-of-tables
         * elements names two different tables, so tracking paths would reject
         * valid documents.
         */
        private final Set<Map<String, Object>> definedTables =
            Collections.newSetFromMap(new IdentityHashMap<>());
        private int index;
        private int line = 1;
        private int column = 1;

        private Parser(String text) {
            // A leading BOM as an escape, not a literal — the same rule the
            // JSON parser applies, so one invisible byte cannot change parsing.
            this.text = text.startsWith("\uFEFF") ? text.substring(1) : text;
        }

        private Map<String, Object> parse() {
            Map<String, Object> current = root;
            while (true) {
                skipLineEnds();
                if (eof()) {
                    break;
                }
                current = peekIs('[') ? parseHeader() : parseKeyValue(current);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> frozen = (Map<String, Object>) freeze(root);
            return frozen;
        }

        // ------------------------------------------------------------------
        // statements
        // ------------------------------------------------------------------

        /**
         * Parses one {@code [table]} / {@code [[array of tables]]} header and
         * returns the table its following key-values belong to.
         */
        private Map<String, Object> parseHeader() {
            advance(); // the '['
            boolean arrayTable = consume('[');
            skipHorizontal();
            List<String> path = parseHeaderPath();
            skipHorizontal();
            expect(']');
            if (arrayTable) {
                expect(']');
            }
            expectLineEnd();
            return resolveHeader(path, arrayTable);
        }

        private List<String> parseHeaderPath() {
            List<String> path = new ArrayList<>();
            path.add(parseKey());
            while (true) {
                skipHorizontal();
                if (!consume('.')) {
                    return path;
                }
                skipHorizontal();
                path.add(parseKey());
                if (path.size() > MAX_TABLE_DEPTH) {
                    throw error(
                        "table header too deep (max " + MAX_TABLE_DEPTH + " levels)");
                }
            }
        }

        /**
         * Resolves (or creates) the table a header names. Sub-tables attach to
         * the last element of an enclosing array of tables; defining a
         * super-table after its sub-tables is allowed; defining the same
         * header twice — or a header where a value already stands — is not.
         * Each array-of-tables element is its own scope, so the same
         * sub-table header is legal once in every element.
         */
        private Map<String, Object> resolveHeader(List<String> path, boolean arrayTable) {
            Map<String, Object> parent = root;
            for (int i = 0; i < path.size() - 1; i++) {
                parent = descend(parent, path.get(i), path.subList(0, i + 1));
            }
            String last = path.getLast();
            Object existing = parent.get(last);
            String named = "[" + String.join(".", path) + "]";
            if (arrayTable) {
                if (existing == null) {
                    Map<String, Object> element = new LinkedHashMap<>();
                    List<Object> array = new ArrayList<>();
                    array.add(element);
                    parent.put(last, array);
                    return element;
                }
                if (isArrayOfTables(existing)) {
                    Map<String, Object> element = new LinkedHashMap<>();
                    @SuppressWarnings("unchecked")
                    List<Object> array = (List<Object>) existing;
                    array.add(element);
                    return element;
                }
                throw error("cannot define " + named + " as an array of tables: it is already "
                    + describe(existing));
            }
            if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                parent.put(last, created);
                definedTables.add(created);
                return created;
            }
            if (existing instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> table = (Map<String, Object>) existing;
                // A super-table named after its sub-tables is allowed; the
                // sub-tables stay where they are. Identity, not path: a
                // header inside a second array-of-tables element names a
                // fresh table and may be defined afresh.
                if (!definedTables.add(table)) {
                    throw error("table " + named + " is defined twice");
                }
                return table;
            }
            throw error("cannot define table " + named + ": it is already "
                + describe(existing));
        }

        private Map<String, Object> descend(
                Map<String, Object> parent, String segment, List<String> pathSoFar) {
            Object existing = parent.get(segment);
            if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                parent.put(segment, created);
                return created;
            }
            if (existing instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> table = (Map<String, Object>) existing;
                return table;
            }
            if (isArrayOfTables(existing)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> element = (Map<String, Object>) ((List<?>) existing).getLast();
                return element;
            }
            throw error("cannot define [" + String.join(".", pathSoFar)
                + "]: '" + segment + "' is already " + describe(existing));
        }

        private static boolean isArrayOfTables(Object value) {
            return value instanceof List<?> list
                && !list.isEmpty()
                && list.getLast() instanceof Map<?, ?>;
        }

        private static String describe(Object value) {
            if (value instanceof Map<?, ?>) {
                return "a table";
            }
            if (isArrayOfTables(value)) {
                return "an array of tables";
            }
            return "a value";
        }

        /**
         * Parses one {@code key = value} line into {@code table} and returns
         * it, so the document loop can treat headers and key-values alike.
         */
        private Map<String, Object> parseKeyValue(Map<String, Object> table) {
            // The duplicate is the repeated key, not the value that follows
            // it: hand-edited files are fixed at the key.
            int keyLine = line;
            int keyColumn = column;
            String key = parseKey();
            skipHorizontal();
            if (peekIs('.')) {
                throw error("dotted keys are not supported in this TOML subset");
            }
            expect('=');
            skipHorizontal();
            Object value = parseValue();
            if (table.containsKey(key)) {
                throw new TomlParseException(keyLine, keyColumn, "duplicate key '" + key + "'");
            }
            table.put(key, value);
            expectLineEnd();
            return table;
        }

        // ------------------------------------------------------------------
        // keys and values
        // ------------------------------------------------------------------

        private String parseKey() {
            if (eof()) {
                throw error("expected a key");
            }
            if (peek() == '"') {
                return parseBasicString();
            }
            int start = index;
            while (!eof() && isBareKeyChar(peek())) {
                advance();
            }
            if (index == start) {
                throw error("expected a key (bare keys are A-Z a-z 0-9 '_' '-')");
            }
            return text.substring(start, index);
        }

        private Object parseValue() {
            if (eof() || peekIs('\n') || peekIs('#')) {
                throw error("expected a value after '='");
            }
            return switch (peek()) {
                case '"' -> {
                    if (text.startsWith("\"\"\"", index)) {
                        throw error(
                            "multi-line strings are not supported in this TOML subset");
                    }
                    yield parseBasicString();
                }
                case '\'' -> throw error(
                    "literal strings ('...') are not supported in this TOML subset");
                case '{' -> throw error(
                    "inline tables are not supported in this TOML subset");
                case 't' -> parseBareWord("true", Boolean.TRUE);
                case 'f' -> parseBareWord("false", Boolean.FALSE);
                case '[' -> parseStringArray();
                case '+', '-' -> parseInteger();
                default -> {
                    if (!isDigit(peek())) {
                        throw error(
                            "expected a value (string, integer, boolean or string array)");
                    }
                    yield parseInteger();
                }
            };
        }

        private Boolean parseBareWord(String word, Boolean value) {
            if (text.startsWith(word, index)) {
                index += word.length();
                column += word.length();
                return value;
            }
            throw error("unknown value; a bare word can only be true or false");
        }

        private Long parseInteger() {
            int start = index;
            if (peekIs('+') || peekIs('-')) {
                advance();
            }
            if (eof() || !isDigit(peek())) {
                throw error("expected a digit in an integer");
            }
            if (peek() == '0') {
                advance();
                if (!eof() && isDigit(peek())) {
                    throw error("leading zeros are not allowed in integers");
                }
            } else {
                while (!eof() && isDigit(peek())) {
                    advance();
                }
            }
            char next = eof() ? '\0' : peek();
            if (next == '.') {
                throw error("floats are not supported in this TOML subset");
            }
            if (next == '-') {
                throw error("date values are not supported in this TOML subset");
            }
            if (next == ':') {
                throw error("time values are not supported in this TOML subset");
            }
            if (next == '_') {
                throw error("digit separators are not supported in this TOML subset");
            }
            if (isAsciiLetter(next)) {
                throw error("integers are decimal only in this TOML subset");
            }
            try {
                return Long.parseLong(text, start, index, 10);
            } catch (NumberFormatException e) {
                throw error("integer out of 64-bit range");
            }
        }

        private String parseBasicString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw error("unterminated string");
                }
                char c = peek();
                if (c == '"') {
                    advance();
                    return out.toString();
                }
                if (c == '\n' || c == '\r') {
                    throw error("a basic string must stay on one line in this TOML subset");
                }
                if (c < 0x20 || c == 0x7F) {
                    throw error("unescaped control character in a string");
                }
                if (c != '\\') {
                    advance();
                    out.append(c);
                    continue;
                }
                advance(); // the backslash
                if (eof()) {
                    throw error("unterminated escape sequence");
                }
                char escape = peek();
                switch (escape) {
                    case '"' -> { advance(); out.append('"'); }
                    case '\\' -> { advance(); out.append('\\'); }
                    case 'b' -> { advance(); out.append('\b'); }
                    case 'f' -> { advance(); out.append('\f'); }
                    case 'n' -> { advance(); out.append('\n'); }
                    case 'r' -> { advance(); out.append('\r'); }
                    case 't' -> { advance(); out.append('\t'); }
                    case 'u' -> { advance(); out.append(parseUnicodeEscape()); }
                    default -> throw error(
                        "unsupported escape sequence '\\" + escape + "'");
                }
            }
        }

        private char parseUnicodeEscape() {
            if (index + 4 > text.length()) {
                throw error("unterminated unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = hexValue(text.charAt(index + i));
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            if (value >= 0xD800 && value <= 0xDFFF) {
                throw error("unicode escape is not a Unicode scalar value");
            }
            for (int i = 0; i < 4; i++) {
                advance();
            }
            return (char) value;
        }

        /** ASCII hex only — TOML escapes take {@code [0-9A-Fa-f]}, not Unicode digits. */
        private static int hexValue(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            return -1;
        }

        /**
         * Parses a single-line string array: string elements only, at least
         * one, optional trailing comma, no comments inside — the whole array
         * must stay on the line it starts on.
         */
        private List<String> parseStringArray() {
            expect('[');
            List<String> result = new ArrayList<>();
            skipHorizontal();
            if (peekIs(']')) {
                throw error("empty string arrays are not supported in this TOML subset");
            }
            while (true) {
                skipHorizontal();
                requireArrayElementStart();
                result.add(parseBasicString());
                skipHorizontal();
                if (consume(']')) {
                    return List.copyOf(result);
                }
                if (eof()) {
                    throw error("unterminated string array");
                }
                if (peekIs('\n')) {
                    throw error("a string array must stay on one line in this TOML subset");
                }
                if (peekIs('#')) {
                    throw error("comments inside a string array are not supported in this TOML subset");
                }
                expect(',');
                skipHorizontal();
                if (consume(']')) { // trailing comma
                    return List.copyOf(result);
                }
            }
        }

        private void requireArrayElementStart() {
            if (eof()) {
                throw error("unterminated string array");
            }
            if (peekIs('\n')) {
                throw error("a string array must stay on one line in this TOML subset");
            }
            if (peekIs('#')) {
                throw error("comments inside a string array are not supported in this TOML subset");
            }
        }

        // ------------------------------------------------------------------
        // cursor
        // ------------------------------------------------------------------

        private void skipLineEnds() {
            while (!eof()) {
                skipHorizontal();
                if (eof()) {
                    return;
                }
                if (peek() == '\n') {
                    advance();
                } else if (peek() == '#') {
                    skipComment();
                } else {
                    return;
                }
            }
        }

        private void skipHorizontal() {
            while (!eof() && (peek() == ' ' || peek() == '\t' || peek() == '\r')) {
                advance();
            }
        }

        private void skipComment() {
            while (!eof() && peek() != '\n') {
                advance();
            }
        }

        /** Horizontal whitespace, an optional comment, then the line's end. */
        private void expectLineEnd() {
            skipHorizontal();
            if (peekIs('#')) {
                skipComment();
            }
            if (eof() || peekIs('\n')) {
                return;
            }
            throw error("expected end of line");
        }

        private void expect(char expected) {
            if (eof() || peek() != expected) {
                throw error("expected '" + expected + "'");
            }
            advance();
        }

        private boolean consume(char expected) {
            if (peekIs(expected)) {
                advance();
                return true;
            }
            return false;
        }

        private boolean peekIs(char expected) {
            return !eof() && peek() == expected;
        }

        private char peek() {
            return text.charAt(index);
        }

        private boolean eof() {
            return index >= text.length();
        }

        private void advance() {
            char c = text.charAt(index++);
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }

        private TomlParseException error(String message) {
            return new TomlParseException(line, column, message);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isAsciiLetter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }

        private static boolean isBareKeyChar(char c) {
            return isDigit(c) || isAsciiLetter(c) || c == '_' || c == '-';
        }

        /**
         * Freezes the tree after parsing: tables and arrays of tables become
         * unmodifiable in place, so no caller can mutate a parsed document.
         */
        private static Object freeze(Object value) {
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> table = (Map<String, Object>) map;
                table.replaceAll((key, nested) -> freeze(nested));
                return Collections.unmodifiableMap(table);
            }
            if (value instanceof List<?> list) {
                List<Object> frozen = new ArrayList<>(list.size());
                for (Object element : list) {
                    frozen.add(freeze(element));
                }
                return Collections.unmodifiableList(frozen);
            }
            return value;
        }
    }
}
