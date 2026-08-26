package com.jujin.freeway.ioc.symbol;

/**
 * Thrown by {@link SymbolSource#resolve(String)} when no provider supplies a
 * value for the symbol — the structured counterpart of a plain
 * {@link IllegalArgumentException}, so {@code resolve(name, defaultValue)}
 * can detect a miss by type instead of matching exception message text.
 *
 * <p>Only a top-level resolution miss carries this type: an unknown symbol
 * encountered <em>inside</em> another symbol's expanded value fails with a
 * plain {@code IllegalArgumentException} and is never mapped to a default —
 * a broken config chain must not silently degrade.
 */
public final class UnknownSymbolException extends IllegalArgumentException {

    private final String name;

    /** @param name the symbol that no provider could resolve */
    public UnknownSymbolException(String name) {
        super("Unknown symbol: " + name);
        this.name = java.util.Objects.requireNonNull(name, "name");
    }

    /** The unresolved symbol name. */
    public String name() {
        return name;
    }
}
