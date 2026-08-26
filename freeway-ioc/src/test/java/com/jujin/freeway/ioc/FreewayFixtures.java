package com.jujin.freeway.ioc;
import com.jujin.freeway.ioc.annotation.Inject;
import java.util.LinkedHashMap;

import static com.jujin.freeway.ioc.FreewayTestSupport.*;

import com.jujin.freeway.ioc.annotation.*;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
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

/** Package-visible fixtures shared by the split container test files. */

interface ContributorMarker {}

class EndpointContributor implements ContributorMarker {
    final Endpoint endpoint;

    @Inject
    EndpointContributor(@Value("${endpoint}") Endpoint endpoint) {
        this.endpoint = endpoint;
    }
}

class PlainLoggerHolder {
    Logger logger; // no @Inject — should stay null
}

interface SpecialConsumer {
    String value();
}

class SpecialConsumerImpl implements SpecialConsumer {
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

class SpecialSymbolProvider implements SymbolProvider {
    static final AtomicInteger instances = new AtomicInteger();

    SpecialSymbolProvider() {
        instances.incrementAndGet();
    }

    @Override
    public String lookup(String name) {
        return IocKeys.SPECIAL.equals(name) ? "special-value" : null;
    }
}

final class IocKeys {
    static final String SPECIAL = "ioc.test.special";
}

class MultiCtorBean {
    boolean noArgUsed;

    MultiCtorBean() {
        noArgUsed = true;
    }

    MultiCtorBean(String name, int count) {
    }
}

class OverrideSymbolSource implements SymbolSource {
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

class ValueConsumer {
    @Value("${ioc.override.key}")
    String value;
}

class SymbolHolder {
    final SymbolSource source;

    @Inject
    SymbolHolder(SymbolSource source) {
        this.source = source;
    }
}

@Retention(RetentionPolicy.RUNTIME)
@interface Unrecognized {
}

interface SimpleGreeter {
    String greet();
}

class SimpleGreeterImpl implements SimpleGreeter {
    @Override
    public String greet() {
        return "hi";
    }
}

class GreeterConsumer {
    @Inject
    @Unrecognized
    SimpleGreeter greeter;
}

interface PrototypeGreeter {
    String greet();
}

class PrototypeGreeterImpl implements PrototypeGreeter {
    static final AtomicInteger created = new AtomicInteger();

    PrototypeGreeterImpl() {
        created.incrementAndGet();
    }

    @Override public String greet() { return "hello"; }
}

interface StatefulCounter {
    void bump();

    int value();
}

class StatefulCounterImpl implements StatefulCounter {
    static final AtomicInteger created = new AtomicInteger();
    static volatile boolean failNextConstruction;
    private int value;

    StatefulCounterImpl() {
        if (failNextConstruction) {
            failNextConstruction = false;
            throw new IllegalStateException("simulated construction failure");
        }
        created.incrementAndGet();
    }

    @Override
    public void bump() {
        value++;
    }

    @Override
    public int value() {
        return value;
    }
}

interface SingletonService {
    String name();
}

class SingletonServiceImpl implements SingletonService {
    @Override public String name() { return "singleton"; }
}

class ThreadScopedDep {}

class ConsumerWithThreadDep {
    final ThreadScopedDep dep;

    @Inject
    ConsumerWithThreadDep(ThreadScopedDep dep) { this.dep = dep; }
}

interface BaseService { String name(); }

interface ExtendedService extends BaseService {}

class ExtendedServiceImpl implements ExtendedService {
    final ThreadScopedDep dep;

    @Inject
    ExtendedServiceImpl(ThreadScopedDep dep) { this.dep = dep; }

    @Override public String name() { return "extended"; }
}

interface Greeter {
    String greet();
}

final class IdChangeService {
    static final AtomicInteger destroyed = new AtomicInteger();

    @PreDestroy
    void destroy() {
        destroyed.incrementAndGet();
    }
}

final class GreeterImpl implements Greeter {
    static final AtomicInteger created = new AtomicInteger();

    public GreeterImpl() {
        created.incrementAndGet();
    }

    @Override
    public String greet() {
        return "hello";
    }
}

final class LoudGreeter implements Greeter {
    @Override
    public String greet() {
        return "HELLO";
    }
}

interface PaymentGateway {
    String name();
}

final class StripeGateway implements PaymentGateway {
    @Override
    public String name() {
        return "stripe";
    }
}

final class PaypalGateway implements PaymentGateway {
    @Override
    public String name() {
        return "paypal";
    }
}

final class CheckoutService {
    private final PaymentGateway gateway;

