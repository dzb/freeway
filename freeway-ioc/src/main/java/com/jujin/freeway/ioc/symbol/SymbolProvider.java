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
     * themselves. Framework tiers: {@code 0} boot config cascade,
     * {@code 10} cloud secret store, {@code 20} cloud dynamic config.
     */
    default int order() {
        return Integer.MAX_VALUE;
    }
}
