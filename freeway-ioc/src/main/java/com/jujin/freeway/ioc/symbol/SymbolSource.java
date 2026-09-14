package com.jujin.freeway.ioc.symbol;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;

/**
 * Resolves symbolic configuration keys ({@code ${...}}) from config, system
 * properties, environment variables, and other providers.
 *
 * <p>This is the framework's single configuration entry point: whatever the
 * source format (properties, JSON, env mapping, CLI), the cascade normalizes
 * it to {@code key=value} and this interface answers lookups over the merged
 * chain. Values are raw strings — typing is a separate, explicit
 * post-processing step (declare a {@link SymbolSpec}, parse the resolved
 * value), and DI gets the same chain via {@code @Symbol}/{@code @Value}:
 * <pre>{@code
 * public record ServerConfig(
 *     @Symbol("server.port") int port,
 *     @Value("${app.name:freeway}") String appName
 * ) {}
 * }</pre>
 *
 * <p>Direct usage:
 * <pre>{@code
 * SymbolSource ss = container.get(SymbolSource.class);
 * String port = ss.resolve("server.port");
 * String host = ss.resolve("server.host", "127.0.0.1");
 * String url = ss.expand("${protocol}://${host}:${port}");
 * }</pre>
 */
public interface SymbolSource {

    /**
     * A source backed by JVM system properties alone: the pre-cascade behavior
     * for standalone construction (tests, benchmarks, direct adapter use). A
     * missing symbol throws from {@link #resolve(String)} and falls back to the
     * default from {@link #resolve(String, String)}; {@link #expand(String)}
     * returns its input unchanged, since there is nothing to expand against.
     *
     * <p>This is the mechanism only — no key names — so it lives here rather
     * than being re-declared by every adapter that also has a container path.
     */
    static SymbolSource systemProperties() {
        // Specs declared without a per-key parser resolve through a Coercer, so
        // this standalone source wires one exactly like the container's chain
        // does — otherwise an adapter that reads a SymbolSpec would work under
        // the container and fail when constructed directly.
        Coercer coercer = new CoercerDefault();
        return new SymbolSource() {
            @Override
            public String resolve(String name) {
                String value = System.getProperty(name);
                if (value == null) throw new UnknownSymbolException(name);
                return value;
            }

            @Override
            public String resolve(String name, String defaultValue) {
                return System.getProperty(name, defaultValue);
            }

            @Override
            public String expand(String input) {
                return input;
            }

            @Override
            public <T> T resolve(SymbolSpec<T> spec) {
                return spec.parse(resolve(spec.key(), null), coercer);
            }
        };
    }

    /**
     * Adds one provider to this source's chain.
     *
     * <p>The container calls this for every {@code SymbolProvider} a module
     * contributes — boot contributes the application's tiers (CLI, environment,
     * config files) that way, cloud contributes its secret store. An
     * implementation that replaces the built-in source therefore has to accept
     * them, or the whole cascade disappears in silence and surfaces much later
     * as "my config file is ignored".</p>
     *
     * <p>A contributed provider carries its own {@link SymbolProvider#order()};
     * ordering across providers is the implementation's job (the built-in one
     * sorts stably by order). The default implementation throws, because a
     * replacement that cannot take contributions is a configuration mistake
     * that must be reported at startup rather than at the first missing key.</p>
     *
     * @param provider the provider to add
     * @throws UnsupportedOperationException when this source cannot take part
     *         in the container's contribution chain
     */
    default void register(SymbolProvider provider) {
        throw new UnsupportedOperationException(
            getClass().getName() + " cannot accept SymbolProvider contributions — override"
                + " SymbolSource.register(SymbolProvider) to keep the container's configuration"
                + " chain, or stop binding this source as the primary SymbolSource"
        );
    }

    /**
     * Resolves a symbol to its value, or throws if the symbol is unknown.
     *
     * @param name the symbol name (e.g. {@code "server.port"})
     * @return the resolved value
     * @throws IllegalArgumentException if the symbol is not found
     */
    String resolve(String name);

    /**
     * Resolves a symbol to its value, returning {@code defaultValue} when the
     * symbol is not found. Delegates to {@link #expand(String)} with the
     * {@code ${name:default}} syntax.
     *
     * <p>Only a missing top-level symbol maps to {@code defaultValue}
     * (detected via {@link UnknownSymbolException}, not message text);
     * expansion errors — depth limit, unclosed expression, or an unknown
     * symbol nested inside another value — propagate instead of silently
     * treating a broken config chain as "absent".
     *
     * @param name         the symbol name
     * @param defaultValue the fallback value (null = return null on miss)
     * @return the resolved value, or defaultValue if not found
     */
    default String resolve(String name, String defaultValue) {
        if (defaultValue == null) {
            try {
                return resolve(name);
            } catch (UnknownSymbolException e) {
                return null;
            }
        }
        return expand("${" + name + ":" + defaultValue + "}");
    }

    /**
     * Resolves and parses a typed configuration key in one step, replacing the
     * two-step {@code spec.parse(resolve(spec.key(), null))} boilerplate. The
     * symbol is resolved as a raw string (null when absent) and handed to the
     * spec's parser, which owns typing and the default value.
     *
     * <p>Example:
     * <pre>{@code
     * int port = symbols.resolve(HTTP_PORT);
     * String pw = symbols.resolve(DB_PASSWORD);
     * }</pre>
     *
     * @param spec the typed key ({@link SymbolSpec} — key name, type, default, parser)
     * @return the parsed value (the spec's default when the symbol is absent)
     */
    default <T> T resolve(SymbolSpec<T> spec) {
        return spec.parse(resolve(spec.key(), null));
    }

    /**
     * Recursively expands {@code ${...}} references in the input string.
     *
     * @param input a string possibly containing {@code ${...}} references
     * @return the expanded string
     * @throws IllegalArgumentException if a reference is unclosed or the
     *         expansion depth exceeds the limit
     */
    String expand(String input);
}
