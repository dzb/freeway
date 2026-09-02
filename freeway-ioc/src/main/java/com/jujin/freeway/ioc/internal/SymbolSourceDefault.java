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

    /** All providers — the framework's own tiers (CLI, JVM system properties,
     *  mapped env, config files) plus module contributions — consulted
     *  by declared {@link SymbolProvider#order()}; equal orders keep
     *  contribution order (stable sort). */
    private final CopyOnWriteArrayList<SymbolProvider> providers = new CopyOnWriteArrayList<>();

    /** Providers sorted by declared order (stable: ties keep contribution
     *  order). Built lazily on first resolve — sorting calls {@code order()}
     *  on every provider, which would force the on-demand class-contribution
     *  facades to materialize at bind time otherwise. */
    private volatile List<SymbolProvider> ordered;

    SymbolSourceDefault(List<SymbolProvider> providers) {
        this.providers.addAll(Objects.requireNonNull(providers, "providers"));
    }

    /**
     * Creates a standard symbol source with the JVM system-properties tier —
     * the process-level override available to every container, including
     * bare containers without the boot cascade. The boot cascade (CLI,
     * mapped env, config files) registers on top through
     * {@link #register(SymbolProvider)}. There is deliberately no raw-env
     * fallback: environment variables reach the chain only through the
     * declared prefix mapping, so an unknown symbol fails instead of
     * silently matching an unrelated variable.
     */
    static SymbolSourceDefault standard() {
        return new SymbolSourceDefault(List.of(
            new SymbolProvider() {
                @Override
                public String lookup(String name) {
                    return System.getProperty(name);
                }

                @Override
                public int order() {
                    return TIER_SYS_PROPS;
                }
            }));
    }

    void register(SymbolProvider provider) {
        // Every provider sits in one ordered list — the declared order()
        // decides, never the install order of the contributing module.
        providers.add(Objects.requireNonNull(provider, "provider"));
        ordered = null; // invalidate the sorted snapshot
    }

    /** All providers in declared precedence order (ascending {@code order()});
     *  equal orders keep contribution order (stable sort). */
    private List<SymbolProvider> orderedProviders() {
        List<SymbolProvider> cached = ordered;
        if (cached == null) {
            List<SymbolProvider> sorted = new ArrayList<>(providers);
            sorted.sort(java.util.Comparator.comparingInt(SymbolProvider::order));
            ordered = cached = List.copyOf(sorted);
        }
        return cached;
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
        for (SymbolProvider provider : orderedProviders()) {
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
