package com.jujin.freeway.db.util;

import com.jujin.freeway.db.dialect.Dialect;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL text scanning: named/positional parameter detection and statement
 * splitting.
 *
 * <p>The scanner never decides by itself which quoting style or comment marker
 * a database uses — those syntax features are declared by {@link Dialect}
 * (see {@link Dialect#identifierQuoteChars()}, {@link Dialect#hashLineComments()},
 * {@link Dialect#dollarQuoting()}, {@link Dialect#escapeStringPrefix()},
 * {@link Dialect#bracketQuoting()}) and consumed via {@link LexerConfig}.
 * Where no dialect is bound (e.g. {@code Sql} builder fragments normalized
 * before a database is known), {@link LexerConfig#SUPERSET} recognizes every
 * built-in dialect's syntax leniently.
 */
public final class SqlTextParser {

    private SqlTextParser() {}

    public record Result(
        List<String> names,
        String sql,
        List<Integer> parameterIndexes
    ) {}

    // ====================== lexer profile ======================

    /**
     * Dialect-declared lexing capabilities. Built from a {@link Dialect} via
     * {@link #of(Dialect)}, or the lenient {@link #SUPERSET} when no dialect
     * is known.
     */
    public static final class LexerConfig {

        private final String identifierQuoteChars;
        private final boolean hashLineComments;
        private final boolean bracketQuoting;
        private final boolean dollarQuoting;
        private final boolean escapeStringPrefix;

        private LexerConfig(
            String identifierQuoteChars,
            boolean hashLineComments,
            boolean bracketQuoting,
            boolean dollarQuoting,
            boolean escapeStringPrefix
        ) {
            this.identifierQuoteChars = identifierQuoteChars;
            this.hashLineComments = hashLineComments;
            this.bracketQuoting = bracketQuoting;
            this.dollarQuoting = dollarQuoting;
            this.escapeStringPrefix = escapeStringPrefix;
        }

        /** Union of every built-in dialect's syntax — the lenient default. */
        public static final LexerConfig SUPERSET = new LexerConfig(
            "\"`",
            true,
            true,
            true,
            true
        );

        /** Capabilities declared by the given dialect. */
        public static LexerConfig of(Dialect dialect) {
            return new LexerConfig(
                dialect.identifierQuoteChars(),
                dialect.hashLineComments(),
                dialect.bracketQuoting(),
                dialect.dollarQuoting(),
                dialect.escapeStringPrefix()
            );
        }

        boolean isQuoteChar(char c) {
            return identifierQuoteChars.indexOf(c) >= 0;
        }

        boolean hashLineComments() {
            return hashLineComments;
        }

        boolean bracketQuoting() {
            return bracketQuoting;
        }

        boolean dollarQuoting() {
            return dollarQuoting;
        }

        boolean escapeStringPrefix() {
            return escapeStringPrefix;
        }
    }

    /**
     * Consumes one scan pass over SQL text. All methods are no-ops by
     * default — implement only what is needed.
     */
    public interface TokenSink {

        /** Raw text slice {@code [from, to)} of {@code sql} — append verbatim. */
        default void text(String sql, int from, int to) {}

        /**
         * A named parameter ({@code :name} / {@code $name}); {@code sourceIndex}
         * points at the {@code :} / {@code $} marker.
         */
        default void named(String name, int sourceIndex) {}

        /** A positional placeholder {@code ?} at {@code sourceIndex}. */
        default void positional(int sourceIndex) {}
    }

    /**
     * Single scan pass over {@code sql}, classifying tokens per
     * {@code config}: string literals, dialect-declared quoted identifiers,
     * {@code --} / {@code #} / {@code /* *&#47;} comments, {@code E'...'}
     * escape strings, {@code $tag$} dollar quotes, {@code ::} casts, named
     * parameters and positional {@code ?}.
     */
    public static void scan(String sql, LexerConfig config, TokenSink sink) {
        int len = sql.length();
        int textStart = 0;
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int after = skipQuoted(sql, i, '\'');
                sink.text(sql, textStart, after);
                i = after;
                textStart = i;
                continue;
            }
            if (config.isQuoteChar(c)) {
                int after = skipQuoted(sql, i, c);
                sink.text(sql, textStart, after);
                i = after;
                textStart = i;
                continue;
            }
            if (config.bracketQuoting() && c == '[') {
                int after = skipBracketQuote(sql, i);
                sink.text(sql, textStart, after);
                i = after;
                textStart = i;
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int after = skipLineComment(sql, i);
                sink.text(sql, textStart, after);
                i = after;
                textStart = i;
                continue;
            }
            if (
                config.hashLineComments() &&
                c == '#' &&
                !(i + 1 < len && sql.charAt(i + 1) == '>')
            ) {
                // # starts a line comment (MySQL) — except the #> / #>> jsonb
                // path operators.
                int after = skipHashComment(sql, i);
                sink.text(sql, textStart, after);
                i = after;
                textStart = i;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int after = skipBlockComment(sql, i);
                sink.text(sql, textStart, after);
                i = after;
                textStart = i;
                continue;
            }
            if (
                config.escapeStringPrefix() &&
                (c == 'E' || c == 'e') &&
                i + 1 < len &&
                sql.charAt(i + 1) == '\''
            ) {
                int after = skipEscapeString(sql, i);
                sink.text(sql, textStart, after);
                i = after;
                textStart = i;
                continue;
            }
            if (config.dollarQuoting() && c == '$') {
                int after = skipDollarQuote(sql, i);
                if (after > i) {
                    sink.text(sql, textStart, after);
                    i = after;
                    textStart = i;
                    continue;
                }
            }
            if (c == ':' && i + 1 < len && sql.charAt(i + 1) == ':') {
                // PostgreSQL :: cast — never a parameter marker. (Names never
                // start with ':', so this is also handled by the param branch
                // below; kept explicit for clarity.)
                i += 2;
                continue;
            }
            if (
                (c == ':' || c == '$') &&
                i + 1 < len &&
                Names.isValidParamStart(sql.charAt(i + 1))
            ) {
                int start = i + 1;
                int end = start;
                while (end < len && Names.isValidParamChar(sql.charAt(end))) {
                    end++;
                }
                sink.text(sql, textStart, i);
                sink.named(sql.substring(start, end), i);
                i = end;
                textStart = i;
                continue;
            }
            if (c == '?') {
                sink.text(sql, textStart, i);
                sink.positional(i);
                i++;
                textStart = i;
                continue;
            }
            i++;
        }
        if (len > textStart) {
            sink.text(sql, textStart, len);
        }
    }

    // ====================== named parameter parsing ======================

    /**
     * Note on {@code $} handling: with dollar quoting enabled (PostgreSQL),
     * {@code $$...$$} / {@code $tag$...$tag$} literals are skipped before
     * named parameters are recognized, so a named parameter written directly
     * before a literal {@code $} (e.g. {@code WHERE x = $name$}) is ambiguous
     * and resolves to the parameter plus a literal {@code $} — write
     * {@code WHERE x = $name || '$'} or use {@code :name} in that case.
     */
    public static Result parseNamed(String sql) {
        return parseNamed(sql, LexerConfig.SUPERSET);
    }

    /** Dialect-aware variant: parses per the target database's declared syntax. */
    public static Result parseNamed(String sql, Dialect dialect) {
        return parseNamed(sql, LexerConfig.of(dialect));
    }

    static Result parseNamed(String sql, LexerConfig config) {
        class Builder implements TokenSink {
            final StringBuilder sb = new StringBuilder(sql.length());
            final List<String> names = new ArrayList<>();
            final List<Integer> parameterIndexes = new ArrayList<>();

            @Override
            public void text(String s, int from, int to) {
                sb.append(s, from, to);
            }

            @Override
            public void named(String name, int sourceIndex) {
                names.add(name);
                parameterIndexes.add(sb.length());
                sb.append('?');
            }

            @Override
            public void positional(int sourceIndex) {
                // Preserve the original ? placeholder in the output text.
                sb.append('?');
            }
        }
        Builder builder = new Builder();
        scan(sql, config, builder);
        return new Result(
            List.copyOf(builder.names),
            builder.sb.toString(),
            List.copyOf(builder.parameterIndexes)
        );
    }

    // ====================== positional placeholder counting ======================

    public static List<Integer> paramIndexes(String sql) {
        return paramIndexes(sql, LexerConfig.SUPERSET);
    }

    /** Dialect-aware variant. */
    public static List<Integer> paramIndexes(String sql, Dialect dialect) {
        return paramIndexes(sql, LexerConfig.of(dialect));
    }

    static List<Integer> paramIndexes(String sql, LexerConfig config) {
        class Collector implements TokenSink {
            final List<Integer> indexes = new ArrayList<>();

            @Override
            public void positional(int sourceIndex) {
                indexes.add(sourceIndex);
            }
        }
        Collector collector = new Collector();
        scan(sql, config, collector);
        return List.copyOf(collector.indexes);
    }

    // ====================== presence checks ======================

    public static boolean hasNamedPlaceholders(String sql) {
        return hasNamedPlaceholders(sql, LexerConfig.SUPERSET);
    }

    /** Dialect-aware variant. */
    public static boolean hasNamedPlaceholders(String sql, Dialect dialect) {
        return hasNamedPlaceholders(sql, LexerConfig.of(dialect));
    }

    static boolean hasNamedPlaceholders(String sql, LexerConfig config) {
        class Flag implements TokenSink {
            boolean found;

            @Override
            public void named(String name, int sourceIndex) {
                found = true;
            }
        }
        Flag flag = new Flag();
        scan(sql, config, flag);
        return flag.found;
    }

    /**
     * Validates that the SQL does not mix named ({@code :name / $name}) and
     * positional ({@code ?}) placeholders. Throws {@link com.jujin.freeway.db.SqlException}
     * if both styles are present.
     */
    public static void requireNoMixedPlaceholders(String sql) {
        requireNoMixedPlaceholders(sql, LexerConfig.SUPERSET);
    }

    /** Dialect-aware variant. */
    public static void requireNoMixedPlaceholders(String sql, Dialect dialect) {
        requireNoMixedPlaceholders(sql, LexerConfig.of(dialect));
    }

    static void requireNoMixedPlaceholders(String sql, LexerConfig config) {
        if (
            hasNamedPlaceholders(sql, config) &&
            !paramIndexes(sql, config).isEmpty()
        ) {
            throw new com.jujin.freeway.db.SqlException(
                "Cannot mix named and positional placeholders in SQL: " + sql);
        }
    }

    // ====================== INSERT detection ======================

    /**
     * Returns true when the statement is an INSERT — the first keyword is
     * {@code INSERT}, or the statement opens with {@code WITH} and a top-level
     * {@code INSERT} follows the CTE block. A depth-0 {@code insert} token in
     * the middle of a SELECT (e.g. {@code select * from insert into}) no
     * longer matches.
     */
    public static boolean hasTopLevelInsert(String sql) {
        return hasTopLevelInsert(sql, LexerConfig.SUPERSET);
    }

    /** Dialect-aware variant. */
    public static boolean hasTopLevelInsert(String sql, Dialect dialect) {
        return hasTopLevelInsert(sql, LexerConfig.of(dialect));
    }

    static boolean hasTopLevelInsert(String sql, LexerConfig config) {
        if (sql == null) {
            return false;
        }
        int len = sql.length();
        int i = 0;
        int depth = 0;
        boolean firstWord = true;
        boolean sawWith = false;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
                continue;
            }
            if (config.isQuoteChar(c)) {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (config.bracketQuoting() && c == '[') {
                i = skipBracketQuote(sql, i);
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
                continue;
            }
            if (
                config.hashLineComments() &&
                c == '#' &&
                !(i + 1 < len && sql.charAt(i + 1) == '>')
            ) {
                i = skipHashComment(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            if (
                config.escapeStringPrefix() &&
                (c == 'E' || c == 'e') &&
                i + 1 < len &&
                sql.charAt(i + 1) == '\''
            ) {
                i = skipEscapeString(sql, i);
                continue;
            }
            if (config.dollarQuoting() && c == '$') {
                int after = skipDollarQuote(sql, i);
                if (after > i) {
                    i = after;
                    continue;
                }
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')' && depth > 0) {
                depth--;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (depth == 0 && Character.isLetter(c)) {
                int start = i;
                i++;
                while (i < len && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }
                String word = sql.substring(start, i);
                if (firstWord) {
                    firstWord = false;
                    if (word.equalsIgnoreCase("insert")) {
                        return true;
                    }
                    if (word.equalsIgnoreCase("with")) {
                        sawWith = true;
                        continue;
                    }
                    return false;
                }
                if (sawWith && word.equalsIgnoreCase("insert")) {
                    return true;
                }
                continue;
            }
            i++;
        }
        return false;
    }

    // ====================== statement splitting ======================

    public static List<String> splitStatements(String sql) {
        return splitStatements(sql, LexerConfig.SUPERSET);
    }

    /** Dialect-aware variant: {@code #} comments only split as comments where the dialect declares them. */
    public static List<String> splitStatements(String sql, Dialect dialect) {
        return splitStatements(sql, LexerConfig.of(dialect));
    }

    static List<String> splitStatements(String sql, LexerConfig config) {
        if (sql.isEmpty()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder(sql.length());
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int after = skipQuoted(sql, i, '\'');
                current.append(sql, i, after);
                i = after;
                continue;
            }
            if (config.isQuoteChar(c)) {
                int after = skipQuoted(sql, i, c);
                current.append(sql, i, after);
                i = after;
                continue;
            }
            if (config.bracketQuoting() && c == '[') {
                int after = skipBracketQuote(sql, i);
                current.append(sql, i, after);
                i = after;
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                // line comments are dropped from statements
                i = skipLineComment(sql, i);
                continue;
            }
            if (
                config.hashLineComments() &&
                c == '#' &&
                !(i + 1 < len && sql.charAt(i + 1) == '>')
            ) {
                i = skipHashComment(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                // block comments are dropped from statements, matching the
                // line-comment handling
                i = skipBlockComment(sql, i);
                continue;
            }
            if (
                config.escapeStringPrefix() &&
                (c == 'E' || c == 'e') &&
                i + 1 < len &&
                sql.charAt(i + 1) == '\''
            ) {
                int after = skipEscapeString(sql, i);
                current.append(sql, i, after);
                i = after;
                continue;
            }
            if (config.dollarQuoting() && c == '$') {
                int after = skipDollarQuote(sql, i);
                if (after > i) {
                    current.append(sql, i, after);
                    i = after;
                    continue;
                }
            }
            if (c == ';') {
                addStatement(statements, current);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    // ====================== skip primitives ======================

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            i++;
            if (c == quote) {
                if (i < len && sql.charAt(i) == quote) {
                    i++;
                } else {
                    break;
                }
            }
        }
        return i;
    }

    private static int skipLineComment(String sql, int start) {
        int i = start;
        int len = sql.length();
        if (i + 1 < len && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
            i += 2;
            while (i < len && sql.charAt(i) != '\n') {
                i++;
            }
            return i;
        }
        return start;
    }

    private static int skipBlockComment(String sql, int start) {
        int i = start;
        int len = sql.length();
        if (i + 1 < len && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
            i += 2;
            while (i < len) {
                char c = sql.charAt(i);
                i++;
                if (c == '*' && i < len && sql.charAt(i) == '/') {
                    i++;
                    break;
                }
            }
            return i;
        }
        return start;
    }

    private static int skipHashComment(String sql, int start) {
        int i = start;
        int len = sql.length();
        while (i < len && sql.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBracketQuote(String sql, int start) {
        int i = start + 1;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            i++;
            if (c == ']') {
                if (i < len && sql.charAt(i) == ']') {
                    i++;
                } else {
                    break;
                }
            }
        }
        return i;
    }

    /**
     * Skips a PostgreSQL escape string {@code E'...'} starting at {@code start}
     * (the {@code E}). Backslash escapes and doubled quotes ({@code ''}) are
     * handled; the scan ends at the first unescaped quote.
     */
    private static int skipEscapeString(String sql, int start) {
        int i = start + 2;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            i++;
            if (c == '\\' && i < len) {
                i++;
                continue;
            }
            if (c == '\'') {
                if (i < len && sql.charAt(i) == '\'') {
                    i++;
                    continue;
                }
                break;
            }
        }
        return i;
    }

    private static int skipDollarQuote(String sql, int start) {
        int len = sql.length();
        int tagEnd = start + 1;
        if (tagEnd >= len) {
            return start;
        }
        char next = sql.charAt(tagEnd);
        if (next != '$' && !Character.isLetterOrDigit(next) && next != '_') {
            return start;
        }
        if (next == '$') {
            int end = sql.indexOf("$$", start + 2);
            if (end < 0) {
                // Unterminated $$... — consume the opener as literal text so
                // the body is neither appended twice nor re-scanned as
                // parameters.
                return start + 2;
            }
            return end + 2;
        }
        int tagEndIdx = tagEnd;
        while (tagEndIdx < len && (Character.isLetterOrDigit(sql.charAt(tagEndIdx)) || sql.charAt(tagEndIdx) == '_')) {
            tagEndIdx++;
        }
        if (tagEndIdx >= len || sql.charAt(tagEndIdx) != '$') {
            return start;
        }
        String tag = sql.substring(start, tagEndIdx + 1);
        int bodyStart = tagEndIdx + 1;
        int end = sql.indexOf(tag, bodyStart);
        if (end < 0) {
            return start;
        }
        return end + tag.length();
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }
}
