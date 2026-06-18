package com.jujin.freeway.ioc.symbol;

public interface SymbolSource {

    /**
     * Resolves a symbol to its value, or throws if the symbol is unknown.
     */
    String resolve(String name);

    /**
     * Resolves a symbol to its value, returning {@code defaultValue} when the
     * symbol is not found. Delegates to {@link #expand(String)} with the
     * {@code ${name:default}} syntax.
     */
    default String resolve(String name, String defaultValue) {
        return expand("${" + name + ":" + defaultValue + "}");
    }

    String expand(String input);
}
