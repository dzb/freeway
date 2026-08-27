package com.jujin.freeway.commons.validation;

import com.jujin.freeway.commons.bean.BeanIntrospector;
import com.jujin.freeway.commons.bean.BeanPlan;
import com.jujin.freeway.commons.bean.BeanProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Declarative validation for beans annotated with {@code @NotNull}, {@code @NotBlank}, {@code @Size}, etc. */
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
        if (!context.withinDepth()) {
            result.addError(
                prefix.isEmpty() ? "(root)" : prefix,
                "maximum validation depth exceeded (nested @Valid chain too deep)",
                null
            );
            return;
        }
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

                Annotation[] annotations = property.annotations();
                for (Annotation ann : annotations) {
                    if (ann instanceof NotNull notNull && value == null) {
                        result.addError(fieldPath, notNull.message(), null);
                    } else if (ann instanceof NotBlank notBlank && (value == null || value.toString().trim().isEmpty())) {
                        result.addError(fieldPath, notBlank.message(), value);
                    } else if (ann instanceof Size size && value != null) {
                        // @Size follows Bean Validation conventions: null is
                        // valid here — nullness belongs to @NotNull.
                        int len = lengthOf(value);
                        // Non-measurable types (-1) skip the constraint,
                        // mirroring @Min/@Max's silent skip on non-Number.
                        if (len >= 0 && (len < size.min() || len > size.max())) {
                            result.addError(
                                fieldPath,
                                size.message()
                                    .replace("{min}", String.valueOf(size.min()))
                                    .replace("{max}", String.valueOf(size.max())),
                                value
                            );
                        }
                    } else if (ann instanceof Min min) {
                        if (value instanceof Number n
                                && isFiniteNumber(n)
                                && toBigDecimal(n).compareTo(BigDecimal.valueOf(min.value())) < 0) {
                            result.addError(
                                fieldPath,
                                min.message().replace("{value}", String.valueOf(min.value())),
                                value
                            );
                        }
                    } else if (ann instanceof Max max) {
                        if (value instanceof Number n
                                && isFiniteNumber(n)
                                && toBigDecimal(n).compareTo(BigDecimal.valueOf(max.value())) > 0) {
                            result.addError(
                                fieldPath,
                                max.message().replace("{value}", String.valueOf(max.value())),
                                value
                            );
                        }
                    }
                }

                if (value != null && hasAnnotation(annotations, Valid.class)) {
                    if (value instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> entry : m.entrySet()) {
                            Object val = entry.getValue();
                            if (val != null) {
                                String key = String.valueOf(entry.getKey());
                                validateBean(val, fieldPath + "." + key, result, context);
                            }
                        }
                    } else if (value.getClass().isArray()) {
                        int len = Array.getLength(value);
                        for (int i = 0; i < len; i++) {
                            Object element = Array.get(value, i);
                            if (element != null) {
                                validateBean(element, fieldPath + "[" + i + "]", result, context);
                            }
                        }
                    } else if (value instanceof Optional<?> opt) {
                        if (opt.isPresent()) {
                            Object inner = opt.get();
                            if (inner != null) {
                                validateBean(inner, fieldPath, result, context);
                            }
                        }
                    } else if (value instanceof Iterable<?> it) {
                        // Collections and any other Iterable (custom, Set,
                        // Queue) — validated element-wise.
                        int i = 0;
                        for (Object element : it) {
                            if (element != null) {
                                validateBean(element, fieldPath + "[" + i + "]", result, context);
                            }
                            i++;
                        }
                    } else {
                        validateBean(value, fieldPath, result, context);
                    }
                }
            }
        } finally {
            context.exit(bean);
        }
    }

    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal bd) return bd;
        if (n instanceof BigInteger bi) return new BigDecimal(bi);
        return new BigDecimal(n.toString());
    }

    private static int lengthOf(Object value) {
        // value != null by contract (the caller guards @Size on null).
        if (value instanceof CharSequence s) return s.length();
        if (value instanceof Collection<?> c) return c.size();
        if (value instanceof Map<?,?> m) return m.size();
        if (value.getClass().isArray()) return Array.getLength(value);
        return -1; // non-measurable — @Size is ignored for this type
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

    /**
     * Bootstrap classloader → JDK internal type (e.g. String, Integer).
     */
    private static boolean isJdkType(Class<?> type) {
        return type.getClassLoader() == null;
    }

    private static boolean isFiniteNumber(Number value) {
        if (value instanceof Double d) return Double.isFinite(d);
        if (value instanceof Float f) return Float.isFinite(f);
        return true;
    }

    private static boolean hasAnnotation(
        Annotation[] annotations,
        Class<? extends Annotation> type
    ) {
        for (Annotation annotation : annotations) {
            if (type.isInstance(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static final class ValidationContext {
        /** Cap on nested {@code @Valid} depth — deep acyclic chains must fail
         *  with a validation error, not StackOverflowError. */
        private static final int MAX_DEPTH = 100;

        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private int depth;

        boolean withinDepth() {
            return depth < MAX_DEPTH;
        }

        boolean enter(Object value) {
            if (!active.add(value)) {
                return false; // cycle — no depth increment, caller returns balanced
            }
            depth++;
            return true;
        }

        void exit(Object value) {
            depth--;
            active.remove(value);
        }
    }
}
