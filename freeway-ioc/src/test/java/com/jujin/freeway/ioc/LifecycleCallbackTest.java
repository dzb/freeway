package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.jujin.freeway.ioc.FreewayTestSupport.*;

/** LifecycleCallbackTest: split from the former FreewayTest monolith (behavior-preserving move). */
class LifecycleCallbackTest {
    @BeforeEach
    void captureSystemProperties() { FreewayTestSupport.capture(); }

    @AfterEach
    void restoreSystemProperties() { FreewayTestSupport.restore(); }

    @Test
    void callsPostConstructAfterInjection() {
        Container container = Freeway.create(binder ->
            binder.bind(PostConstructBean.class).to(PostConstructBean.class)
        );
        PostConstructBean bean = container.get(PostConstructBean.class);

        assertTrue(bean.initialized, "@PostConstruct should be called");
    }

    @Test
    void callsPostConstructOnPrototypeScope() {
        Container container = Freeway.create(binder ->
            binder.bind(PostConstructBean.class).to(PostConstructBean.class).scope(Scope.PROTOTYPE)
        );

        PostConstructBean bean = container.get(PostConstructBean.class);
        assertTrue(bean.initialized);
    }

    @Test
    void callsPreDestroyOnClose() {
        Container container = Freeway.create(binder ->
            binder.bind(PreDestroyBean.class).to(PreDestroyBean.class)
        );
        PreDestroyBean bean = container.get(PreDestroyBean.class);

        assertFalse(bean.destroyed);
        container.close();
        assertTrue(bean.destroyed, "@PreDestroy should be called on container close");
    }

    @Test
    void callsPrivatePostConstructAfterInjection() {
        Container container = Freeway.create(binder ->
            binder.bind(PrivateLifecycleBean.class).to(PrivateLifecycleBean.class)
        );

        PrivateLifecycleBean bean = container.get(PrivateLifecycleBean.class);

        assertTrue(bean.initialized, "private @PostConstruct should be called");
    }

    @Test
    void callsPrivatePreDestroyOnClose() {
        Container container = Freeway.create(binder ->
            binder.bind(PrivateLifecycleBean.class).to(PrivateLifecycleBean.class)
        );

        PrivateLifecycleBean bean = container.get(PrivateLifecycleBean.class);

        assertFalse(bean.destroyed);
        container.close();
        assertTrue(bean.destroyed, "private @PreDestroy should be called on container close");
    }

    @Test
    void preDestroyCalledBeforeAutoCloseable() {
        Container container = Freeway.create(binder ->
            binder.bind(LifecycleOrderBean.class).to(LifecycleOrderBean.class)
        );
        LifecycleOrderBean bean = container.get(LifecycleOrderBean.class);

        container.close();

        assertEquals("preDestroy,close", bean.order());
        assertEquals(2, bean.events.size(),
            "a class carrying both runs both — the two phases deduplicate separately,"
                + " not against each other");
    }

    @Test
    void rejectsInvalidPostConstructSignature() {
        Container container = Freeway.create(binder ->
            binder.bind(InvalidPostConstructBean.class).to(InvalidPostConstructBean.class)
        );

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(InvalidPostConstructBean.class));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void rejectsMultiplePostConstructInClass() {
        Container container = Freeway.create(binder ->
            binder.bind(DoublePostConstructBean.class).to(DoublePostConstructBean.class)
        );

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(DoublePostConstructBean.class));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void inheritedPostConstructFromParent() {
        Container container = Freeway.create(binder ->
            binder.bind(SubPostConstructBean.class).to(SubPostConstructBean.class)
        );
        SubPostConstructBean bean = container.get(SubPostConstructBean.class);

        assertTrue(bean.parentInit, "parent @PostConstruct should be inherited");
    }

    /**
     * A class-based binding with no {@code to(...)} builds its own instance, and
     * that build used to construct AND initialize before handing the instance to
     * the binding's materialize step — so the fields were injected twice and the
     * one-shot {@code @PostConstruct} rule was carried by the realize scope rather
     * than by the shape of the path. Both are invisible while field injection is
     * idempotent assignment, which is why only a counted callback can see them.
     */
    @Test
    void concreteBindingWithoutProviderInjectsFieldsOnceAndPostConstructsOnce() {
        CountingLifecycleBean.POST_CONSTRUCT.set(0);
        Dependency.CONSTRUCTED.set(0);

        try (Container container = Freeway.create(binder -> {
            // PROTOTYPE, so every field-injection pass builds a fresh
            // dependency: a second pass is then visible as a second construction
            // rather than being hidden by an idempotent overwrite.
            binder.bind(Dependency.class).scope(Scope.PROTOTYPE);
            binder.bind(CountingLifecycleBean.class);
        })) {

            container.get(CountingLifecycleBean.class);
        }

        assertEquals(1, CountingLifecycleBean.POST_CONSTRUCT.get(),
            "bind(Concrete.class) with no to(...) must post-construct once");
        assertEquals(1, Dependency.CONSTRUCTED.get(),
            "and inject its fields once — the binding's materialize step is the "
                + "one that initializes, so the construction step must not "
                + "initialize as well and leave the one-shot rule resting on the "
                + "realize scope instead of on the shape of the path");
    }

    static class Dependency {
        static final AtomicInteger CONSTRUCTED = new AtomicInteger();

        Dependency() {
            CONSTRUCTED.incrementAndGet();
        }
    }

    static class CountingLifecycleBean {
        static final java.util.concurrent.atomic.AtomicInteger POST_CONSTRUCT =
            new java.util.concurrent.atomic.AtomicInteger();

        @Inject
        Dependency dependency;

        @PostConstruct
        void init() {
            POST_CONSTRUCT.incrementAndGet();
        }
    }

    @Test
    void capturedInstanceProviderGetsFullLifecycle() {
        // A singleton provider returning a pre-built instance must run the
        // same lifecycle as every other binding: field injection,
        // @PostConstruct on realization and @PreDestroy on close.
        InstanceLifecycleBean instance = new InstanceLifecycleBean();
        Container container = Freeway.create(binder ->
            binder.bind(InstanceLifecycleBean.class).to(c -> instance)
        );
        assertFalse(instance.initialized, "lifecycle starts at realization, not at bind");
        assertTrue(container.get(InstanceLifecycleBean.class) == instance,
            "a captured-instance provider resolves to the provided instance");
        assertTrue(instance.initialized, "@PostConstruct must run for captured instances");
        assertFalse(instance.destroyed);
        container.close();
        assertTrue(instance.destroyed, "@PreDestroy must still run for captured instances");
    }

    static class InstanceLifecycleBean {
        boolean initialized;
        boolean destroyed;

        @PostConstruct
        void init() {
            initialized = true;
        }

        @PreDestroy
        void shutdown() {
            destroyed = true;
        }
    }
}
