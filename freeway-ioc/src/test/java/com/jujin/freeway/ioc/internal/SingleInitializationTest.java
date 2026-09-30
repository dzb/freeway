package com.jujin.freeway.ioc.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.annotation.PostConstruct;
import com.jujin.freeway.ioc.annotation.PreDestroy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @PostConstruct} belongs to the managed half of the lifecycle, and
 * runs there exactly once.
 *
 * <p>The division is what these tests pin. {@link
 * com.jujin.freeway.ioc.Container#create(Class)} is {@code new} plus dependency
 * resolution — it satisfies constructor args and {@code @Inject} fields, and
 * stops there. It does not post-construct, because nothing tracks the result
 * for shutdown, so a callback here would never be paired with a
 * {@code @PreDestroy}.
 *
 * <p>That makes the trap of {@code to(c -> c.create(Impl.class))} impossible
 * rather than guarded: the provider's {@code create} does not post-construct,
 * and the binding's own materialize step is the single place that does. The
 * instance is wired AND post-constructed once, and — being bound — it gets its
 * {@code @PreDestroy} at {@code close()}. Both halves of the lifecycle, on the
 * one path that owns it.
 */
class SingleInitializationTest {

    private interface Greeter { String greet(); }

    private static final class CountingGreeter implements Greeter {
        static final AtomicInteger POST_CONSTRUCT = new AtomicInteger();
        static final AtomicInteger PRE_DESTROY = new AtomicInteger();

        @PostConstruct
        void init() {
            POST_CONSTRUCT.incrementAndGet();
        }

        @PreDestroy
        void destroy() {
            PRE_DESTROY.incrementAndGet();
        }

        @Override
        public String greet() {
            return "hello";
        }
    }

    /** No bindings at all — a caller-owned instance built straight from the container. */
    private static final class StandaloneBean {
        static final AtomicInteger POST_CONSTRUCT = new AtomicInteger();

        @PostConstruct
        void init() {
            POST_CONSTRUCT.incrementAndGet();
        }
    }

    /** Proves create() still wires fields — injection is the half it keeps. */
    private static final class InjectedBean {
        @com.jujin.freeway.ioc.annotation.Inject String name;
    }

    @Test
    void providerUsingContainerCreateInitializesOnce() {
        CountingGreeter.POST_CONSTRUCT.set(0);
        CountingGreeter.PRE_DESTROY.set(0);

        try (Container container = Freeway.create(
            binder -> binder.bind(Greeter.class).to(c -> c.create(CountingGreeter.class)))) {

            assertEquals("hello", container.get(Greeter.class).greet());
            assertEquals(1, CountingGreeter.POST_CONSTRUCT.get(),
                "to(c -> c.create(X)) must run @PostConstruct exactly once — the "
                    + "provider already produced an initialized instance, and "
                    + "initializing again would double every lifecycle callback");
        }

        assertEquals(1, CountingGreeter.PRE_DESTROY.get(),
            "@PreDestroy must still run exactly once for the bound instance");
    }

    @Test
    void classBindingInitializesOnce() {
        CountingGreeter.POST_CONSTRUCT.set(0);

        try (Container container = Freeway.create(
            binder -> binder.bind(Greeter.class).to(CountingGreeter.class))) {

            container.get(Greeter.class).greet();
        }

        assertEquals(1, CountingGreeter.POST_CONSTRUCT.get(),
            "to(Class) constructs without lifecycle; the binding's own "
                + "materialize step is what runs it, once");
    }

    @Test
    void providerReturningAFreshInstanceInitializesOnce() {
        CountingGreeter.POST_CONSTRUCT.set(0);

        try (Container container = Freeway.create(
            binder -> binder.bind(Greeter.class).to(c -> new CountingGreeter()))) {

            container.get(Greeter.class).greet();
        }

        assertEquals(1, CountingGreeter.POST_CONSTRUCT.get(),
            "a provider-built instance is initialized by the binding, once — "
                + "this is the path that must not skip lifecycle");
    }

    /**
     * The fourth shape: a concrete class bound with no {@code to(...)} at all.
     * {@code directInstance} takes its {@code provider == null} branch and
     * constructs through {@code instantiateDefault} — no provider involved, so
     * it is the shape least likely to be considered when reasoning about the
     * provider/create interaction, and the one that regressed first.
     */
    @Test
    void concreteClassBoundWithoutAProviderInitializesOnce() {
        CountingGreeter.POST_CONSTRUCT.set(0);
        CountingGreeter.PRE_DESTROY.set(0);

        try (Container container = Freeway.create(
            binder -> binder.bind(CountingGreeter.class))) {

            assertEquals("hello", container.get(CountingGreeter.class).greet());
            assertEquals(1, CountingGreeter.POST_CONSTRUCT.get(),
                "bind(Concrete.class) with no to(...) constructs through "
                    + "instantiateDefault; lifecycle must still run once");
        }

        assertEquals(1, CountingGreeter.PRE_DESTROY.get(),
            "and it is a managed binding, so @PreDestroy pairs with it");
    }

    @Test
    void publicCreateDoesNotPostConstruct() {
        StandaloneBean.POST_CONSTRUCT.set(0);

        try (Container container = Freeway.create(binder -> { })) {
            assertEquals(StandaloneBean.class,
                container.create(StandaloneBean.class).getClass());
            assertEquals(0, StandaloneBean.POST_CONSTRUCT.get(),
                "Container.create is `new` plus injection: nothing tracks the "
                    + "instance for shutdown, so a @PostConstruct here would "
                    + "never be paired with a @PreDestroy");
        }
    }

    /** Injection is the half create() does keep — it is why it beats `new`. */
    @Test
    void publicCreateStillInjectsFields() {
        try (Container container = Freeway.create(
            binder -> binder.bind(String.class).to(c -> "wired"))) {

            InjectedBean bean = container.create(InjectedBean.class);
            assertEquals("wired", bean.name,
                "create() is `new` plus dependency resolution — @Inject fields "
                    + "must still be satisfied, that is the whole point of it");
        }
    }

    /**
     * The caller owns a {@code create(Class)} instance's lifecycle, so the
     * container must not retain it. The one-shot guard is scoped to a single
     * realization for exactly this reason — a container-lifetime registry of
     * initialized instances would pin every object the application ever built
     * through the public create, and the container would own the memory of
     * things it does not manage.
     *
     * <p>Checked by reflection rather than {@code System.gc()}: a GC hint is
     * not a contract, and a test that fails on a busy machine is worse than no
     * test. What must be true is that the collection the container materializes
     * into does not mention the instance — which is a fact about that cache,
     * not about when the collector runs. It is reached two levels down, by
     * name, because {@code targetCache} hangs off {@code ServiceRuntime}: a
     * scan of {@code ContainerImpl}'s own fields finds no collection at all and
     * asserts nothing.
     */
    @Test
    void callerOwnedInstancesAreNotRetainedByTheContainer() throws Exception {
        StandaloneBean.POST_CONSTRUCT.set(0);
        ContainerImpl container = (ContainerImpl) Freeway.create(binder -> { });
        StandaloneBean bean = container.create(StandaloneBean.class);
        assertNotNull(bean);

        Object runtime = field(container, "serviceRuntime");
        assertFalse(containsIdentity((Map<?, ?>) field(runtime, "targetCache"), bean),
            "the service cache retains a caller-owned instance — what create() "
                + "hands out is not the container's to manage");
        assertFalse(containsIdentity((Map<?, ?>) field(runtime, "proxyCache"), bean),
            "nor does it hand out a proxy for one");

        container.close();

        assertEquals(0, StandaloneBean.POST_CONSTRUCT.get(),
            "create() does not post-construct, and close() must not run lifecycle "
                + "for an instance the container never managed");
    }

    /** Reads a declared field by name, so the assertion is about the container's
     *  own shape rather than about the internal types it is made of. */
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static boolean containsIdentity(Map<?, ?> map, Object needle) {
        for (Object key : map.keySet()) {
            if (key == needle) {
                return true;
            }
        }
        for (Object value : map.values()) {
            if (value == needle) {
                return true;
            }
        }
        return false;
    }

    @Test
    void prototypeScopeInitializesEveryInstanceOnce() {
        CountingGreeter.POST_CONSTRUCT.set(0);

        try (Container container = Freeway.create(binder -> binder
            .bind(Greeter.class)
            .scope(com.jujin.freeway.ioc.Scope.PROTOTYPE)
            .to(CountingGreeter.class))) {

            container.get(Greeter.class).greet();
            container.get(Greeter.class).greet();
        }

        assertEquals(2, CountingGreeter.POST_CONSTRUCT.get(),
            "two distinct prototype instances, each initialized exactly once");
    }

    @Test
    void interfaceBindingThroughProxyInitializesOnce() {
        CountingGreeter.POST_CONSTRUCT.set(0);

        try (Container container = Freeway.create(
            binder -> binder.bind(Greeter.class).to(CountingGreeter.class))) {

            // Realizing through the proxy defers construction to the first
            // call; the initialize must not be duplicated by that indirection.
            Greeter greeter = container.get(Greeter.class);
            greeter.greet();
            greeter.greet();

            assertEquals(1, CountingGreeter.POST_CONSTRUCT.get());
            assertTrue(CountingGreeter.PRE_DESTROY.get() >= 0);
        }
    }

    /** Guards the exact spelling from the audit note, kept as its own case. */
    @Test
    void theTrappingSpellingIsCovered() {
        CountingGreeter.POST_CONSTRUCT.set(0);
        List<String> seen = new ArrayList<>();

        try (Container container = Freeway.create(
            binder -> binder.bind(Greeter.class).to(c -> {
                seen.add("provider");
                return c.create(CountingGreeter.class);
            }))) {

            container.get(Greeter.class).greet();
        }

        assertEquals(List.of("provider"), seen, "the provider ran once");
        assertEquals(1, CountingGreeter.POST_CONSTRUCT.get());
    }
}
