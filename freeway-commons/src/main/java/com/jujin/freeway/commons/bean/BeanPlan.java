package com.jujin.freeway.commons.bean;

import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable metadata snapshot of a class's bean/record structure.
 *
 * <p>Describes the type's constructor, all readable/writable properties,
 * and whether it is a record or a regular Java bean.
 *
 * <p>Obtained via {@link BeanIntrospector#plan(Class)}.
 */
public final class BeanPlan {
    private final Class<?> type;
    private final boolean record;
    private final BeanConstructor constructor;
    private final List<BeanProperty> properties;
    private final Map<String, BeanProperty> index;

    private BeanPlan(Class<?> type, boolean record, BeanConstructor constructor, List<BeanProperty> properties) {
        this.type = type;
        this.record = record;
        this.constructor = constructor;
        this.properties = List.copyOf(properties);
        Map<String, BeanProperty> map = new LinkedHashMap<>();
        for (BeanProperty property : properties) {
            map.putIfAbsent(property.name(), property);
        }
        this.index = Map.copyOf(map);
    }

    /**
     * Creates a {@link BeanPlan} for the given type. Results are cached by
     * {@link BeanIntrospector}.
     *
     * @param type the class to introspect
     * @return a new bean plan
     */
    public static BeanPlan of(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return type.isRecord() ? forRecord(type) : forBean(type);
    }

    /** Returns the underlying class. */
    public Class<?> type() {
        return type;
    }

    /** Returns true if the type is a Java record. */
    public boolean record() {
        return record;
    }

    /** Returns the constructor metadata, or null if the type has no usable constructor. */
    public BeanConstructor constructor() {
        return constructor;
    }

    /** Returns true if the type has a usable constructor. */
    public boolean isConstructable() {
        return constructor != null;
    }

    /** Returns an unmodifiable list of all properties. */
    public List<BeanProperty> properties() {
        return properties;
    }

    /** Looks up a property by name. Returns null if not found. */
    public BeanProperty property(String name) {
        return index.get(name);
    }

    private static BeanPlan forRecord(Class<?> type) {
        try {
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] parameterTypes = new Class<?>[components.length];
            List<BeanProperty> properties = new ArrayList<>(components.length);
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                properties.add(new RecordBeanProperty(
                    component.getName(),
                    component.getGenericType(),
                    recordAnnotations(type, component),
                    MethodHandleUtils.methodHandle(component.getAccessor())
                ));
            }
            Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            return new BeanPlan(type, true, BeanConstructor.of(constructor), properties);
        } catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException("Cannot resolve record constructor for " + type.getName(), ex);
        }
    }

    /** JDK classes are never bean property sources (and their modules are not
     *  open to VarHandle creation) — the superclass walk stops here.
     *  Bootstrap-loaded packages outside java./javax./jdk. (e.g.
     *  org.xml.sax.helpers, com.sun.net.httpserver) live in non-open modules
     *  too — their private fields are equally unreachable via publicLookup. */
    private static boolean isJdkClass(Class<?> type) {
        if (type.getClassLoader() == null) {
            return true;
        }
        String pkg = type.getPackageName();
        return pkg.startsWith("java.")
            || pkg.startsWith("javax.")
            || pkg.startsWith("jdk.");
    }

    /**
     * Collect annotations from both the record component and the backing field.
     * <p>
     * Annotations with only {@code @Target(FIELD)} are not visible via
     * {@link RecordComponent#getAnnotations()} — they only appear on the field.
     * This merges both sources so consumers can read field-targeted annotations
     * (e.g. validation constraints) on records regardless of their {@code @Target}.
     */
    private static Annotation[] recordAnnotations(Class<?> type, RecordComponent component) {
        Annotation[] componentAnns = component.getAnnotations();
        try {
            Field field = type.getDeclaredField(component.getName());
            Annotation[] fieldAnns = field.getDeclaredAnnotations();
            if (fieldAnns.length == 0) {
                return componentAnns;
            }
            if (componentAnns.length == 0) {
                return fieldAnns;
            }
            // Merge: component wins on duplicates, field fills gaps
            Set<Class<? extends Annotation>> seen = Collections.newSetFromMap(
                new IdentityHashMap<>());
            List<Annotation> merged = new ArrayList<>();
            for (Annotation a : componentAnns) {
                seen.add(a.annotationType());
                merged.add(a);
            }
            for (Annotation a : fieldAnns) {
                if (seen.add(a.annotationType())) {
                    merged.add(a);
                }
            }
            return merged.toArray(new Annotation[0]);
        } catch (NoSuchFieldException | RuntimeException e) {
            return componentAnns;
        }
    }

    private static BeanPlan forBean(Class<?> type) {
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor();
        } catch (NoSuchMethodException ex) {
            constructor = null;
        }
        Map<String, Method> setters = new LinkedHashMap<>();
        for (Method method : type.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1
                    && method.getName().startsWith("set")
                    && method.getName().length() > 3) {
                String prop = Introspector.decapitalize(method.getName().substring(3));
                setters.putIfAbsent(prop, method);
            }
        }
        Map<String, BeanProperty> unique = new LinkedHashMap<>();
        Class<?> current = type;
        // Stop at JDK classes: their fields live in non-open modules
        // (java.base etc.) where no VarHandle can be created, and they are
        // not bean properties anyway. Custom exceptions / ArrayList subclasses
        // keep their own app-class fields.
        while (current != null
                && current != Object.class
                && !isJdkClass(current)) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                if (unique.containsKey(field.getName())) {
                    continue;
                }
                Method setter = setters.get(field.getName());
                unique.put(field.getName(), new FieldBeanProperty(
                    field.getName(),
                    field.getGenericType(),
                    field.getAnnotations(),
                    MethodHandleUtils.varHandle(field),
                    setter != null ? MethodHandleUtils.methodHandle(setter) : null,
                    !Modifier.isFinal(field.getModifiers()) || setter != null
                ));
            }
            current = current.getSuperclass();
        }
        return new BeanPlan(type, false, constructor != null ? BeanConstructor.of(constructor) : null, new ArrayList<>(unique.values()));
    }


    private record RecordBeanProperty(String name, Type type, Annotation[] annotations, MethodHandle accessor) implements BeanProperty {
        @Override
        public Annotation[] annotations() {
            return annotations.clone();
        }

        @Override
        public boolean isWritable() {
            return false;
        }

        @Override
        public Object read(Object target) {
            try {
                return MethodHandleUtils.invoke(accessor, target);
            } catch (Error e) { throw e; } catch (Throwable ex) {
                throw new IllegalArgumentException("Cannot read record property: " + name, ex);
            }
        }

        @Override
        public void write(Object target, Object value) {
            throw new UnsupportedOperationException("Record property is read-only: " + name);
        }
    }

    private record FieldBeanProperty(String name, Type type, Annotation[] annotations, VarHandle field, MethodHandle setter, boolean writable) implements BeanProperty {
        @Override
        public boolean isWritable() {
            return writable;
        }
        @Override
        public Annotation[] annotations() {
            return annotations.clone();
        }

        @Override
        public Object read(Object target) {
            return field.get(target);
        }

        @Override
        public void write(Object target, Object value) {
            if (!writable) {
                throw new UnsupportedOperationException("Property is read-only: " + name);
            }
            if (setter != null) {
                try {
                    MethodHandleUtils.invoke(setter, target, value);
                } catch (Error e) { throw e; } catch (Throwable ex) {
                    throw new IllegalArgumentException("Cannot write property: " + name, ex);
                }
            } else {
                field.set(target, value);
            }
        }
    }
}
