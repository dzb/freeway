package com.jujin.freeway.ioc.symbol;

import java.util.Map;
import java.util.function.Supplier;

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
     * A map-backed provider: {@code lookup} reads
     * {@code values.get().get(name)} — re-reading the supplier on every
     * lookup, so a live snapshot (a hot-reloading tier) and a constant map
     * behave identically.
     */
    static SymbolProvider of(Supplier<Map<String, String>> values, int order) {
        return new SymbolProvider() {
            @Override
            public String lookup(String name) {
                return values.get().get(name);
            }

            @Override
            public int order() {
                return order;
            }
        };
    }

    /**
     * The JVM system-properties tier ({@code -Dkey=value}) — the process-level
     * ops override, declared at {@link #TIER_SYS_PROPS}.
     *
     * <p>One definition serves both assemblies: the container seeds its chain
     * with this provider, and a standalone chain — an adapter without a
     * container, a test, a benchmark — chains exactly it
     * ({@code SymbolSource.of(coercer, SymbolProvider.systemProperties())}), so
     * {@code -D} precedence is the same answer on both paths.
     *
     * <p>Values come back verbatim: {@code ${...}} expansion and typing are the
     * chain's job, not a tier's.
     */
    static SymbolProvider systemProperties() {
        return new SymbolProvider() {
            @Override
            public String lookup(String name) {
                return System.getProperty(name);
            }

            @Override
            public int order() {
                return TIER_SYS_PROPS;
            }
        };
    }

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
     * themselves. The framework declares four tiers, each answering one
     * ownership question — app launch args, JVM-level overrides, declared env
     * mapping, deployable file baseline:
     * {@link #TIER_CLI} → {@link #TIER_SYS_PROPS} → {@link #TIER_ENV} →
     * {@link #TIER_FILES}. Modules slot their own sources in between (e.g. the
     * cloud secret store declares order 15, between env and files).
     * Environment variables reach the chain only through the declared prefix
     * mapping — there is no raw-env fallback tier.
     */
    default int order() {
        return Integer.MAX_VALUE;
    }

    /** Framework tier: application CLI arguments ({@code --key=value}). */
    int TIER_CLI = 0;
    /** Framework tier: JVM system properties ({@code -Dkey=value}) — the
     *  process-level ops override, outranking files and environment. */
    int TIER_SYS_PROPS = 5;
    /** Framework tier: environment variables ({@code FREEWAY_} prefix). */
    int TIER_ENV = 10;
    /** Framework tier: config files (classpath baseline + filesystem
     *  overrides, hot-reloadable). */
    int TIER_FILES = 20;
}
