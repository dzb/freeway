package com.jujin.freeway.commons.toml;

/**
 * A TOML document did not parse. Carries the one-based {@code line()} and
 * {@code column()} of the offending position — the message names them in the
 * same shape ({@code line L, column C: detail}), and callers that know the
 * source file prepend it, so a failure always reads
 * {@code <file>: line L, column C: detail}.
 */
public final class TomlParseException extends IllegalArgumentException {

    private final int line;
    private final int column;

    TomlParseException(int line, int column, String message) {
        super("line " + line + ", column " + column + ": " + message);
        this.line = line;
        this.column = column;
    }

    /** The one-based line of the offending position. */
    public int line() {
        return line;
    }

    /** The one-based column of the offending position. */
    public int column() {
        return column;
    }
}
