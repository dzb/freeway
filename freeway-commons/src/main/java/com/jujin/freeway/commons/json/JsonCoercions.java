package com.jujin.freeway.commons.json;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import com.jujin.freeway.commons.scalar.Coercer;
import com.jujin.freeway.commons.scalar.CoercerDefault;

final class JsonCoercions {
    private static final CoercerDefault DEFAULT_COERCER = new CoercerDefault();

    private JsonCoercions() {
    }

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

    static Object deepCopy(Object value) {
        return JsonNormalizer.deepCopy(value);
    }

    private static Object coerce(Object value, Type type, Coercer coercer, TypeContext context) {
        Type resolvedType = context.resolve(type);
        if (resolvedType instanceof Class<?> targetType) {
            return coerce(value, targetType, coercer, context);
        }
        if (resolvedType instanceof ParameterizedType parameterizedType) {
            return coerceParameterized(value, parameterizedType, coercer, context.child(parameterizedType));
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
        throw new IllegalArgumentException("Unsupported JSON target type: " + resolvedType.getTypeName());
    }

    private static Object coerce(Object value, Class<?> targetType, Coercer coercer, TypeContext context) {
        Object plain = normalize(value);
        if (plain == null) {
            return CoercerDefault.defaultValue(targetType);
        }
        if (targetType.isInstance(plain)) {
            return targetType.cast(plain);
        }
        if (plain instanceof JsonObject object && Map.class.isAssignableFrom(targetType)) {
            return coerceToMap(object, targetType, String.class, Object.class, coercer, context);
        }
        if (plain instanceof JsonArray array && Collection.class.isAssignableFrom(targetType)) {
            return coerceToCollection(array, targetType, Object.class, coercer, context);
        }
        if (plain instanceof JsonObject object && !targetType.isArray() && !targetType.isEnum()) {
            BeanPlan plan = BeanIntrospector.plan(targetType);
            return plan.record()
                ? constructRecord(object, plan, coercer, context)
                : constructBean(object, plan, coercer, context);
        }
        if (plain instanceof JsonArray array && targetType.isArray()) {
            return coerceToArray(array, targetType.getComponentType(), coercer, context);
        }
        return coercer.coerce(plain, targetType);
    }

    private static Object coerceParameterized(Object value, ParameterizedType type, Coercer coercer, TypeContext context) {
        Class<?> rawType = rawClass(type.getRawType());
        Object plain = normalize(value);
        if (plain == null) {
            return CoercerDefault.defaultValue(rawType);
        }
        if (plain instanceof JsonObject object && Map.class.isAssignableFrom(rawType)) {
            Type[] args = type.getActualTypeArguments();
            Type keyType = args.length > 0 ? args[0] : String.class;
            Type valueType = args.length > 1 ? args[1] : Object.class;
            return coerceToMap(object, rawType, keyType, valueType, coercer, context);
        }
        if (plain instanceof JsonArray array && Collection.class.isAssignableFrom(rawType)) {
            Type elementType = type.getActualTypeArguments().length > 0 ? type.getActualTypeArguments()[0] : Object.class;
            return coerceToCollection(array, rawType, elementType, coercer, context);
        }
        if (plain instanceof JsonObject object && !rawType.isArray() && !rawType.isEnum()) {
            BeanPlan plan = BeanIntrospector.plan(rawType);
            return plan.record()
                ? constructRecord(object, plan, coercer, context)
                : constructBean(object, plan, coercer, context);
        }
        if (plain instanceof JsonArray array && rawType.isArray()) {
            return coerceToArray(array, rawType.getComponentType(), coercer, context);
        }
        if (rawType.isInstance(plain)) {
            return rawType.cast(plain);
        }
        return coercer.coerce(plain, rawType);
    }

    private static Object constructRecord(JsonObject data, BeanPlan plan, Coercer coercer, TypeContext context) {
        if (!plan.constructable()) {
            throw new IllegalArgumentException("Cannot construct record type: " + plan.type().getName());
        }
        Object[] args = new Object[plan.properties().size()];
        for (int i = 0; i < plan.properties().size(); i++) {
            BeanProperty property = plan.properties().get(i);
            Type propertyType = context.resolve(property.type());
            args[i] = data.containsKey(property.name())
                ? coerce(data.get(property.name()), propertyType, coercer, context)
                : coerce(null, propertyType, coercer, context);
        }
        return plan.constructor().newInstance(args);
    }

    private static Object constructBean(JsonObject data, BeanPlan plan, Coercer coercer, TypeContext context) {
        if (!plan.constructable()) {
            throw new IllegalArgumentException("Type " + plan.type().getName() + " has no no-arg constructor");
        }
        Object bean = plan.constructor().newInstance();
        for (BeanProperty property : plan.properties()) {
            if (!property.writable() || !data.containsKey(property.name())) {
                continue;
            }
            Type propertyType = context.resolve(property.type());
            property.write(bean, coerce(data.get(property.name()), propertyType, coercer, context));
        }
        return bean;
    }

    private static Object coerceToArray(JsonArray array, Class<?> componentType, Coercer coercer, TypeContext context) {
        Object result = Array.newInstance(componentType, array.size());
        for (int i = 0; i < array.size(); i++) {
            Array.set(result, i, coerce(array.get(i), componentType, coercer, context));
        }
        return result;
    }

    private static Object coerceToMap(JsonObject object, Class<?> targetType, Type keyType, Type valueType, Coercer coercer, TypeContext context) {
        Object target = newMutableInstance(targetType, LinkedHashMap.class);
        Map<Object, Object> mutable = target instanceof Map<?, ?> map
            ? castMap(map)
            : new LinkedHashMap<>();
        object.forEach((key, value) ->
            mutable.put(
                coerce(key, keyType, coercer, context),
                coerce(value, valueType, coercer, context)
            )
        );
        return mutable;
    }

    private static Object coerceToCollection(JsonArray array, Class<?> targetType, Type elementType, Coercer coercer, TypeContext context) {
        Object target = newMutableInstance(targetType, ArrayList.class);
        Collection<Object> mutable = target instanceof Collection<?> collection
            ? castCollection(collection)
            : new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            mutable.add(coerce(array.get(i), elementType, coercer, context));
        }
        return mutable;
    }

