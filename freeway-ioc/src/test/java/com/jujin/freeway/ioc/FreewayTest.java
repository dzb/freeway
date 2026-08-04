package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.scoped.ScopedCache;
import com.jujin.freeway.ioc.annotation.*;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        CheckoutService service = container.create(CheckoutService.class);

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

        NamedCheckoutService service = container.create(NamedCheckoutService.class);

        assertEquals("paypal", service.gatewayName());
    }

    @Test
    void resolvesExplicitInjectIdSyntaxSugar() {
        Container container = Freeway.create(
            binder -> binder.bind(PaymentGateway.class).to(StripeGateway.class).id("stripe").primary(),
            binder -> binder.bind(PaymentGateway.class).to(PaypalGateway.class).id("paypal")
        );

        InjectNamedCheckoutService service = container.create(InjectNamedCheckoutService.class);

        assertEquals("paypal", service.gatewayName());
    }

    @Test
    void loggerServiceAndInjectionUseOwningTypeByDefault() {
        Container container = Freeway.create();
        LoggerSource loggerSource = container.get(LoggerSource.class);

        assertEquals(LoggerFieldHolder.class.getName(), loggerSource.get(LoggerFieldHolder.class).getName());
        assertTrue(loggerSource.get(LoggerFieldHolder.class).isInfoEnabled());

        LoggerFieldHolder fieldHolder = container.create(LoggerFieldHolder.class);
        assertEquals(LoggerFieldHolder.class.getName(), fieldHolder.loggerName());

        LoggerCtorHolder ctorHolder = container.create(LoggerCtorHolder.class);
        assertEquals(LoggerCtorHolder.class.getName(), ctorHolder.loggerName());
    }

    @Test
    void loggerInjectionCanUseExplicitName() {
        Container container = Freeway.create();

        NamedLoggerHolder holder = container.create(NamedLoggerHolder.class);

        assertEquals("audit", holder.loggerName());
    }

    interface Marker {}

    static class EndpointContributor implements Marker {
        final Endpoint endpoint;

        @Inject
        EndpointContributor(@Value("${endpoint}") Endpoint endpoint) {
            this.endpoint = endpoint;
        }
    }

    static class PlainLoggerHolder {
        Logger logger; // no @Inject — should stay null
    }

    @Test
    void loggerFieldNotInjectedWithoutAnnotation() {
        Container container = Freeway.create();
        PlainLoggerHolder holder = container.create(PlainLoggerHolder.class);
        assertNull((Object) holder.logger,
                "Logger field without @Inject should not be injected");
    }

    @Test
    void contributedClassSeesCoerceRuleFromSameModule() {
        System.setProperty("endpoint", "192.168.1.1:443");
        Container container = Freeway.create(binder -> {
            binder.contribute(CoerceRule.class).add(new CoerceRule<>(
                String.class, Endpoint.class,
                v -> { String[] p = v.split(":", 2); return new Endpoint(p[0], Integer.parseInt(p[1])); }));
            binder.contribute(Marker.class).add(EndpointContributor.class);
        });

        var marker = container.extension(Marker.class).all().stream()
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
    void lambdaModulesAreInstalledByIdentity() {
        List<String> log = new ArrayList<>();
        Container container = Freeway.create(
            binder -> { log.add("a"); },
            binder -> { log.add("b"); }
        );
        assertEquals(List.of("a", "b"), log,
                "lambda modules should dedup by identity, not class");
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
            binder -> binder.contribute(Marker.class).add(EndpointContributor.class)
        );

        var marker = container.extension(Marker.class).all().stream()
                .filter(m -> m instanceof EndpointContributor)
                .map(m -> (EndpointContributor) m)
                .findFirst().orElseThrow();

        assertEquals(new Endpoint("10.0.0.1", 8080), marker.endpoint);
        System.clearProperty("endpoint");
    }

    // ── regression: deferred add(Class) wiring for built-in consumers ──

    public interface SpecialConsumer {
        String value();
    }

    public static class SpecialConsumerImpl implements SpecialConsumer {
        final String value;

        @Inject
        SpecialConsumerImpl(@Value("${" + IocKeys.SPECIAL + "}") String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }

    public static class SpecialSymbolProvider implements SymbolProvider {
        @Override
        public String lookup(String name) {
            return IocKeys.SPECIAL.equals(name) ? "special-value" : null;
        }
    }

    private static final class IocKeys {
        static final String SPECIAL = "ioc.test.special";
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

    // ── regression: multi-constructor beans ───────────────────────

    public static class MultiCtorBean {
        boolean noArgUsed;

        MultiCtorBean() {
            noArgUsed = true;
        }

        MultiCtorBean(String name, int count) {
        }
    }

    @Test
    void createPrefersNoArgConstructorOverLargerConstructor() {
        Container container = Freeway.create(binder -> {});
        MultiCtorBean bean = container.create(MultiCtorBean.class);
        assertTrue(bean.noArgUsed,
            "no-arg constructor must be preferred over a larger convenience constructor");
        container.close();
    }

    // ── regression: scope-lifecycle isolation and primary override ──

    @Test
    void standaloneScopedCacheValueIsNotClosedByContainer() {
        Container container = Freeway.create(binder -> {});
        List<AutoCloseable> closed = new ArrayList<>();

        ScopedCache.within(() -> {
            ScopedCache.get("k", () -> (AutoCloseable) () -> closed.add(null));
            return null;
        });

        container.close();
        assertTrue(closed.isEmpty(),
            "standalone ScopedCache values must not be closed by the container hook");
    }

    public static class OverrideSymbolSource implements SymbolSource {
        @Override
        public String resolve(String name) {
            return "override:" + name;
        }

        @Override
        public String expand(String input) {
            return input.startsWith("${")
                ? resolve(input.substring(2, input.length() - 1))
                : input;
        }
    }

    public static class ValueConsumer {
        @Value("${ioc.override.key}")
        String value;
    }

    public static class SymbolHolder {
        final SymbolSource source;

        @Inject
        SymbolHolder(SymbolSource source) {
            this.source = source;
        }
    }

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

    // ── regression: unrecognized annotation at injection point ────

    @Retention(RetentionPolicy.RUNTIME)
    @interface Unrecognized {
    }

    public interface SimpleGreeter {
        String greet();
    }

    public static class SimpleGreeterImpl implements SimpleGreeter {
        @Override
        public String greet() {
            return "hi";
        }
    }

    public static class GreeterConsumer {
        @Inject
        @Unrecognized
        SimpleGreeter greeter;
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


    interface PrototypeGreeter {
        String greet();
    }

    static class PrototypeGreeterImpl implements PrototypeGreeter {
        @Override public String greet() { return "hello"; }
    }

    interface SingletonService {
        String name();
    }

    static class SingletonServiceImpl implements SingletonService {
        @Override public String name() { return "singleton"; }
    }

    static class ThreadScopedDep {}

    static class ConsumerWithThreadDep {
        final ThreadScopedDep dep;

        @Inject
        ConsumerWithThreadDep(ThreadScopedDep dep) { this.dep = dep; }
    }

    interface BaseService { String name(); }
    interface ExtendedService extends BaseService {}

    static class ExtendedServiceImpl implements ExtendedService {
        final ThreadScopedDep dep;

        @Inject
        ExtendedServiceImpl(ThreadScopedDep dep) { this.dep = dep; }

        @Override public String name() { return "extended"; }
    }

    @Test
    void singletonThroughIndirectInterfaceRejectsThreadScopedDependency() {
        // Bind ExtendedServiceImpl under BaseService — an indirect interface.
        // ExtendedServiceImpl extends ExtendedService extends BaseService.
        // findOwnerBinding must walk ExtendedService → BaseService to find the binding.
        Container container = Freeway.create(binder -> {
            binder.bind(BaseService.class).to(ExtendedServiceImpl.class).scope(Scope.SINGLETON);
            binder.bind(ThreadScopedDep.class).to(ThreadScopedDep.class).scope(Scope.THREAD);
        });

        // trigger proxy realization via ScopedCache (thread scope)
        assertThrows(RuntimeException.class, () ->
            ScopedCache.within(() -> {
                BaseService svc = container.get(BaseService.class);
                svc.name(); // triggers proxy → realize → scope check
            }));
    }

    @Test
    void singletonThroughInterfaceRejectsThreadScopedDependency() {
        Container container = Freeway.create(binder -> {
            binder.bind(SingletonService.class).to(SingletonServiceImpl.class).scope(Scope.SINGLETON);
            binder.bind(ThreadScopedDep.class).to(ThreadScopedDep.class).scope(Scope.THREAD);
        });

        assertThrows(RuntimeException.class, () ->
            container.create(ConsumerWithThreadDep.class),
                "Singleton injected with thread-scoped dep should fail");
    }

    @Test
    void prototypeBindingHonorsAdvice() {
        List<String> calls = new ArrayList<>();
        Container container = Freeway.create(binder ->
            binder.bind(PrototypeGreeter.class)
                  .to(PrototypeGreeterImpl.class)
                  .scope(Scope.PROTOTYPE)
                  .advise(advisor -> advisor.wrap(
                      inv -> true,
                      inv -> {
                          calls.add("advised");
                          return inv.proceed();
                      })));

        PrototypeGreeter g1 = container.get(PrototypeGreeter.class);
        PrototypeGreeter g2 = container.get(PrototypeGreeter.class);
        g1.greet();
        g2.greet();

        assertEquals(List.of("advised", "advised"), calls,
                "advice should fire on every PROTOTYPE resolution");
        assertNotSame(g1, g2, "PROTOTYPE should create new instance each time");
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
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web"))
        );

        ListFeatureCatalog config = container.create(ListFeatureCatalog.class);

        assertEquals(List.of("core", "web"), config.featureNames());
    }

    @Test
    void parameterExtensionsAggregateContributions() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web"))
        );

        ListFeatureCatalog config = container.create(ListFeatureCatalog.class);

        assertEquals(List.of("core", "web"), config.featureNames());
    }

    @Test
    void extensionsCanOrderContributionsById() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class)
                .add("web", new AppFeature("web"))
                .after("db", "metrics"),
            binder -> binder.contribute(AppFeature.class)
                .add("core", new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class)
                .add("db", new AppFeature("db"))
                .after("core"),
            binder -> binder.contribute(AppFeature.class)
                .add("metrics", new AppFeature("metrics"))
                .before("web")
        );

        ListFeatureCatalog config = container.create(ListFeatureCatalog.class);

        assertEquals(List.of("core", "db", "metrics", "web"), config.featureNames());
    }

    @Test
    void extensionOrderingRejectsDuplicateIds() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> Freeway.create(
            binder -> binder.contribute(AppFeature.class).add("same", new AppFeature("first")),
            binder -> binder.contribute(AppFeature.class).add("same", new AppFeature("second"))
        ));

        assertTrue(ex.getMessage().contains("Duplicate contribution id same"));
    }

    @Test
    void extensionOrderingIgnoresMissingIds() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class)
                .add("first", new AppFeature("first"))
                .after("missing")
                .after("second"),
            binder -> binder.contribute(AppFeature.class)
                .add("second", new AppFeature("second"))
        );

        ListFeatureCatalog config = container.create(ListFeatureCatalog.class);
        assertEquals(List.of("second", "first"), config.featureNames(),
            "a missing id must be ignored, not fail ordering");
    }

    @Test
    void extensionOrderingWarnsOnMissingIds() {
        var records = new ArrayList<java.util.logging.LogRecord>();
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(
            "com.jujin.freeway.ioc.extension.Extension");
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(handler);
        try {
            Container container = Freeway.create(
                binder -> binder.contribute(AppFeature.class)
                    .add("first", new AppFeature("first"))
                    .after("missing")
            );
            container.extension(AppFeature.class).all();

            assertTrue(records.stream().anyMatch(r ->
                    r.getLevel() == java.util.logging.Level.WARNING
                        && r.getMessage() != null
                        && r.getMessage().contains("missing")),
                "missing ordering ids must produce a warning");
        } finally {
            jul.removeHandler(handler);
        }
    }

    @Test
    void extensionOrderingRejectsCycles() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class)
                .add("first", new AppFeature("first"))
                .after("second"),
            binder -> binder.contribute(AppFeature.class)
                .add("second", new AppFeature("second"))
                .after("first")
        );

        Throwable ex = assertThrows(Throwable.class, () -> container.create(ListFeatureCatalog.class));

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
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web")),
            binder -> binder.contribute(AppFlagEntry.class).add("debug", new AppFlagEntry("debug", new AppFlag("debug", true))),
            binder -> binder.contribute(AppFlagEntry.class).add("timing", new AppFlagEntry("timing", new AppFlag("timing", false)))
        );

        MixedExtensionCatalog catalog = container.create(MixedExtensionCatalog.class);

        assertEquals(List.of("core", "web"), catalog.featureNames());
        assertEquals(Map.of("debug", new AppFlag("debug", true), "timing", new AppFlag("timing", false)), catalog.flags());
    }

    @Test
    void extensionEntriesPreserveKeys() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFlagEntry.class).add("debug", new AppFlagEntry("debug", new AppFlag("debug", true))),
            binder -> binder.contribute(AppFlagEntry.class).add("audit-special", new AppFlagEntry("audit-special", new AppFlag("audit-special", true)))
        );

        MixedExtensionCatalog catalog = container.create(MixedExtensionCatalog.class);

        assertEquals(Map.of("debug", new AppFlag("debug", true), "audit-special", new AppFlag("audit-special", true)), catalog.flags());
    }

    @Test
    void extensionEntriesSupportNonStringKeys() {
        Container container = Freeway.create(
            binder -> binder.contribute(EnumAppFlagEntry.class).add("debug", new EnumAppFlagEntry(FlagKey.DEBUG, new AppFlag("debug", true))),
            binder -> binder.contribute(EnumAppFlagEntry.class).add("timing", new EnumAppFlagEntry(FlagKey.TIMING, new AppFlag("timing", false)))
        );

        EnumKeyExtensionCatalog catalog = container.create(EnumKeyExtensionCatalog.class);

        assertEquals(Map.of("debug", new AppFlag("debug", true), "timing", new AppFlag("timing", false)), catalog.flags());
    }

    @Test
    void listInjectionFromExtensionParam() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web"))
        );
        ListFeatureCatalog catalog = container.create(ListFeatureCatalog.class);
        assertEquals(List.of("core", "web"), catalog.featureNames());
    }

    @Test
    void listInjectionFromExtensionField() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("web"))
        );
        FieldListFeatureCatalog catalog = container.create(FieldListFeatureCatalog.class);
        assertEquals(List.of("core", "web"), catalog.featureNames());
    }

    @Test
    void mapInjectionFromExtensionParam() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add("core", new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add("web", new AppFeature("web"))
        );
        MapFeatureCatalog catalog = container.create(MapFeatureCatalog.class);
        assertEquals(Map.of("core", new AppFeature("core"), "web", new AppFeature("web")), catalog.features());
    }

    @Test
    void mapInjectionFromExtensionField() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add("core", new AppFeature("core")),
            binder -> binder.contribute(AppFeature.class).add("web", new AppFeature("web"))
        );
        FieldMapFeatureCatalog catalog = container.create(FieldMapFeatureCatalog.class);
        assertEquals(Map.of("core", new AppFeature("core"), "web", new AppFeature("web")), catalog.features());
    }

    @Test
    void mapInjectionExcludesUnnamedContributions() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add(new AppFeature("unnamed")),
            binder -> binder.contribute(AppFeature.class).add("named", new AppFeature("named"))
        );
        MapFeatureCatalog catalog = container.create(MapFeatureCatalog.class);
        assertEquals(Map.of("named", new AppFeature("named")), catalog.features());
    }

    @Test
    void extensionInjectionRejectedConstructor() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add("core", new AppFeature("core"))
        );
        Throwable ex = assertThrows(Throwable.class, () ->
            container.create(ExtensionConstructorInjection.class));
        assertTrue(ex.getCause().getMessage().contains("Extension<V> is not injectable"));
    }

    @Test
    void extensionInjectionRejectedField() {
        Container container = Freeway.create(
            binder -> binder.contribute(AppFeature.class).add("core", new AppFeature("core"))
        );
        Throwable ex = assertThrows(Throwable.class, () ->
            container.create(ExtensionFieldInjection.class));
        assertTrue(ex.getCause().getMessage().contains("Extension<V> is not injectable"));
    }

    @Test
    void containerNotInjectableByField() {
        Container container = Freeway.create(binder -> {});
        Throwable ex = assertThrows(Throwable.class, () ->
            container.create(ContainerFieldInjection.class));
        assertTrue(ex.getCause().getMessage().contains("No service registered for type"),
            "Got: " + ex.getCause().getMessage());
    }

    @Test
    void containerNotInjectableByConstructor() {
        Container container = Freeway.create(binder -> {});
        Throwable ex = assertThrows(Throwable.class, () ->
            container.create(ContainerConstructorInjection.class));
        assertTrue(ex.getCause().getMessage().contains("No service registered for type"),
            "Got: " + ex.getCause().getMessage());
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
        @Inject("paypal")
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

        @com.jujin.freeway.ioc.annotation.Inject
        public LoggerCtorHolder(Logger logger) {
            this.logger = logger;
        }

        String loggerName() {
            return logger.getName();
        }
    }

    public static final class NamedLoggerHolder {
        @Inject("audit")
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

    public record AppFeature(String name) {}

    public record AppFlag(String name, boolean enabled) {}

    public enum FlagKey { DEBUG, TIMING }

    // ---- Test consumers ----

    public static final class MixedExtensionCatalog {
        private final List<AppFeature> features;
        private final Map<String, AppFlag> flags;

        public MixedExtensionCatalog(List<AppFeature> features, Map<String, AppFlagEntry> flagEntries) {
            this.features = List.copyOf(features);
            Map<String, AppFlag> map = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, AppFlagEntry> e : flagEntries.entrySet())
                map.put(e.getKey(), e.getValue().flag());
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
        private final Map<String, AppFlag> flags;

        public EnumKeyExtensionCatalog(Map<String, EnumAppFlagEntry> flagEntries) {
            Map<String, AppFlag> map = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, EnumAppFlagEntry> e : flagEntries.entrySet())
                map.put(e.getKey(), e.getValue().flag());
            this.flags = Map.copyOf(map);
        }

        Map<String, AppFlag> flags() {
            return flags;
        }
    }

    record AppFlagEntry(String key, AppFlag flag) {}
    record EnumAppFlagEntry(FlagKey key, AppFlag flag) {}

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
        Container container = Freeway.create(binder -> {
            binder.bind(Greeter.class).to(GreeterImpl.class);
            binder.bind(Greeter.class).to(GreeterImpl.class);
        });
        // Multiple bindings of the same type without .primary() is caught at resolve time
        assertThrows(IllegalArgumentException.class, () -> container.get(Greeter.class));
    }

    @Test
    void rejectsUnknownSymbol() {
        Container container = Freeway.create();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.create(UnknownSymbolService.class));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }

    @Test
    void expandEscapesDollarBrace() {
        Container container = Freeway.create();
        SymbolSource symbols = container.get(SymbolSource.class);

        assertEquals("a ${b} c", symbols.expand("a \\${b} c"),
            "\\${ must emit a literal ${");
        assertEquals("${not-a-symbol}", symbols.expand("\\${not-a-symbol}"),
            "an escaped expression must not be resolved");

        // An even backslash run leaves the expression active — the backslashes
        // are literal and ${...} still resolves.
        System.setProperty(NAME_KEY, "resolved");
        assertEquals("a \\\\resolved", symbols.expand("a \\\\${" + NAME_KEY + "}"),
            "an even backslash run stays literal and the expression still resolves");
        System.clearProperty(NAME_KEY);
        container.close();
    }

    @Test
    void expandEscapesDollarBraceInsideResolvedValue() {
        System.setProperty(APP_NAME_KEY, "price is \\${total}");
        Container container = Freeway.create();
        SymbolSource symbols = container.get(SymbolSource.class);

        assertEquals("price is ${total}", symbols.expand("${" + APP_NAME_KEY + "}"),
            "escaped ${ in a resolved value must not be expanded again");
        System.clearProperty(APP_NAME_KEY);
        container.close();
    }

    @Test
    void configuredValueCoercionErrorIncludesContext() {
        System.setProperty(APP_NAME_KEY, "not-a-list");
        Container container = Freeway.create();
        try {
            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> container.create(UncoercibleListService.class));
            Throwable cause = ex.getCause();
            assertTrue(cause != null && cause.getMessage() != null
                    && cause.getMessage().contains("Cannot coerce configured value"),
                "coercion failure should include config context, got: "
                    + (cause == null ? null : cause.getMessage()));
        } finally {
            System.clearProperty(APP_NAME_KEY);
        }
        container.close();
    }

    @Test
    void rejectsUnclosedSymbolExpression() {
        Container container = Freeway.create();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.create(UnclosedSymbolService.class));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
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

    public static final class UncoercibleListService {
        @Value("${" + APP_NAME_KEY + "}")
        private java.util.List<String> values;
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

    // ---- List<Foo> contribution consumers ----

    public static final class ListFeatureCatalog {
        private final List<AppFeature> features;

        public ListFeatureCatalog(List<AppFeature> features) {
            this.features = List.copyOf(features);
        }

        List<String> featureNames() {
            return features.stream().map(AppFeature::name).toList();
        }
    }

    public static final class FieldListFeatureCatalog {
        @Inject
        private List<AppFeature> features;

        List<String> featureNames() {
            return features.stream().map(AppFeature::name).toList();
        }
    }

    // ──── Map<String, Foo> contribution consumers ────

    public static final class MapFeatureCatalog {
        private final Map<String, AppFeature> features;

        public MapFeatureCatalog(Map<String, AppFeature> features) {
            this.features = Map.copyOf(features);
        }

        Map<String, AppFeature> features() {
            return features;
        }
    }

    public static final class FieldMapFeatureCatalog {
        @Inject
        private Map<String, AppFeature> features;

        Map<String, AppFeature> features() {
            return features;
        }
    }

    // ──── rejected injection patterns ────

    public static final class ExtensionConstructorInjection {
        public ExtensionConstructorInjection(Extension<AppFeature> features) {}
    }

    public static final class ExtensionFieldInjection {
        @SuppressWarnings("unused")
        @Inject
        private Extension<AppFeature> features;
    }

    public static final class ContainerFieldInjection {
        @SuppressWarnings("unused")
        @Inject
        private Container container;
    }

    public static final class ContainerConstructorInjection {
        public ContainerConstructorInjection(Container container) {}
    }

    // ──── add(Class) ────

    interface Labeled { String label(); }

    public static final class CoreBean implements Labeled {
        @Override public String label() { return "core"; }
    }

    public static final class WebBean implements Labeled {
        @Override public String label() { return "web"; }
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

    // ──── Marker annotation tests ────

    @Test
    void markerAnnotationResolvesCorrectService() {
        Container container = Freeway.create(binder -> {
            binder.bind(Cache.class).to(FastCache.class).marker(Fast.class);
            binder.bind(Cache.class).to(SlowCache.class).marker(Slow.class);
        });

        // By-marker resolution via container.get(type, markers...)
        Cache cache = container.get(Cache.class, Fast.class);
        assertEquals("fast", cache.name());

        // Injection point with @Fast marker
        CacheConsumer consumer = container.create(CacheConsumer.class);
        assertEquals("fast", consumer.cacheName());
    }

    @Test
    void primaryAlsoAddsMarker() {
        Container container = Freeway.create(binder -> {
            binder.bind(Cache.class).to(FastCache.class).primary();
            binder.bind(Cache.class).to(SlowCache.class);
        });

        // .primary() should add @Primary as a marker, so get(type, Primary.class) works
        Cache cache = container.get(Cache.class, Primary.class);
        assertEquals("fast", cache.name());
    }

    @Test
    void markerResolutionRejectsAmbiguousMatch() {
        Container container = Freeway.create(binder -> {
            binder.bind(Cache.class).to(FastCache.class).marker(Fast.class);
            binder.bind(Cache.class).to(SlowCache.class).marker(Fast.class); // same marker!
        });

        assertThrows(IllegalArgumentException.class, () ->
                container.get(Cache.class, Fast.class));
    }

    @Test
    void builtinMarkerPropagatesToCoreServices() {
        Container container = Freeway.create();
        // Core services registered via registerBuiltin() should carry @Builtin
        var symbols = container.get(com.jujin.freeway.ioc.symbol.SymbolSource.class, Builtin.class);
        assertNotNull(symbols);
    }

    @Test
    void moduleLevelMarkerPropagatesToBindings() {
        Container container = Freeway.create(new MarkerTestModule());

        // The Cache binding should inherit @Builtin from the module
        Cache cache = container.get(Cache.class, Builtin.class);
        assertEquals("fast", cache.name());
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD})
    @interface Fast {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD})
    @interface Slow {
    }

    interface Cache {
        String name();
    }

    @Fast
    static class FastCache implements Cache {
        @Override
        public String name() {
            return "fast";
        }
    }

    @Slow
    static class SlowCache implements Cache {
        @Override
        public String name() {
            return "slow";
        }
    }

    static class CacheConsumer {
        @Inject
        @Fast
        Cache cache;

        String cacheName() {
            return cache.name();
        }
    }

    // Module-level marker propagation
    @com.jujin.freeway.ioc.annotation.Marker(Builtin.class)
    static class MarkerTestModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(Cache.class).to(FastCache.class);
        }
    }
}
