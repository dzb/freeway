package com.jujin.freeway.ioc.symbol;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the vocabulary rules: reflection harvest, namespace fence, suggestion shape. */
class KnownKeysTest {

    /** Stands in for a one-namespace *ConfigKeys class: keys are strings, values are not. */
    public static final class FakeKeys {
        public static final String SERVER_HOST = "freeway.fake.server.host";
        public static final String SERVER_PORT = "freeway.fake.server.port";
        public static final String DASHED_VALUE = "freeway-fake";
        public static final int NOT_A_KEY = 8080;
    }

    /** A table spelling two namespaces: legal, but each one has to be declared. */
    public static final class TwoNamespaceKeys {
        public static final String FAKE_PREFIX = "freeway.fake";
        public static final String FAKE_PORT = "freeway.fake.server.port";
        public static final String OTHER_PREFIX = "freeway.other";
        public static final String OTHER = "freeway.other.key";
    }

    /** A table with no listable keys: contributing it would switch the check off in silence. */
    public static final class EmptyKeys {
        public static final String NOT_A_KEY = "unrelated";
        public static final int NUMBER = 1;
    }

    @Test
    void reflectionHarvestsTheNamespacesStrings() {
        KnownKeys vocab = KnownKeys.of(FakeKeys.class, "freeway.fake");
        assertEquals(Set.of("freeway.fake.server.host", "freeway.fake.server.port"), vocab.keys(),
            "non-String fields and values outside freeway.* are not keys");
    }

    @Test
    void aLookAlikeNeighbourNamespaceIsRefused() {
        // "freeway.fake2" starts with "freeway.fake" as a string but is a different namespace:
        // the fence is on dot boundaries, so this table may not quietly absorb it.
        KnownKeys vocab =
            new KnownKeys(Set.of("freeway.fake"), Set.of("freeway.fake.server.port"));
        assertTrue(vocab.suggest("freeway.fake2.server.port").isEmpty(),
            "a neighbour namespace gets no suggestions from this table");
        assertThrows(IllegalArgumentException.class, () -> new KnownKeys(
            Set.of("freeway.fake"), Set.of("freeway.fake2.server.port")));
    }

    @Test
    void aTableKeyOutsideTheDeclaredNamespaceIsRefused() {
        // Dropping it instead would leave freeway.other.* with no vocabulary at all — the
        // unknown-key check would never report a typo there, and nothing would say why.
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> KnownKeys.of(TwoNamespaceKeys.class, "freeway.fake"));
        assertTrue(error.getMessage().contains("freeway.other.key"), error.getMessage());

        // Declaring both namespaces is the fix, and it is the module's call to make: one
        // vocabulary for the table — the shape a module with two namespaces contributes.
        KnownKeys both =
            KnownKeys.of(TwoNamespaceKeys.class, "freeway.fake", "freeway.other");
        assertEquals(Set.of("freeway.fake.server.port", "freeway.other.key"), both.keys(),
            "a declared namespace constant names a prefix, never a key");
        assertTrue(both.suggest("freeway.fake.server.port").isEmpty());
        assertTrue(both.suggest("freeway.other.ke").contains("freeway.other.key"));
    }

    @Test
    void unreadableConstantFailsLoudlyInsteadOfVanishing() throws Exception {
        // A constants class another package cannot access: getFields() shows its public constants,
        // reading them fails. Skipping them silently would hand back an empty vocabulary — the
        // unknown-key check switched off for that prefix with nothing to notice — so it must fail.
        Class<?> hidden = Class.forName("com.jujin.freeway.ioc.fixtures.HiddenKeys");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> KnownKeys.of(hidden, "freeway.hidden"));
        assertTrue(error.getMessage().contains("HiddenKeys"), error.getMessage());
    }

    @Test
    void constructorFencesKeysToPrefix() {
        assertThrows(IllegalArgumentException.class,
            () -> new KnownKeys(Set.of("freeway.fake"), Set.of("freeway.other.key")));
    }

    @Test
    void exactHitSuggestsNothing() {
        KnownKeys vocab = KnownKeys.of(FakeKeys.class, "freeway.fake");
        assertTrue(vocab.suggest("freeway.fake.server.port").isEmpty());
    }

    @Test
    void typoSuggestsNearestCappedAndOrdered() {
        KnownKeys vocab = new KnownKeys(Set.of("freeway.t"),
            Set.of("freeway.t.bat", "freeway.t.cart", "freeway.t.cut", "freeway.t.cap"));
        // "cat" is 1 edit from each — 4 candidates, 3 returned, alphabetical.
        assertEquals(
            List.of("freeway.t.bat", "freeway.t.cap", "freeway.t.cart"),
            vocab.suggest("freeway.t.cat"));
    }

    @Test
    void distantAndForeignKeysStaySilent() {
        KnownKeys vocab = KnownKeys.of(FakeKeys.class, "freeway.fake");
        assertTrue(vocab.suggest("freeway.fake.server.port.number").isEmpty());
        assertTrue(vocab.suggest("freeway.other.key").isEmpty());
        assertTrue(vocab.suggest(null).isEmpty());
    }

    @Test
    void prefixOnlyAdmissionNeverSuggests() {
        assertTrue(KnownKeys.admit("freeway.log").suggest("freeway.log.leve").isEmpty());
    }

    @Test
    void anEmptyHarvestIsRefused() {
        // of() with nothing listable must fail, not hand back an empty vocabulary: the empty
        // shape belongs to admit(...) alone, whose prefix-only meaning the check understands.
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> KnownKeys.of(EmptyKeys.class, "freeway.fake"));
        assertTrue(error.getMessage().contains("admit"), error.getMessage());
    }
}
