package com.jujin.freeway.commons.toml;

import java.util.Map;

/**
 * Static TOML entry point: parsing config and manifest documents in the
 * subset grammar Freeway accepts.
 *
 * <p><b>The subset.</b> Tables {@code [a.b]} and arrays of tables
 * {@code [[a]]} (bare or quoted key segments), bare keys
 * ({@code A-Z a-z 0-9 _ -}) and basic-string keys, basic strings with the
 * {@code \b \t \n \f \r \" \\} escapes plus four-digit unicode escapes,
 * decimal 64-bit integers, booleans, and single-line string arrays. Comments
 * ({@code #}) live on their own line, after a value, or after a header — the
 * whole document is line-oriented, so multi-line strings, literal strings,
 * inline tables, dotted keys, floats and date/time values are rejected and
 * named as such rather than guessed at.
 *
 * <p><b>Duplicates.</b> A repeated key, a table header defined twice, or a
 * header colliding with an already-assigned value is an error — unlike JSON,
 * where the last duplicate wins. Config and manifest files are hand-edited;
 * a repeated key there is a mistake to surface, not a precedence rule.
 *
 * <p><b>The result.</b> An immutable tree in document order: tables are
 * {@code Map<String, Object>}, values are {@code String}, {@code Long},
 * {@code Boolean}, {@code List<String>} (string arrays) or
 * {@code List<Map<String, Object>>} (arrays of tables). The configuration
 * cascade flattens this tree with {@code Maps.flatten}; applications that
 * want typed values read the tree directly.
 *
 * <p><b>Failure.</b> Malformed input throws {@link TomlParseException}, which
 * carries the one-based {@code line()} and {@code column()} and names both in
 * its message; a caller that knows the source file prepends it, so the
 * failure reads {@code <file>: line L, column C: detail}.
 */
public final class TomlUtils {

    private TomlUtils() {}

    /**
     * Parses one TOML document into an immutable table; a blank document
     * yields an empty map. See the class javadoc for the grammar, the
     * duplicate rules and the failure contract.
     */
    public static Map<String, Object> parse(String text) {
        return TomlParser.parse(text);
    }
}
