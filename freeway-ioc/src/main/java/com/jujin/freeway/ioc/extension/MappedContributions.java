package com.jujin.freeway.ioc.extension;

public interface MappedContributions<K, V> {
    /**
     * Adds a new keyed contribution.
     *
     * <p>Duplicate keys fail immediately.</p>
     */
    void put(K key, V value);

    /**
     * Replaces an existing keyed contribution explicitly.
     *
     * <p>The key must already exist; missing keys fail immediately.</p>
     */
    void override(K key, V value);
}
