package com.jujin.freeway.ioc.symbol;

/**
 * Pluggable provider for a single symbol namespace. Registered via
 * {@code binder.contribute(SymbolProvider.class)}.
 *
 * <p>Example — reading from a custom config source:
 * <pre>{@code
 * binder.contribute(SymbolProvider.class)
 *     .add(name -> System.getenv("MYAPP_" + name.replace('.', '_')));
 * }</pre>
 */
public interface SymbolProvider {

    /**
     * Looks up a symbol by name.
     *
     * @param name the symbol name
     * @return the value, or null if not found
     */
    String lookup(String name);

    /**
     * Resolution order: providers are consulted in ascending {@code order()}
     * and the first non-null value wins. Declaring the order makes precedence
     * explicit and independent of module install order — a provider that must
     * be consulted early cannot be silently outranked by installing its
     * module later (or silently promoted by installing it earlier).
     *
     * <p>Providers without a declared order default to the last tier
     * ({@link Integer#MAX_VALUE}) and keep contribution order among
     * themselves. The framework declares exactly three tiers:
     * {@link #TIER_CLI} → {@link #TIER_ENV} → {@link #TIER_FILES}.
     * Modules slot their own sources in between (e.g. the cloud secret
     * store declares order 15, between env and files).
     */
    default int order() {
        return Integer.MAX_VALUE;
    }

    /** Framework tier: CLI arguments ({@code --key=value}). */
    int TIER_CLI = 0;
    /** Framework tier: environment variables ({@code FREEWAY_} prefix). */
    int TIER_ENV = 10;
    /** Framework tier: config files (classpath baseline + filesystem
     *  overrides, hot-reloadable). */
    int TIER_FILES = 20;
}
