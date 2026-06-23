package com.jujin.freeway.ioc;

/**
 * Service lifecycle scopes.
 *
 * <ul>
 *   <li>{@link #SINGLETON} — one instance per container (default)</li>
 *   <li>{@link #THREAD} — one instance per {@link Scoping#within} boundary</li>
 *   <li>{@link #PROTOTYPE} — new instance every resolution</li>
 * </ul>
 */
public enum Scope {
    SINGLETON,
    THREAD,
    PROTOTYPE,
}
