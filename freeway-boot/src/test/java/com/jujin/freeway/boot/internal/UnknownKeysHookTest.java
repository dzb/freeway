package com.jujin.freeway.boot.internal;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.KnownKeys;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure check and the wiring that feeds it: universe assembly is asserted, not smoke-run. */
class UnknownKeysHookTest {

    private static final KnownKeys FAKE = new KnownKeys(Set.of("freeway.fake"),
        Set.of("freeway.fake.server.port"));

    @Test
    void typoProducesNoticeWithFix() {
        List<String> notices = UnknownKeysHook.check(
            Set.of("freeway.fake.sever.port"), List.of(FAKE));
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("freeway.fake.sever.port"));
        assertTrue(notices.get(0).contains("freeway.fake.server.port"));
    }

    @Test
    void knownAppAndRetiredKeysStaySilent() {
        List<String> notices = UnknownKeysHook.check(
            Set.of("freeway.fake.server.port", "myapp.theme", "freeway.web.server.port"),
            List.of(FAKE));
        assertTrue(notices.isEmpty());
    }

    @Test
    void hookRunsAgainstARealContainer() {
        try (Container container = Freeway.create(
            new BootModule(AppConfigDefault.of(
                Map.of("freeway.fake.sever.port", "8080"), List.of())),
            binder -> binder.contribute(KnownKeys.class).add(FAKE))) {
            new UnknownKeysHook().start(container);
        }
    }

    @Test
    void hookReadsTheDeclaredUniverseFromTheConfig() {
        // The wiring the feature rests on: a config whose keys() stops reporting its
        // tiers turns the check off with nothing to notice — so it is asserted, not smoke-run.
        try (Container container = Freeway.create(
            new BootModule(AppConfigDefault.of(
                Map.of("freeway.fake.sever.port", "8080"), List.of())),
            binder -> binder.contribute(KnownKeys.class).add(FAKE))) {
            List<String> notices = UnknownKeysHook.notices(container);

            assertEquals(1, notices.size(), notices.toString());
            assertTrue(notices.get(0).contains("freeway.fake.sever.port"));
            assertTrue(notices.get(0).contains("freeway.fake.server.port"));
        }
    }

    @Test
    void bootDeclaresItsOwnKeysThroughItsTable() {
        // The cascade's own keys come from BootModule.ConfigKeys via one of(...) call, like every other
        // table does — so a typo of an activation key names the fix instead of being silent.
        try (Container container = Freeway.create(
            new BootModule(AppConfigDefault.of(
                Map.of("freeway.profil", "prod"), List.of())))) {
            List<String> notices = UnknownKeysHook.notices(container);

            assertEquals(1, notices.size(), notices.toString());
            assertTrue(notices.get(0).contains("freeway.profil"));
            assertTrue(notices.get(0).contains("freeway.profile"));
        }
    }

    @Test
    void fixedLogKeysSuggestAFixWhileDynamicOnesStaySilent() {
        // Logging is the infrastructure modules' only configuration: its fixed keys are declared
        // (logical typo -> fix), while freeway.log.file.<name>.* is dynamic and cannot be listed —
        // its prefix is admitted (KnownKeys.admit), exempting the family from the namespace rule —
        // so a legitimate named file must not be reported as an unknown key.
        try (Container container = Freeway.create(
            new BootModule(AppConfigDefault.of(
                Map.of("freeway.log.leval", "DEBUG", "freeway.log.file.app.path", "logs/app.log"),
                List.of())))) {
            List<String> notices = UnknownKeysHook.notices(container);

            assertEquals(1, notices.size(), notices.toString());
            assertTrue(notices.get(0).contains("freeway.log.leval"));
            assertTrue(notices.get(0).contains("freeway.log.level"));
        }
    }

    @Test
    void hookAlsoReadsTheSystemPropertiesTier() {
        // -D keys never pass through AppConfig, so the hook reads them itself; without this the
        // most convenient way to typo a key would be the one it cannot see.
        System.setProperty("freeway.fake.sever.port", "8080");
        try (Container container = Freeway.create(
            new BootModule(AppConfigDefault.of(Map.of(), List.of())),
            binder -> binder.contribute(KnownKeys.class).add(FAKE))) {
            List<String> notices = UnknownKeysHook.notices(container);

            assertEquals(1, notices.size(), notices.toString());
            assertTrue(notices.get(0).contains("freeway.fake.sever.port"));
        } finally {
            System.clearProperty("freeway.fake.sever.port");
        }
    }

    @Test
    void unknownKeyInAClaimedNamespaceIsNamedWithoutSuggestions() {
        // Second rule: a key under a namespace a module declared (root excluded) can be
        // judged — no vocabulary lists it, so nothing reads it — even when it is too far
        // from every listed key for a spelling suggestion.
        List<String> notices = UnknownKeysHook.check(
            Set.of("freeway.fake.absent.control"), List.of(FAKE));

        assertEquals(1, notices.size(), notices.toString());
        assertTrue(notices.get(0).contains("freeway.fake.absent.control"));
        assertTrue(notices.get(0).contains("namespace 'freeway.fake'"), notices.get(0));
        assertTrue(!notices.get(0).contains("did you mean"), notices.get(0));
    }

    @Test
    void keysOutsideEveryClaimedNamespaceStaySilent() {
        // Subset assembly: a namespace no vocabulary was contributed for cannot be judged —
        // and the root counts as no claim at all (boot's table sits on freeway. generically,
        // so every unknown key is under it). Silence, not a guess.
        try (Container container = Freeway.create(
            new BootModule(AppConfigDefault.of(
                Map.of("freeway.othermod.thing", "1"), List.of())))) {
            List<String> notices = UnknownKeysHook.notices(container);

            assertTrue(notices.isEmpty(), notices.toString());
        }
    }

    @Test
    void aPrefixOnlyVocabularyExemptsItsFamilyFromTheNamespaceRule() {
        // admit(...) marks a family that can never be listed: presence alone proves nothing
        // there, so the claimed-namespace rule must not fire — while the same key without
        // the admission is named, pinned by contrast.
        List<String> without = UnknownKeysHook.check(
            Set.of("freeway.fake.server.admin.token"), List.of(FAKE));
        List<String> with = UnknownKeysHook.check(
            Set.of("freeway.fake.server.admin.token"),
            List.of(FAKE, KnownKeys.admit("freeway.fake.server.")));

        assertEquals(1, without.size(), without.toString());
        assertTrue(without.get(0).contains("namespace 'freeway.fake'"), without.get(0));
        assertTrue(with.isEmpty(), with.toString());
    }
}
