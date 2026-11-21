package com.jujin.freeway.commons.toml;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlUtilsTest {

    // ------------------------------------------------------------------
    // the happy grammar
    // ------------------------------------------------------------------

    @Test
    void parsesTablesAndTypedValues() {
        Map<String, Object> parsed = TomlUtils.parse("""
            port = 9191
            debug = false
            name = "boot"

            [server]
            host = "localhost"
            workers = 4
            secure = true

            [server.tls]
            key-store = "ks.p12"
            """);

        assertEquals(9191L, parsed.get("port"));
        assertInstanceOf(Long.class, parsed.get("port"), "integers are Long");
        assertEquals(Boolean.FALSE, parsed.get("debug"));
        assertEquals("boot", parsed.get("name"));

        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) parsed.get("server");
        assertEquals("localhost", server.get("host"));
        assertEquals(4L, server.get("workers"));
        assertEquals(Boolean.TRUE, server.get("secure"));

        @SuppressWarnings("unchecked")
        Map<String, Object> tls = (Map<String, Object>) server.get("tls");
        assertEquals("ks.p12", tls.get("key-store"), "dotted headers nest");
    }

    @Test
    void parsesQuotedKeysWithCoordinates() {
        Map<String, Object> parsed = TomlUtils.parse("""
            [deps]
            "com.example:foo" = "1.2.3"

            [deps."com.corp:internal-lib"]
            version = "2.1.0"
            """);

        @SuppressWarnings("unchecked")
        Map<String, Object> deps = (Map<String, Object>) parsed.get("deps");
        assertEquals("1.2.3", deps.get("com.example:foo"),
            "quoted keys carry colons and dots");
        @SuppressWarnings("unchecked")
        Map<String, Object> internal = (Map<String, Object>) deps.get("com.corp:internal-lib");
        assertEquals("2.1.0", internal.get("version"));
    }

    @Test
    void parsesStringEscapes() {
        // The backslash-u spelling cannot appear verbatim in Java source — the
        // compiler's unicode pass consumes it before string escapes even run —
        // so the TOML unicode escape is assembled at runtime.
        String unicodeEscape = "\\" + (char) 'u' + "0041";
        Map<String, Object> parsed = TomlUtils.parse(
            "all = \"a\\tb\\nc\\fd\\re\\\"f\\\\g" + unicodeEscape + "\"\n");

        assertEquals("a\tb\nc\fd\re\"f\\gA", parsed.get("all"));
    }

    @Test
    void parsesStringArrays() {
        Map<String, Object> parsed = TomlUtils.parse("""
            tags = [ "a" , "b" ]
            ids = ["x", "y", ]
            """);

        assertEquals(List.of("a", "b"), parsed.get("tags"));
        assertEquals(List.of("x", "y"), parsed.get("ids"),
            "a trailing comma closes the array");
        assertInstanceOf(List.class, parsed.get("tags"));
    }

    @Test
    void parsesArraysOfTables() {
        Map<String, Object> parsed = TomlUtils.parse("""
            [[artifacts]]
            id = "a"
            depth = 1

            [[artifacts]]
            id = "b"

            [artifacts.extra]
            note = "attaches to the last element"
            """);

        Object value = parsed.get("artifacts");
        assertInstanceOf(List.class, value);
        List<?> artifacts = (List<?>) value;
        assertEquals(2, artifacts.size());
        assertEquals("a", ((Map<?, ?>) artifacts.getFirst()).get("id"));
        assertEquals(1L, ((Map<?, ?>) artifacts.getFirst()).get("depth"));
        assertEquals("b", ((Map<?, ?>) artifacts.getLast()).get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = (Map<String, Object>) ((Map<?, ?>) artifacts.getLast()).get("extra");
        assertEquals("attaches to the last element", extra.get("note"),
            "a sub-table header after an array of tables attaches to the last element");
    }

    @Test
    void allowsDefiningASuperTableAfterItsSubTables() {
        Map<String, Object> parsed = TomlUtils.parse("""
            [x.y.z]
            deep = true

            [x]
            shallow = 1
            """);

        @SuppressWarnings("unchecked")
        Map<String, Object> x = (Map<String, Object>) parsed.get("x");
        assertEquals(1L, x.get("shallow"));
        @SuppressWarnings("unchecked")
        Map<String, Object> y = (Map<String, Object>) x.get("y");
        assertEquals(true, ((Map<?, ?>) y.get("z")).get("deep"));
    }

    @Test
    void allowsTheSameSubTableHeaderInEachArrayOfTablesElement() {
        // Each array element is its own table scope: the second element's
        // [a.b] is implicitly created by [a.b.c] and may then be defined
        // explicitly. The first element's explicit [a.b] must not turn that
        // into a duplicate.
        Map<String, Object> parsed = TomlUtils.parse("""
            [[a]]
            [a.b]
            x = 1

            [[a]]
            [a.b.c]
            y = 2

            [a.b]
            z = 3
            """);

        List<?> elements = (List<?>) parsed.get("a");
        assertEquals(2, elements.size());
        Map<?, ?> b = (Map<?, ?>) ((Map<?, ?>) elements.getLast()).get("b");
        assertEquals(3L, b.get("z"));
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) b.get("c");
        assertEquals(2L, c.get("y"));
    }

    @Test
    void toleratesCommentsBlankLinesAndCrlf() {
        Map<String, Object> parsed = TomlUtils.parse(
            "# leading comment\r\n\r\nkey = \"value\" # trailing\r\n# tail\r\n");
        assertEquals("value", parsed.get("key"));
        assertEquals(Map.of(), TomlUtils.parse(""), "a blank document is an empty table");
        assertEquals(Map.of(), TomlUtils.parse("# only comments\n\n# more\n"));
    }

    @Test
    void stripsALeadingBom() {
        Map<String, Object> parsed = TomlUtils.parse("\uFEFFkey = \"v\"\n");
        assertEquals("v", parsed.get("key"));
    }

    // ------------------------------------------------------------------
    // duplicates and conflicts — errors, unlike JSON's last-one-wins
    // ------------------------------------------------------------------

    @Test
    void rejectsDuplicateKeys() {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse("[server]\nport = 1\nport = 2\n"));
        assertTrue(ex.getMessage().contains("duplicate key 'port'"),
            "got: " + ex.getMessage());
        assertEquals(3, ex.line());
    }

    @Test
    void duplicateKeyErrorsPointAtTheRepeatedKey() {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse("a = 1\nb = 2\na = 3\n"));

        assertEquals(3, ex.line());
        assertEquals(1, ex.column(), "the repeated key is where the mistake is");
        assertTrue(ex.getMessage().contains("duplicate key 'a'"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsADuplicateTableHeader() {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse("[a]\nk = 1\n[a]\nk = 2\n"));
        assertTrue(ex.getMessage().contains("defined twice"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsAHeaderWhereAValueStands() {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse("a = 1\n[a]\n"));
        assertTrue(ex.getMessage().contains("already a value"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsAValueWhereATableStands() {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse("[a]\nb = 1\n[a.b]\n"));
        assertTrue(ex.getMessage().contains("already a value"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsTableThenArrayOfTables() {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse("[a]\n[[a]]\n"));
        assertTrue(ex.getMessage().contains("already a table"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsArrayOfTablesThenTable() {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse("[[a]]\n[a]\n"));
        assertTrue(ex.getMessage().contains("already an array of tables"),
            "got: " + ex.getMessage());
    }

    @Test
    void rejectsAHeaderThroughAValue() {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse("a = 1\n[a.b]\n"));
        assertTrue(ex.getMessage().contains("'a' is already a value"), "got: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // the rejected grammar — named as such, never guessed
    // ------------------------------------------------------------------

    @Test
    void rejectsLiteralStrings() {
        assertRejectsSubset("key = 'literal'\n", "literal strings");
    }

    @Test
    void rejectsMultiLineStrings() {
        assertRejectsSubset("key = \"\"\"multi\"\"\"\n", "multi-line strings");
    }

    @Test
    void rejectsInlineTables() {
        assertRejectsSubset("point = { x = 1 }\n", "inline tables");
    }

    @Test
    void rejectsDottedKeys() {
        assertRejectsSubset("server.port = 9191\n", "dotted keys");
    }

    @Test
    void rejectsFloats() {
        assertRejectsSubset("ratio = 2.5\n", "floats");
    }

    @Test
    void rejectsDates() {
        assertRejectsSubset("day = 1979-05-27\n", "date values");
    }

    @Test
    void rejectsTimes() {
        assertRejectsSubset("at = 17:32:00\n", "time values");
    }

    @Test
    void rejectsDigitSeparators() {
        assertRejectsSubset("big = 1_000\n", "digit separators");
    }

    @Test
    void rejectsNonDecimalIntegers() {
        assertRejectsSubset("hex = 0x1F\n", "decimal only");
    }

    @Test
    void rejectsLeadingZeros() {
        assertRejectsSubset("zero = 007\n", "leading zeros");
    }

    @Test
    void rejectsOutOfRangeIntegers() {
        assertRejectsSubset("big = 9223372036854775808\n", "64-bit range");
    }

    @Test
    void rejectsEmptyStringArrays() {
        assertRejectsSubset("list = [ ]\n", "empty string arrays");
    }

    @Test
    void rejectsCommentsInsideAStringArray() {
        assertRejectsSubset("list = [ \"a\", # note\n \"b\" ]\n", "comments inside a string array");
    }

    @Test
    void rejectsAMultiLineStringArray() {
        assertRejectsSubset("list = [ \"a\",\n \"b\" ]\n", "stay on one line");
    }

    @Test
    void rejectsNonStringArrayElements() {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse("list = [ \"a\", 2 ]\n"));
        assertTrue(ex.getMessage().contains("expected '\"'"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsUnknownEscapes() {
        assertRejectsSubset("key = \"bad \\q escape\"\n", "unsupported escape sequence");
    }

    @Test
    void rejectsNonAsciiHexDigitsInAUnicodeEscape() {
        // Full-width digits are Unicode digits; TOML hex digits are ASCII.
        String fullWidth = "\\" + (char) 'u' + "００４１";
        assertRejectsSubset("key = \"" + fullWidth + "\"\n", "invalid unicode escape");
    }

    @Test
    void rejectsSurrogateCodePointsInAUnicodeEscape() {
        // A lone surrogate is not a Unicode scalar value, so it is not a
        // legal TOML escape — and a pair spelled as two backslash-u escapes
        // is not either.
        String loneSurrogate = "\\" + (char) 'u' + "D800";
        assertRejectsSubset("key = \"" + loneSurrogate + "\"\n",
            "not a Unicode scalar value");
    }

    @Test
    void rejectsUnescapedControlCharacters() {
        assertRejectsSubset("key = \"tab\there\"\n", "control character");
        assertRejectsSubset("key = \"del" + (char) 0x7F + "here\"\n", "control character");
    }

    @Test
    void rejectsANewlineInsideABasicString() {
        assertRejectsSubset("key = \"line one\nline two\"\n", "stay on one line");
    }

    @Test
    void rejectsContentAfterAValue() {
        assertRejectsSubset("key = 1 2\n", "expected end of line");
    }

    @Test
    void rejectsContentAfterAHeader() {
        assertRejectsSubset("[a] junk\nk = 1\n", "expected end of line");
    }

    @Test
    void rejectsAMissingValue() {
        assertRejectsSubset("key =\n", "expected a value after '='");
    }

    @Test
    void rejectsAnUnknownBareWord() {
        assertRejectsSubset("key = tru\n", "bare word can only be true or false");
    }

    // ------------------------------------------------------------------
    // the failure contract
    // ------------------------------------------------------------------

    @Test
    void errorsCarryTheOffendingPosition() {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse("a = 1\nb = @\n"));
        assertEquals(2, ex.line());
        assertEquals(5, ex.column(), "'@' is the fifth character of line 2");
        assertEquals("line 2, column 5: expected a value (string, integer, boolean or string array)",
            ex.getMessage());
    }

    @Test
    void oversizedInputIsRejectedBeforeParsing() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> TomlUtils.parse("k = \"v\"\n".repeat(3_000_000)));
        assertTrue(ex.getMessage().contains("too large"), "got: " + ex.getMessage());
    }

    @Test
    void theParsedTreeIsImmutable() {
        Map<String, Object> parsed = TomlUtils.parse("""
            [server]
            tags = [ "a", "b" ]
            """);

        assertThrows(UnsupportedOperationException.class, () -> parsed.put("x", "y"));
        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) parsed.get("server");
        assertThrows(UnsupportedOperationException.class, () -> server.put("x", "y"));
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) server.get("tags");
        assertThrows(UnsupportedOperationException.class, () -> tags.add("c"));
    }

    private static void assertRejectsSubset(String document, String expectedMessagePart) {
        TomlParseException ex = assertThrows(TomlParseException.class,
            () -> TomlUtils.parse(document));
        assertTrue(ex.getMessage().contains(expectedMessagePart),
            "expected the error to name the subset rule '" + expectedMessagePart
                + "', got: " + ex.getMessage());
    }
}
