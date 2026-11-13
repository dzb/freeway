package com.jujin.freeway.ioc;

/**
 * Service lifecycle scopes.
 *
 * <ul>
 *   <li>{@link #SINGLETON} — one instance per container (default)</li>
 *   <li>{@link #BOUNDARY} — one instance per {@link ScopeBoundary#within} boundary</li>
 *   <li>{@link #PROTOTYPE} — new instance every resolution</li>
 * </ul>
 */
public enum Scope {
    SINGLETON,
    BOUNDARY,
    PROTOTYPE,
}
