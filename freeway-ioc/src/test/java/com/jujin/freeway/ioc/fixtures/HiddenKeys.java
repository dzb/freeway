package com.jujin.freeway.ioc.fixtures;

/**
 * Package-private on purpose: another package's reflection sees its public constants in
 * {@code getFields()} but cannot read them — the case {@code KnownKeys.of} must refuse to
 * turn into an empty vocabulary.
 */
class HiddenKeys {
    public static final String KEY = "freeway.hidden.key";
}