    private static Object coerceGenericArray(Object value, GenericArrayType arrayType, Coercer coercer, TypeContext context) {
        Object plain = normalize(value);
        if (plain == null) {
            return null;
        }
        if (!(plain instanceof JsonArray array)) {
            throw new IllegalArgumentException("Unsupported JSON target type: " + arrayType.getTypeName());
        }
        Type componentType = context.resolve(arrayType.getGenericComponentType());
        Class<?> componentClass = rawClass(componentType);
        Object result = Array.newInstance(componentClass, array.size());
        for (int i = 0; i < array.size(); i++) {
            Array.set(result, i, coerce(array.get(i), componentType, coercer, context));
        }
        return result;
    }

    private static Object newMutableInstance(Class<?> targetType, Class<?> fallbackType) {
        if (targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) {
            return fallbackType == null ? null : instantiate(fallbackType);
        }
        Object instance = instantiate(targetType);
        if (instance != null) {
            return instance;
        }
        return fallbackType == null ? null : instantiate(fallbackType);
    }

    private static Object instantiate(Class<?> targetType) {
        try {
            Constructor<?> constructor = targetType.getDeclaredConstructor();
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
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

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> rawType) {
            return rawType;
        }
        if (type instanceof GenericArrayType arrayType) {
            return Array.newInstance(rawClass(arrayType.getGenericComponentType()), 0).getClass();
        }
        throw new IllegalArgumentException("Unsupported raw type: " + type.getTypeName());
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
            Class<?> rawType = rawClass(type.getRawType());
            TypeVariable<?>[] variables = rawType.getTypeParameters();
            Type[] arguments = type.getActualTypeArguments();
            Map<TypeVariable<?>, Type> next = new LinkedHashMap<>(bindings);
            for (int i = 0; i < variables.length && i < arguments.length; i++) {
                next.put(variables[i], resolve(arguments[i]));
            }
            return new TypeContext(Map.copyOf(next));
        }

        Type resolve(Type type) {
            if (type instanceof TypeVariable<?> variable) {
                Type bound = bindings.get(variable);
                if (bound != null) {
                    return resolve(bound);
                }
                return firstBound(variable);
            }
            if (type instanceof ParameterizedType parameterizedType) {
                Type ownerType = parameterizedType.getOwnerType();
                Type resolvedOwner = ownerType == null ? null : resolve(ownerType);
                Type[] arguments = parameterizedType.getActualTypeArguments();
                Type[] resolvedArguments = new Type[arguments.length];
                boolean changed = resolvedOwner != ownerType;
                for (int i = 0; i < arguments.length; i++) {
                    resolvedArguments[i] = resolve(arguments[i]);
                    if (resolvedArguments[i] != arguments[i]) {
                        changed = true;
                    }
                }
                if (!changed) {
                    return parameterizedType;
                }
                return new ResolvedParameterizedType(resolvedOwner, rawClass(parameterizedType.getRawType()), resolvedArguments);
            }
            if (type instanceof GenericArrayType arrayType) {
                Type resolvedComponent = resolve(arrayType.getGenericComponentType());
                if (resolvedComponent == arrayType.getGenericComponentType()) {
                    return arrayType;
                }
                return new ResolvedGenericArrayType(resolvedComponent);
            }
            if (type instanceof WildcardType wildcard) {
                Type[] upperBounds = wildcard.getUpperBounds();
                if (upperBounds.length > 0) {
                    return resolve(upperBounds[0]);
                }
                Type[] lowerBounds = wildcard.getLowerBounds();
                if (lowerBounds.length > 0) {
                    return resolve(lowerBounds[0]);
                }
                return Object.class;
            }
            return type;
        }
    }

    private record ResolvedParameterizedType(Type ownerType, Class<?> rawType, Type[] actualTypeArguments) implements ParameterizedType {
        private ResolvedParameterizedType {
            Objects.requireNonNull(rawType, "rawType");
            actualTypeArguments = Objects.requireNonNull(actualTypeArguments, "actualTypeArguments").clone();
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

    private record ResolvedGenericArrayType(Type componentType) implements GenericArrayType {
        private ResolvedGenericArrayType {
            componentType = Objects.requireNonNull(componentType, "componentType");
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
