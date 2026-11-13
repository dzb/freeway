package com.jujin.freeway.ioc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.PostConstruct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition contract around deferred non-config contributions: every
 * value is constructed and published before any field injection or
 * {@code @PostConstruct} runs. A service realized from a field-injection chain
 * therefore sees the complete extension view, while an eager collection read
 * that cannot be complete fails loudly and names the deferred
 * {@code Supplier} form.
 */
class ContributionLifecycleTest {

    interface Ability {
        String name();
    }

    interface Marker {
    }

    interface ConsumerMarker {
    }

    /** A service that snapshots the contributed abilities — the reported shape. */
    public static final class Registry {
        final List<Ability> all;

        public Registry(List<Ability> all) {
            this.all = List.copyOf(all);
        }
    }

    public static final class A1 implements Ability {
        @Inject Registry registry;

        @Override
        public String name() {
            return "a1";
        }
    }

    public static final class A2 implements Ability {
        @Override
        public String name() {
            return "a2";
        }
    }

    public static final class A3 implements Ability {
        @Override
        public String name() {
            return "a3";
        }
    }

    public static final class A4 implements Ability {
        @Override
        public String name() {
            return "a4";
        }
    }

    public static final class A5 implements Ability {
        @Override
        public String name() {
            return "a5";
        }
    }

    public static final class A6 implements Ability {
        @Override
        public String name() {
            return "a6";
        }
    }

    public static final class A7 implements Ability {
        @Override
        public String name() {
            return "a7";
        }
    }

    public static final class A8 implements Ability {
        @Override
        public String name() {
            return "a8";
        }
    }

    public static final class B1 implements Ability {
        @Override
        public String name() {
            return "b1";
        }
    }

    public static final class B2 implements Ability {
        @Override
        public String name() {
            return "b2";
        }
    }

    public static final class B3 implements Ability {
        @Override
        public String name() {
            return "b3";
        }
    }

    /** Realized from A1's field injection while the drain is still running. */
    @Test
    void fieldInjectedServiceSeesEveryCrossModuleContribution() {
        Container container = Freeway.create(
            binder -> {
                binder.contribute(Ability.class).addDeferred(A1.class);
                binder.contribute(Ability.class).addDeferred(A2.class);
                binder.contribute(Ability.class).addDeferred(A3.class);
                binder.contribute(Ability.class).addDeferred(A4.class);
                binder.contribute(Ability.class).addDeferred(A5.class);
                binder.contribute(Ability.class).addDeferred(A6.class);
                binder.contribute(Ability.class).addDeferred(A7.class);
                binder.contribute(Ability.class).addDeferred(A8.class);
                binder.bind(Registry.class).to(Registry.class);
            },
            binder -> {
                binder.contribute(Ability.class).addDeferred(B1.class);
                binder.contribute(Ability.class).addDeferred(B2.class);
                binder.contribute(Ability.class).addDeferred(B3.class);
            }
        );
        try {
            assertEquals(11, container.get(Registry.class).all.size(),
                "a service realized from the field-injection chain must see every "
                    + "cross-module contribution, not the prefix published so far");
            assertEquals(11, container.extension(Ability.class).all().size());
        } finally {
            container.close();
        }
    }

    public static final class PostConstructProbe implements Marker {
        static int seen = -1;

        @Inject
        List<Ability> abilities;

        @PostConstruct
        void init() {
            seen = abilities.size();
        }
    }

    /** Contributions are constructed before any of them is post-constructed. */
    @Test
    void postConstructSeesTheCompleteStore() {
        PostConstructProbe.seen = -1;
        Container container = Freeway.create(
            binder -> {
                binder.contribute(Marker.class).addDeferred(PostConstructProbe.class);
                binder.contribute(Ability.class).addDeferred(B1.class);
                binder.contribute(Ability.class).addDeferred(B2.class);
                binder.contribute(Ability.class).addDeferred(B3.class);
            }
        );
        try {
            assertEquals(3, PostConstructProbe.seen,
                "the field view injected before @PostConstruct must be complete");
        } finally {
            container.close();
        }
    }

    public static final class First implements Marker {
        @PostConstruct
        void init() {
            OrderTest.events.add("post");
        }
    }

    public static final class Second implements Marker {
        public Second() {
            OrderTest.events.add("construct");
        }
    }

    static final class OrderTest {
        static final List<String> events = new ArrayList<>();
    }

    @Test
    void noContributionIsPostConstructedBeforeEveryContributionIsConstructed() {
        OrderTest.events.clear();
        Container container = Freeway.create(
            binder -> {
                binder.contribute(Marker.class).addDeferred(First.class);
                binder.contribute(Marker.class).addDeferred(Second.class);
            }
        );
        try {
            assertEquals(List.of("construct", "post"), OrderTest.events,
                "@PostConstruct belongs to final build: Second's constructor must "
                    + "run before First's callback");
        } finally {
            container.close();
        }
    }

    public static final class EagerConsumer implements ConsumerMarker {
        public EagerConsumer(List<Ability> abilities) {
            // The constructor is the eager read the completeness gate refuses.
        }
    }

    @Test
    void eagerConstructorCollectionFailsInsteadOfFreezingAPrefix() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            Freeway.create(
                binder -> {
                    binder.contribute(Ability.class).addDeferred(B1.class);
                    binder.contribute(ConsumerMarker.class).addDeferred(EagerConsumer.class);
                },
                binder -> binder.contribute(Ability.class).addDeferred(B2.class)
            ));
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("EagerConsumer"), message);
        assertTrue(message.contains("Supplier"), message);
    }

    public static final class SupplierConsumer implements ConsumerMarker {
        final Supplier<List<Ability>> abilities;

        @Inject
        Supplier<List<Ability>> fieldAbilities;

        @Inject
        Supplier<Map<String, Ability>> byId;

        public SupplierConsumer(Supplier<List<Ability>> abilities) {
            this.abilities = abilities;
        }
    }

    @Test
    void supplierDefersTheReadUntilAfterStartup() {
        Container container = Freeway.create(
            binder -> {
                binder.contribute(ConsumerMarker.class).addDeferred(SupplierConsumer.class);
                binder.contribute(Ability.class).addDeferred(B1.class);
                binder.contribute(Ability.class).addDeferred(B2.class);
                binder.contribute(Ability.class).addDeferred(B3.class);
            }
        );
        try {
            SupplierConsumer consumer = (SupplierConsumer)
                container.extension(ConsumerMarker.class).all().get(0);
            assertEquals(3, consumer.abilities.get().size(),
                "the Supplier form reads the store after composition, when it is complete");
            assertEquals(3, consumer.fieldAbilities.get().size(),
                "a Supplier field uses the same deferred view");
            assertEquals(3, consumer.byId.get().size(),
                "Supplier<Map<String, E>> resolves the named view lazily");
        } finally {
            container.close();
        }
    }
}
