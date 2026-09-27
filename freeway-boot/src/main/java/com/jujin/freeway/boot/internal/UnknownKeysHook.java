package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.KnownKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup loudness for a configuration key nothing reads, under two rules.
 * First: a configured {@code freeway.*} key within one or two edits of a
 * contributed {@link KnownKeys} entry is named once, with its nearest
 * spelling(s). Second: failing a near match, a key whose namespace a module
 * has claimed (prefix declared, root excluded) is still named — a claimed
 * namespace means the check can judge it, and no vocabulary listing the key
 * means nothing reads it. A key matching neither rule stays silent: the root
 * {@code freeway.} claims nothing (boot's table sits on it generically), no
 * vocabulary was contributed for that namespace at all (subset assembly — a
 * check cannot judge what it was never told), or a prefix-only admission
 * marks the family as unlistable ({@link KnownKeys#admit}). A typo'd key is
 * never resolved by anyone, so without this check the server runs silently
 * on defaults — the same failure the retired-prefix notice covers for one
 * historic rename, generalized to future keys.
 *
 * <p>Runs once at startup (hot reload does not re-check); warns only, never
 * fails — configuration stays total. Keys outside {@code freeway.*} and
 * application keys outside the namespace never enter; the retired
 * {@code freeway.web.*} twins have their own precise notice and suggest
 * nothing here.
 */
final class UnknownKeysHook implements RuntimeHook {

    static final String HOOK_ID = "freeway.boot.unknown-keys";

    private static final Logger LOG = LoggerFactory.getLogger(UnknownKeysHook.class);

    @Override
    public void start(Container container) {
        for (String notice : notices(container)) {
            LOG.warn("{}", notice);
        }
    }

    /**
     * The notices this container's configuration produces: the pure check over the config's
     * declared universe plus the container-built system-properties tier. Package-visible so a test
     * can assert the wiring the hook depends on — a config that stopped reporting its keys would
     * otherwise disable the check in silence.
     */
    static List<String> notices(Container container) {
        Set<String> universe = new HashSet<>(container.get(AppConfig.class).keys());
        // The system-properties tier is built by the container, not the
        // config — read it here so a -D typo is caught too.
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(KnownKeys.ROOT_PREFIX)) {
                universe.add(name);
            }
        }
        return check(universe, container.extension(KnownKeys.class).all());
    }

    /**
     * Pure check: unknown-key notices in deterministic (sorted) order. The
     * hook only logs them; tests assert them.
     */
    static List<String> check(Set<String> universe, List<KnownKeys> vocabularies) {
        Set<String> known = new HashSet<>();
        List<KnownKeys> listed = new ArrayList<>();
        List<KnownKeys> admitted = new ArrayList<>();
        for (KnownKeys vocab : vocabularies) {
            known.addAll(vocab.keys());
            (vocab.prefixOnly() ? admitted : listed).add(vocab);
        }
        List<String> sorted = new ArrayList<>(universe);
        Collections.sort(sorted);
        List<String> notices = new ArrayList<>();
        for (String key : sorted) {
            if (!key.startsWith(KnownKeys.ROOT_PREFIX) || known.contains(key)) {
                continue;
            }
            List<String> suggestions = new ArrayList<>();
            for (KnownKeys vocab : vocabularies) {
                suggestions.addAll(vocab.suggest(key));
                if (suggestions.size() >= 3) {
                    break;
                }
            }
            if (suggestions.size() > 3) {
                suggestions = suggestions.subList(0, 3);
            }
            if (!suggestions.isEmpty()) {
                notices.add("Unknown config key '" + key + "' — did you mean "
                    + suggestions.stream()
                        .map(s -> "'" + s + "'")
                        .collect(Collectors.joining(" or "))
                    + "?");
                continue;
            }
            if (isAdmitted(key, admitted)) {
                continue; // a family that can never be listed: presence proves nothing
            }
            String namespace = claimedNamespace(key, listed);
            if (namespace != null) {
                notices.add("Unknown config key '" + key + "' — namespace '" + namespace
                    + "' is declared but no module reads this key");
            }
            // Nothing claims the key's namespace: a vocabulary never contributed
            // cannot be judged against, so silence (subset assembly).
        }
        return notices;
    }

    /** Whether a prefix-only admission covers {@code key}. */
    private static boolean isAdmitted(String key, List<KnownKeys> admitted) {
        for (KnownKeys vocab : admitted) {
            if (vocab.namespaceUnder(key) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The most specific claimed namespace {@code key} sits under, or
     * {@code null} when none claims it. The root {@code freeway.} never
     * counts: boot sits on it generically, so it claims no module surface —
     * a key under the root alone is as unjudgeable as an absent vocabulary.
     */
    private static String claimedNamespace(String key, List<KnownKeys> listed) {
        String claimed = null;
        for (KnownKeys vocab : listed) {
            String namespace = vocab.namespaceUnder(key);
            if (namespace == null || namespace.equals(KnownKeys.ROOT_PREFIX)) {
                continue;
            }
            if (claimed == null || namespace.length() > claimed.length()) {
                claimed = namespace;
            }
        }
        return claimed;
    }
}
