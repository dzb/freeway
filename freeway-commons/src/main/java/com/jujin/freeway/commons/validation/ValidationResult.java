package com.jujin.freeway.commons.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collects the field errors produced by {@link BeanValidator}. */
public class ValidationResult {
    private final List<ValidationError> errors = new ArrayList<>();

    public boolean hasErrors() { return !errors.isEmpty(); }

    public List<ValidationError> errors() {
        return Collections.unmodifiableList(errors);
    }

    public void addError(String field, String message, Object rejectedValue) {
        errors.add(new ValidationError(field, message, rejectedValue));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (ValidationError e : errors) {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(e.toString());
        }
        return sb.toString();
    }
}
