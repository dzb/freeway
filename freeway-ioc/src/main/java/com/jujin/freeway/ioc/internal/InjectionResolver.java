package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanParameter;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.util.Types;
import com.jujin.freeway.ioc.MissingBindingException;
import com.jujin.freeway.ioc.LoggerSource;
import com.jujin.freeway.ioc.Scope;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.IntermediateType;
import com.jujin.freeway.ioc.AmbiguousBindingException;
import com.jujin.freeway.ioc.annotation.NotThreadSafe;
import com.jujin.freeway.ioc.annotation.Symbol;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class InjectionResolver {
    private static final Logger LOG = LoggerFactory.getLogger(InjectionResolver.class);

    private final ContainerImpl container;

    InjectionResolver(ContainerImpl container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    Object[] resolveArguments(Class<?> ownerType, List<BeanParameter> parameters) {
        Object[] args = new Object[parameters.size()];
        for (int i = 0; i < parameters.size(); i++) {
            args[i] = resolveParameter(ownerType, parameters.get(i));
        }
        return args;
    }

    void injectFields(Object instance) {
        Class<?> ownerType = instance.getClass();
        BeanPlan plan = BeanIntrospector.plan(ownerType);
        for (BeanProperty property : plan.properties()) {
            if (!property.isWritable()) {
                // A non-writable property (final field without setter, or a
                // getter-only computed value) carrying an injection annotation
                // would otherwise be silently skipped and keep its default
                // value until the error surfaces at runtime. Fail fast with a
                // clear directive instead. Non-writable properties WITHOUT any
                // injection annotation stay untouched (existing behavior).
                AnnotationLookup lookup = of(property);
                if (hasInjectionAnnotation(lookup) || hasSymbolAnnotation(lookup)) {
                    if (property.isFieldBacked()) {
                        throw new IllegalStateException(
                            "Cannot inject into final field " + property.name()
                                + " on " + ownerType.getName()
                                + " — use constructor injection instead"
                        );
                    }
                    throw new IllegalStateException(
                        "Cannot inject into non-writable property " + property.name()
                            + " on " + ownerType.getName()
                            + " — it is a getter-only derived value; annotate a "
                            + "writable field or use constructor injection instead"
                    );
                }
                continue;
            }
            Object value = resolveValue(
                ownerType,
                of(property),
                property.type(),
                Types.rawClass(property.type()),
                false
            );
            if (value == null) {
                continue;
            }
            try {
                property.write(instance, value);
            } catch (RuntimeException ex) {
                throw new RuntimeException(
                    "Unable to inject field " + property.name() + " on " + instance.getClass().getName(),
                    ex
                );
            }
        }
    }

    private Object resolveParameter(Class<?> ownerType, BeanParameter parameter) {
        Type parameterType = parameter.type();
        Class<?> rawType = Types.rawClass(parameterType);
        return resolveValue(ownerType, of(parameter), parameterType, rawType, true);
    }

    /**
     * Resolves {@code List<Foo>}, {@code Map<String, Foo>}, and
     * {@code Extension<Foo>} from the contribution mechanism. Constructor
     * parameters consume contributions implicitly (the constructor is the
     * single mandatory injection point — failure is loud at startup, so
     * there is no silent-miss risk); fields require an explicit
     * {@code @Inject}. An unannotated {@code List}/{@code Map} constructor
     * parameter resolves to the contributed view, like any other
     * contributed-typed parameter.
     *
     * <p>An explicit {@code @Inject("id")} on a {@code List}/{@code Map}
     * injection point prefers a bound service with that id; only when no such
     * binding exists does resolution fall back to contributions. This lets a
     * user bind their own {@code List<Foo>}/{@code Map<String, Foo>} service
     * and inject it by id instead of always receiving the contributed view.
     *
     * <p>{@code Extension<Foo>} is intentionally rejected — inject
     * {@code List<Foo>} or {@code Map<String, Foo>} instead.
     */
    private Object resolveContributed(
        Type memberType,
        Class<?> targetType,
        AnnotationLookup lookup,
        boolean parameterMode
    ) {
        if (!(memberType instanceof ParameterizedType pt)) {
            return null;
        }
        // An @Value/@Symbol on a List/Map injection point means "coerce the
        // configured value", not "consume contributions" — otherwise
        // @Symbol List<String> would silently inject an empty contribution
        // list and drop the configuration.
        if (hasSymbolAnnotation(lookup)) {
            return null;
        }
        // Constructor parameters consume contributions implicitly — the
        // constructor is the single mandatory injection point, so a
        // resolution failure is loud at startup and there is no
        // silent-miss risk. Fields require an explicit @Inject (they are
        // writable and can be forgotten). An explicit @Inject("id") on
        // either prefers a bound service of that type/id.
        if (!parameterMode && !hasInjectionAnnotation(lookup)) {
            return null;
        }
        Type[] typeArgs = pt.getActualTypeArguments();
        if (targetType == Extension.class) {
            throw new IllegalArgumentException(
                "Extension<V> is not injectable by design. "
                + "Use @Inject List<V> to consume all contributions, "
                + "or @Inject Map<String, V> to consume named contributions by id."
            );
        }
        if (targetType == List.class) {
            if (typeArgs.length < 1 || !(typeArgs[0] instanceof Class<?> entryType)) {
                return null;
            }
            Object bound = resolveById(targetType, lookup);
            if (bound != null) {
                return bound;
            }
            return container.extension(entryType).all();
        }
        if (targetType == Map.class) {
            if (typeArgs.length < 2 || typeArgs[0] != String.class
                || !(typeArgs[1] instanceof Class<?> entryType)) {
                return null;
            }
            Object bound = resolveById(targetType, lookup);
            if (bound != null) {
                return bound;
            }
            return container.extension(entryType).asMap();
        }
        return null;
    }

    /**
     * Resolves a service explicitly qualified by {@code @Inject("id")} on a
     * {@code List}/{@code Map} injection point: prefers a bound service of
     * that exact type/id, falling back to the contributed view when no such
     * binding exists.
     */
    private Object resolveById(Class<?> targetType, AnnotationLookup lookup) {
        String id = resolveId(lookup);
        if (id == null) {
            return null;
        }
        try {
            return container.get(targetType, id);
        } catch (MissingBindingException e) {
            return null; // no bound service with this id — use contributions
        }
    }

    private static AnnotationLookup of(BeanProperty property) {
        return new AnnotationLookup(property.annotations());
    }

    private static AnnotationLookup of(BeanParameter parameter) {
        return new AnnotationLookup(parameter.annotations());
    }

    private static AnnotationLookup of(AnnotatedElement element) {
        return new AnnotationLookup(element.getAnnotations());
    }

    /**
     * The annotations at one injection point. All three sources
     * ({@link BeanProperty}, {@link BeanParameter}, {@link AnnotatedElement})
     * expose them as a plain array, so lookup is a single scan.
     */
    private record AnnotationLookup(Annotation[] all) {

        <A extends Annotation> Optional<A> annotation(Class<A> type) {
            return find(all, type);
        }

        Annotation[] annotations() {
            return all;
        }
    }

    private static <A extends Annotation> Optional<A> find(
        Annotation[] annotations, Class<A> type
    ) {
        for (Annotation annotation : annotations) {
            if (type.isInstance(annotation)) {
                return Optional.of(type.cast(annotation));
            }
        }
        return Optional.empty();
    }

    private Logger resolveLogger(Class<?> ownerType, AnnotationLookup lookup) {
        String id = resolveId(lookup);
        // Through the container, like SymbolSource and Coercer: a module that
        // binds its own primary LoggerSource must be honored here too.
        LoggerSource loggers = container.get(LoggerSource.class);
        return id == null
            ? loggers.get(Objects.requireNonNull(ownerType, "ownerType"))
            : loggers.get(id);
    }

    private static boolean hasInjectionAnnotation(AnnotationLookup lookup) {
        return lookup.annotation(Inject.class).isPresent();
    }

    private static boolean hasSymbolAnnotation(AnnotationLookup lookup) {
        return lookup.annotation(Symbol.class).isPresent();
    }

    private String resolveId(AnnotationLookup lookup) {
        return normalizedId(lookup.annotation(Inject.class).orElse(null));
    }

    private Object resolveSymbolValue(AnnotationLookup lookup, Class<?> targetType) {
        // Resolve SymbolSource/Coercer through the container so a primary
        // override is honored at every injection site (constructor, field,
        // @Symbol) instead of hard-coding the built-in instance.
        var symbol = lookup.annotation(Symbol.class);
        if (symbol.isPresent()) {
            String val = symbol.get().value();
            // ${...} template → expand (supports defaults and variable composition)
            // Plain key      → resolve (missing = fail-fast)
            String raw = val.contains("${")
                ? container.get(SymbolSource.class).expand(val)
                : container.get(SymbolSource.class).resolve(val);
            return coerceConfiguredValue(targetType, raw, lookup);
        }

        return null;
    }

    private Object coerceConfiguredValue(Class<?> targetType, Object rawValue, AnnotationLookup lookup) {
        var intermediateType = lookup.annotation(IntermediateType.class);
        Object value = rawValue;
        try {
            if (intermediateType.isPresent()) {
                value = container.get(Coercer.class).coerce(rawValue, intermediateType.get().value());
            }
            return container.get(Coercer.class).coerce(value, targetType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Cannot coerce configured value '" + rawValue + "' to " + targetType.getName()
                    + (intermediateType.isPresent()
                        ? " via intermediate type " + intermediateType.get().value().getName()
                        : ""),
                e
            );
        }
    }

    /**
     * Per-container warn-once registry for unrecognized injection-point
     * annotations — instance-scoped so embedded multi-container apps don't
     * share dedup state (and a closed container's set can't suppress another
     * container's warnings).
     */
    private final Set<String> markerWarned = ConcurrentHashMap.newKeySet();

    private Object resolveValue(
        Class<?> ownerType,
        AnnotationLookup lookup,
        Type memberType,
        Class<?> targetType,
        boolean parameterMode
    ) {
        if (targetType == Logger.class
                && (parameterMode || hasInjectionAnnotation(lookup))) {
            return resolveLogger(ownerType, lookup);
        }
        // List<Foo> / Map<String, Foo> / Extension<Foo> — resolved from
        // the contribution mechanism. Must precede resolveInjected so @Inject
        // on these types does not attempt a broken container.get(...).
        Object contributed = resolveContributed(memberType, targetType, lookup, parameterMode);
        if (contributed != null) {
            return contributed;
        }
        Object injected = resolveInjected(ownerType, lookup, targetType);
        if (injected != null) {
            return injected;
        }
        Object configured = resolveSymbolValue(lookup, targetType);
        if (configured != null) {
            return configured;
        }
        if (!parameterMode) {
            return null;
        }
        // Constructor parameters may carry marker annotations without @Inject.
        // A plain String parameter resolves container.get(String.class) via the
        // same marker/scope path as every other type — no special case that
        // would skip scope-compatibility validation.
        return resolveService(ownerType, lookup, targetType);
    }

    private Object resolveInjected(Class<?> ownerType, AnnotationLookup lookup, Class<?> targetType) {
        if (!hasInjectionAnnotation(lookup)) {
            return null;
        }
        if (hasSymbolAnnotation(lookup)) {
            throw new IllegalArgumentException(
                "Cannot combine service injection and @Symbol on "
                    + ownerType.getName()
            );
        }
        String id = resolveId(lookup);
        if (id != null) {
            validateScopeBeforeResolution(
                ownerType, container.bindingIndex().find(targetType, id));
            return container.get(targetType, id);
        }
        // No explicit id — try marker-based resolution
        return resolveService(ownerType, lookup, targetType);
    }

    private Object resolveService(Class<?> ownerType, AnnotationLookup lookup, Class<?> targetType) {
        Set<Class<? extends Annotation>> markers = resolveMarkers(ownerType, lookup);
        // Validate before realization: a singleton owner directly injecting a
        // thread-scoped concrete class must get the dedicated diagnostic even
        // when no scope is open (otherwise "No open scope" from realize() masks
        // the real contract violation). The binding validated is the one the
        // selection below will actually pick — markers choose for a
        // multi-binding interface.
        @SuppressWarnings("unchecked")
        Class<? extends Annotation>[] markerArr =
                markers.toArray(new Class[0]);
        BindingImpl<?> selected = markers.isEmpty()
            ? uniqueOrNull(targetType)
            : container.markerIndex().findByMarker(targetType, markerArr);
        validateScopeBeforeResolution(ownerType, selected);
        Object service;
        if (!markers.isEmpty()) {
            service = container.get(targetType, markerArr);
        } else {
            service = container.get(targetType);
        }
        return service;
    }

    /**
     * Scans the injection point for annotations that are known markers.
     * Returns the set of marker annotations found.
     *
     * <p>Annotations that are neither framework annotations nor known markers
     * are ignored with a warning — they may be markers the binding forgot to
     * register via {@code .marker(...)}, which would otherwise resolve the
     * wrong service silently. Each (annotation, owner) pair is warned about
     * only once so prototype-heavy code does not flood the log.
     */
    private Set<Class<? extends Annotation>> resolveMarkers(
            Class<?> ownerType,
            AnnotationLookup lookup
    ) {
        Set<Class<? extends Annotation>> result = new HashSet<>();
        for (Annotation ann : lookup.annotations()) {
            Class<? extends Annotation> annType = ann.annotationType();
            // Skip framework annotations that aren't markers
            if (annType == Inject.class || annType == Symbol.class
                    || annType == IntermediateType.class) {
                continue;
            }
            // Check if this annotation is a known marker
            if (container.markerIndex().isKnownMarker(annType)) {
                result.add(annType);
            } else if (markerWarned.add(annType.getName() + "#" + ownerType.getName())) {
                LOG.warn(
                    "Ignoring unrecognized annotation {} at an injection point on {}; "
                        + "register it with .marker({}.class) on the binding, "
                        + "or remove it from the injection point",
                    annType.getName(),
                    ownerType.getName(),
                    annType.getSimpleName()
                );
            }
        }
        return result;
    }

    private BindingImpl<?> findOwnerBinding(Class<?> ownerType) {
        BindingImpl<?> exact = uniqueOrNull(ownerType);
        if (exact != null) return exact;
        // Check full interface hierarchy (direct + super-interfaces)
        BindingImpl<?> b = findSingletonInterface(ownerType, new HashSet<>());
        if (b != null) return b;
        // Check superclass chain (stop before Object)
        for (Class<?> sup = ownerType.getSuperclass();
             sup != null && sup != Object.class;
             sup = sup.getSuperclass()) {
            b = uniqueOrNull(sup);
            if (b != null && b.scope() == Scope.SINGLETON) return b;
        }
        return null;
    }

    private BindingImpl<?> findSingletonInterface(Class<?> type, Set<Class<?>> visited) {
        for (Class<?> iface : type.getInterfaces()) {
            if (!visited.add(iface)) continue;
            BindingImpl<?> b = uniqueOrNull(iface);
            if (b != null && b.scope() == Scope.SINGLETON) return b;
            b = findSingletonInterface(iface, visited);
            if (b != null) return b;
        }
        return null;
    }

    /**
     * The validator's heuristic lookups must not be louder than the thing they
     * validate: an interface implemented by several bindings (a contributed
     * {@code HttpFilter}, say) is simply {@code null} here — the real
     * selection happens at the {@code get} below and reports ambiguity in
     * its own terms.
     */
    @SuppressWarnings("unchecked")
    private BindingImpl<?> uniqueOrNull(Class<?> type) {
        try {
            return container.bindingIndex().findUnique((Class<Object>) type);
        } catch (AmbiguousBindingException ambiguous) {
            return null;
        }
    }

    private void validateScopeBeforeResolution(
        Class<?> ownerType,
        BindingImpl<?> targetBinding
    ) {
        if (targetBinding == null) {
            return;
        }
        Class<?> targetType = targetBinding.type();
        BindingImpl<?> ownerBinding = findOwnerBinding(ownerType);
        if (ownerBinding == null || ownerBinding.scope() != Scope.SINGLETON) {
            return;
        }
        boolean targetIsInterface = targetType.isInterface();
        if (targetBinding.scope() == Scope.THREAD) {
            if (!targetIsInterface) {
                throw new IllegalStateException(
                    "Singleton service " + ownerType.getName()
                        + " cannot directly inject thread-scoped concrete class "
                        + targetType.getName()
                        + ". Use an interface with proxy support instead."
                );
            }
            // A THREAD-scoped interface binding is genuinely safe in a
            // singleton: the proxy resolves per-scope, so each thread gets
            // its own target.
            return;
        }
        // @NotThreadSafe is enforced through the interface too. A singleton-scoped
        // proxy caches exactly one target, and a PROTOTYPE instance injected into
        // a singleton holder is held by that one holder — declaring an interface
        // does not launder the marker. (.to(impl) copies the impl's class
        // markers onto the interface-keyed binding, so the binding is the
        // source of truth here.)
        if (targetBinding.markers().contains(NotThreadSafe.class)) {
            throw new IllegalStateException(
                "Singleton service " + ownerType.getName() + " cannot inject "
                    + "@NotThreadSafe " + targetType.getName()
                    + (targetIsInterface
                        ? " (bound to a @NotThreadSafe implementation)" : "")
                    + " — the singleton shares it across threads, and the "
                    + "interface proxy holds one target, so proxying does not "
                    + "change that. Declare the implementation @ThreadSafe, "
                    + "bind it Scope.THREAD (then the proxy resolves per "
                    + "thread), or inject it into a prototype/thread-scoped "
                    + "holder."
            );
        }
    }

    /**
     * The trimmed {@code @Inject("id")} value, or {@code null} when absent.
     * Takes the concrete annotation type — this method is only ever called
     * with an {@code Inject}, so there is no other branch to handle.
     */
    private static String normalizedId(Inject inject) {
        if (inject == null) {
            return null;
        }
        String value = inject.value();
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

}
