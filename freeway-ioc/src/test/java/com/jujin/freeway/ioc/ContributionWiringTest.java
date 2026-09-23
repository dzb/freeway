package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.ioc.annotation.*;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.jujin.freeway.ioc.FreewayTestSupport.*;

/** ContributionWiringTest: split from the former FreewayTest monolith (behavior-preserving move). */
class ContributionWiringTest {
    @BeforeEach
    void captureSystemProperties() { FreewayTestSupport.capture(); }

    @AfterEach
    void restoreSystemProperties() { FreewayTestSupport.restore(); }

    @Test
    void contributedClassSeesCoerceRuleFromSameModule() {
        System.setProperty("endpoint", "192.168.1.1:443");
        Container container = Freeway.create(binder -> {
            binder.contribute(CoerceRule.class).add(new CoerceRule<>(
                String.class, Endpoint.class,
                v -> { String[] p = v.split(":", 2); return new Endpoint(p[0], Integer.parseInt(p[1])); }));
            binder.contribute(ContributorMarker.class).add(EndpointContributor.class);
        });

        var marker = container.extension(ContributorMarker.class).all().stream()
                .filter(m -> m instanceof EndpointContributor)
                .map(m -> (EndpointContributor) m)
                .findFirst().orElseThrow();

        assertEquals(new Endpoint("192.168.1.1", 443), marker.endpoint);
        System.clearProperty("endpoint");
    }

    @Test
    void classContributionAutoIdsIncludePackageName() {
        Container container = Freeway.create(binder -> {
            // CoreBean auto-id = core_bean@<package>
            binder.contribute(Labeled.class).add(CoreBean.class);
            binder.contribute(Labeled.class).add(WebBean.class).after(
                    "core_bean@" + CoreBean.class.getPackageName());
        });
        List<String> labels = container.extension(Labeled.class).all()
                .stream().map(Labeled::label).toList();
        assertEquals(List.of("core", "web"), labels,
                "auto-ids with package suffix should allow ordering");
    }

    @Test
    void classContributionCanUseCoerceRuleFromSeparateModule() {
        System.setProperty("endpoint", "10.0.0.1:8080");
        Container container = Freeway.create(
            // module A: registers coercion
            binder -> binder.contribute(CoerceRule.class).add(new CoerceRule<>(
                String.class, Endpoint.class,
                v -> { String[] p = v.split(":", 2); return new Endpoint(p[0], Integer.parseInt(p[1])); })),
            // module B: contributes a class that depends on that coercion
            binder -> binder.contribute(ContributorMarker.class).add(EndpointContributor.class)
        );

        var marker = container.extension(ContributorMarker.class).all().stream()
                .filter(m -> m instanceof EndpointContributor)
                .map(m -> (EndpointContributor) m)
                .findFirst().orElseThrow();

        assertEquals(new Endpoint("10.0.0.1", 8080), marker.endpoint);
        System.clearProperty("endpoint");
    }

    @Test
    void sameModuleClassContributedSymbolProviderIsWired() {
        Container container = Freeway.create(binder -> {
            binder.contribute(SymbolProvider.class).add(SpecialSymbolProvider.class);
            binder.contribute(SpecialConsumer.class).add(SpecialConsumerImpl.class);
        });

        Object consumer = container.extension(SpecialConsumer.class).all().get(0);
        assertEquals("special-value", ((SpecialConsumer) consumer).value(),
            "add(Class) SymbolProvider in the same module must be wired before the consumer is created");
        container.close();
    }

    @Test
    void classContributedSymbolProviderFromEarlierModuleIsWired() {
        Container container = Freeway.create(
            binder -> binder.contribute(SymbolProvider.class).add(SpecialSymbolProvider.class),
            binder -> binder.contribute(SpecialConsumer.class).add(SpecialConsumerImpl.class)
        );

        Object consumer = container.extension(SpecialConsumer.class).all().get(0);
        assertEquals("special-value", ((SpecialConsumer) consumer).value());
        container.close();
    }

