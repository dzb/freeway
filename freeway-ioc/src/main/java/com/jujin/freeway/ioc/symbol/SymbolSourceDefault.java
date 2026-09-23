package com.jujin.freeway.ioc.symbol;

import com.jujin.freeway.commons.coercion.Coercer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The built-in {@link SymbolSource}: one chain over {@link SymbolProvider}
 * tiers, assembled by {@link SymbolSource#of}.
 *
 * <p>Static tiers (the container's system-properties tier) sit beside the
 * contributed view, which the container supplies as a live read of its
 * {@code SymbolProvider} extension store — boot contributes the application's
 * tiers (CLI, mapped env, config files) and every module contribution flows
 * through the same store. There is no install step: a declared contribution
 * is visible on the next lookup, and a replaced source takes the same view.
 *
 * <p>There is deliberately no raw-env fallback: environment variables reach
 * the chain only through the declared prefix mapping, so an unknown symbol
 * fails instead of silently matching an unrelated variable.
 */
final class SymbolSourceDefault implements SymbolSource {
    private static final int MAX_EXPAND_DEPTH = 40;

    /** The static tiers — immutable for the source's lifetime. */
    private final List<SymbolProvider> statics;

    /** The live contributed view — re-read on every lookup. */
    private final Supplier<List<SymbolProvider>> contributed;

    /**
     * The merge of statics + contributed view, sorted by declared
     * {@link SymbolProvider#order()} (stable: ties keep contribution order),
     * paired with the exact view instance it was built from. The view
     * identity is the invalidation key: {@code Extension.all()} hands back
     * the same list until a contribution lands, and the store is frozen once
     * the container seals — so a hit is a pointer compare, and a rebuild
     * (composition thread only) is idempotent.
     */
    private record Snapshot(List<SymbolProvider> view, List<SymbolProvider> merged) {}

    private volatile Snapshot snapshot;

    /** The chain's {@link Coercer} — lets the one-step {@code resolve(spec)}
     *  parse coercer-backed types (Duration, Boolean, user {@code CoerceRule}s)
     *  without the two-step {@code spec.parse(resolve(key), coercer)} idiom.
     *  The container passes its own, so contributed rules apply here too. */
    private final Coercer coercer;

    SymbolSourceDefault(
        Coercer coercer,
        List<SymbolProvider> statics,
        Supplier<List<SymbolProvider>> contributed
    ) {
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        this.statics = List.copyOf(Objects.requireNonNull(statics, "statics"));
        this.contributed = Objects.requireNonNull(contributed, "contributed");
    }

    @Override
    public <T> T resolve(SymbolSpec<T> spec) {
        // The chain's coercer also covers specs that declare a
        // per-key parser — SymbolSpec.parse prefers the explicit parser.
        return spec.parse(resolve(spec.key(), null), coercer);
    }

    /** All providers in declared precedence order (ascending {@code order()});
     *  equal orders keep contribution order (stable sort). */
    private List<SymbolProvider> orderedProviders() {
        List<SymbolProvider> view = contributed.get();
        Snapshot s = snapshot;
        if (s != null && s.view() == view) {
            return s.merged();
        }
        List<SymbolProvider> sorted = new ArrayList<>(statics);
        sorted.addAll(view);
        sorted.sort(Comparator.comparingInt(SymbolProvider::order));
        s = new Snapshot(view, List.copyOf(sorted));
        snapshot = s;
        return s.merged();
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
        throw new UnknownSymbolException(name, sourcesSummary());
    }

    /**
     * Which sources the miss went through, so "no tier holds this key"
     * reads differently from "the chain is empty". Providers expose
     * {@code order()} but no key enumeration, so no near-miss suggestions.
     */
    private String sourcesSummary() {
        List<SymbolProvider> consulted = orderedProviders();
        if (consulted.isEmpty()) {
            return " — the config chain has no sources at all";
        }
        StringBuilder orders = new StringBuilder();
        for (SymbolProvider provider : consulted) {
            if (orders.length() > 0) {
                orders.append(", ");
            }
            orders.append(provider.order());
        }
        return " — no value in any of " + consulted.size()
            + " configured sources (orders " + orders + ")";
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
     * tracked by depth, so a default like {@code ${a:${b}}} and
     * {@code ${a:x{y}z}} parse as symbol {@code a} with the full default
     * {@code ${b}} / {@code x{y}z} instead of ending at the inner brace.
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
     * Recursively expands {@code ${...}} references with a depth limit
     * to prevent stack overflow.
     * <p>
     * Recursively expands {@code ${...}} references with a depth limit to
     * prevent stack overflow.
     * <p>
     * Default value syntax: {@code ${name:-default}} / {@code ${name:default}}
     * (see {@link #expand(String)}).
     *
     * @param input the string to expand
     * @param depth current recursion depth
     * @return the expanded string
     * @throws IllegalArgumentException if depth exceeds the maximum or symbol is unclosed
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
                throw new IllegalArgumentException(
                    "Unknown symbol: " + symbol + sourcesSummary()
                );
            }
            out.append(expand(value, depth + 1));
            i = end + 1;
        }
        return out.toString();
    }
}
