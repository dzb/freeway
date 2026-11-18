package com.jujin.freeway.cloud.discovery;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Name resolution as a seam: {@link DnsDiscovery} asks this for every address a
 * name currently maps to.
 *
 * <p>The system resolver ({@link #system()}) is the production implementation —
 * the JVM's own address cache and the platform resolver are exactly what a
 * deployment already runs. The interface exists so tests never depend on real
 * DNS, and so an application can plug a different source (a resolver library, a
 * pinned test topology) without a second discovery implementation.</p>
 */
@FunctionalInterface
public interface HostResolver {

    /** Every address {@code host} resolves to; throws when the name cannot be resolved. */
    InetAddress[] resolve(String host) throws UnknownHostException;

    /** The platform resolver, {@code InetAddress.getAllByName}. */
    static HostResolver system() {
        return InetAddress::getAllByName;
    }
}
