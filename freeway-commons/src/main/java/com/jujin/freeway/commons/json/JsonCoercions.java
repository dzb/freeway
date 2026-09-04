package com.jujin.freeway.commons.json;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerImpl;
import com.jujin.freeway.commons.util.Types;
import java.lang.ClassValue;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TransferQueue;

final class JsonCoercions {

    private static final CoercerImpl DEFAULT_COERCER = new CoercerImpl();
    private static final MethodHandles.Lookup PUBLIC =
        MethodHandles.publicLookup();

    private JsonCoercions() {}

    @SuppressWarnings("unchecked")
    static <T> T coerce(Object value, Class<T> targetType) {
        return (T) coerce(value, (Type) targetType, DEFAULT_COERCER);
    }

    @SuppressWarnings("unchecked")
    static <T> T coerce(Object value, Class<T> targetType, Coercer coercer) {
        return (T) coerce(value, (Type) targetType, coercer);
    }

    static Object coerce(Object value, Type type) {
        return coerce(value, type, DEFAULT_COERCER);
    }

    static Object coerce(Object value, Type type, Coercer coercer) {
        Objects.requireNonNull(coercer, "coercer");
        return coerce(value, type, coercer, TypeContext.empty());
    }

    static Object normalize(Object value) {
        return JsonNormalizer.normalize(value);
    }

    private static Object coerce(
        Object value,
        Type type,
        Coercer coercer,
        TypeContext context
    ) {
        Type resolvedType = context.resolve(type);
        if (resolvedType instanceof Class<?> targetType) {
            return coerce(value, targetType, coercer, context);
        }
        if (resolvedType instanceof ParameterizedType parameterizedType) {
            return coerceParameterized(
                value,
                parameterizedType,
                coercer,
                context.child(parameterizedType)
            );
        }
        if (resolvedType instanceof GenericArrayType arrayType) {
            return coerceGenericArray(value, arrayType, coercer, context);
        }
        if (resolvedType instanceof TypeVariable<?> variable) {
            Type fallback = firstBound(variable);
            return coerce(value, fallback, coercer, context);
        }
        if (resolvedType instanceof WildcardType wildcard) {
            Type fallback = firstBound(wildcard);
            return coerce(value, fallback, coercer, context);
        }
        throw new IllegalArgumentException(
            "Unsupported JSON target type: " + resolvedType.getTypeName()
        );
    }

    private static Object coerce(
        Object value,
        Class<?> targetType,
        Coercer coercer,
        TypeContext context
    ) {
        return coerceStructured(
            value,
            targetType,
            String.class,
            Object.class,
            Object.class,
            coercer,
            context
        );
    }

    private static Object coerceParameterized(
        Object value,
        ParameterizedType type,
        Coercer coercer,
        TypeContext context
    ) {
        Class<?> rawType = Types.rawClass(type.getRawType());
        Type[] args = type.getActualTypeArguments();
        return coerceStructured(
            value,
            rawType,
            args.length > 0 ? args[0] : String.class,
            args.length > 1 ? args[1] : Object.class,
            args.length > 0 ? args[0] : Object.class,
            coercer,
            context
        );
    }

    // Shared dispatch chain for Class and ParameterizedType targets. The
    // isInstance short-circuit must come first (e.g. Object.class targets);
    // for parameterized targets the raw type can never be a supertype of
    // JsonObject/JsonArray, so its position is unobservable there.
    private static Object coerceStructured(
        Object value,
        Class<?> rawType,
        Type keyType,
        Type valueType,
        Type elementType,
        Coercer coercer,
        TypeContext context
    ) {
        Object plain = normalize(value);
        if (plain == null) {
            return CoercerImpl.defaultValue(rawType);
        }
        if (rawType.isInstance(plain)) {
            return rawType.cast(plain);
        }
        if (
            plain instanceof JsonObject object &&
            Map.class.isAssignableFrom(rawType)
        ) {
            return coerceToMap(
                object,
                rawType,
                keyType,
                valueType,
                coercer,
                context
            );
        }
        if (
            plain instanceof JsonArray array &&
            Collection.class.isAssignableFrom(rawType)
        ) {
            return coerceToCollection(
                array,
                rawType,
                elementType,
                coercer,
                context
            );
        }
        if (
            plain instanceof JsonObject object &&
            !rawType.isArray() &&
            !rawType.isEnum()
        ) {
            BeanPlan plan = BeanIntrospector.plan(rawType);
            return plan.record()
                ? constructRecord(object, plan, coercer, context)
                : constructBean(object, plan, coercer, context);
        }
        if (plain instanceof JsonArray array && rawType.isArray()) {
            return coerceToArray(
                array,
                rawType.getComponentType(),
                coercer,
                context
            );
        }
        return coercer.coerce(plain, rawType);
    }

