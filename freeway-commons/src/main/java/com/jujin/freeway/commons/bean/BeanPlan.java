package com.jujin.freeway2.commons.bean;

import java.beans.Introspector;
import java.lang.invoke.MethodHandle;
import java.lang.annotation.Annotation;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    static BeanPlan of(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return type.isRecord() ? forRecord(type) : forBean(type);
    }

    public Class<?> type() {
        return type;
    }

    public boolean record() {
        return record;
    }

    public BeanConstructor constructor() {
        return constructor;
    }

    public boolean constructable() {
        return constructor != null;
    }

    public List<BeanProperty> properties() {
        return properties;
    }

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
                    component.getAnnotations(),
                    MethodHandleUtils.methodHandle(component.getAccessor())
                ));
            }
            Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            return new BeanPlan(type, true, BeanConstructor.of(constructor), properties);
        } catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException("Cannot resolve record constructor for " + type.getName(), ex);
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
            if (method.getParameterCount() == 1 && method.getName().startsWith("set") && method.getName().length() > 3) {
                String prop = Introspector.decapitalize(method.getName().substring(3));
                setters.putIfAbsent(prop, method);
            }
        }
        Map<String, BeanProperty> unique = new LinkedHashMap<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
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





    private record RecordBeanProperty(String name, java.lang.reflect.Type type, Annotation[] annotations, MethodHandle accessor) implements BeanProperty {
        @Override
        public Annotation[] annotations() {
            return annotations.clone();
        }

        @Override
        public boolean writable() {
            return false;
        }

        @Override
        public Object read(Object target) {
            try {
                return MethodHandleUtils.invoke(accessor, target);
            } catch (Throwable ex) {
                throw new IllegalArgumentException("Cannot read record property: " + name, ex);
            }
        }

        @Override
        public void write(Object target, Object value) {
            throw new UnsupportedOperationException("Record property is read-only: " + name);
        }
    }

    private record FieldBeanProperty(String name, java.lang.reflect.Type type, Annotation[] annotations, VarHandle field, MethodHandle setter, boolean writable) implements BeanProperty {
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
                } catch (Throwable ex) {
                    throw new IllegalArgumentException("Cannot write property: " + name, ex);
                }
            } else {
                field.set(target, value);
            }
        }
    }
}
