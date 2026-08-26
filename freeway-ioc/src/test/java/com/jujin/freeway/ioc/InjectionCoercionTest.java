package com.jujin.freeway.ioc;
import com.jujin.freeway.ioc.annotation.Inject;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.ioc.annotation.*;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.jujin.freeway.ioc.FreewayTestSupport.*;

/** InjectionCoercionTest: split from the former FreewayTest monolith (behavior-preserving move). */
class InjectionCoercionTest {
    @BeforeEach
    void captureSystemProperties() { FreewayTestSupport.capture(); }

    @AfterEach
    void restoreSystemProperties() { FreewayTestSupport.restore(); }

    @Test
    void primarySymbolSourceOverrideIsHonoredEverywhere() {
        Container container = Freeway.create(binder ->
            binder.bind(SymbolSource.class).to(new OverrideSymbolSource()).primary()
        );

        ValueConsumer consumer = container.create(ValueConsumer.class);
        assertEquals("override:ioc.override.key", consumer.value,
            "@Value expansion must use the primary SymbolSource override");

        SymbolHolder holder = container.create(SymbolHolder.class);
        assertEquals("override:y", holder.source.resolve("y"),
            "constructor injection of SymbolSource must honor the primary override");
        container.close();
    }

    @Test
    void injectWithUnrecognizedAnnotationStillResolvesPlainBinding() {
        Container container = Freeway.create(binder -> {
            binder.bind(SimpleGreeter.class).to(SimpleGreeterImpl.class);
            binder.bind(GreeterConsumer.class).to(GreeterConsumer.class);
        });

        GreeterConsumer consumer = container.get(GreeterConsumer.class);
        assertEquals("hi", consumer.greeter.greet(),
            "unrecognized annotation at the injection point should not break resolution");
        container.close();
    }

    @Test
    void createsAnnotatedTypesWithSymbolExpansionAndCoercion() {
        System.setProperty(PORT_KEY, "8081");
        System.setProperty(NAME_KEY, "freeway");

        Container container = Freeway.create();
        ConfiguredService service = container.create(ConfiguredService.class);

        assertEquals(8081, service.port());
        assertEquals("freeway", service.name());
        assertEquals("8081:freeway", service.summary());
    }

    @Test
    void injectsAnnotatedFields() {
        System.setProperty(PORT_KEY, "9090");
        System.setProperty(NAME_KEY, "field-app");

        Container container = Freeway.create();
        FieldConfiguredService service = container.create(FieldConfiguredService.class);

        assertEquals(9090, service.port());
        assertEquals("field-app", service.name());
    }

        private static boolean chainContains(Throwable t, Class<? extends Throwable> type, String fragment) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c) && c.getMessage() != null && c.getMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