    @Inject
    public CheckoutService(@Inject PaymentGateway gateway) {
        this.gateway = gateway;
    }

    String gatewayName() {
        return gateway.name();
    }
}

final class NamedCheckoutService {
    @Inject("paypal")
    private PaymentGateway gateway;

    String gatewayName() {
        return gateway.name();
    }
}

final class InjectNamedCheckoutService {
    @Inject("paypal")
    private PaymentGateway gateway;

    String gatewayName() {
        return gateway.name();
    }
}

final class LoggerFieldHolder {
    @Inject
    private Logger logger;

    String loggerName() {
        return logger.getName();
    }
}

final class LoggerCtorHolder {
    private final Logger logger;

    @Inject
    public LoggerCtorHolder(Logger logger) {
        this.logger = logger;
    }

    String loggerName() {
        return logger.getName();
    }
}

final class NamedLoggerHolder {
    @Inject("audit")
    private Logger logger;

    String loggerName() {
        return logger.getName();
    }
}

final class ConfiguredService {
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

final class FieldConfiguredService {
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

final class FinalInjectFieldBean {
    @Inject
    final String name = "default";
}

final class FinalValueFieldBean {
    @Value("${" + PORT_KEY + "}")
    final String port = "default";
}

final class FinalPlainFieldBean {
    final String name = "default";
}

record AppFeature(String name) {}

record AppFlag(String name, boolean enabled) {}

enum FlagKey { DEBUG, TIMING }

final class MixedExtensionCatalog {
    private final List<AppFeature> features;
    private final Map<String, AppFlag> flags;