    @Test
    void symbolProviderDeclaredAfterConsumerInSameModuleIsWired() {
        // Regression: deferred class contributions flushed FIFO, so a
        // SymbolProvider declared after its consumer in the same module failed
        // construction with "Unknown symbol". Providers must flush first.
        Container container = Freeway.create(binder -> {
            binder.contribute(SpecialConsumer.class).add(SpecialConsumerImpl.class);
            binder.contribute(SymbolProvider.class).add(SpecialSymbolProvider.class);
        });

        Object consumer = container.extension(SpecialConsumer.class).all().get(0);
        assertEquals("special-value", ((SpecialConsumer) consumer).value(),
            "provider declared after its consumer must still be wired before the consumer is created");
        container.close();
    }

    @Test
    void classSymbolProviderCreatedExactlyOnce() {
        // The config layer drains first and creates the provider exactly once:
        // deferred consumers resolve against that one instance, no facade.
        SpecialSymbolProvider.instances.set(0);
        Container container = Freeway.create(binder -> {
            binder.contribute(SymbolProvider.class).add(SpecialSymbolProvider.class);
            binder.contribute(SpecialConsumer.class).add(SpecialConsumerImpl.class);
        });

        Object consumer = container.extension(SpecialConsumer.class).all().get(0);
        assertEquals("special-value", ((SpecialConsumer) consumer).value());
        assertEquals(1, SpecialSymbolProvider.instances.get(),
            "facade lookup and flush must not create two provider instances");
        container.close();
    }

    @Test
    void createPrefersNoArgConstructorOverLargerConstructor() {
        Container container = Freeway.create(binder -> {});
        MultiCtorBean bean = container.create(MultiCtorBean.class);
        assertTrue(bean.noArgUsed,
            "no-arg constructor must be preferred over a larger convenience constructor");
        container.close();
    }

    @Test
    void classContributionCreatesAndOrders() {
        Container container = Freeway.create(
            binder -> binder.contribute(Labeled.class)
                .add(WebBean.class).after("core_bean@" + CoreBean.class.getPackageName()),
            binder -> binder.contribute(Labeled.class)
                .add(CoreBean.class)
        );

        List<String> labels = container.extension(Labeled.class).all()
            .stream().map(Labeled::label).toList();
        assertEquals(List.of("core", "web"), labels);
    }

    @Test
    void orderingDeclaredAfterFirstReadIsHonored() {
        // Regression: before()/after() applied after the sorted cache was
        // built were silently dropped — the stale order was served forever.
        // The window for both the read and the constraint is composition:
        // the deferred factory below warms the cache during the drain, then
        // declares the constraint on the earlier entry — both after that
        // entry landed. The re-read happens *inside* the factory: the
        // factory's own add() right after return would invalidate the cache
        // anyway and mask a constraint that failed to. (Post-composition
        // the window is sealed; see
        // ExtensionAggregationTest.containerSealsExtensionsAfterComposition.)
        postReadHandle.set(null);
        Container container = Freeway.create(
            binder -> binder.contribute(Labeled.class).add("first", new CoreBean()),
            binder -> postReadHandle.set(
                binder.contribute(Labeled.class).add("second", new WebBean())),
            binder -> binder.contribute(Labeled.class).add("warmer", c -> {
                // Warm the sorted cache (insertion order, no constraints yet)…
                c.extension(Labeled.class).all();
                // …then constrain an entry that is already in the store…
                postReadHandle.get().before("first");
                // …and re-read before this factory's own entry lands: only
                // before()'s invalidation can explain the new order here.
                assertEquals(List.of("web", "core"),
                    c.extension(Labeled.class).all().stream()
                        .map(Labeled::label).toList(),
                    "before() after the first all() must invalidate the cached order");
                return (Labeled) () -> "warmer";
            })
        );

        assertEquals(List.of("web", "core", "warmer"),
            container.extension(Labeled.class).all().stream()
                .map(Labeled::label).toList(),
            "ordering declared after the first all() must invalidate the cached order");
        container.close();
    }

