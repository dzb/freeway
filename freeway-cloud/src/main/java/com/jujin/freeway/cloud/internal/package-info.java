/**
 * Cross-domain cloud support — <strong>no stability promise</strong> across
 * releases.
 *
 * <p>This package holds only helpers that genuinely have no single feature
 * owner: optional binding lookup, constant-time comparison, the shared
 * outbound HTTP-client base, backend selection guard, service identity
 * derivation and the HTTP-service declaration shared by discovery and the
 * event mesh. Feature implementations live beside their feature API in
 * {@code cloud.<domain>.internal}; code outside this module must not reference
 * these classes.</p>
 */
package com.jujin.freeway.cloud.internal;