    public MixedExtensionCatalog(
        List<AppFeature> features,
        Map<String, AppFlagEntry> flagEntries
    ) {
        this.features = List.copyOf(features);
        Map<String, AppFlag> map = new LinkedHashMap<>();
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

final class EnumKeyExtensionCatalog {
    private final Map<String, AppFlag> flags;

    public EnumKeyExtensionCatalog(Map<String, EnumAppFlagEntry> flagEntries) {
        Map<String, AppFlag> map = new LinkedHashMap<>();
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

record Endpoint(String host, int port) {
}

record Timeout(int millis) {
}

final class EndpointHolder {
    private final Endpoint endpoint;

    public EndpointHolder(@Value("${" + ENDPOINT_KEY + "}") Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    Endpoint endpoint() {
        return endpoint;
    }
}

final class TimeoutHolder {
    @Value("${" + TIMEOUT_KEY + "}")
    @IntermediateType(Integer.class)
    private Timeout timeout;

    Timeout timeout() {
        return timeout;
    }
}

final class AppNameHolder {
    private final String name;

    public AppNameHolder(@Value("${" + APP_NAME_KEY + "}") String name) {
        this.name = name;
    }

    String name() {
        return name;
    }
}

final class UnknownSymbolService {
    @Symbol("nonexistent.key")
    private String value;
}

final class UncoercibleListService {
    @Value("${" + APP_NAME_KEY + "}")
    private List<String> values;
}

final class UnclosedSymbolService {
    @Value("${unclosed")
    private String value;
}

final class ConflictingAnnotationsService {
    @Inject
    @Value("${some.path}")
    private PaymentGateway gateway;
}

final class ConfiguredListConsumer {
    @SuppressWarnings("unused")
    ConfiguredListConsumer(@Value("${" + LIST_KEY + "}") List<String> values) {}
}

final class QualifiedListConsumer {
    private final List<String> values;

    QualifiedListConsumer(@Inject("mylist") List<String> values) {
        this.values = values;
    }

    List<String> values() {
        return values;
    }
}

final class FallbackListConsumer {
    private final List<String> values;

    FallbackListConsumer(@Inject("missing-id") List<String> values) {
        this.values = values;
    }

    List<String> values() {
        return values;
    }
}

final class PlainListConsumer {
    final List<String> values;

    PlainListConsumer(List<String> values) {
        this.values = values;
    }
}

final class PostConstructBean {
    boolean initialized;

    @PostConstruct
    void init() {
        initialized = true;
    }
}

final class PreDestroyBean {
    volatile boolean destroyed;

    @PreDestroy
    void cleanup() {
        destroyed = true;
    }
}

final class LifecycleOrderBean implements AutoCloseable {
    final List<String> events = new ArrayList<>();

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

final class PrivateLifecycleBean {
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

interface ScopedApi {
    int id();
}

final class ScopedCounter implements ScopedApi {
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

final class ScopedSingletonService {
    @Inject
    private ScopedApi api;

    boolean proxied() {
        return Proxy.isProxyClass(api.getClass());
    }

    int currentId() {
        return api.id();
    }
}

final class ScopedSingleton {
    @Inject
    private ScopedCounter counter;
}

@NotThreadSafe
final class UnsafeShared {
}

final class SingletonHoldingUnsafe {
    @Inject
    private UnsafeShared shared;
}

final class PrototypeHoldingUnsafe {
    @Inject
    private UnsafeShared shared;
}

@ThreadSafe
@NotThreadSafe
final class ConflictingContract {
}

@ThreadSafe
final class ThreadSafeGreeterImpl implements Greeter {
    @Override
    public String greet() {
        return "safe";
    }
}

class ParentPostConstructBean {
    boolean parentInit;

    @PostConstruct
    void parentInit() {
        parentInit = true;
    }
}

final class SubPostConstructBean extends ParentPostConstructBean {
}

final class DoublePostConstructBean {
    @PostConstruct
    void init1() {
    }

    @PostConstruct
    void init2() {
    }
}

final class InvalidPostConstructBean {
    @PostConstruct
    void init(String arg) {
    }
}

final class ListFeatureCatalog {
    private final List<AppFeature> features;

    public ListFeatureCatalog(List<AppFeature> features) {
        this.features = List.copyOf(features);
    }

    List<String> featureNames() {
        return features.stream().map(AppFeature::name).toList();
    }
}

final class FieldListFeatureCatalog {
    @Inject
    private List<AppFeature> features;

    List<String> featureNames() {
        return features.stream().map(AppFeature::name).toList();
    }
}

final class MapFeatureCatalog {
    private final Map<String, AppFeature> features;

    public MapFeatureCatalog(Map<String, AppFeature> features) {
        this.features = Map.copyOf(features);
    }

    Map<String, AppFeature> features() {
        return features;
    }
}

final class FieldMapFeatureCatalog {
    @Inject
    private Map<String, AppFeature> features;

    Map<String, AppFeature> features() {
        return features;
    }
}

final class ExtensionConstructorInjection {
    public ExtensionConstructorInjection(Extension<AppFeature> features) {}
}

final class ExtensionFieldInjection {
    @SuppressWarnings("unused")
    @Inject
    private Extension<AppFeature> features;
}

final class ContainerFieldInjection {
    @SuppressWarnings("unused")
    @Inject
    private Container container;
}

final class ContainerConstructorInjection {
    public ContainerConstructorInjection(Container container) {}
}

interface Labeled { String label(); }

final class CoreBean implements Labeled {
    @Override public String label() { return "core"; }
}

final class WebBean implements Labeled {
    @Override public String label() { return "web"; }
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
class FastCache implements Cache {
    @Override
    public String name() {
        return "fast";
    }
}

@Slow
class SlowCache implements Cache {
    @Override
    public String name() {
        return "slow";
    }
}

class CacheConsumer {
    @Inject
    @Fast
    Cache cache;

    String cacheName() {
        return cache.name();
    }
}

@com.jujin.freeway.ioc.annotation.Marker(Builtin.class)
class MarkerTestModule implements ModuleEx {
    @Override
    public void bind(Binder binder) {
        binder.bind(Cache.class).to(FastCache.class);
    }
}

interface NestedDep {
}

class NestedDepImpl implements NestedDep {
}

interface NestedDepConsumer {
}

class NestedDepConsumerImpl implements NestedDepConsumer {
    final NestedDep dep;

    @Inject
    NestedDepConsumerImpl(NestedDep dep) {
        this.dep = dep;
    }
}

interface LaterDep {
}

class LaterDepImpl implements LaterDep {
}

interface LaterDepConsumer {
}

class LaterDepConsumerImpl implements LaterDepConsumer {
    final LaterDep dep;

    @Inject
    LaterDepConsumerImpl(LaterDep dep) {
        this.dep = dep;
    }
}

class ConcurrentServiceImpl {
}

class CycleA {
    @Inject
    CycleA(CycleB b) {
    }
}

class CycleB {
    @Inject
    CycleB(CycleA a) {
    }
}
