package com.jujin.freeway.commons.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable field-error result produced by {@link BeanValidator}.
 *
 * <p>The validator accumulates in a package-private {@link Collector}; callers
 * only ever receive a result with a defensive copy of the errors. Reading a
 * result and adding to it are therefore different capabilities.</p>
 */
public final class ValidationResult {
    private final List<ValidationError> errors;

    private ValidationResult(List<ValidationError> errors) {
        this.errors = List.copyOf(errors);
    }

    /** Value form, mainly for callers that already own their errors. */
    public static ValidationResult of(List<ValidationError> errors) {
        return new ValidationResult(Objects.requireNonNull(errors, "errors"));
    }

    public boolean hasErrors() { return !errors.isEmpty(); }

    public List<ValidationError> errors() { return errors; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (ValidationError e : errors) {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(e.toString());
        }
        return sb.toString();
    }

    /** Accumulator used only while validating one bean tree. */
    static final class Collector {
        private final List<ValidationError> errors = new ArrayList<>();

        void addError(String field, String message, Object rejectedValue) {
            errors.add(new ValidationError(field, message, rejectedValue));
        }

        ValidationResult build() {
            return new ValidationResult(errors);
        }
    }
}
