package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.CloudConfigKeys;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The externally visible identity this node presents: the scheme and host the
 * registry stores, the mesh dials and peers call back on. One derivation for
 * both — a node cannot present two names.
 *
 * <p>Both values are <b>derived by default</b> ({@code auto}) because neither
 * can be written down for the machine it will run on: the scheme follows the
 * HTTP server's transport, and the host is whatever address peers can reach.
 * An explicit value always wins; the choice is logged either way, so an
 * operator can see what {@code auto} picked without turning on debug logging.
 */
public final class ServiceIdentity {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceIdentity.class);

    /** Platform-provided pod address (Kubernetes downward API). */
    private static final String POD_IP_ENV = "POD_IP";

    private ServiceIdentity() {
    }

    /**
     * The registered scheme: {@code auto} (or unset) follows the HTTP server —
     * https when it terminates TLS, http otherwise. An explicit value must be
     * {@code http} or {@code https}; anything else names the key and fails
     * startup, because a typo would otherwise register an endpoint nobody can
     * dial (the mesh derives {@code ws}/{@code wss} from this same answer).
     */
    public static String scheme(String raw, boolean serverSecure, String serviceId) {
        boolean derived = isAuto(raw, CloudConfigKeys.REGISTRY_SERVICE_SCHEME_AUTO);
        String scheme = derived
            ? (serverSecure ? "https" : "http")
            : raw.trim().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(
                CloudConfigKeys.REGISTRY_SERVICE_SCHEME + " must be "
                    + CloudConfigKeys.REGISTRY_SERVICE_SCHEME_AUTO
                    + ", http or https: " + raw);
        }
        LOG.info("Service '{}' registers scheme {} — {}",
            serviceId, scheme,
            derived ? "auto, following the HTTP server's transport" : "explicitly configured");
        return scheme;
    }

    /**
     * The registered host: {@code auto} (or unset) never picks an address the
     * server does not listen on, and never registers a bind-all address while
     * a real one exists.
     *
     * <ol>
     *   <li>A server bound to one specific address (the default
     *       {@code 127.0.0.1}, or a chosen interface address) registers that
     *       address — it listens nowhere else, so any other answer would be a
     *       lie.</li>
     *   <li>A bind-all server ({@code 0.0.0.0} / {@code ::}, the container
     *       case) registers the platform's {@code POD_IP} when it is injected,
     *       else the first routable local address.</li>
     *   <li>With no routable address at all the bind-all value is kept and the
     *       caller warns — a config that cannot be derived is worth saying out
     *       loud.</li>
     * </ol>
     *
     * <p>An explicit host always wins: on a multi-homed host no derivation can
     * know which address belongs to the mesh, so the deployment decides.</p>
     */
    public static String host(String raw, String bindHost, String serviceId) {
        if (!isAuto(raw, CloudConfigKeys.REGISTRY_SERVICE_HOST_AUTO)) {
            return raw.trim();
        }
        return deriveHost(bindHost, podIp(), firstRoutableAddress(), serviceId);
    }

    /**
     * The derivation with its inputs injected — the rule in one place, and
     * testable without depending on the machine's interfaces.
     */
    static String deriveHost(String bindHost, String podIp, String localAddress, String serviceId) {
        String bind = bindHost == null ? "" : bindHost.trim();
        if (!isBindAll(bind)) {
            LOG.info("Service '{}' registers host {} — auto, the HTTP server's bind address "
                    + "(it listens only there)", serviceId, bind);
            return bind;
        }
        if (podIp != null && !podIp.isBlank() && !isBindAll(podIp)) {
            String value = podIp.trim();
            LOG.info("Service '{}' registers host {} — auto, from ${}", serviceId, value, POD_IP_ENV);
            return value;
        }
        if (localAddress != null && !localAddress.isBlank() && !isBindAll(localAddress)) {
            String value = localAddress.trim();
            LOG.info("Service '{}' registers host {} — auto, first routable local address "
                    + "(the server binds {})", serviceId, value, bind);
            return value;
        }
        LOG.info("Service '{}' registers host {} — auto found no routable local address; "
                + "peers cannot call this endpoint", serviceId, bind);
        return bind;
    }

    /**
     * Whether {@code host} is a bind-all (or empty) address — no single
     * address a peer could dial. The registry declaration warns on these, and
     * {@link #host} derives past them.
     */
    public static boolean isBindAll(String host) {
        if (host == null) {
            return true;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.equals("0.0.0.0") || value.equals("::")
            || value.equals("[::]") || value.equals("*");
    }

    /** Unset and {@code auto} mean the same thing: derive it. */
    private static boolean isAuto(String raw, String autoToken) {
        return raw == null || raw.isBlank() || autoToken.equalsIgnoreCase(raw.trim());
    }

    private static String podIp() {
        String value = System.getenv(POD_IP_ENV);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The first address that a peer on another host could dial: IPv4 preferred
     * (an IPv6-only answer would break peers that speak IPv4), loopback,
     * link-local and any address of a down interface excluded. Returns null
     * when the host has none — a single-NIC container without {@code POD_IP}
     * is exactly the case the bind-address fallback and its warning cover.
     */
    private static String firstRoutableAddress() {
        List<String> routable = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                            || address.isAnyLocalAddress()) {
                        continue;
                    }
                    if (address instanceof Inet4Address) {
                        routable.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            LOG.debug("Interface enumeration failed: {}", e.getMessage());
        }
        return routable.isEmpty() ? null : routable.get(0);
    }
}
