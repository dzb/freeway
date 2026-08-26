package com.jujin.freeway.ioc.symbol;

/**
 * Resolves symbolic configuration keys ({@code ${...}}) from config, system
 * properties, environment variables, and other providers.
 *
 * <p>Injected via {@code @Symbol} or {@code @Value}:
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
 * int port = Integer.parseInt(ss.resolve("server.port"));
 * String host = ss.resolve("server.host", "127.0.0.1");
 * String url = ss.expand("${protocol}://${host}:${port}");
 * }</pre>
 */
public interface SymbolSource {

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
     * Recursively expands {@code ${...}} references in the input string.
     *
     * @param input a string possibly containing {@code ${...}} references
     * @return the expanded string
     * @throws IllegalArgumentException if a reference is unclosed or the
     *         expansion depth exceeds the limit
     */
    String expand(String input);
}
