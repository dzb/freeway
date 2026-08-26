package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.*;
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
}
