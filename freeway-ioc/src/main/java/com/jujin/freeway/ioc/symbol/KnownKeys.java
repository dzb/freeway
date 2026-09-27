package com.jujin.freeway.ioc.symbol;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A module's declared config vocabulary: the namespace(s) it owns plus every key
 * spelled under them — the allowlist boot checks configured keys against. A
 * misspelled key is never resolved by anyone, so it can only be found by
 * comparing the configured universe with the declared one; this record is the
 * declared half. Modules contribute theirs
 * ({@code binder.contribute(KnownKeys.class)}); boot reads the extension at
 * startup and warns once per unknown {@code freeway.*} key within edit
 * distance 2 of a listed spelling, with the fix traveling in the report;
 * failing a near match, a key under a declared module namespace (the root
 * claims nothing) is named once as read by no one. A key matching neither
 * rule stays silent — a vocabulary never contributed cannot be judged —
 * and a prefix-only admission ({@link #admit}) exempts its dynamic family,
 * whose names cannot be listed at all.
 *
 * <p>One vocabulary per module key table, listing every namespace that table
 * declares: a module with two namespaces ({@code freeway.cloud} and
 * {@code freeway.app}) states both, so no key of the table can sit outside the
 * vocabulary by accident.
 *
 * <p>Like {@link SymbolSpec}, this is parameterized by the caller's keys and
 * knows no concrete key itself — the layer rule that keeps key declarations
 * with their feature modules.
 */
public record KnownKeys(Set<String> prefixes, Set<String> keys) {

    /** The framework namespace: every key the cascade owns starts with this. */
    public static final String ROOT_PREFIX = "freeway.";

    /** Suggestion rules, pinned by test. */
    private static final int MAX_DISTANCE = 2;
    private static final int MAX_SUGGESTIONS = 3;

    public KnownKeys {
        Objects.requireNonNull(prefixes, "prefixes");
        Objects.requireNonNull(keys, "keys");
        prefixes = Set.copyOf(prefixes);
        if (prefixes.isEmpty()) {
            throw new IllegalArgumentException("at least one prefix is required");
        }
        for (String prefix : prefixes) {
            if (prefix.isBlank()) {
                throw new IllegalArgumentException("prefix must not be blank");
            }
        }
        keys = Set.copyOf(keys);
        for (String key : keys) {
            if (prefixes.stream().noneMatch(prefix -> under(key, prefix))) {
                throw new IllegalArgumentException(
                    "key '" + key + "' is outside every declared prefix " + prefixes);
            }
        }
    }

    /**
     * Reflects the vocabulary off a module's key table — a {@code *ConfigKeys}
     * class: every public static {@code String} field spelling a {@code freeway.*}
     * key under one of {@code prefixes}. New keys arrive automatically — no second
     * list to maintain.
     *
     * <p>Only key tables belong here. {@code freeway.*} is also the spelling of
     * identity strings that are not configurable — runtime-hook ids
     * ({@code CloudHooks}), WebSocket route paths, contribution ids — and feeding
     * one of those classes in would admit unconfigurable names as "known keys".
     *
     * <p>The table and the vocabulary must agree: a key in {@code keysClass}
     * outside every given prefix is refused rather than dropped, because dropping
     * it would leave a namespace the unknown-key check never reports on. A module
     * whose table spells two namespaces passes both.
     *
     * <p>A constant that cannot be read also fails the bind instead of being
     * skipped: a package-private constants class yields no accessible fields at
     * all, so a silent skip would hand back an empty vocabulary and quietly
     * switch the check off for that module's whole namespace.
     */
    public static KnownKeys of(Class<?> keysClass, String... prefixes) {
        Objects.requireNonNull(keysClass, "keysClass");
        Set<String> declared = Set.of(prefixes);
        Set<String> keys = new HashSet<>();
        List<String> strays = new ArrayList<>();
        for (Field field : keysClass.getFields()) {
            if (field.getType() != String.class
                || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String value = readConstant(field, keysClass);
            if (value == null || !value.startsWith(ROOT_PREFIX)) {
                continue; // a default value or an unrelated string, not a key
            }
            if (declared.contains(value)) {
                continue; // the namespace constant itself names a prefix, not a key
            }
            if (declared.stream().anyMatch(prefix -> under(value, prefix))) {
                keys.add(value);
            } else {
                strays.add(field.getName() + "=\"" + value + "\"");
            }
        }
        if (!strays.isEmpty()) {
            throw new IllegalStateException(
                keysClass.getName() + " declares " + strays + " outside " + declared
                    + " — a table and its vocabulary must agree. Declare that namespace too"
                    + " (KnownKeys.of(" + keysClass.getSimpleName() + ".class, \"<prefix>\"))"
                    + " or move the constant out of the key table");
        }
        return new KnownKeys(declared, keys);
    }

    private static String readConstant(Field field, Class<?> keysClass) {
        try {
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Cannot read config key constant " + keysClass.getName() + "." + field.getName()
                    + " — the vocabulary must be readable, not silently empty", e);
        }
    }

    /**
     * Prefix-only admission: the prefix is known (its spelling owned
     * elsewhere), but no fuzzy vocabulary is declared under it — keys there
     * never suggest, and never false-positive either.
     *
     * <p>For a namespace whose names cannot be listed at all — every one of them is
     * built from data, so any list would be arbitrary. A namespace that is only
     * <em>partly</em> dynamic is declared instead: its fixed names join the
     * vocabulary through {@link #of(Class, String...)} and its dynamic ones are
     * covered by an admission of their own prefix (the per-file
     * {@code freeway.log.file.<name>.*} keys work that way).
     *
     * <p>Admission is also the exemption marker the unknown-key check reads:
     * its declared-namespace rule reports a key no vocabulary lists under a
     * claimed namespace, and an admission says that namespace's family can
     * never be listed — so presence alone stays silent there, while a genuine
     * near-match typo inside the family is still named.
     */
    public static KnownKeys admit(String... prefixes) {
        return new KnownKeys(Set.of(prefixes), Set.of());
    }

    /**
     * Same-prefix keys within edit distance 2 of {@code unknown}, nearest
     * first (ties alphabetical), at most 3. An exact hit suggests nothing.
     */
    public List<String> suggest(String unknown) {
        if (unknown == null || prefixes.stream().noneMatch(prefix -> under(unknown, prefix))
            || keys.contains(unknown)) {
            return List.of();
        }
        record Scored(String key, int distance) {}
        List<Scored> scored = new ArrayList<>();
        for (String key : keys) {
            int distance = distance(unknown, key);
            if (distance > 0 && distance <= MAX_DISTANCE) {
                scored.add(new Scored(key, distance));
            }
        }
        scored.sort((a, b) -> a.distance != b.distance
            ? Integer.compare(a.distance, b.distance)
            : a.key.compareTo(b.key));
        return scored.stream()
            .limit(MAX_SUGGESTIONS)
            .map(Scored::key)
            .toList();
    }

    /**
     * Whether this vocabulary names a namespace without listing its keys —
     * the shape {@link #admit(String...)} produces. The unknown-key check
     * reads it as the exemption marker for a family that can never be listed.
     */
    public boolean prefixOnly() {
        return keys.isEmpty();
    }

    /**
     * The most specific namespace declared here that {@code key} sits under
     * (dot-boundary), or {@code null} when it sits under none. Longest wins,
     * so the more specific of two declarations names the namespace the key
     * actually lives in.
     */
    public String namespaceUnder(String key) {
        if (key == null) {
            return null;
        }
        String best = null;
        for (String prefix : prefixes) {
            if (under(key, prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best;
    }

    /**
     * Whether a key belongs to a namespace: equal to the prefix, or below it on a dot boundary.
     * A string-prefix test would accept a look-alike neighbour ({@code freeway.http2.port} under
     * {@code freeway.http}), and the fence exists to keep exactly those out of a table.
     */
    private static boolean under(String key, String prefix) {
        return key.equals(prefix)
            || key.startsWith(prefix.endsWith(".") ? prefix : prefix + ".");
    }

    /** Classic two-row Levenshtein — keys are short, allocation is trivial. */
    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] curr = new int[b.length() + 1];
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                curr[j] = Math.min(
                    Math.min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            prev = curr;
        }
        return prev[b.length()];
    }
}
