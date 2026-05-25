package com.jujin.freeway2.ioc;

import com.jujin.freeway2.ioc.ServiceId;
import com.jujin.freeway2.ioc.annotation.Inject;
import com.jujin.freeway2.ioc.annotation.Named;
import com.jujin.freeway2.commons.scalar.Coercer;
import com.jujin.freeway2.commons.scalar.CoercionRule;
import com.jujin.freeway2.ioc.annotation.ExtensionPoint;
import com.jujin.freeway2.ioc.annotation.IntermediateType;
import com.jujin.freeway2.ioc.symbol.SymbolProvider;
import com.jujin.freeway2.ioc.annotation.Symbol;
import com.jujin.freeway2.ioc.annotation.Value;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Freeway2Test {
    private static final String PORT_KEY = "freeway2.test.port";
    private static final String NAME_KEY = "freeway2.test.name";
    private static final String ENDPOINT_KEY = "freeway2.test.endpoint";
    private static final String TIMEOUT_KEY = "freeway2.test.timeout";
    private static final String APP_NAME_KEY = "freeway2.test.app.name";
    private String previousPort;
    private String previousName;
    private String previousEndpoint;
    private String previousTimeout;
    private String previousAppName;

    @BeforeEach
    void captureSystemProperties() {
        previousPort = System.getProperty(PORT_KEY);
        previousName = System.getProperty(NAME_KEY);
        previousEndpoint = System.getProperty(ENDPOINT_KEY);
        previousTimeout = System.getProperty(TIMEOUT_KEY);
        previousAppName = System.getProperty(APP_NAME_KEY);
        GreeterImpl.created.set(0);
    }

    @AfterEach
    void restoreSystemProperties() {
        restoreProperty(PORT_KEY, previousPort);
        restoreProperty(NAME_KEY, previousName);
        restoreProperty(ENDPOINT_KEY, previousEndpoint);
        restoreProperty(TIMEOUT_KEY, previousTimeout);
        restoreProperty(APP_NAME_KEY, previousAppName);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void bindsServicesAndResolvesById() {
        Container container = Freeway2.create(binder -> binder.bind(Greeter.class).to(GreeterImpl.class).id(ServiceId.of("primary")));

        Greeter service = container.get(Greeter.class);
        Greeter namedService = container.get(Greeter.class, ServiceId.of("primary"));

        assertTrue(Proxy.isProxyClass(service.getClass()));
        assertSame(service, namedService);
        assertEquals("hello", service.greet());
        assertEquals(1, GreeterImpl.created.get());
    }

    @Test
    void resolvesPrimaryBindingWhenNoServiceIdIsProvided() {
        Container container = Freeway2.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id(ServiceId.of("stripe")).primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id(ServiceId.of("paypal"))
        );

        CheckoutService service = container.get(CheckoutService.class);

        assertEquals("stripe", service.gatewayName());
        assertEquals("stripe", container.get(PaymentGateway.class).name());
    }

    @Test
    void resolvesExplicitNamedServiceInjection() {
        Container container = Freeway2.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id(ServiceId.of("stripe")).primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id(ServiceId.of("paypal"))
        );

        NamedCheckoutService service = container.get(NamedCheckoutService.class);

        assertEquals("paypal", service.gatewayName());
    }

    @Test
    void resolvesExplicitInjectServiceIdSyntaxSugar() {
        Container container = Freeway2.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id(ServiceId.of("stripe")).primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id(ServiceId.of("paypal"))
        );

        InjectNamedCheckoutService service = container.get(InjectNamedCheckoutService.class);

        assertEquals("paypal", service.gatewayName());
    }

    @Test
    void rejectsMultiplePrimaryBindingsForTheSameType() {
        Container container = Freeway2.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id(ServiceId.of("stripe")).primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id(ServiceId.of("paypal")).primary()
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> container.get(PaymentGateway.class));

        assertTrue(ex.getMessage().contains("Multiple primary services"));
    }

    @Test
    void createsAnnotatedTypesWithSymbolExpansionAndCoercion() {
        System.setProperty(PORT_KEY, "8081");
        System.setProperty(NAME_KEY, "freeway");

        Container container = Freeway2.create();
        ConfiguredService service = container.get(ConfiguredService.class);

        assertEquals(8081, service.port());
        assertEquals("freeway", service.name());
        assertEquals("8081:freeway", service.summary());
    }

    @Test
    void injectsAnnotatedFields() {
        System.setProperty(PORT_KEY, "9090");
        System.setProperty(NAME_KEY, "field-app");

        Container container = Freeway2.create();
        FieldConfiguredService service = container.get(FieldConfiguredService.class);

        assertEquals(9090, service.port());
        assertEquals("field-app", service.name());
    }

    @Test
    void coercesCommonTypes() {
        Container container = Freeway2.create();
        Coercer coercer = container.get(Coercer.class);

        assertEquals(42, coercer.coerce("42", Integer.class));
        assertEquals(Boolean.TRUE, coercer.coerce("true", Boolean.class));
        assertEquals('x', coercer.coerce("xyz", char.class));
    }

    @Test
    void modulesCanContributeCustomCoercions() {
        System.setProperty(ENDPOINT_KEY, "localhost:8088");

        Container container = Freeway2.create(binder ->
            binder.contribute((Class) CoercionRule.class).add(new CoercionRule<>(
                String.class,
                Endpoint.class,
                value -> {
                    String[] parts = value.split(":", 2);
                    return new Endpoint(parts[0], Integer.parseInt(parts[1]));
                }
            ))
        );

        EndpointHolder holder = container.get(EndpointHolder.class);

        assertEquals(new Endpoint("localhost", 8088), holder.endpoint());
    }

    @Test
    void intermediateTypeCanBridgeCustomCoercions() {
        System.setProperty(TIMEOUT_KEY, "2500");

        Container container = Freeway2.create(binder ->
            binder.contribute((Class) CoercionRule.class).add(new CoercionRule<>(
                Integer.class,
                Timeout.class,
                Timeout::new
            ))
        );

        TimeoutHolder holder = container.get(TimeoutHolder.class);

        assertEquals(new Timeout(2500), holder.timeout());
    }

    @Test
    void modulesCanContributeSymbolProviders() {
        Container container = Freeway2.create(binder ->
            binder.contribute(SymbolProvider.class).add(name -> APP_NAME_KEY.equals(name) ? "freeway2" : null)
        );

        AppNameHolder holder = container.get(AppNameHolder.class);

        assertEquals("freeway2", holder.name());
    }

    @Test
    void interfaceBindingsAreLazyUntilInvoked() {
        Container container = Freeway2.create(binder -> binder.bind(Greeter.class).to(GreeterImpl.class));

        Greeter service = container.get(Greeter.class);

        assertTrue(Proxy.isProxyClass(service.getClass()));
        assertEquals(0, GreeterImpl.created.get());

        assertEquals("hello", service.greet());
        assertEquals(1, GreeterImpl.created.get());
    }

    @Test
    void adviceChainSupportsMultipleAdvisorsAndShortCircuit() {
        java.util.List<String> log = new java.util.ArrayList<>();

        Container container = Freeway2.create(binder ->
            binder.bind(Greeter.class)
                .to(GreeterImpl.class)
                .advise(advisor -> advisor.wrap(
                    invocation -> invocation.method().getName().equals("greet"),
                    invocation -> {
                        log.add("before-first");
                        try {
                            return invocation.proceed();
                        } finally {
                            log.add("after-first");
                        }
                    }
                ))
                .advise(advisor -> advisor.wrap(
                    invocation -> invocation.method().getName().equals("greet"),
                    invocation -> {
                        log.add("short-circuit");
                        return "short-circuited";
                        // does NOT call proceed() — short circuits
                    }
                ))
        );

        Greeter service = container.get(Greeter.class);
        assertEquals("short-circuited", service.greet());
        // after-first fires because first advisor's proceed() returned after second short-circuited
        assertEquals(java.util.List.of("before-first", "short-circuit", "after-first"), log);
        assertEquals(1, GreeterImpl.created.get());
    }

    @Test
    void advisorsCanWrapServiceMethods() {
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();

        Container container = Freeway2.create(binder ->
            binder.bind(Greeter.class)
                .to(GreeterImpl.class)
                .advise(advisor -> advisor.wrap(
                    invocation -> invocation.method().getName().equals("greet"),
                    invocation -> {
                        before.incrementAndGet();
                        try {
                            return invocation.proceed();
                        } finally {
                            after.incrementAndGet();
                        }
                    }
                ))
        );

        Greeter service = container.get(Greeter.class);

        assertTrue(Proxy.isProxyClass(service.getClass()));
        assertEquals("hello", service.greet());
        assertEquals(1, before.get());
        assertEquals(1, after.get());
        assertEquals(1, GreeterImpl.created.get());
    }

    @Test
    void extensionPointsAggregateContributions() {
        Container container = Freeway2.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web"))
        );

        AppConfig config = container.get(AppConfig.class);

        assertEquals(List.of("core", "web"), config.featureNames());
    }

    @Test
    void mappedExtensionPointsAggregateKeyedContributions() {
        Container container = Freeway2.create(
            binder -> binder.contributeMapped(AppFlag.class).put("debug", new AppFlag("debug", true)),
            binder -> binder.contributeMapped(AppFlag.class).put("timing", new AppFlag("timing", false))
        );

        AppFlagCatalog catalog = container.get(AppFlagCatalog.class);

        assertEquals(Map.of("debug", new AppFlag("debug", true), "timing", new AppFlag("timing", false)), catalog.flags());
    }

    interface Greeter {
        String greet();
    }

    public static final class GreeterImpl implements Greeter {
        static final AtomicInteger created = new AtomicInteger();

        public GreeterImpl() {
            created.incrementAndGet();
        }

        @Override
        public String greet() {
            return "hello";
        }
    }

    interface PaymentGateway {
        String name();
    }

    public static final class StripeGateway implements PaymentGateway {
        @Override
        public String name() {
            return "stripe";
        }
    }

    public static final class PaypalGateway implements PaymentGateway {
        @Override
        public String name() {
            return "paypal";
        }
    }

    public static final class CheckoutService {
        private final PaymentGateway gateway;

        @Inject
        public CheckoutService(@Inject PaymentGateway gateway) {
            this.gateway = gateway;
        }

        String gatewayName() {
            return gateway.name();
        }
    }

    public static final class NamedCheckoutService {
        @Named("paypal")
        private PaymentGateway gateway;

        String gatewayName() {
            return gateway.name();
        }
    }

    public static final class InjectNamedCheckoutService {
        @Inject("paypal")
        private PaymentGateway gateway;

        String gatewayName() {
            return gateway.name();
        }
    }

    public static final class ConfiguredService {
        private final int port;
        private final String name;

        public ConfiguredService(@Symbol(PORT_KEY) int port, @Value("${" + NAME_KEY + "}") String name) {
            this.port = port;
            this.name = name;
        }

        int port() {
            return port;
        }

        String name() {
            return name;
        }

        String summary() {
            return port + ":" + name;
        }
    }

    public static final class FieldConfiguredService {
        @Value("${" + PORT_KEY + "}")
        private int port;

        @Symbol(NAME_KEY)
        private String name;

        int port() {
            return port;
        }

        String name() {
            return name;
        }
    }

    public static final class AppConfig {
        private final List<AppFeature> features;

        public AppConfig(@ExtensionPoint(value = AppFeature.class) Collection<AppFeature> features) {
            this.features = List.copyOf(features);
        }

        List<String> featureNames() {
            return features.stream().map(AppFeature::name).toList();
        }
    }

    public record AppFeature(String name) {
    }

    public static final class AppFlagCatalog {
        private final Map<String, AppFlag> flags;

        public AppFlagCatalog(@ExtensionPoint(AppFlag.class) Map<String, AppFlag> flags) {
            this.flags = Map.copyOf(flags);
        }

        Map<String, AppFlag> flags() {
            return flags;
        }
    }

    public record AppFlag(String name, boolean enabled) {
    }

    public record Endpoint(String host, int port) {
    }

    public record Timeout(int millis) {
    }

    public static final class EndpointHolder {
        private final Endpoint endpoint;

        public EndpointHolder(@Value("${" + ENDPOINT_KEY + "}") Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        Endpoint endpoint() {
            return endpoint;
        }
    }

    public static final class TimeoutHolder {
        @Value("${" + TIMEOUT_KEY + "}")
        @IntermediateType(Integer.class)
        private Timeout timeout;

        Timeout timeout() {
            return timeout;
        }
    }

    public static final class AppNameHolder {
        private final String name;

        public AppNameHolder(@Value("${" + APP_NAME_KEY + "}") String name) {
            this.name = name;
        }

        String name() {
            return name;
        }
    }

    @Test
    void prototypeBindingCreatesNewInstanceEachTime() {
        Container container = Freeway2.create(binder ->
            binder.bind(Greeter.class).to(GreeterImpl.class).scope(Scope.PROTOTYPE)
        );

        Greeter one = container.get(Greeter.class);
        Greeter two = container.get(Greeter.class);

        // Prototype: each get() returns a new instance
        assertNotEquals(System.identityHashCode(one), System.identityHashCode(two));
        assertEquals(2, GreeterImpl.created.get());
    }

    @Test
    void rejectsDuplicateBinding() {
        assertThrows(IllegalStateException.class, () -> Freeway2.create(binder -> {
            binder.bind(Greeter.class).to(GreeterImpl.class);
            binder.bind(Greeter.class).to(GreeterImpl.class);
        }));
    }

    @Test
    void rejectsUnknownSymbol() {
        Container container = Freeway2.create();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(UnknownSymbolService.class));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }

    @Test
    void rejectsUnclosedSymbolExpression() {
        Container container = Freeway2.create();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(UnclosedSymbolService.class));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }

    @Test
    void rejectsMixedInjectAndConfiguredAnnotations() {
        Container container = Freeway2.create(binder ->
            binder.bind(PaymentGateway.class).to(StripeGateway.class));
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(ConflictingAnnotationsService.class));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }

    @Test
    void rejectsAdvisorOnNonInterfaceType() {
        Container container = Freeway2.create(binder ->
            binder.bind(GreeterImpl.class)
                .to(GreeterImpl.class)
                .advise(advisor -> advisor.wrap(
                    invocation -> true,
                    invocation -> invocation.proceed()
                ))
        );
        assertThrows(IllegalArgumentException.class,
            () -> container.get(GreeterImpl.class));
    }

    public static final class UnknownSymbolService {
        @Symbol("nonexistent.key")
        private String value;
    }

    public static final class UnclosedSymbolService {
        @Value("${unclosed")
        private String value;
    }

    public static final class ConflictingAnnotationsService {
        @Inject
        @Value("${some.path}")
        private PaymentGateway gateway;
    }
}
