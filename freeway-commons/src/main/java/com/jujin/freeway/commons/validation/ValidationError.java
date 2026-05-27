package com.jujin.freeway.commons.validation;

public class ValidationError {
    private final String field;
    private final String message;
    private final Object rejectedValue;

    public ValidationError(String field, String message, Object rejectedValue) {
        this.field = field;
        this.message = message;
        this.rejectedValue = rejectedValue;
    }

    public String getField() { return field; }
    public String getMessage() { return message; }
    public Object getRejectedValue() { return rejectedValue; }

    @Override
    public String toString() {
        return field + ": " + message + (rejectedValue != null ? " (rejected: " + rejectedValue + ")" : "");
    }
}