@Test
    void injectIntoFinalFieldWithInjectThrows() {
        // Regression: a final field carrying @Inject was silently skipped and
        // kept its default value — the mis-injection surfaced only at runtime.
        Container container = Freeway.create();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.create(FinalInjectFieldBean.class),
            "injecting into a final field must fail fast at construction");
        assertTrue(chainContains(ex, IllegalStateException.class, "use constructor injection"),
            "the error must point at constructor injection, got: " + ex);
        container.close();
    }

    @Test
    void injectIntoFinalFieldWithValueThrows() {
        Container container = Freeway.create();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.create(FinalValueFieldBean.class),
            "a final field carrying @Value must fail fast, not stay at its default");
        assertTrue(chainContains(ex, IllegalStateException.class, "use constructor injection"),
            "got: " + ex);
        container.close();
    }

    @Test
    void finalFieldWithoutInjectionAnnotationIsLeftAlone() {
        // Non-writable fields WITHOUT any injection annotation keep the
        // existing skip behavior — no exception, default value preserved.
        Container container = Freeway.create();
        FinalPlainFieldBean bean = assertDoesNotThrow(
            () -> container.create(FinalPlainFieldBean.class),
            "a plain final field must not trip the injection guard");
        assertEquals("default", bean.name);
        container.close();
    }

    @Test
    void normalFieldInjectionStillWorks() {
        // Sanity: the non-writable guard must not disturb ordinary injection.
        System.setProperty(PORT_KEY, "7070");
        System.setProperty(NAME_KEY, "field-app");
        Container container = Freeway.create();
        try {
            FieldConfiguredService service = container.create(FieldConfiguredService.class);
            assertEquals(7070, service.port());
            assertEquals("field-app", service.name());
        } finally {
            System.clearProperty(PORT_KEY);
            System.clearProperty(NAME_KEY);
        }
        container.close();
    }

    @Test
    void coercesCommonTypes() {
        Container container = Freeway.create();
        Coercer coercer = container.get(Coercer.class);

        assertEquals(42, coercer.coerce("42", Integer.class));
        assertEquals(Boolean.TRUE, coercer.coerce("true", Boolean.class));
        assertEquals('x', coercer.coerce("xyz", char.class));
    }

    @Test
    void modulesCanContributeCustomCoercions() {
        System.setProperty(ENDPOINT_KEY, "localhost:8088");

        Container container = Freeway.create(binder ->
            binder.contribute(CoerceRule.class).add(new CoerceRule<>(
                String.class,
                Endpoint.class,
                value -> {
                    String[] parts = value.split(":", 2);
                    return new Endpoint(parts[0], Integer.parseInt(parts[1]));
                }
            ))
        );

        EndpointHolder holder = container.create(EndpointHolder.class);

        assertEquals(new Endpoint("localhost", 8088), holder.endpoint());
    }

    @Test
    void intermediateTypeCanBridgeCustomCoercions() {
        System.setProperty(TIMEOUT_KEY, "2500");

        Container container = Freeway.create(binder ->
            binder.contribute(CoerceRule.class).add(new CoerceRule<>(
                Integer.class,
                Timeout.class,
                Timeout::new
            ))
        );

        TimeoutHolder holder = container.create(TimeoutHolder.class);

        assertEquals(new Timeout(2500), holder.timeout());
    }

    @Test
    void modulesCanContributeSymbolProviders() {
        Container container = Freeway.create(binder ->
            binder.contribute(SymbolProvider.class).add(name -> APP_NAME_KEY.equals(name) ? "freeway" : null)
        );

        AppNameHolder holder = container.create(AppNameHolder.class);

        assertEquals("freeway", holder.name());
    }

    @Test
    void rejectsMixedInjectAndConfiguredAnnotations() {
        Container container = Freeway.create(binder ->
            binder.bind(PaymentGateway.class).to(StripeGateway.class));
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.create(ConflictingAnnotationsService.class));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }

    @Test
    void configuredListParameterNotSwallowedByContributionMechanism() {
        // Regression: constructor parameters resolved contributed types
        // (List<Foo>) BEFORE checking @Value/@Symbol, so a
        // @Value List<String> parameter silently injected an EMPTY
        // contribution list and dropped the configuration.
        System.setProperty(LIST_KEY, "a,b");
        try {
            Container container = Freeway.create(binder ->
                binder.bind(ConfiguredListConsumer.class).to(ConfiguredListConsumer.class));

            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> container.get(ConfiguredListConsumer.class));
            assertTrue(ex.getCause().getMessage().contains("Cannot coerce"),
                "@Value List<String> must reach the coercer, not be replaced by an empty contribution list");
            container.close();
        } finally {
            System.clearProperty(LIST_KEY);
        }
    }

    @Test
    void qualifiedListInjectionPrefersBoundServiceOverContributions() {
        // Regression: resolveContributed fired for ANY List<X> constructor
        // parameter BEFORE id-based injection, so a user-bound List<String>
        // service was permanently shadowed by the contribution mechanism —
        // @Inject("mylist") List<String> injected the contributions instead of
        // the bound service. An explicit id must prefer the bound service.
        Container container = Freeway.create(
            binder -> binder.bind(List.class)
                .to(ignored -> List.of("bound-a", "bound-b"))
                .id("mylist")
                .scope(Scope.PROTOTYPE),
            binder -> binder.contribute(String.class).add("contributed-a")
        );

        QualifiedListConsumer consumer = container.create(QualifiedListConsumer.class);

        assertEquals(List.of("bound-a", "bound-b"), consumer.values(),
            "@Inject(\"mylist\") List<String> must resolve the bound service, not the contributions");
        container.close();
    }

    @Test
    void qualifiedListInjectionFallsBackToContributionsWhenUnbound() {
        // No List binding with the requested id: resolution must fall back to
        // the contribution view instead of failing.
        Container container = Freeway.create(
            binder -> binder.contribute(String.class).add("contributed-a")
        );

        FallbackListConsumer consumer = container.create(FallbackListConsumer.class);

        assertEquals(List.of("contributed-a"), consumer.values(),
            "an id without a matching binding must fall back to contributions");
        container.close();
    }

    @Test
    void unannotatedListParameterConsumesContributions() {
        // Constructor parameters consume contributions implicitly — the
        // constructor is the single mandatory injection point, so resolution
        // failure is loud at startup and there is no silent-miss risk; no
        // @Inject ceremony is required on parameters (fields still require
        // @Inject). An explicit @Inject("id") prefers a bound service.
        Container container = Freeway.create(
            binder -> binder.contribute(String.class).add("contributed-a")
        );

        PlainListConsumer consumer = container.create(PlainListConsumer.class);
        assertEquals(List.of("contributed-a"), consumer.values,
            "an unannotated List constructor parameter must receive the contributed view");
        container.close();
    }
}
