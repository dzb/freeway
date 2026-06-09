package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.Named;
import com.jujin.freeway.ioc.annotation.PostConstruct;
import com.jujin.freeway.ioc.annotation.PreDestroy;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.ioc.annotation.Extension;
import com.jujin.freeway.ioc.annotation.IntermediateType;
import com.jujin.freeway.ioc.extension.ExtensionPoint;
import com.jujin.freeway.ioc.annotation.Symbol;
import com.jujin.freeway.ioc.annotation.Value;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;
import org.slf4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreewayTest {
    private static final String PORT_KEY = "freeway.test.port";
    private static final String NAME_KEY = "freeway.test.name";
    private static final String ENDPOINT_KEY = "freeway.test.endpoint";
    private static final String TIMEOUT_KEY = "freeway.test.timeout";
    private static final String APP_NAME_KEY = "freeway.test.app.name";
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
        Container container = Freeway.create(binder -> binder.bind(Greeter.class).to(GreeterImpl.class).id("primary"));

        Greeter service = container.get(Greeter.class);
        Greeter namedService = container.get(Greeter.class, "primary");

        assertTrue(Proxy.isProxyClass(service.getClass()));
        assertSame(service, namedService);
        assertEquals("hello", service.greet());
        assertEquals(1, GreeterImpl.created.get());
    }

    @Test
    void rejectsBlankServiceId() {
        assertThrows(IllegalArgumentException.class, () ->
            Freeway.create(binder -> binder.bind(Greeter.class).to(GreeterImpl.class).id("   "))
        );

        Container container = Freeway.create(binder -> binder.bind(Greeter.class).to(GreeterImpl.class));
        assertThrows(IllegalArgumentException.class, () -> container.get(Greeter.class, "   "));
    }

    @Test
    void resolvesPrimaryBindingWhenNoIdIsProvided() {
        Container container = Freeway.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id("paypal")
        );

        CheckoutService service = container.get(CheckoutService.class);

        assertEquals("stripe", service.gatewayName());
        assertEquals("stripe", container.get(PaymentGateway.class).name());
    }

    @Test
    void resolvesUniqueAssignableBindingWhenNoExactBindingExists() {
        Container container = Freeway.create(
            binder -> binder.bind(GreeterImpl.class).to(GreeterImpl.class)
        );

        Greeter service = container.get(Greeter.class);

        assertInstanceOf(GreeterImpl.class, service);
        assertEquals("hello", service.greet());
    }

    @Test
    void exactBindingTakesPriorityOverAssignableBinding() {
        Container container = Freeway.create(
            binder -> binder.bind(Greeter.class).to(GreeterImpl.class),
            binder -> binder.bind(LoudGreeter.class).to(LoudGreeter.class).primary()
        );

        Greeter service = container.get(Greeter.class);

        assertTrue(Proxy.isProxyClass(service.getClass()));
        assertEquals("hello", service.greet());
    }

    @Test
    void resolvesExplicitNamedServiceInjection() {
        Container container = Freeway.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id("paypal")
        );

        NamedCheckoutService service = container.get(NamedCheckoutService.class);

        assertEquals("paypal", service.gatewayName());
    }

    @Test
    void resolvesExplicitInjectIdSyntaxSugar() {
        Container container = Freeway.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id("paypal")
        );

        InjectNamedCheckoutService service = container.get(InjectNamedCheckoutService.class);

        assertEquals("paypal", service.gatewayName());
    }

    @Test
    void loggerServiceAndInjectionUseOwningTypeByDefault() {
        Container container = Freeway.create();
        LoggerSource loggerSource = container.get(LoggerSource.class);

        assertEquals(LoggerFieldHolder.class.getName(), loggerSource.get(LoggerFieldHolder.class).getName());
        assertTrue(loggerSource.get(LoggerFieldHolder.class).isInfoEnabled());

        LoggerFieldHolder fieldHolder = container.get(LoggerFieldHolder.class);
        assertEquals(LoggerFieldHolder.class.getName(), fieldHolder.loggerName());

        LoggerCtorHolder ctorHolder = container.get(LoggerCtorHolder.class);
        assertEquals(LoggerCtorHolder.class.getName(), ctorHolder.loggerName());
    }

    @Test
    void loggerInjectionCanUseExplicitName() {
        Container container = Freeway.create();

        NamedLoggerHolder holder = container.get(NamedLoggerHolder.class);

        assertEquals("audit", holder.loggerName());
    }

    @Test
    void rejectsMultiplePrimaryBindingsForTheSameType() {
        Container container = Freeway.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id("paypal").primary()
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> container.get(PaymentGateway.class));

        assertTrue(ex.getMessage().contains("Multiple primary services"));
    }

    @Test
    void createsAnnotatedTypesWithSymbolExpansionAndCoercion() {
        System.setProperty(PORT_KEY, "8081");
        System.setProperty(NAME_KEY, "freeway");

        Container container = Freeway.create();
        ConfiguredService service = container.get(ConfiguredService.class);

        assertEquals(8081, service.port());
        assertEquals("freeway", service.name());
        assertEquals("8081:freeway", service.summary());
    }

    @Test
    void injectsAnnotatedFields() {
        System.setProperty(PORT_KEY, "9090");
        System.setProperty(NAME_KEY, "field-app");

        Container container = Freeway.create();
        FieldConfiguredService service = container.get(FieldConfiguredService.class);

        assertEquals(9090, service.port());
        assertEquals("field-app", service.name());
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
            binder.contribute(CoercionRules.class).add(new CoerceRule<>(
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

        Container container = Freeway.create(binder ->
            binder.contribute(CoercionRules.class).add(new CoerceRule<>(
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
        Container container = Freeway.create(binder ->
            binder.contribute(SymbolProviders.class).add(name -> APP_NAME_KEY.equals(name) ? "freeway" : null)
        );

        AppNameHolder holder = container.get(AppNameHolder.class);

        assertEquals("freeway", holder.name());
    }

    @Test
    void interfaceBindingsAreLazyUntilInvoked() {
        Container container = Freeway.create(binder -> binder.bind(Greeter.class).to(GreeterImpl.class));

        Greeter service = container.get(Greeter.class);

        assertTrue(Proxy.isProxyClass(service.getClass()));
        assertEquals(0, GreeterImpl.created.get());

        assertEquals("hello", service.greet());
        assertEquals(1, GreeterImpl.created.get());
    }

    @Test
    void adviceChainSupportsMultipleAdvisorsAndShortCircuit() {
        java.util.List<String> log = new java.util.ArrayList<>();

        Container container = Freeway.create(binder ->
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
                        // does NOT call proceed() - short circuits
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

        Container container = Freeway.create(binder ->
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
    void extensionsAggregateContributions() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeatures.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeatures.class).add(new AppFeature("web"))
        );

        AppConfig config = container.get(AppConfig.class);

        assertEquals(List.of("core", "web"), config.featureNames());
    }

    @Test
    void parameterExtensionsAggregateContributions() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeatures.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeatures.class).add(new AppFeature("web"))
        );

        ParameterAppConfig config = container.get(ParameterAppConfig.class);

        assertEquals(List.of("core", "web"), config.featureNames());
    }

    @Test
    void extensionsCanOrderContributionsById() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeatures.class)
                .add("web", new AppFeature("web"))
                .after("db", "metrics"),
            binder -> binder.contribute(AppFeatures.class)
                .add("core", new AppFeature("core")),
            binder -> binder.contribute(AppFeatures.class)
                .add("db", new AppFeature("db"))
                .after("core"),
            binder -> binder.contribute(AppFeatures.class)
                .add("metrics", new AppFeature("metrics"))
                .before("web")
        );

        AppConfig config = container.get(AppConfig.class);

        assertEquals(List.of("core", "db", "metrics", "web"), config.featureNames());
    }

    @Test
    void extensionOrderingRejectsDuplicateIds() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Freeway.create(
            binder -> binder.contribute(AppFeatures.class).add("same", new AppFeature("first")),
            binder -> binder.contribute(AppFeatures.class).add("same", new AppFeature("second"))
        ));

        assertTrue(ex.getMessage().contains("Duplicate contribution id same"));
    }

    @Test
    void extensionOrderingRejectsCycles() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeatures.class)
                .add("first", new AppFeature("first"))
                .after("second"),
            binder -> binder.contribute(AppFeatures.class)
                .add("second", new AppFeature("second"))
                .after("first")
        );

        Throwable ex = assertThrows(Throwable.class, () -> container.get(AppConfig.class));

        Throwable root = ex;
        while (root.getCause() != null) root = root.getCause();
        assertTrue(
            root.getMessage().contains("Contribution order cycle detected"),
            "Expected cycle detection message, got: " + root.getMessage()
        );
    }

    @Test
    void fieldExtensionsOverrideTypeDefault() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeatures.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeatures.class).add(new AppFeature("web")),
            binder -> binder.contribute(AppFlags.class).add(new AppFlags.Entry("debug", new AppFlag("debug", true))),
            binder -> binder.contribute(AppFlags.class).add(new AppFlags.Entry("timing", new AppFlag("timing", false)))
        );

        MixedExtensionCatalog catalog = container.get(MixedExtensionCatalog.class);

        assertEquals(List.of("core", "web"), catalog.featureNames());
        assertEquals(Map.of("debug", new AppFlag("debug", true), "timing", new AppFlag("timing", false)), catalog.flags());
    }

    @Test
    void extensionEntriesPreserveKeys() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeatures.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFlags.class).add(new AppFlags.Entry("debug", new AppFlag("debug", true))),
            binder -> binder.contribute(AppFlags.class).add(new AppFlags.Entry("   ", new AppFlag("blank-key", true)))
        );

        MixedExtensionCatalog catalog = container.get(MixedExtensionCatalog.class);

        assertEquals(Map.of("debug", new AppFlag("debug", true), "   ", new AppFlag("blank-key", true)), catalog.flags());
    }

    @Test
    void extensionEntriesSupportNonStringKeys() {
        Container container = Freeway.create(
            binder -> binder.contribute(EnumAppFlags.class).add(new EnumAppFlags.Entry(FlagKey.DEBUG, new AppFlag("debug", true))),
            binder -> binder.contribute(EnumAppFlags.class).add(new EnumAppFlags.Entry(FlagKey.TIMING, new AppFlag("timing", false)))
        );

        EnumKeyExtensionCatalog catalog = container.get(EnumKeyExtensionCatalog.class);

        assertEquals(
            Map.of(
                FlagKey.DEBUG, new AppFlag("debug", true),
                FlagKey.TIMING, new AppFlag("timing", false)
            ),
            catalog.flags()
        );
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

    public static final class LoudGreeter implements Greeter {
        @Override
        public String greet() {
            return "HELLO";
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

    public static final class LoggerFieldHolder {
        @Inject
        private Logger logger;

        String loggerName() {
            return logger.getName();
        }
    }

    public static final class LoggerCtorHolder {
        private final Logger logger;

        public LoggerCtorHolder(Logger logger) {
            this.logger = logger;
        }

        String loggerName() {
            return logger.getName();
        }
    }

    public static final class NamedLoggerHolder {
        @Named("audit")
        @Inject
        private Logger logger;

        String loggerName() {
            return logger.getName();
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

    @Extension(AppFeature.class)
    // ---- Test extension points ----

    public interface AppFeatures extends ExtensionPoint<AppFeature> {}

    public interface AppFlags extends ExtensionPoint<AppFlags.Entry> {
        record Entry(String key, AppFlag flag) {}
    }

    public interface EnumAppFlags extends ExtensionPoint<EnumAppFlags.Entry> {
        record Entry(FlagKey key, AppFlag flag) {}
    }

    public record AppFeature(String name) {}

    public record AppFlag(String name, boolean enabled) {}

    public enum FlagKey { DEBUG, TIMING }

    // ---- Test consumers ----

    public static final class AppConfig {
        private final List<AppFeature> features;

        public AppConfig(AppFeatures features) {
            this.features = List.copyOf(features.all());
        }

        List<String> featureNames() {
            return features.stream().map(AppFeature::name).toList();
        }
    }

    public static final class ParameterAppConfig {
        private final List<AppFeature> features;

        public ParameterAppConfig(AppFeatures features) {
            this.features = List.copyOf(features.all());
        }

        List<String> featureNames() {
            return features.stream().map(AppFeature::name).toList();
        }
    }

    public static final class MixedExtensionCatalog {
        private final List<AppFeature> features;
        private final Map<String, AppFlag> flags;

        public MixedExtensionCatalog(AppFeatures features, AppFlags flags) {
            this.features = List.copyOf(features.all());
            Map<String, AppFlag> map = new java.util.LinkedHashMap<>();
            for (AppFlags.Entry entry : flags.all()) map.put(entry.key(), entry.flag());
            this.flags = Map.copyOf(map);
        }

        List<String> featureNames() {
            return features.stream().map(AppFeature::name).toList();
        }

        Map<String, AppFlag> flags() {
            return flags;
        }
    }

    public static final class EnumKeyExtensionCatalog {
        private final Map<FlagKey, AppFlag> flags;

        public EnumKeyExtensionCatalog(EnumAppFlags flags) {
            Map<FlagKey, AppFlag> map = new java.util.LinkedHashMap<>();
            for (EnumAppFlags.Entry entry : flags.all()) map.put(entry.key(), entry.flag());
            this.flags = Map.copyOf(map);
        }

        Map<FlagKey, AppFlag> flags() {
            return flags;
        }
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
        Container container = Freeway.create(binder ->
            binder.bind(Greeter.class).to(GreeterImpl.class).scope(Scope.PROTOTYPE)
        );

        Greeter one = container.get(Greeter.class);
        Greeter two = container.get(Greeter.class);

        // Prototype: each get() returns a new instance
        assertNotEquals(System.identityHashCode(one), System.identityHashCode(two));
        assertEquals(2, GreeterImpl.created.get());
    }

    @Test
    void instanceBindingReturnsTheBoundInstance() {
        GreeterImpl bound = new GreeterImpl();
        Container container = Freeway.create(binder ->
            binder.bind(GreeterImpl.class).to(bound)
        );

        assertSame(bound, container.get(GreeterImpl.class));
    }

    @Test
    void instanceBindingRejectsNonSingletonScopeBeforeTo() {
        GreeterImpl bound = new GreeterImpl();
        assertThrows(IllegalStateException.class, () ->
            Freeway.create(binder ->
                binder.bind(GreeterImpl.class).scope(Scope.PROTOTYPE).to(bound)
            )
        );
    }

    @Test
    void instanceBindingRejectsNonSingletonScopeAfterTo() {
        GreeterImpl bound = new GreeterImpl();
        assertThrows(IllegalStateException.class, () ->
            Freeway.create(binder ->
                binder.bind(GreeterImpl.class).to(bound).scope(Scope.PROTOTYPE)
            )
        );
    }

    @Test
    void scopedBindingReusesWithinScopeAndIsDestroyedOnClose() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        assertThrows(IllegalStateException.class, () -> container.get(ScopedCounter.class));

        AtomicReference<ScopedCounter> firstHolder = new AtomicReference<>();
        scoping.within(() -> {
            ScopedCounter first = container.get(ScopedCounter.class);
            firstHolder.set(first);
            ScopedCounter second = container.get(ScopedCounter.class);

            assertSame(first, second);
            assertEquals(1, ScopedCounter.created.get());
            assertEquals(0, ScopedCounter.destroyed.get());
            return null;
        });
        ScopedCounter first = firstHolder.get();

        assertEquals(1, ScopedCounter.destroyed.get());

        scoping.within(() -> {
            ScopedCounter third = container.get(ScopedCounter.class);
            assertNotSame(first, third);
            assertEquals(2, ScopedCounter.created.get());
            return null;
        });

        assertEquals(2, ScopedCounter.destroyed.get());
    }

    @Test
    void scopedBindingWithinUsesScopedValue() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        assertThrows(IllegalStateException.class, () -> container.get(ScopedCounter.class));

        int result = scoping.within(() -> {
            ScopedCounter first = container.get(ScopedCounter.class);
            ScopedCounter second = container.get(ScopedCounter.class);
            assertSame(first, second);
            assertEquals(1, ScopedCounter.created.get());
            assertEquals(0, ScopedCounter.destroyed.get());
            return first.id();
        });

        assertEquals(1, ScopedCounter.destroyed.get());

        scoping.within(() -> {
            ScopedCounter third = container.get(ScopedCounter.class);
            assertEquals(2, ScopedCounter.created.get());
            return null;
        });

        assertEquals(2, ScopedCounter.destroyed.get());
    }

    @Test
    void scopedValueNestingShadowsOuter() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        scoping.within(() -> {
            ScopedCounter outer = container.get(ScopedCounter.class);
            assertEquals(1, ScopedCounter.created.get());
            assertEquals(0, ScopedCounter.destroyed.get());

            // Nested within() creates a new scope, shadowing the outer
            scoping.within(() -> {
                ScopedCounter inner = container.get(ScopedCounter.class);
                assertNotSame(outer, inner);
                assertEquals(2, ScopedCounter.created.get());
                assertEquals(0, ScopedCounter.destroyed.get()); // outer not yet destroyed
                return null;
            });

            assertEquals(1, ScopedCounter.destroyed.get()); // inner destroyed

            // Outer session still alive
            ScopedCounter stillOuter = container.get(ScopedCounter.class);
            assertSame(outer, stillOuter);
            return null;
        });

        assertEquals(2, ScopedCounter.destroyed.get()); // outer destroyed
    }

    @Test
    void withinPropagatesReturnValue() {
        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        ScopedApi api = scoping.within(() -> container.get(ScopedCounter.class));
        assertNotNull(api);
    }

    @Test
    void withinClosesScopeOnException() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        assertThrows(RuntimeException.class, () ->
            scoping.within(() -> {
                container.get(ScopedCounter.class);
                assertEquals(1, ScopedCounter.created.get());
                throw new RuntimeException("boom");
            })
        );

        // Scope must be closed even though the work threw
        assertEquals(1, ScopedCounter.destroyed.get());
    }

    @Test
    void containerCloseAfterWithinReleasesScopes() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        scoping.within(() -> {
            container.get(ScopedCounter.class);
            return null;
        });

        assertEquals(1, ScopedCounter.destroyed.get());

        // Container close should be safe after scopes have been cleaned up
        container.close();
    }

    @Test
    void withinRefusesAfterContainerClose() {
        Container container = Freeway.create(binder ->
            binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD)
        );
        Scoping scoping = container.get(Scoping.class);

        container.close();

        assertThrows(IllegalStateException.class, () ->
            scoping.within(() -> null)
        );
    }

    @Test
    void singletonCanInjectScopedInterfaceThroughProxy() {
        ScopedCounter.created.set(0);
        ScopedCounter.destroyed.set(0);

        Container container = Freeway.create(
            binder -> {
                binder.bind(ScopedApi.class).to(ScopedCounter.class).scope(Scope.THREAD);
                binder.bind(ScopedSingletonService.class).to(ScopedSingletonService.class);
            }
        );
        Scoping scoping = container.get(Scoping.class);

        ScopedSingletonService service = container.get(ScopedSingletonService.class);
        assertTrue(service.proxied());

        int firstId = scoping.within(() -> {
            int id = service.currentId();
            assertEquals(id, service.currentId()); // same scope, same id
            return id;
        });

        int secondId = scoping.within(service::currentId);
        assertNotEquals(firstId, secondId);

        assertEquals(2, ScopedCounter.created.get());
        assertEquals(2, ScopedCounter.destroyed.get());
    }

    @Test
    void singletonRejectsDirectInjectionOfThreadScopedConcrete() {
        Container container = Freeway.create(
            binder -> {
                binder.bind(ScopedCounter.class).to(ScopedCounter.class).scope(Scope.THREAD);
                binder.bind(ScopedSingleton.class).to(ScopedSingleton.class);
            }
        );

        RuntimeException ex = assertThrows(RuntimeException.class, () -> container.get(ScopedSingleton.class));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void rejectsDuplicateBinding() {
        assertThrows(IllegalStateException.class, () -> Freeway.create(binder -> {
            binder.bind(Greeter.class).to(GreeterImpl.class);
            binder.bind(Greeter.class).to(GreeterImpl.class);
        }));
    }

    @Test
    void rejectsUnknownSymbol() {
        Container container = Freeway.create();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(UnknownSymbolService.class));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }

    @Test
    void rejectsUnclosedSymbolExpression() {
        Container container = Freeway.create();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(UnclosedSymbolService.class));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }

    @Test
    void rejectsMixedInjectAndConfiguredAnnotations() {
        Container container = Freeway.create(binder ->
            binder.bind(PaymentGateway.class).to(StripeGateway.class));
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(ConflictingAnnotationsService.class));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }

    @Test
    void rejectsAdvisorOnNonInterfaceType() {
        Container container = Freeway.create(binder ->
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

    // ========== @PostConstruct / @PreDestroy tests ==========

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

    // --- Test helpers for lifecycle annotations ---

    public static final class PostConstructBean {
        boolean initialized;

        @PostConstruct
        void init() {
            initialized = true;
        }
    }

    public static final class PreDestroyBean {
        volatile boolean destroyed;

        @PreDestroy
        void cleanup() {
            destroyed = true;
        }
    }

    public static final class LifecycleOrderBean implements AutoCloseable {
        final java.util.List<String> events = new java.util.ArrayList<>();

        @PreDestroy
        void preDestroy() {
            events.add("preDestroy");
        }

        @Override
        public void close() {
            events.add("close");
        }

        String order() {
            return String.join(",", events);
        }
    }

    public static final class PrivateLifecycleBean {
        boolean initialized;
        volatile boolean destroyed;

        @PostConstruct
        private void init() {
            initialized = true;
        }

        @PreDestroy
        private void cleanup() {
            destroyed = true;
        }
    }

    public interface ScopedApi {
        int id();
    }

    public static final class ScopedCounter implements ScopedApi {
        static final AtomicInteger created = new AtomicInteger();
        static final AtomicInteger destroyed = new AtomicInteger();
        private final int id = created.incrementAndGet();

        @Override
        public int id() {
            return id;
        }

        @PreDestroy
        void destroy() {
            destroyed.incrementAndGet();
        }
    }

    public static final class ScopedSingletonService {
        @Inject
        private ScopedApi api;

        boolean proxied() {
            return Proxy.isProxyClass(api.getClass());
        }

        int currentId() {
            return api.id();
        }
    }

    public static final class ScopedSingleton {
        @Inject
        private ScopedCounter counter;
    }

    public static class ParentPostConstructBean {
        boolean parentInit;

        @PostConstruct
        void parentInit() {
            parentInit = true;
        }
    }

    public static final class SubPostConstructBean extends ParentPostConstructBean {
    }

    public static final class DoublePostConstructBean {
        @PostConstruct
        void init1() {
        }

        @PostConstruct
        void init2() {
        }
    }

    public static final class InvalidPostConstructBean {
        @PostConstruct
        void init(String arg) {
        }
    }
}