    private static Object constructRecord(
        JsonObject data,
        BeanPlan plan,
        Coercer coercer,
        TypeContext context
    ) {
        if (!plan.isConstructable()) {
            throw new IllegalArgumentException(
                "Cannot construct record type: " + plan.type().getName()
            );
        }
        Object[] args = new Object[plan.properties().size()];
        for (int i = 0; i < plan.properties().size(); i++) {
            BeanProperty property = plan.properties().get(i);
            Type propertyType = context.resolve(property.type());
            args[i] = data.containsKey(property.name())
                ? coerce(
                      data.get(property.name()),
                      propertyType,
                      coercer,
                      context
                  )
                : coerce(null, propertyType, coercer, context);
        }
        return plan.constructor().newInstance(args);
    }

    private static Object constructBean(
        JsonObject data,
        BeanPlan plan,
        Coercer coercer,
        TypeContext context
    ) {
        if (!plan.isConstructable()) {
            throw new IllegalArgumentException(
                "Type " + plan.type().getName() + " has no no-arg constructor"
            );
        }
        Object bean = plan.constructor().newInstance();
        for (BeanProperty property : plan.properties()) {
            if (!property.isWritable() || !data.containsKey(property.name())) {
                continue;
            }
            Type propertyType = context.resolve(property.type());
            property.write(
                bean,
                coerce(
                    data.get(property.name()),
                    propertyType,
                    coercer,
                    context
                )
            );
        }
        return bean;
    }

    private static Object coerceToArray(
        JsonArray array,
        Class<?> componentType,
        Coercer coercer,
        TypeContext context
    ) {
        Object result = Array.newInstance(componentType, array.size());
        for (int i = 0; i < array.size(); i++) {
            Array.set(
                result,
                i,
                coerce(array.get(i), componentType, coercer, context)
            );
        }
        return result;
    }

    private static Object coerceToMap(
        JsonObject object,
        Class<?> targetType,
        Type keyType,
        Type valueType,
        Coercer coercer,
        TypeContext context
    ) {
        Map<Object, Object> mutable = newMapInstance(targetType, keyType);
        for (Map.Entry<String, Object> entry : object.entries()) {
            mutable.put(
                coerce(entry.getKey(), keyType, coercer, context),
                coerce(entry.getValue(), valueType, coercer, context)
            );
        }
        return mutable;
    }

    private static Object coerceToCollection(
        JsonArray array,
        Class<?> targetType,
        Type elementType,
        Coercer coercer,
        TypeContext context
    ) {
        Collection<Object> mutable = newCollectionInstance(
            targetType,
            elementType
        );
        for (int i = 0; i < array.size(); i++) {
            mutable.add(coerce(array.get(i), elementType, coercer, context));
        }
        return mutable;
    }

    private static Object coerceGenericArray(
        Object value,
        GenericArrayType arrayType,
        Coercer coercer,
        TypeContext context
    ) {
        Object plain = normalize(value);
        if (plain == null) {
            return null;
        }
        if (!(plain instanceof JsonArray array)) {
            throw new IllegalArgumentException(
                "Unsupported JSON target type: " + arrayType.getTypeName()
            );
        }
        Type componentType = context.resolve(
            arrayType.getGenericComponentType()
        );
        Class<?> componentClass = Types.rawClass(componentType);
        Object result = Array.newInstance(componentClass, array.size());
        for (int i = 0; i < array.size(); i++) {
            Array.set(
                result,
                i,
                coerce(array.get(i), componentType, coercer, context)
            );
        }
        return result;
    }

    private static Map<Object, Object> newMapInstance(
        Class<?> targetType,
        Type keyType
    ) {
        if (targetType == EnumMap.class) {
            return newEnumMap(keyType);
        }
        if (
            targetType.isInterface() ||
            Modifier.isAbstract(targetType.getModifiers())
        ) {
            if (ConcurrentNavigableMap.class.isAssignableFrom(targetType)) {
                return new ConcurrentSkipListMap<>();
            }
            if (
                NavigableMap.class.isAssignableFrom(targetType) ||
                SortedMap.class.isAssignableFrom(targetType)
            ) {
                return new TreeMap<>();
            }
            if (ConcurrentMap.class.isAssignableFrom(targetType)) {
                return new ConcurrentHashMap<>();
            }
            return new LinkedHashMap<>();
        }
        Object instance = instantiate(targetType);
        if (instance instanceof Map<?, ?> map) {
            return castMap(map);
        }
        throw new IllegalArgumentException(
            "Cannot instantiate map type: " + targetType.getName()
        );
    }

