package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.UnknownSymbolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

final class SymbolSourceDefault implements SymbolSource {
    private static final int MAX_EXPAND_DEPTH = 40;

    /** Explicitly contributed providers (e.g. the boot config cascade) — checked first. */
    private final CopyOnWriteArrayList<SymbolProvider> contributed = new CopyOnWriteArrayList<>();

    /** Built-in fallbacks (system properties, environment variables) — checked last. */
    private final CopyOnWriteArrayList<SymbolProvider> defaults = new CopyOnWriteArrayList<>();

    SymbolSourceDefault(List<SymbolProvider> providers) {
        this.defaults.addAll(Objects.requireNonNull(providers, "providers"));
    }

    /**
     * Creates a standard symbol source that looks up values from System
     * Properties first, then falls back to Environment Variables.
     * <p>
     * Lookup order: {@link System#getProperty(String)} first, then
     * {@link System#getenv(String)} (property wins).
     * <p>
     * Note: {@code System.getenv()} behaviour depends on the OS:
     * <ul>
     *   <li><b>Windows</b>: case-insensitive — {@code PATH} equals {@code path}</li>
     *   <li><b>Linux / macOS</b>: case-sensitive — {@code PATH} and {@code path} are distinct</li>
     * </ul>
     */
    static SymbolSourceDefault standard() {
        List<SymbolProvider> providers = new ArrayList<>();
        providers.add(System::getProperty);
        providers.add(System::getenv);
        return new SymbolSourceDefault(providers);
    }

    void register(SymbolProvider provider) {
        // Explicit providers take priority over the implicit system sources so
        // the documented config cascade (CLI > env > files, via AppConfig) is
        // honored by @Value/@Symbol instead of being outranked by JVM -D flags.
        contributed.add(Objects.requireNonNull(provider, "provider"));
    }

    @Override
    public String resolve(String name) {
        String value = raw(name);
        if (value != null) {
            return expand(value);
        }
        // The typed sentinel is what resolve(name, default) matches on. Only
        // this top-level miss carries it — an unknown symbol nested inside
        // another value's expansion (expand() below) stays a plain IAE and
        // propagates instead of degrading to the default.
        throw new UnknownSymbolException(name);
    }

    private String raw(String name) {
        for (SymbolProvider provider : contributed) {
            String value = provider.lookup(name);
            if (value != null) {
                return value;
            }
        }
        for (SymbolProvider provider : defaults) {
            String value = provider.lookup(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Recursively expands {@code ${...}} symbol references in the input string.
     * <p>
     * If the expanded value itself contains {@code ${...}} expressions they
     * will be expanded recursively. This means that if a symbol's value
     * contains unescaped {@code ${...}} syntax that matches another symbol
     * name, it will also be expanded.
     * <p>
     * Escape syntax: a backslash immediately before {@code ${} emits a literal
     * {@code ${} — e.g. {@code "price is \${total}"} stays as-is. An even run
     * of backslashes leaves the expression active (the backslashes are literal).
     */
    @Override
    public String expand(String input) {
        return expand(input, 0);
    }

    /**
     * Finds the closing {@code }} for the expression starting at
     * {@code ${} at {@code from}-1. Every {@code {} — nested {@code ${...}}
     * references and literal braces inside a default value alike — is
     * tracked by depth, so {@code ${a:${b}}} and {@code ${a:x{y}z}} parse
     * as symbol {@code a} with the full default {@code ${b}} / {@code x{y}z}
     * instead of ending at the inner brace.
     */
    private static int closingBrace(String input, int from) {
        int depth = 0;
        for (int i = from; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    /**
     * Recursively expands {@code ${...}} symbol references with a depth limit
     * to prevent stack overflow.
     * <p>
     * If the expanded value itself contains {@code ${...}} expressions they
     * will be expanded recursively. This means that if a symbol's value
     * contains unescaped {@code ${...}} syntax that matches another symbol
     * name, it will also be expanded.
     * <p>
     * Default value syntax {@code ${name:-default}} — if the default value
     * itself contains {@code ${...}} it will also be expanded, so avoid
     * introducing circular references in defaults.
     *
     * @param input the string to expand
     * @param depth the current recursion depth
     * @return the expanded string
     * @throws IllegalArgumentException if depth exceeds the limit or a symbol is unclosed
     */
    private String expand(String input, int depth) {
        if (depth > MAX_EXPAND_DEPTH) {
            throw new IllegalArgumentException(
                "Symbol expansion exceeded max depth of " + MAX_EXPAND_DEPTH + ": " + input
            );
        }
        if (input == null || input.indexOf("${") < 0) {
            return input;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            int start = input.indexOf("${", i);
            if (start < 0) {
                out.append(input, i, input.length());
                break;
            }
            // Count the backslash run immediately before "${". An odd run
            // escapes the expression: drop one backslash and emit "${" literally.
            int backslashes = 0;
            for (int k = start - 1; k >= i && input.charAt(k) == '\\'; k--) {
                backslashes++;
            }
            if ((backslashes & 1) == 1) {
                out.append(input, i, start - 1);
                out.append("${");
                i = start + 2;
                continue;
            }
            out.append(input, i, start);
            int end = closingBrace(input, start + 2);
            if (end < 0) {
                throw new IllegalArgumentException("Unclosed symbol expression in: " + input);
            }
            String expr = input.substring(start + 2, end);
            String symbol = expr;
            String defaultValue = null;
            int colon = expr.indexOf(':');
            if (colon >= 0) {
                symbol = expr.substring(0, colon);
                defaultValue = expr.substring(colon + 1);
                // ${name:-default} — the ":-" separator (shell semantics)
                // drops a single leading dash from the default, so
                // ${port:-8080} yields "8080" not "-8080".
                // ${name:default} keeps the default verbatim, and
                // ${name:} / ${name:-} both yield the empty string.
                if (defaultValue.startsWith("-")) {
                    defaultValue = defaultValue.substring(1);
                }
            }
            // Whitespace around the symbol name is formatting, not identity:
            // ${ port } looks up "port". Defaults keep their verbatim value.
            symbol = symbol.trim();
            String value = raw(symbol);
            if (value == null) {
                value = defaultValue;
            }
            if (value == null) {
                throw new IllegalArgumentException("Unknown symbol: " + symbol);
            }
            out.append(expand(value, depth + 1));
            i = end + 1;
        }
        return out.toString();
    }
}
