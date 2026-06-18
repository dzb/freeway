package com.jujin.freeway.commons.validation;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BeanValidator {

    private static final Logger LOG = LoggerFactory.getLogger(BeanValidator.class);

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
            if (isJdkType(beanType)) {
                LOG.debug(
                    "Unable to introspect {} for validation annotations: {}",
                    beanType.getName(),
                    e.toString()
                );
                return false;
            }
            throw new IllegalStateException(
                "Failed to introspect " + beanType.getName() + " for validation annotations",
                e
            );
        }
        return false;
    }

    public static ValidationResult validate(Object bean) {
        ValidationResult result = new ValidationResult();
        if (bean == null) {
            result.addError("(root)", "must not be null", null);
            return result;
        }
        validateBean(bean, "", result, new ValidationContext());
        return result;
    }

    private static void validateBean(
        Object bean,
        String prefix,
        ValidationResult result,
        ValidationContext context
    ) {
        if (!context.enter(bean)) {
            return;
        }
        try {
            BeanPlan plan;
            try {
                plan = BeanIntrospector.plan(bean.getClass());
            } catch (RuntimeException e) {
                if (isJdkType(bean.getClass())) {
                    LOG.debug(
                        "Unable to introspect {} for validation: {}",
                        bean.getClass().getName(),
                        e.toString()
                    );
                    return;
                }
                throw new IllegalStateException(
                    "Failed to introspect " + bean.getClass().getName() + " for validation",
                    e
                );
            }

            for (BeanProperty property : plan.properties()) {
                Object value = property.read(bean);

                String fieldPath = prefix.isEmpty()
                    ? property.name()
                    : prefix + "." + property.name();

                for (Annotation ann : property.annotations()) {
                    if (ann instanceof NotNull notNull && value == null) {
                        result.addError(fieldPath, notNull.message(), null);
                    } else if (ann instanceof NotBlank notBlank && (value == null || value.toString().trim().isEmpty())) {
                        result.addError(fieldPath, notBlank.message(), value);
                    } else if (ann instanceof Size size) {
                        int len = lengthOf(value);
                        if (len < size.min() || len > size.max()) {
                            result.addError(
                                fieldPath,
                                size.message()
                                    .replace("{min}", String.valueOf(size.min()))
                                    .replace("{max}", String.valueOf(size.max())),
                                value
                            );
                        }
                    } else if (ann instanceof Min min) {
                        if (value instanceof Number n && n.longValue() < min.value()) {
                            result.addError(
                                fieldPath,
                                min.message().replace("{value}", String.valueOf(min.value())),
                                value
                            );
                        }
                    } else if (ann instanceof Max max) {
                        if (value instanceof Number n && n.longValue() > max.value()) {
                            result.addError(
                                fieldPath,
                                max.message().replace("{value}", String.valueOf(max.value())),
                                value
                            );
                        }
                    }
                }

                if (property.hasAnnotation(Valid.class) && value != null) {
                    validateBean(value, fieldPath, result, context);
                }
            }
        } finally {
            context.exit(bean);
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
            if (
                ann instanceof NotNull ||
                ann instanceof NotBlank ||
                ann instanceof Size ||
                ann instanceof Min ||
                ann instanceof Max ||
                ann instanceof Valid
            ) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJdkType(Class<?> type) {
        return type.getClassLoader() == null;
    }

    private static final class ValidationContext {
        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());

        boolean enter(Object value) {
            return active.add(value);
        }

        void exit(Object value) {
            active.remove(value);
        }
    }
}
