package com.jujin.freeway.commons.validation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class BeanValidator {

    private BeanValidator() {}

    public static boolean isAnnotated(Class<?> beanType) {
        for (Field field : getAllFields(beanType)) {
            if (hasValidationAnnotation(field)) {
                return true;
            }
        }
        return false;
    }

    public static ValidationResult validate(Object bean) {
        ValidationResult result = new ValidationResult();
        if (bean == null) {
            result.addError("(root)", "must not be null", null);
            return result;
        }
        validateBean(bean, "", result);
        return result;
    }

    private static void validateBean(Object bean, String prefix, ValidationResult result) {
        for (Field field : getAllFields(bean.getClass())) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(bean);
            } catch (IllegalAccessException e) {
                continue;
            }

            String fieldPath = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();

            for (Annotation ann : field.getDeclaredAnnotations()) {
                if (ann instanceof NotNull) {
                    if (value == null) {
                        result.addError(fieldPath, ((NotNull) ann).message(), null);
                    }
                } else if (ann instanceof NotBlank) {
                    if (value == null || value.toString().trim().isEmpty()) {
                        result.addError(fieldPath, ((NotBlank) ann).message(), value);
                    }
                } else if (ann instanceof Size size) {
                    int len = lengthOf(value);
                    if (len < size.min() || len > size.max()) {
                        result.addError(fieldPath, size.message().replace("{min}", String.valueOf(size.min())).replace("{max}", String.valueOf(size.max())), value);
                    }
                } else if (ann instanceof Min min) {
                    if (value instanceof Number n && n.longValue() < min.value()) {
                        result.addError(fieldPath, min.message().replace("{value}", String.valueOf(min.value())), value);
                    }
                } else if (ann instanceof Max max) {
                    if (value instanceof Number n && n.longValue() > max.value()) {
                        result.addError(fieldPath, max.message().replace("{value}", String.valueOf(max.value())), value);
                    }
                }
            }

            if (field.isAnnotationPresent(Valid.class) && value != null) {
                validateBean(value, fieldPath, result);
            }
        }
    }

    private static int lengthOf(Object value) {
        if (value == null) return 0;
        if (value instanceof CharSequence s) return s.length();
        if (value instanceof Collection<?> c) return c.size();
        if (value.getClass().isArray()) return Array.getLength(value);
        return 0;
    }

    private static Field[] getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    fields.add(f);
                }
            }
        }
        return fields.toArray(new Field[0]);
    }

    private static boolean hasValidationAnnotation(Field field) {
        for (Annotation ann : field.getDeclaredAnnotations()) {
            if (ann instanceof NotNull || ann instanceof NotBlank
                || ann instanceof Size || ann instanceof Min
                || ann instanceof Max || ann instanceof Valid) {
                return true;
            }
        }
        return false;
    }
}