    private static Collection<Object> newCollectionInstance(
        Class<?> targetType,
        Type elementType
    ) {
        if (targetType == EnumSet.class) {
            return newEnumSet(elementType);
        }
        if (
            targetType.isInterface() ||
            Modifier.isAbstract(targetType.getModifiers())
        ) {
            if (TransferQueue.class.isAssignableFrom(targetType)) {
                return new LinkedTransferQueue<>();
            }
            if (BlockingDeque.class.isAssignableFrom(targetType)) {
                return new LinkedBlockingDeque<>();
            }
            if (BlockingQueue.class.isAssignableFrom(targetType)) {
                return new LinkedBlockingQueue<>();
            }
            if (
                NavigableSet.class.isAssignableFrom(targetType) ||
                SortedSet.class.isAssignableFrom(targetType)
            ) {
                return new TreeSet<>();
            }
            if (
                Deque.class.isAssignableFrom(targetType) ||
                Queue.class.isAssignableFrom(targetType)
            ) {
                return new ArrayDeque<>();
            }
            if (Set.class.isAssignableFrom(targetType)) {
                return new LinkedHashSet<>();
            }
            return new ArrayList<>();
        }
        Object instance = instantiate(targetType);
        if (instance instanceof Collection<?> collection) {
            return castCollection(collection);
        }
        throw new IllegalArgumentException(
            "Cannot instantiate collection type: " + targetType.getName()
        );
    }

    private static Map<Object, Object> newEnumMap(Type keyType) {
        Class<?> enumType = Types.rawClass(keyType);
        if (!enumType.isEnum()) {
            throw new IllegalArgumentException(
                "EnumMap requires an enum key type: " + keyType.getTypeName()
            );
        }
        @SuppressWarnings("unchecked")
        Class<? extends Enum> rawEnum =
            (Class<? extends Enum>) enumType.asSubclass(Enum.class);
        return castMap(new EnumMap<>(rawEnum));
    }

    private static Collection<Object> newEnumSet(Type elementType) {
        Class<?> enumType = Types.rawClass(elementType);
        if (!enumType.isEnum()) {
            throw new IllegalArgumentException(
                "EnumSet requires an enum element type: " +
                    elementType.getTypeName()
            );
        }
        @SuppressWarnings("unchecked")
        Class<? extends Enum> rawEnum =
            (Class<? extends Enum>) enumType.asSubclass(Enum.class);
        return castCollection(EnumSet.noneOf(rawEnum));
    }

    private static Object instantiate(Class<?> targetType) {
        Optional<MethodHandle> handle = EMPTY_CONSTRUCTORS.get(targetType);
        if (handle.isEmpty()) {
            return null;
        }
        try {
            return handle.get().invoke();
        } catch (Error e) {
            throw e;
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * No-arg constructor handle per concrete class. {@link ClassValue} gives
     * lock-free reads and weak class association (no classloader leak); the
     * empty {@link Optional} caches the negative case so classes without a
     * no-arg constructor are not re-introspected on every instantiation —
     * type-safe, no casts at the call site.
     */
    private static final ClassValue<Optional<MethodHandle>> EMPTY_CONSTRUCTORS = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> type) {
            return Optional.ofNullable(emptyConstructor(type));
        }
    };

