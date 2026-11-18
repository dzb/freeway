package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.cloud.CloudHookIds;
import com.jujin.freeway.cloud.CloudKeys;
import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.OptionalBinding;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds DNS discovery without a registry process: a service-id → name map whose
 * answers are expanded into instances, plus the default client-side load
 * balancer.
 *
 * <p>The middle option between {@link StaticModule} (a fixed endpoint list) and
 * a registry backend (Fleet/Consul): the name is stable, the members behind it
 * change with every deployment. It binds no {@code ServiceRegistry}, so
 * {@code MemberModule} does not apply — nothing is registered, renewed or
 * drained. The {@link CachingDiscovery} wrapper owns freshness
 * ({@code freeway.cloud.discovery.dns.refresh}), which is where the
 * keep-stale-on-failure policy lives for every network-backed discovery.</p>
 *
 * <p>Config form: {@code new DnsModule()} reads
 * {@code freeway.cloud.discovery.dns.services} with the static grammar, but
 * rejects IP literals (they are static entries) and repeated service ids (the
 * answer is the membership). A grammar error fails startup; a resolution
 * failure never does — a service with no ready pods, or a resolver that is not
 * up yet, is a runtime state.</p>
 */
public final class DnsModule implements ModuleEx {

    private static final Logger LOG = LoggerFactory.getLogger(DnsModule.class);

    private static final SymbolSpec<Duration> REFRESH =
        SymbolSpec.of(ConfigKeys.REFRESH, Duration.class, ConfigKeys.REFRESH_DEFAULT);

    private final DnsDiscovery discovery;
    private final HostResolver resolver;

    /**
     * Config-driven DNS discovery. Reads {@code freeway.cloud.discovery.dns.*}
     * from the container's {@link SymbolSource}; the {@code services} key must
     * contain at least one {@code serviceId=scheme://host:port[/basePath]} entry.
     *
     * <p>Use {@link #of(Map)} or {@link #of(DnsDiscovery)} when the topology is
     * already a typed value in the composition root.</p>
     */
    public DnsModule() {
        this(null, null);
    }

    private DnsModule(DnsDiscovery discovery, HostResolver resolver) {
        this.discovery = discovery;
        this.resolver = resolver;
    }

    /** Builds a DNS module from a typed map, using the system resolver. */
    public static DnsModule of(Map<String, ServiceEndpoint> names) {
        return new DnsModule(new DnsDiscovery(names), null);
    }

    /** Builds a DNS module from an already configured discovery (custom resolver/family). */
    public static DnsModule of(DnsDiscovery discovery) {
        return new DnsModule(Objects.requireNonNull(discovery, "discovery"), null);
    }

    /** Config-driven, with an explicit resolver (tests and custom resolver wiring). */
    static DnsModule configDriven(HostResolver resolver) {
        return new DnsModule(null, Objects.requireNonNull(resolver, "resolver"));
    }

    @Override
    public void bind(Binder b) {
        // The default balancer is a fallback, not a primary: a caller-supplied
        // weighted/canary policy must win without an ambiguity error.
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(ConfigKeys.class, CloudKeys.PREFIX));
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(LoadBalancer.ConfigKeys.class, CloudKeys.PREFIX));
        b.bind(ServiceDiscovery.class)
            .to(container -> CachingDiscovery.around(
                discovery != null
                    ? discovery
                    : DnsDiscovery.of(
                        container.get(SymbolSource.class),
                        resolver != null ? resolver : HostResolver.system(),
                        OptionalBinding.get(container, Metrics.class)),
                refresh(container.get(SymbolSource.class)),
                OptionalBinding.get(container, Metrics.class)))
            .primary();
        b.bind(LoadBalancer.class)
            .to((Container container) -> new LoadBalancerDefault(
                container.get(SymbolSource.class).resolve(
                    SymbolSpec.of(
                        LoadBalancer.ConfigKeys.LOCAL_ZONE, String.class, ""))))
            .marker(Local.class);
        if (discovery == null) {
            b.contribute(RuntimeHook.class)
                .add(CloudHookIds.DNS_DISCOVERY, new DnsConfigHook())
                .beforeIfPresent(CloudHookIds.HTTP_SERVER);
        }
    }

    /**
     * Freshness policy: the global {@code refresh}, one value for every service
     * in v1. A negative value fails startup; zero means "resolve on every call"
     * — {@link CachingDiscovery} reads it as "cache nothing".
     */
    private static CachingDiscovery.TtlPolicy refresh(SymbolSource symbols) {
        Duration ttl = symbols.resolve(REFRESH);
        if (ttl.isNegative()) {
            throw new IllegalArgumentException(ConfigKeys.REFRESH + " must not be negative: " + ttl);
        }
        return serviceId -> ttl;
    }

    /** Keys read by config-driven DNS discovery. */
    public static final class ConfigKeys {
        private ConfigKeys() {}

        /** Comma-separated {@code serviceId=scheme://host:port[/basePath]} entries; the host is a DNS name. */
        public static final String SERVICES = "freeway.cloud.discovery.dns.services";

        /** How long one resolution answer is reused before asking again. */
        public static final String REFRESH = "freeway.cloud.discovery.dns.refresh";

        public static final Duration REFRESH_DEFAULT = Duration.ofSeconds(30);

        /** {@code any} / {@code ipv4} / {@code ipv6}: which resolved addresses become instances. */
        public static final String FAMILY = "freeway.cloud.discovery.dns.family";

        public static final String FAMILY_DEFAULT = "any";
    }

    /** Proves the config parses before the app starts, then warms each name —
     *  a typo in a name is visible in the startup log without being fatal. */
    private static final class DnsConfigHook implements RuntimeHook {
        @Override
        public void start(Container container) {
            // A grammar error is a deployment error: fail startup, as static does.
            List<DiscoveryEntries.Entry> entries = DiscoveryEntries.parse(
                container.get(SymbolSource.class), ConfigKeys.SERVICES);
            ServiceDiscovery discovery = container.get(ServiceDiscovery.class);
            for (DiscoveryEntries.Entry entry : entries) {
                try {
                    int addresses = discovery.instances(entry.serviceId()).size();
                    LOG.info("DNS discovery '{}' resolves '{}' to {} address(es)",
                        entry.serviceId(), entry.endpoint().host(), addresses);
                } catch (RuntimeException failed) {
                    LOG.warn("DNS discovery '{}' could not resolve '{}' at startup — "
                            + "the first call retries: {}",
                        entry.serviceId(), entry.endpoint().host(), failed.getMessage());
                }
            }
        }
    }
}
