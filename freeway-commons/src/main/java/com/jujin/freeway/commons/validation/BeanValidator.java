package com.jujin.freeway.commons.validation;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.Collection;

public final class BeanValidator {

    private BeanValidator() {}

    public static boolean isAnnotated(Class<?> beanType) {
        try {
            BeanPlan plan = BeanIntrospector.plan(beanType);
            for (BeanProperty property : plan.properties()) {
                if (hasValidationAnnotation(property)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // JDK class or otherwise inaccessible type — cannot have validation annotations
            return false;
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
        BeanPlan plan;
        try {
            plan = BeanIntrospector.plan(bean.getClass());
        } catch (RuntimeException e) {
            // JDK class or otherwise inaccessible type — nothing to validate
            return;
        }
        for (BeanProperty property : plan.properties()) {
            Object value = property.read(bean);

            String fieldPath = prefix.isEmpty()
                ? property.name()
                : prefix + "." + property.name();

            for (Annotation ann : property.annotations()) {
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
                        result.addError(fieldPath, size.message()
                            .replace("{min}", String.valueOf(size.min()))
                            .replace("{max}", String.valueOf(size.max())), value);
                    }
                } else if (ann instanceof Min min) {
                    if (value instanceof Number n && n.longValue() < min.value()) {
                        result.addError(fieldPath,
                            min.message().replace("{value}", String.valueOf(min.value())), value);
                    }
                } else if (ann instanceof Max max) {
                    if (value instanceof Number n && n.longValue() > max.value()) {
                        result.addError(fieldPath,
                            max.message().replace("{value}", String.valueOf(max.value())), value);
                    }
                }
            }

            if (property.hasAnnotation(Valid.class) && value != null) {
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

    private static boolean hasValidationAnnotation(BeanProperty property) {
        for (Annotation ann : property.annotations()) {
            if (ann instanceof NotNull || ann instanceof NotBlank
                || ann instanceof Size || ann instanceof Min
                || ann instanceof Max || ann instanceof Valid) {
                return true;
            }
        }
        return false;
    }
}
