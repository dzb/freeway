package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.ioc.annotation.*;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void onDemandProviderFacadeCreatesExactlyOnce() {
        // The on-demand facade (wired at declaration) and the flush must share
        // one instance: the first lookup triggers creation, force() reuses it.
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
        postReadHandle.set(null);
        Container container = Freeway.create(binder -> {
            binder.contribute(Labeled.class).add("first", new CoreBean());
            postReadHandle.set(binder.contribute(Labeled.class).add("second", new WebBean()));
        });
        Extension<Labeled> ext = container.extension(Labeled.class);

        // Warm the cache first (insertion order, no constraints yet).
        assertEquals(List.of("core", "web"),
            ext.all().stream().map(Labeled::label).toList());

        // Declare the ordering constraint after the first read.
        postReadHandle.get().before("first");

        assertEquals(List.of("web", "core"),
            ext.all().stream().map(Labeled::label).toList(),
            "ordering declared after the first all() must invalidate the cached order");
        container.close();
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
    void nestedInstallClassContributionResolvesOuterBindings() {
        // A module installed NESTED inside another module's bind() contributes
        // a class whose constructor needs a service bound by the outer module
        // AFTER the nested install. Class contributions run only after every
        // module (nested included) has bound, so this must resolve.
        Container container = Freeway.create(outer -> {
            outer.install(new ModuleEx() {
                @Override
                public void bind(Binder inner) {
                    inner.contribute(NestedDepConsumer.class).add(NestedDepConsumerImpl.class);
                }
            });
            outer.bind(NestedDep.class).to(NestedDepImpl.class);
        });
        var consumers = container.extension(NestedDepConsumer.class).all();
        assertEquals(1, consumers.size());
        assertTrue(consumers.getFirst() instanceof NestedDepConsumerImpl);
        assertSame(container.get(NestedDep.class),
            ((NestedDepConsumerImpl) consumers.getFirst()).dep);
    }
}
