package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DNS service discovery: a service id maps to a name, and every address the
 * name currently resolves to is an instance.
 *
 * <p>The middle backend between static discovery and a registry. Static means
 * "these endpoints, until a redeploy"; a registry means "membership the
 * deployment owns and publishes". DNS means "the name is stable, the members
 * behind it change with every deployment": a Kubernetes headless Service
 * publishes one {@code A} record per ready pod, and an internal DNS server can
 * round-robin a fixed name over a fleet. Nothing is registered, nothing is
 * drained, nothing is probed — the answer is the whole membership.</p>
 *
 * <p><b>Answer → instances.</b> Every resolved address becomes one
 * {@link ServiceInstance} whose endpoint carries the literal address (never a
 * reverse lookup). The instance id is {@code serviceId@ip:port}, the shape
 * {@code HttpServiceDeclaration} derives for registration by default, so the
 * event mesh's self-exclusion matches without extra configuration. Answers are
 * filtered by {@link AddressFamily}, deduplicated by literal and sorted, so a
 * refresh returns a stable snapshot.</p>
 *
 * <p><b>Eviction is the snapshot diff.</b> A refresh replaces the whole list;
 * an instance absent from the new answer is gone from that moment. There is no
 * per-instance grace and no drain signal — DNS does not say "this one is
 * leaving", only what the name points to now. An attempt already running keeps
 * the endpoint it chose, while retries re-resolve and cannot hit a just-evicted
 * instance. An address that returns later is the same instance id and simply
 * resumes.</p>
 *
 * <p><b>Freshness belongs to the wrapper.</b> This class resolves on every
 * call; the module binds it inside {@link CachingDiscovery} with the
 * {@code refresh} window, which is exactly the policy split the framework uses
 * for every network-backed discovery (see {@link CachingDiscovery}). A failed
 * resolution is an exception, which the cache turns into "serve the last answer
 * within the grace window, then fail" — a resolver outage must not empty the
 * topology.</p>
 *
 * <p>Config form: {@link #of(SymbolSource)} reads
 * {@code freeway.cloud.discovery.dns.services} with the same grammar as static
 * discovery ({@code serviceId=scheme://host:port[/basePath]}), but the host
 * must be a DNS name — an IP literal is rejected and the message points at
 * {@link StaticModule} — and a repeated service id is rejected, because
 * membership comes from the answer, not from the list.</p>
 */
public final class DnsDiscovery implements ServiceDiscovery {

    private static final String RESOLVE_OK = "cloud.registry.dns.resolve.ok";
    private static final String RESOLVE_FAIL = "cloud.registry.dns.resolve.fail";

    private final Map<String, ServiceEndpoint> names;
    private final HostResolver resolver;
    private final AddressFamily family;
    private final Metrics metrics;

    /** Programmatic form: the system resolver, every address family. */
    public DnsDiscovery(Map<String, ServiceEndpoint> names) {
        this(names, HostResolver.system(), AddressFamily.ANY, null);
    }

    /** Programmatic form with an explicit resolver and address family. */
    public DnsDiscovery(Map<String, ServiceEndpoint> names, HostResolver resolver,
                        AddressFamily family) {
        this(names, resolver, family, null);
    }

    DnsDiscovery(Map<String, ServiceEndpoint> names, HostResolver resolver,
                 AddressFamily family, Metrics metrics) {
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(family, "family");
        Map<String, ServiceEndpoint> copy = new LinkedHashMap<>();
        names.forEach((serviceId, endpoint) -> {
            Objects.requireNonNull(serviceId, "serviceId");
            copy.put(serviceId, Objects.requireNonNull(endpoint, "endpoint"));
        });
        this.names = Map.copyOf(copy);
        this.resolver = resolver;
        this.family = family;
        this.metrics = metrics;
    }

    /**
     * Reads {@code freeway.cloud.discovery.dns.services} and
     * {@code freeway.cloud.discovery.dns.family} from the symbol chain.
     */
    public static DnsDiscovery of(SymbolSource symbols) {
        return of(symbols, HostResolver.system(), null);
    }

    static DnsDiscovery of(SymbolSource symbols, HostResolver resolver, Metrics metrics) {
        Objects.requireNonNull(symbols, "symbols");
        List<DiscoveryEntries.Entry> entries = DiscoveryEntries.parse(
            symbols, DnsModule.ConfigKeys.SERVICES);

        Map<String, ServiceEndpoint> names = new LinkedHashMap<>();
        for (DiscoveryEntries.Entry entry : entries) {
            String serviceId = entry.serviceId();
            String host = entry.endpoint().host();
            if (DiscoveryEntries.isIpLiteral(host)) {
                throw DiscoveryEntries.invalidEntry(
                    DnsModule.ConfigKeys.SERVICES, entry.index(),
                    "host must be a DNS name, not an IP literal — a literal endpoint is a static "
                        + "entry (StaticModule): '" + entry.address() + "'");
            }
            if (names.putIfAbsent(serviceId, entry.endpoint()) != null) {
                throw DiscoveryEntries.invalidEntry(
                    DnsModule.ConfigKeys.SERVICES, entry.index(),
                    "duplicate service id '" + serviceId
                        + "': membership comes from the resolved answer, so one service maps to "
                        + "one name");
            }
        }
        return new DnsDiscovery(names, resolver, family(symbols), metrics);
    }

    /** The {@code family} token; an unknown value fails naming the key and the tokens. */
    static AddressFamily family(SymbolSource symbols) {
        String raw = symbols.resolve(SymbolSpec.of(
            DnsModule.ConfigKeys.FAMILY, String.class, DnsModule.ConfigKeys.FAMILY_DEFAULT));
        return SymbolSpec.mode(
            DnsModule.ConfigKeys.FAMILY, raw,
            Map.of(
                "any", AddressFamily.ANY,
                "ipv4", AddressFamily.IPV4,
                "ipv6", AddressFamily.IPV6),
            AddressFamily.ANY);
    }

    @Override
    public List<ServiceInstance> instances(String serviceId) {
        ServiceEndpoint spec = names.get(serviceId);
        if (spec == null) {
            return List.of();
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(spec.host());
        } catch (UnknownHostException e) {
            count(RESOLVE_FAIL);
            throw new ResolutionException(serviceId, spec.host(), e);
        }
        count(RESOLVE_OK);

        List<ServiceInstance> snapshot = new ArrayList<>(addresses.length);
        Set<String> seen = new HashSet<>(addresses.length);
        for (InetAddress address : addresses) {
            if (address == null || !family.accepts(address)) {
                continue;
            }
            String literal = literal(address);
            if (literal.isEmpty() || !seen.add(literal)) {
                continue;
            }
            ServiceEndpoint endpoint = ServiceEndpoint.of(
                spec.scheme(), literal, spec.port(), spec.basePath());
            snapshot.add(ServiceInstance.of(
                serviceId, instanceId(serviceId, literal, spec.port()), endpoint));
        }
        snapshot.sort(Comparator.comparing(ServiceInstance::instanceId));
        return List.copyOf(snapshot);
    }

    /**
     * The address as a URI host: the literal form only (a name would make every
     * member look like the same instance), with any IPv6 scope identifier
     * dropped — {@code %eth0} is not representable in a URI and a link-local
     * scope does not survive a hop between nodes anyway.
     */
    private static String literal(InetAddress address) {
        String raw = address.getHostAddress();
        int scope = raw.indexOf('%');
        return scope < 0 ? raw : raw.substring(0, scope);
    }

    /** The default registration identity shape, so the mesh self-exclusion matches. */
    private static String instanceId(String serviceId, String literal, int port) {
        return serviceId + "@" + literal + ":" + port;
    }

    private void count(String name) {
        if (metrics != null) {
            metrics.counter(name).increment();
        }
    }

    /** Address families an answer may contribute; {@code ANY} accepts both. */
    public enum AddressFamily {
        ANY, IPV4, IPV6;

        boolean accepts(InetAddress address) {
            return switch (this) {
                case ANY -> true;
                case IPV4 -> address instanceof Inet4Address;
                case IPV6 -> address instanceof Inet6Address;
            };
        }
    }

    /**
     * A name that could not be resolved. Unchecked because
     * {@link ServiceDiscovery} is; the cache treats it like any other failing
     * backend (stale grace, then failure outward), and RPC wraps it in its
     * single failure type.
     */
    public static final class ResolutionException extends IllegalStateException {

        private final String serviceId;
        private final String host;

        ResolutionException(String serviceId, String host, UnknownHostException cause) {
            super("DNS discovery for service '" + serviceId + "' could not resolve '"
                + host + "': " + cause.getMessage(), cause);
            this.serviceId = serviceId;
            this.host = host;
        }

        public String serviceId() {
            return serviceId;
        }

        public String host() {
            return host;
        }
    }
}