    private static MethodHandle emptyConstructor(Class<?> targetType) {
        try {
            var lookup = MethodHandles.privateLookupIn(
                targetType,
                MethodHandles.lookup()
            );
            return lookup.unreflectConstructor(
                targetType.getDeclaredConstructor()
            );
        } catch (ReflectiveOperationException ex) {
            // privateLookupIn fails for non-open modules (e.g. every class in
            // java.base like ArrayList/HashMap). Public no-arg constructors
            // of public classes stay reachable via publicLookup.
            try {
                return PUBLIC.unreflectConstructor(
                    targetType.getConstructor()
                );
            } catch (ReflectiveOperationException ex2) {
                return null;
            }
        } catch (Error e) {
            throw e;
        } catch (Throwable ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> castMap(Map<?, ?> map) {
        return (Map<Object, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> castCollection(Collection<?> collection) {
        return (Collection<Object>) collection;
    }

    private static Type firstBound(TypeVariable<?> variable) {
        Type[] bounds = variable.getBounds();
        return bounds.length == 0 ? Object.class : bounds[0];
    }

    private static Type firstBound(WildcardType wildcard) {
        Type[] bounds = wildcard.getUpperBounds();
        if (bounds.length > 0) {
            return bounds[0];
        }
        bounds = wildcard.getLowerBounds();
        return bounds.length > 0 ? bounds[0] : Object.class;
    }

    private static final class TypeContext {

        private static final TypeContext EMPTY = new TypeContext(Map.of());
        private final Map<TypeVariable<?>, Type> bindings;

        private TypeContext(Map<TypeVariable<?>, Type> bindings) {
            this.bindings = bindings;
        }

        static TypeContext empty() {
            return EMPTY;
        }

        TypeContext child(ParameterizedType type) {
            Class<?> rawType = Types.rawClass(type.getRawType());
            TypeVariable<?>[] variables = rawType.getTypeParameters();
            Type[] arguments = type.getActualTypeArguments();
            Map<TypeVariable<?>, Type> next = new LinkedHashMap<>(bindings);
            for (int i = 0; i < variables.length && i < arguments.length; i++) {
                next.put(variables[i], resolve(arguments[i]));
            }
            return new TypeContext(Map.copyOf(next));
        }

        Type resolve(Type type) {
            return resolve(type, Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        /**
         * Resolves a type against the current bindings. {@code seen} tracks
         * type variables currently being resolved so that cyclic bounds
         * (e.g. {@code class Node<T extends Comparable<T>>} where a
         * self-referential field like {@code Node<T> next} binds {@code T} to
         * {@code Comparable<T>}) fall back to {@code Object.class} instead of
         * recursing forever into {@code resolve(T) -> resolve(Comparable<T>)
         * -> resolve(T) -> ...}.
         */
        private Type resolve(Type type, Set<TypeVariable<?>> seen) {
            if (type instanceof TypeVariable<?> variable) {
                if (!seen.add(variable)) {
                    // Re-entrant type variable — its bound (transitively)
                    // references the variable itself. Erase to Object so the
                    // coercion can proceed with the raw bound.
                    return Object.class;
                }
                Type bound = bindings.get(variable);
                if (bound != null) {
                    return resolve(bound, seen);
                }
                return firstBound(variable);
            }
            if (type instanceof ParameterizedType parameterizedType) {
                Type ownerType = parameterizedType.getOwnerType();
                Type resolvedOwner =
                    ownerType == null ? null : resolve(ownerType, seen);
                Type[] arguments = parameterizedType.getActualTypeArguments();
                Type[] resolvedArguments = new Type[arguments.length];
                boolean changed = resolvedOwner != ownerType;
                for (int i = 0; i < arguments.length; i++) {
                    resolvedArguments[i] = resolve(arguments[i], seen);
                    if (resolvedArguments[i] != arguments[i]) {
                        changed = true;
                    }
                }
                if (!changed) {
                    return parameterizedType;
                }
                return new ResolvedParameterizedType(
                    resolvedOwner,
                    Types.rawClass(parameterizedType.getRawType()),
                    resolvedArguments
                );
            }
            if (type instanceof GenericArrayType arrayType) {
                Type resolvedComponent = resolve(
                    arrayType.getGenericComponentType(),
                    seen
                );
                if (resolvedComponent == arrayType.getGenericComponentType()) {
                    return arrayType;
                }
                return new ResolvedGenericArrayType(resolvedComponent);
            }
            if (type instanceof WildcardType wildcard) {
                Type[] upperBounds = wildcard.getUpperBounds();
                if (upperBounds.length > 0) {
                    return resolve(upperBounds[0], seen);
                }
                Type[] lowerBounds = wildcard.getLowerBounds();
                if (lowerBounds.length > 0) {
                    return resolve(lowerBounds[0], seen);
                }
                return Object.class;
            }
            return type;
        }
    }

    private record ResolvedParameterizedType(
        Type ownerType,
        Class<?> rawType,
        Type[] actualTypeArguments
    ) implements ParameterizedType {
        private ResolvedParameterizedType {
            Objects.requireNonNull(rawType, "rawType");
            actualTypeArguments = Objects.requireNonNull(
                actualTypeArguments,
                "actualTypeArguments"
            ).clone();
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder(rawType.getTypeName());
            if (actualTypeArguments.length > 0) {
                out.append('<');
                for (int i = 0; i < actualTypeArguments.length; i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(actualTypeArguments[i].getTypeName());
                }
                out.append('>');
            }
            return out.toString();
        }
    }

    private record ResolvedGenericArrayType(
        Type componentType
    ) implements GenericArrayType {
        private ResolvedGenericArrayType {
            componentType = Objects.requireNonNull(
                componentType,
                "componentType"
            );
        }

        @Override
        public Type getGenericComponentType() {
            return componentType;
        }

        @Override
        public String toString() {
            return componentType.getTypeName() + "[]";
        }
    }
}