    @Test
    void deferredHandleRejectsOrderingAfterItsContributionLanded() {
        // The applied window: a deferred handle goes inert the moment its
        // instance lands in the drain — seal() has not run yet (composed is
        // still false), but nothing will ever replay a constraint declared
        // now, so it must fail loudly exactly like the post-seal case.
        postReadHandle.set(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> Freeway.create(
                binder -> postReadHandle.set(
                    binder.contribute(Labeled.class).add("first", c -> {
                        return (Labeled) () -> "core";
                    })),
                binder -> binder.contribute(Labeled.class).add("second", c -> {
                    // Deferred runnables drain in declaration order, so
                    // "first" has already landed and applied its handle.
                    postReadHandle.get().before("first");
                    return (Labeled) () -> "web";
                })
            ));
        assertTrue(ex.getMessage().contains("before()"),
            "message must name the operation: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("replay"),
            "message must explain the consequence: " + ex.getMessage());
    }

    @Test
    void classContributionCanDependOnLaterModuleBinding() {
        // Module A contributes an implementation class whose constructor needs
        // a service declared by module B. Class contributions are instantiated
        // only after every module has bound, so declaration order must not
        // matter — the contributed class resolves across modules.
        Container container = Freeway.create(
            binder -> binder.contribute(LaterDepConsumer.class).add(LaterDepConsumerImpl.class),
            binder -> binder.bind(LaterDep.class).to(LaterDepImpl.class)
        );
        var consumers = container.extension(LaterDepConsumer.class).all();
        assertEquals(1, consumers.size());
        assertTrue(consumers.getFirst() instanceof LaterDepConsumerImpl);
        // Constructor dependency resolved from the later module's binding.
        assertSame(container.get(LaterDep.class),
            ((LaterDepConsumerImpl) consumers.getFirst()).dep);
    }

    @Test
    void nestedModuleClassContributionResolvesOuterBindings() {
        // A nested module contributes a class whose constructor needs a service
        // bound by the module that declares it. Class contributions run only
        // after every module has bound, so this must resolve regardless of
        // declaration order.
        Container container = Freeway.create(ModuleNode.app("test",
            ModuleNode.of(outer -> outer.bind(NestedDep.class).to(NestedDepImpl.class)),
            ModuleNode.of(inner ->
                inner.contribute(NestedDepConsumer.class).add(NestedDepConsumerImpl.class))));
        var consumers = container.extension(NestedDepConsumer.class).all();
        assertEquals(1, consumers.size());
        assertTrue(consumers.getFirst() instanceof NestedDepConsumerImpl);
        assertSame(container.get(NestedDep.class),
            ((NestedDepConsumerImpl) consumers.getFirst()).dep);
    }

    @Test
    void factoryContributionGetsTheContainerAndSeesLaterModuleBindings() {
        // The factory form is the container-aware sibling of add(Class): it
        // must run in the same deferred phase — after every module has bound —
        // so a service declared by a later module resolves inside the factory,
        // and the container handed in is the container itself.
        Container[] seen = new Container[1];
        Container container = Freeway.create(
            binder -> binder.contribute(Labeled.class).add("made", c -> {
                seen[0] = c;
                return () -> c.get(LaterDep.class) == null ? "missing" : "resolved";
            }),
            binder -> binder.bind(LaterDep.class).to(LaterDepImpl.class)
        );
        assertSame(container, seen[0]);
        assertEquals(List.of("resolved"),
            container.extension(Labeled.class).all().stream()
                .map(Labeled::label).toList());
        container.close();
    }

    @Test
    void factoryContributionOrderingDeclaredAtBindTimeSurvivesDeferral() {
        // Ordering declared on the handle returned at bind time must reach
        // the real entry when the deferred instance lands in the extension.
        Container container = Freeway.create(binder -> {
            binder.contribute(Labeled.class).add("first", new CoreBean());
            binder.contribute(Labeled.class).add("last", new WebBean());
            binder.contribute(Labeled.class)
                .add("middle", c -> (Labeled) () -> "middle")
                .before("last");
        });
        assertEquals(List.of("core", "middle", "web"),
            container.extension(Labeled.class).all().stream()
                .map(Labeled::label).toList());
        container.close();
    }

    @Test
    void factoryContributionRejectsDuplicateId() {
        // The id lands with the deferred instance, so the duplicate surfaces
        // at container build — the same failure the instance form gives,
        // under the same message.
        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> Freeway.create(binder -> {
                binder.contribute(Labeled.class).add("taken", new CoreBean());
                binder.contribute(Labeled.class)
                    .add("taken", c -> (Labeled) () -> "other");
            })
        );
        assertTrue(ex.getMessage().contains("taken"));
    }
}
